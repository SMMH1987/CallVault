/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.server.speakers.SpeakerTurnCodec
import com.baba.callvault.server.speakers.SpeakerTurnDetector
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A carrier call with each direction on its own channel: LEFT = uplink (the user), RIGHT = downlink
 * (the other person), encoded as a real stereo file.
 *
 * **Why not stereo `VOICE_CALL`.** [DirectAudioRecorderSession] asks `VOICE_CALL` for two channels and
 * gets them on some HALs, but on others — a Galaxy A56 on One UI, measured — both channels carry the
 * same mix, so nothing can be told apart afterwards. `VOICE_UPLINK` and `VOICE_DOWNLINK` are separate
 * taps, and on that same phone two of them opened side by side gave two genuinely different signals
 * (L/R correlation ≈ 0). The cost is a second `AudioRecord`, which some HALs refuse; [start] then
 * throws and the caller falls back to the ordinary `VOICE_CALL` capture, so a call is never lost.
 *
 * **Why the file stays stereo.** The point of this source is that a transcript can be made of each
 * side separately. The ordinary path downmixes to mono to give the whole bit rate to one voice; here
 * the bit rate is raised to at least [MIN_STEREO_BIT_RATE] instead, so each side keeps enough of it.
 *
 * The two records are free-running, so they are paired by the real time their audio was captured
 * ([SlotPairer]) exactly as [VoipCaptureSession] pairs an app call — neither side can push the other
 * along, and a stall on one side becomes silence on that side rather than a drift.
 */
