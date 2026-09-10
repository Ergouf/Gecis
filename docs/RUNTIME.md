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
2. If no cached Antigravity OAuth session exists, the native layer starts an Antigravity authentication process.
3. Gecis opens the Google authorization URL in the system browser.
4. The OAuth client, scopes, state/PKCE values and token exchange remain owned by Antigravity; Gecis does not substitute an unrelated Android Google Sign-In ID token.
5. Headless chat reuses the resulting Antigravity session. If the session becomes invalid, the runtime emits an authentication-required event and the app restarts OAuth before retrying the pending message.

Android does not provide the Linux Secret Service used by the normal Linux keyring path. The first implementation therefore enables Antigravity's file-backed credential mode with `GEMINI_FORCE_FILE_STORAGE=true` and places its HOME under the app-private `noBackupFilesDir`. This keeps credentials private to the application and out of Android backup, while avoiding a bundled D-Bus/keyring stack.

Current Antigravity builds have used more than one OAuth token filename (`antigravity-oauth-token` and `jetski-standalone-oauth-token`). Runtime compatibility must be verified against the pinned engine rather than assumed. The native probe fails closed if the pinned binary no longer exposes the expected file-storage OAuth contract.

The initial Android integration uses Antigravity's remote-style browser handoff because it keeps OAuth protocol ownership inside Antigravity. A seamless loopback/deep-link handoff may replace the one-time code handoff only after it is verified against the pinned engine on Android.

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
- OAuth state lives under app-private storage; it is not exposed as a web credential.
- The future `fenbi.db` adapter must open the database read-only and inject only retrieved text into a prompt/context layer.
- Remote JavaScript must not coexist with the native bridge in production; Markdown/KaTeX assets must be bundled into the APK before release.
