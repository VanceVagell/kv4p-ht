# CI debug signing key

`kv4p-ci-debug.keystore` is an intentionally public signing key used only for
PR and nightly debug APKs. Keeping this key stable lets one CI build update a
previous CI build without uninstalling the app first.

This key provides no authenticity: anyone with the repository can sign an APK
with it. Never use it for a production release, an app store upload, or any
build presented as trusted. Production signing keys must remain private.

- Store password: `android`
- Key alias: `kv4p-ci-debug`
- Key password: `android`
- SHA-256 certificate fingerprint:
  `24:5E:4C:B9:A6:AC:EA:30:C1:2F:8E:FA:11:2F:3B:64:EB:69:FD:E9:43:59:75:22:FA:24:F7:AA:FA:6E:B9:5C`

Gradle uses this key for every `debug` build, including Android Studio, PR, and
nightly builds. This shared certificate lets those builds update each other.
