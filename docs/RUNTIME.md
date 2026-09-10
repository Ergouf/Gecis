# Gecis embedded runtime contract

Gecis is a **single Android application**. Users must not be required to install Termux or launch a user-visible localhost service.

## Upstream

The current Android-compatible engine source is `wallentx/antigravity-cli-termux`. Its release contains two binaries:

- `agy`: a Bionic bootstrapper designed specifically for native Termux.
- `agy.va39`: the patched Antigravity ARM64 engine.

Gecis must **not** embed the existing `agy` bootstrapper unchanged. It explicitly assumes native Termux state such as `TERMUX_VERSION`, `PREFIX/bin`, Termux glibc, CA roots and resolver configuration.

## Gecis packaging model

The APK packages a verified ARM64 payload through `jniLibs/arm64-v8a/` so Android installs executable native code into `ApplicationInfo.nativeLibraryDir`.

Reserved runtime names used by the Kotlin launcher:

- `libgecis_ld.so` — packaged glibc dynamic loader.
- `libgecis_agy.so` — the verified VA39-patched Antigravity engine.
- `libgecis_*.so` — Android-safe aliases for the glibc shared-library closure required by the engine.

The exact shared-library closure is generated and verified during the native-payload build pipeline rather than guessed in application code. The staging step rewrites `DT_NEEDED` entries to Android-safe names.

Gecis does not download executable code after installation.

## Google account authentication

Gecis uses **Antigravity-compatible Google OAuth**. API-key authentication is not part of the product flow.

The user flow is intentionally minimal:

1. The first chat message detects that no encrypted Antigravity OAuth session exists.
2. The Android native layer starts a loopback callback listener bound only to `127.0.0.1:51121`.
3. Gecis opens the Google authorization page in the system browser using Antigravity's installed/public OAuth client, the Antigravity scopes, PKCE S256 and a fresh random state value.
4. After the user chooses a Google account and grants access, Google redirects to the loopback callback. Gecis validates `state` and exchanges the authorization code through Android's native HTTPS stack.
5. The resulting `{auth_method:"consumer", token:{...}}` credential is encrypted at rest with Android Keystore. The WebView never receives it.
6. Headless chat decrypts the credential only when launching the child process and supplies it through `JETSKI_OAUTH_TOKEN`.
7. If the session becomes invalid, the runtime clears the encrypted credential, starts OAuth again, and retries the pending chat turn after successful login.

There is no API-key screen and no manual authorization-code paste step in the current product flow.

The pinned `agy.va39` v1.2.0 binary has been inspected by CI. It exposes these relevant credential markers:

- `JETSKI_OAUTH_TOKEN`
- `jetski-standalone-oauth-token`
- `org.freedesktop.secrets`

It does **not** expose `GEMINI_FORCE_FILE_STORAGE`; Gecis therefore does not depend on that older compatibility switch.

The OAuth client is a public/installed-app client protected by PKCE rather than by treating its embedded client secret as confidential. The token wrapper passed to `JETSKI_OAUTH_TOKEN` matches the credential shape used by Antigravity (`auth_method=consumer`, access token, refresh token and expiry).

## Android DNS and TLS projection

The Termux glibc build contains a Termux-specific resolver pathname. That path does not exist in a standalone Gecis APK and previously caused OAuth/API DNS failures in the upstream Termux port.

Gecis handles this explicitly:

- the native staging pipeline rewrites the staged glibc resolver pathname to the relative `resolv.conf` and fails closed if no resolver pathname can be verified and rewritten;
- `ProcessBuilder` launches Antigravity with `noBackupFilesDir` as its working directory;
- immediately before runtime launch, Android `ConnectivityManager` supplies the active network's DNS servers and Gecis writes them to that private `resolv.conf`;
- the current Termux/curl Mozilla CA bundle is pinned by SHA256 at build time, bundled inside the APK, copied into private storage, and exposed to the embedded runtime through `SSL_CERT_FILE`;
- runtime executable code and CA material are never downloaded after installation.

This keeps Wi-Fi, cellular and VPN DNS behavior aligned with the Android device instead of hard-coding public resolvers or Termux paths.

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
- OAuth loopback accepts connections only on the local device and validates a cryptographically random state plus PKCE verifier/challenge.
- OAuth credentials are encrypted at rest with Android Keystore and Android backup is disabled for app data.
- Antigravity runs with `--sandbox`; Gecis never adds `--dangerously-skip-permissions`.
- The future `fenbi.db` adapter must open the database read-only and inject only retrieved text into a prompt/context layer.
- Remote JavaScript must not coexist with the native bridge in production; Markdown/KaTeX assets must be bundled into the APK before release.