internal class DualTrackRecorderSession(
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. The muxer writes through it; [stop] closes it after finalising. */
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {

    private val stopRequested = AtomicBoolean(false)

    @Volatile private var speakerTurnsEncoded: String = ""

    override fun speakerTurns(): String = speakerTurnsEncoded

    @Volatile private var syncSummary: String = ""

    override fun captureDiagnostics(): String = syncSummary

    private val ledger = VoipSyncLedger(SAMPLE_RATE, CHUNK_FRAMES)

    /** The monotonic moment the two records were started; the wall clock of every sync line. */
    @Volatile private var startedNanos = 0L

    /** A chunk of one side with the real time its audio was captured (see [VoipSyncLedger]). */
    private class Chunk(val bytes: ByteArray, val contentNanos: Long)

    @Volatile private var nearRecord: AudioRecord? = null
    @Volatile private var farRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var muxThread: Thread? = null

    /** Ledger ids for the two captures this session holds. */
    @Volatile private var nearAuditId: Int = 0
    @Volatile private var farAuditId: Int = 0

    override fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // Release our own resources but do NOT close outFd — the caller falls back with it.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val near = openRecord(MediaRecorder.AudioSource.VOICE_UPLINK, "uplink")
        nearRecord = near
        nearAuditId = CaptureAudit.opened("dual-track near capture (VOICE_UPLINK)")
        val far = openRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK, "downlink")
        farRecord = far
        farAuditId = CaptureAudit.opened("dual-track far capture (VOICE_DOWNLINK)")

        val mime = encoderMimeFor(codec)
        val requested = maxOf(bitRate, MIN_STEREO_BIT_RATE)
        val effectiveBitRate = EncoderLimits.resolveBitRate(mime, requested, SAMPLE_RATE, ENCODE_CHANNELS)
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveBitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val encoderName = EncoderLimits.encoderNameFor(mime, SAMPLE_RATE, ENCODE_CHANNELS)
        val enc = (if (encoderName != null) MediaCodec.createByCodecName(encoderName) else MediaCodec.createEncoderByType(mime))
        runCatching { enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
            .onFailure { runCatching { enc.release() } }
            .getOrThrow()
        encoder = enc

        // Muxer last: everything riskier has succeeded, so the output fd is only consumed now.
        val mux = MediaMuxer(outFd.fileDescriptor, codec.outputFormat)
        muxer = mux

        enc.start()
        startedNanos = System.nanoTime()
        near.startRecording()
        far.startRecording()
        if (near.recordingState != AudioRecord.RECORDSTATE_RECORDING ||
            far.recordingState != AudioRecord.RECORDSTATE_RECORDING
        ) {
            throw IllegalStateException(
                "dual-track records did not both enter RECORDING (uplink=${near.recordingState} downlink=${far.recordingState})"
            )
        }
        AppLogger.i(
            TAG,
            "Dual-track capture started: codec=${codec.cliKey} rate=$SAMPLE_RATE ch=$ENCODE_CHANNELS " +
                "bitRate=$effectiveBitRate (asked $bitRate); near buf=${near.bufferSizeInFrames} far buf=${far.bufferSizeInFrames}; " +
                "sdk=${android.os.Build.VERSION.SDK_INT} ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        )

        muxThread = Thread {
            runCatching { captureLoop(enc, mux) }
                .onFailure { AppLogger.w(TAG, "Dual-track capture loop ended: ${it.message}") }
        }.apply { isDaemon = true; name = "dual-track-capture" }.also { it.start() }
    }

    private fun openRecord(source: Int, what: String): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("no buffer size for $what capture")
        val rec = HostAudioRecord.open(
            "dual-track $what capture", source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
        )
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            throw IllegalStateException("$what capture would not initialise")
        }
        return rec
    }

    /**
     * Pairs one slot from each direction, interleaves L = near / R = far and drives the encoder. Each
     * side is read on its own thread so a slow or silent one cannot stall the other.
     */
    private fun captureLoop(enc: MediaCodec, mux: MediaMuxer) {
        val qNear: BlockingQueue<Chunk> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val qFar: BlockingQueue<Chunk> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val readers = listOf(feeder(qNear, ledger.near), feeder(qFar, ledger.far))
        readers.forEach { it.start() }

        val silence = ByteArray(CHUNK_BYTES)
        val stereo = ByteArray(CHUNK_BYTES * 2)
        // Near is the user by construction here, so these turns are exact rather than inferred.
        val speakers = SpeakerTurnDetector(SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        var nearPeak = 0
        var farPeak = 0
        var nearEverHeard = false
        var farEverHeard = false
        var nextSnapshotFrames = SNAPSHOT_FRAMES
        val pairer = SlotPairer(CHUNK_NANOS)
        var slotNanos = -1L

        try {
            while (!stopRequested.get()) {
                if (slotNanos < 0) {
                    // Anchor on the earlier first capture, waiting briefly for the other side so a
                    // slow start is padded at the head rather than the file starting without it.
                    val n = qNear.peek()
                    val f = qFar.peek()
                    val waited = System.nanoTime() - startedNanos
                    if ((n == null || f == null) && waited < ANCHOR_WAIT_NANOS) { Thread.sleep(SLOT_POLL_MS); continue }
                    if (n == null && f == null) { Thread.sleep(SLOT_POLL_MS); continue }
                    slotNanos = pairer.anchor(n?.contentNanos, f?.contentNanos)
                    AppLogger.i(TAG, "Dual-track sync: file anchored ${(slotNanos - startedNanos) / 1_000_000L}ms after start (near=${n != null} far=${f != null})")
                }
                if (!pairer.due(slotNanos, System.nanoTime())) { Thread.sleep(SLOT_POLL_MS); continue }
                val nearChunk = takeForSlot(qNear, slotNanos, pairer, ledger.near)
                val farChunk = takeForSlot(qFar, slotNanos, pairer, ledger.far)
                slotNanos += CHUNK_NANOS
                val n = nearChunk?.bytes ?: silence
                val f = farChunk?.bytes ?: silence
                ledger.paired(nearChunk?.contentNanos, farChunk?.contentNanos)

                var o = 0
                for (i in 0 until CHUNK_BYTES step 2) {
                    stereo[o] = n[i]; stereo[o + 1] = n[i + 1]          // L = near (uplink)
                    stereo[o + 2] = f[i]; stereo[o + 3] = f[i + 1]      // R = far (downlink)
                    val ns = ((n[i].toInt() and 0xFF) or (n[i + 1].toInt() shl 8)).toShort().toInt()
                    val fs = ((f[i].toInt() and 0xFF) or (f[i + 1].toInt() shl 8)).toShort().toInt()
                    val na = if (ns < 0) -ns else ns
                    val fa = if (fs < 0) -fs else fs
                    if (na > nearPeak) nearPeak = na
                    if (fa > farPeak) farPeak = fa
                    o += 4
                }
                if (nearPeak > SILENCE_THRESHOLD) nearEverHeard = true
                if (farPeak > SILENCE_THRESHOLD) farEverHeard = true

                runCatching { speakers.accept(stereo, stereo.size) }
                    .onFailure { AppLogger.w(TAG, "Speaker turn detection failed: ${it.message}") }

                var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inIdx < 0 && !stopRequested.get()) {
                    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }
                if (inIdx < 0) break
                enc.getInputBuffer(inIdx)!!.apply { clear(); put(stereo, 0, stereo.size) }
                enc.queueInputBuffer(inIdx, 0, stereo.size, totalFrames * 1_000_000L / SAMPLE_RATE, 0)
                totalFrames += CHUNK_FRAMES
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)

                if (totalFrames >= nextSnapshotFrames) {
                    nextSnapshotFrames += SNAPSHOT_FRAMES
                    AppLogger.i(
                        TAG,
                        ledger.snapshot(totalFrames, System.nanoTime() - startedNanos, qNear.size, qFar.size) +
                            " peak near=$nearPeak far=$farPeak",
                    )
                    nearPeak = 0; farPeak = 0
                }
            }

            val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
            syncSummary = DUAL_TRACK_MARKER + ledger.summary(totalFrames, System.nanoTime() - startedNanos)
            AppLogger.i(TAG, "Dual-track capture finished: ${totalFrames / SAMPLE_RATE}s, nearHeard=$nearEverHeard farHeard=$farEverHeard")
            AppLogger.i(TAG, syncSummary)
            if (!nearEverHeard || !farEverHeard) {
                AppLogger.w(TAG, "One side was never audible (near=$nearEverHeard far=$farEverHeard) — this HAL may not serve both taps at once")
            }
        } finally {
            readers.forEach { it.interrupt() }
            runCatching { speakerTurnsEncoded = SpeakerTurnCodec.encode(speakers.finish()) }
                .onFailure { AppLogger.w(TAG, "Could not encode dual-track speaker turns: ${it.message}") }
        }
    }

    /** The chunk of [q] for the slot at [slotNanos], or null for silence; older chunks are discarded. */
    private fun takeForSlot(q: BlockingQueue<Chunk>, slotNanos: Long, pairer: SlotPairer, side: VoipSyncLedger.Side): Chunk? {
        while (true) {
            when (pairer.classify(slotNanos, q.peek()?.contentNanos)) {
                SlotPairer.Take.TAKE -> return q.poll()
                SlotPairer.Take.SILENCE -> { side.substituted(); return null }
                SlotPairer.Take.DISCARD -> { q.poll(); side.discarded() }
            }
        }
    }

    /** Reads whole chunks from one direction into its queue, stamped with their capture time. */
    private fun feeder(q: BlockingQueue<Chunk>, side: VoipSyncLedger.Side) = Thread {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val record = (if (side === ledger.near) nearRecord else farRecord) ?: return@Thread
        side.newRecord()
        val buf = ByteArray(CHUNK_BYTES)
        var frameIndex = 0L
        val ts = android.media.AudioTimestamp()
        while (!stopRequested.get()) {
            var off = 0
            while (off < CHUNK_BYTES && !stopRequested.get()) {
                val r = runCatching { record.read(buf, off, CHUNK_BYTES - off) }.getOrDefault(-1)
                if (r <= 0) {
                    if (stopRequested.get()) return@Thread
                    AppLogger.d(TAG, "${side.name} read=$r, feeder ending")
                    return@Thread
                }
                off += r
            }
            if (off < CHUNK_BYTES) return@Thread
            val readAt = System.nanoTime()
            if (side.needsTimestamp || side.chunksRead % TIMESTAMP_EVERY_CHUNKS == 0L) {
                val ok = runCatching { record.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) }
                    .getOrDefault(AudioRecord.ERROR)
                if (ok == AudioRecord.SUCCESS) side.timestamp(ts.framePosition, ts.nanoTime)
            }
            val contentNanos = side.contentNanos(frameIndex, readAt)
            frameIndex += CHUNK_FRAMES
            side.read()
            if (!q.offer(Chunk(buf.copyOf(), contentNanos))) side.dropped()
        }
    }.apply { isDaemon = true; name = "dual-track-${side.name}" }

    /** Drains available encoder output into the muxer. Returns whether the muxer is (now) started. */
    private fun drainEncoder(
        enc: MediaCodec, mux: MediaMuxer, info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean, drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        var track = if (muxerStarted) 0 else -1
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, if (drainToEos) END_OF_STREAM_TIMEOUT_US else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat)   // carries the codec-specific data
                    mux.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (drainToEos) continue else return muxerStarted
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(track, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted
                }
            }
        }
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        AppLogger.i(TAG, "Stopping dual-track capture session")
        runCatching { muxThread?.join(STOP_JOIN_MS) }
        if (muxThread?.isAlive == true) {
            // Same rule as the direct path: releasing the encoder or muxer under a live writer crashes the
            // daemon, which costs the next call too. Leave them to the process.
            AppLogger.e(TAG, "Dual-track mux thread still running after $STOP_JOIN_MS ms — leaving encoder/muxer/fd to the process")
            runCatching { nearRecord?.stop() }; runCatching { farRecord?.stop() }
            return
        }
        runCatching { nearRecord?.stop() }
        CaptureAudit.released(nearAuditId, runCatching { nearRecord?.release() }.exceptionOrNull())
        runCatching { farRecord?.stop() }
        CaptureAudit.released(farAuditId, runCatching { farRecord?.release() }.exceptionOrNull())
        nearAuditId = 0; farAuditId = 0
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        runCatching { muxer?.stop() }    // writes the trailer — without it the file won't play
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        nearRecord = null; farRecord = null; encoder = null; muxer = null
        AppLogger.i(TAG, "Dual-track capture stopped")
        CaptureAudit.assertNoneLive("after stopping dual-track capture")
    }

    /** Releases what start() managed to build, leaving [outFd] open for the caller's fallback. */
    private fun cleanupPartial() {
        runCatching { nearRecord?.stop() }
        runCatching { nearRecord?.release() }
        if (nearAuditId != 0) { CaptureAudit.released(nearAuditId); nearAuditId = 0 }
        runCatching { farRecord?.stop() }
        runCatching { farRecord?.release() }
        if (farAuditId != 0) { CaptureAudit.released(farAuditId); farAuditId = 0 }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        nearRecord = null; farRecord = null; encoder = null; muxer = null
    }

    companion object {
        private const val TAG = "CV:DualTrack"

        /**
         * Leads this session's capture diagnostics so the app can tell, after the fact, that the file
         * really is L = near / R = far — a failed start falls back to the mixed call, and the app must
         * not stamp `near_channel` on that.
         */
        const val DUAL_TRACK_MARKER = "dual-track L=near R=far; "
        private const val SAMPLE_RATE = 48_000
        private const val ENCODE_CHANNELS = 2
        private const val BUFFER_FACTOR = 4
        private const val CHUNK_FRAMES = 960                 // 20 ms at 48 kHz
        private const val CHUNK_BYTES = CHUNK_FRAMES * 2     // mono PCM-16, per side
        private const val QUEUE_CHUNKS = 400                 // ~8 s of slack per direction
        private const val CHUNK_NANOS = CHUNK_FRAMES * 1_000_000_000L / SAMPLE_RATE
        private const val SLOT_POLL_MS = 5L
        private const val ANCHOR_WAIT_NANOS = 1_000_000_000L
        private const val SNAPSHOT_FRAMES = SAMPLE_RATE * 10L
        private const val TIMESTAMP_EVERY_CHUNKS = 50L
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val STOP_JOIN_MS = 5_000L
        private const val SILENCE_THRESHOLD = 100

        /**
         * The least total bit rate a stereo file is encoded at. At the app's 24 kbps default each side
         * would get ~12 kbps, which is what made the far party sound starved before the mono downmix
         * (CHANGELOG 1.4.4); 64 kbps leaves 32 kbps a side, plenty for speech recognition.
         */
        const val MIN_STEREO_BIT_RATE = 64_000

        private fun encoderMimeFor(codec: ScrcpyAudioCodec): String = when (codec) {
            ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        }

        /** True if this device has an encoder that accepts 48 kHz stereo in the chosen codec. */
        fun supports(codec: ScrcpyAudioCodec): Boolean = runCatching {
            EncoderLimits.supportsFormat(encoderMimeFor(codec), SAMPLE_RATE, ENCODE_CHANNELS)
        }.getOrDefault(false)
    }
}
