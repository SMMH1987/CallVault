# App config: updates, messages and managed settings

This fork reads one JSON file to learn about new versions, blocked versions, messages for users,
settings managed centrally and feature switches. It does not depend on Google Play. The example
with every field is at [`tools/app-config/app-config.example.json`](../tools/app-config/app-config.example.json).

In software terms this is **remote config** (externalized configuration): behaviour lives in one
file outside the code, so it changes without a new build. It follows what Firebase Remote Config,
LaunchDarkly and Telegram's `help.getConfig` do:

- **Defaults inside the app.** `app/src/main/assets/default-config.json` holds every default. The
  fetched file is laid over it key by key, so the server only needs to send what differs.
- **Last good copy.** A file that is missing, unsigned or broken never replaces the stored one.
- **Signed.** The file can move the server address, so a phone only accepts it with a valid
  signature from the company key (see below).
- **Versioned.** `schema` says which format the file uses; keep the file in git for history and rollback.
- **Staged rollout.** `update.rollout_percent` offers a version to that share of phones first.

## Where the app looks

Addresses are tried in this order, and the first one that answers wins:

1. The address typed in Settings → Avanegar → Config file address (empty by default).
2. The addresses in `config_urls` of the last file the phone read. This is how the address can be
   moved later without a new build.
3. `<Avanegar server address>/app/config.json`.
4. The address built into the app (`CONFIG_FALLBACK_URL`). By default this is `app-config.json` on
   the `app-config` branch of this repository, served from raw.githubusercontent.com. A build can
   change it with `-PconfigFallbackUrl=...`.

The app only fetches `https://` addresses. It reads the file when it opens (at most every 15
minutes) and every 4 hours in the background. The last good copy is kept on the phone, so a blocked
version stays blocked when the phone is offline.

## Fields

| Field | Meaning |
|---|---|
| `update.version_code` | Version code of the newest build. Any build with a lower code shows the update banner and popup. |
| `update.version_name`, `changelog` | Shown to the user. |
| `update.apk_url`, `apk_size`, `apk_sha256` | Where to get the APK. The download must match the size and the SHA-256. |
| `update.page_url` | Optional page for the "Download in the browser" button. |
| `update.critical` | Marks an essential version. |
| `versions.min_supported_code` | Builds below this code cannot be used: the app shows the "sorry, please update" screen instead of its screens. |
| `versions.blocked_codes` | Specific broken builds to block the same way. |
| `versions.block_title`, `block_body` | Optional text for that screen; a Persian default is built in. |
| `messages[]` | `id`, `title`, `body`, `level` (`info`, `warning`, `critical`), `action_label`, `action_url`, `until` (date), `min_version_code`, `max_version_code`, `channels`. Each message is shown once per phone. A `critical` message can only be closed with its buttons. |
| `settings` | Settings the company manages: `avanegar_server_url`, `avanegar_upload_enabled`, `avanegar_wifi_only`. Only keys that are present are written. |
| `features` | On/off switches, read in code with `RemoteConfig.state.value?.feature("name", default)`. |
| `wallet` | `topup_url` (with `{amount}`), `topup_amounts`, `default_topup`, `currency`, `warn_below`, `critical_below`, `warn_text`, `critical_text`, `empty_text`. |
| `update.rollout_percent` | 0-100, default 100. Each phone draws a fixed number once; a blocked phone is always offered the update. |
| `channels.<name>` | Overrides for one kind of build. The build's channel is set with `-PbuildChannel=` and defaults to `company`. |

Calls keep being recorded while the app is blocked. Only the screens wait for the update.

## Signing

Every config file needs a signature next to it: `config.json` → `config.json.sig`, made with

```
python3 tools/app-config/sign_config.py config.json --key config-signing-key.pem
```

Re-sign after every edit. The private key stays with the company, never in the app or in git; the
app carries only the public key (`CONFIG_PUBLIC_KEY`, set with `-PconfigPublicKey=`). To replace a
leaked key, create one with `sign_config.py --new-key`, ship a build with its public key, and sign
with it from then on.

## Publishing a version

1. Build the APK with the **same signing key** as the installed one. The app only installs an update
   signed with its own key. This is also what keeps recordings and settings across the update.
2. Run `python3 tools/app-config/publish_release.py <apk> --config <current config.json> --out <folder> --base-url https://<domain>:8440/app --changelog "..."`.
   Add `--critical`, `--min-supported <code>`, `--block <code>` or `--rollout 10` when needed, and
   `--key config-signing-key.pem` to sign it in the same step.
3. Copy the folder to the server, where it is served at `/app/`. With the Caddy setup from
   `deploy/expose_avanegar_api.sh` in the API repository, add this to the site block:

   ```
   handle_path /app/* {
       root * /srv/avanegar-app
       file_server
   }
   ```

4. Optionally commit the same `config.json` and `.sig` as `app-config.json` and `app-config.json.sig`
   on the `app-config` branch, so the GitHub fallback is current too.
