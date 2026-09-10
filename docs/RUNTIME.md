# Gecis embedded runtime contract

Gecis is a **single Android application**. Users must not be required to install Termux or launch a localhost server.

## Upstream

The current Android-compatible engine source is `wallentx/antigravity-cli-termux`. Its release contains two binaries:

- `agy`: a Bionic bootstrapper designed specifically for native Termux.
- `agy.va39`: the patched Antigravity ARM64 engine.

Gecis must **not** embed the existing `agy` bootstrapper unchanged. It explicitly checks for a native Termux environment (`TERMUX_VERSION`, `PREFIX/bin`, Termux glibc, CA bundle and resolver configuration).

## Gecis packaging model

The APK should package a verified ARM64 payload through `jniLibs/arm64-v8a/` so Android installs executable native code into `ApplicationInfo.nativeLibraryDir`.

Reserved runtime names used by the Kotlin launcher:

- `libgecis_ld.so` — glibc dynamic loader adapted/packaged for the Gecis runtime.
- `libgecis_agy.so` — the verified VA39-patched Antigravity engine.
- additional glibc shared objects required by the engine.

The exact shared-library closure must be generated and verified during the native-payload build pipeline, rather than guessed in application code.

Gecis does not download executable code after installation.

## Conversation protocol

Gecis launches one persistent process using:

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

- WebView never receives a filesystem path for `fenbi.db` or the AI binary.
- WebView communicates only through the narrow `GecisNative` JavaScript bridge.
- Antigravity runs with `--sandbox`; Gecis never adds `--dangerously-skip-permissions`.
- The future `fenbi.db` adapter must open the database read-only and inject only retrieved text into a prompt/context layer.
