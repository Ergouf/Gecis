# Gecis embedded runtime contract

Gecis is a **single Android application**. Users must not be required to install Termux or launch a localhost server.

## Upstream

The current Android-compatible engine source is `wallentx/antigravity-cli-termux`. Its release contains two binaries:

- `agy`: a Bionic bootstrapper designed specifically for native Termux.
- `agy.va39`: the patched Antigravity ARM64 engine.

Gecis must **not** embed the existing `agy` bootstrapper unchanged. It explicitly checks for a native Termux environment (`TERMUX_VERSION`, `PREFIX/bin`, Termux glibc, CA bundle and resolver configuration).

## Gecis packaging model

The APK packages a verified ARM64 payload through `jniLibs/arm64-v8a/` so Android installs executable native code into `ApplicationInfo.nativeLibraryDir`.

Reserved runtime names used by the Kotlin launcher:

- `libgecis_ld.so` — packaged glibc dynamic loader.
- `libgecis_agy.so` — the verified VA39-patched Antigravity engine.
- `libgecis_*.so` — Android-safe aliases for the glibc shared-library closure required by the engine.

The exact shared-library closure is generated and verified during the native-payload build pipeline rather than guessed in application code. The staging step rewrites `DT_NEEDED` entries to the Android-safe names.

Gecis does not download executable code after installation.

## Google account authentication

Gecis uses **Antigravity's own Google OAuth session**. API-key authentication is not part of the product flow.

The rules are:

1. The WebView never receives OAuth credentials.
2. If no encrypted Antigravity OAuth session exists, the native layer starts an Antigravity authentication process.
3. Gecis opens the Google authorization URL in the system browser.
4. The OAuth client, scopes, state/PKCE values and token exchange remain owned by Antigravity; Gecis does not substitute an unrelated Android Google Sign-In ID token.
5. The resulting Antigravity OAuth JSON is immediately moved into an Android Keystore-backed encrypted vault and transient plaintext token files are deleted.
6. Headless chat decrypts the credential only when launching the child process and supplies it through `JETSKI_OAUTH_TOKEN`.
7. If the session becomes invalid, the runtime clears the vault and restarts OAuth before retrying the pending chat turn.

The pinned `agy.va39` v1.2.0 binary has been inspected by CI. It exposes these relevant markers:

- `JETSKI_OAUTH_TOKEN`
- `jetski-standalone-oauth-token`
- `org.freedesktop.secrets`

It does **not** expose `GEMINI_FORCE_FILE_STORAGE`. Gecis therefore must not depend on that older compatibility switch.

During interactive login, Gecis watches the transient Antigravity token locations used by current/older builds, normalizes the OAuth payload to the `{token, auth_method}` wrapper accepted by `JETSKI_OAUTH_TOKEN`, encrypts it with Android Keystore, then deletes the plaintext source. No Linux D-Bus/Secret Service service is bundled into the app.

The initial integration can use Antigravity's remote-style browser handoff if required by the pinned engine. A same-device loopback callback is preferred when verified because it removes manual code copying while still leaving OAuth protocol ownership inside Antigravity.

## Conversation protocol

After authentication, Gecis launches one persistent process using:

```text
<loader> --library-path <nativeLibraryDir> <engine> \
  --input-format stream-json \
  --output-format stream-json \
  --sandbox \
  --print-timeout 5m
```

Each user turn is sent as one NDJSON object on stdin:

```json
{"event":"user","message":{"content":"..."}}
```

The app consumes `step_update.step_update.text_delta` events for streaming UI updates and treats each `result` event as the end of the current turn. Only one turn is in flight at a time.

## Security boundary

- WebView never receives a filesystem path for `fenbi.db`, the AI binary, or OAuth credentials.
- WebView communicates only through the narrow `GecisNative` JavaScript bridge.
- Antigravity runs with `--sandbox`; Gecis never adds `--dangerously-skip-permissions`.
- OAuth credentials are encrypted at rest with Android Keystore and are excluded from Android backup by living behind app-owned encrypted state/no-backup runtime storage.
- The future `fenbi.db` adapter must open the database read-only and inject only retrieved text into a prompt/context layer.
- Remote JavaScript must not coexist with the native bridge in production; Markdown/KaTeX assets must be bundled into the APK before release.
