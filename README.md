# InfinAssistant Bridge

Android app that connects an InfinAssistant watch (PineTime running the InfinAssistant firmware) to Home Assistant. It relays the watch's sensor readings and notifications to Home Assistant, and it carries commands from Home Assistant back to the watch. Earlier builds were named "PineTime HA Bridge".

## Install

1. Download the APK from the latest [GitHub Release](https://github.com/ryan-werts/infinassistant-bridge/releases).
2. Install it on an Android device running Android 12 or later (minSdk 31). Allow installs from your browser or file manager if Android asks.
3. Follow the [setup guide](docs/setup-guide.md) to pair the watch and connect to Home Assistant.

Release APKs are signed with the release certificate. Its SHA-256 fingerprint is below.

**SHA-256 of the release signing certificate:**
`92:7C:3F:CD:79:1D:A6:61:AE:06:03:09:0C:B9:1C:55:0E:E5:9C:FB:7B:92:8E:8E:C6:69:90:5A:09:7A:5A:EC`

Check a downloaded APK with `apksigner verify --print-certs <apk>`; its "certificate SHA-256 digest" must match this, ignoring the colons and the letter case.

## Compatibility

<!-- compat:begin -->

| Component | Version | Release |
|-----------|---------|---------|
| Firmware (InfiniTime fork) | 2.4.105 | [2.4.105](https://github.com/ryan-werts/infinassistant-firmware/releases/tag/2.4.105) |
| Android app (InfinAssistant Bridge) | 2.1.127 | [2.1.127](https://github.com/ryan-werts/infinassistant-bridge/releases/tag/2.1.127) |
| Home Assistant integration | 0.3.5 | [0.3.5](https://github.com/ryan-werts/infinassistant-ha/releases/tag/0.3.5) |

**Breaking pairs.** These pairs must be upgraded together:

- App 2.1.124 or later needs firmware 2.4.103 or later. The watch's BLE config key was renamed from `infinisstant` to `infinassistant`, and older firmware is not supported. The app detects older firmware and offers the update.
- App 2.1.124 or later needs integration 0.3.3 or later. The Home Assistant domain was renamed from `infinisstant` to `infinassistant`.

Other version mismatches degrade gracefully: the HELLO feature bits tell each side what the other supports. If the watch does not report feature bit 16, the app shows "Update the watch" and offers the firmware bundled with it.

<!-- compat:end -->

## Bundled firmware

Each release bundles these watch firmware versions. The release notes link each one to its source tag.

- **2.4.105**: [source at tag 2.4.105](https://github.com/ryan-werts/infinassistant-firmware/releases/tag/2.4.105). Licensed GPL-3.0; the source for the bundled zip is at that tag, and [NOTICE-MODIFICATIONS.md](https://github.com/ryan-werts/infinassistant-firmware/blob/infinassistant/NOTICE-MODIFICATIONS.md) lists what this fork changes from InfiniTime.

## Build from source

    git clone https://github.com/ryan-werts/infinassistant-bridge.git
    cd infinassistant-bridge
    ./gradlew assembleDebug

For local settings, copy `owner-overrides.properties.example` to `owner-overrides.properties` and edit the copy. The copy is git-ignored, so keep it out of commits.

## Signing (maintainers)

`assembleRelease` signs only when these environment variables are set. Without them it builds an unsigned APK.

- `ANDROID_KEYSTORE_PATH`: path to the keystore file
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The release workflow reads the keystore from the repository secret `ANDROID_KEYSTORE_B64` (base64), and the passwords and alias from the secrets of the same names. The keystore is never stored in the repository.

## Contributing

This repository is a mirror of the InfinAssistant source repository. Pull requests are applied to the source repository first, and they reach this repository with the next export. Open an issue to discuss larger changes before you start.

The CI's golden-vector job (`golden-vectors` in `.github/workflows/ci.yml`) downloads the protocol vectors from the public firmware repository at the tag named in `core-data/src/test/resources/golden-vectors.pin`. That job fails until the tag exists publicly.

## License

MIT. See [LICENSE](LICENSE). Copyright (c) W6 Technology.
