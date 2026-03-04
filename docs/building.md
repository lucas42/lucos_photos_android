# Building from Source

## Requirements

- Android SDK with API level 35 (Android 15) and build tools
- Gradle (the project includes a wrapper — `./gradlew` — so no global install is needed)
- JDK 17

## Running the tests

```bash
./gradlew test
```

## Building a debug APK

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`. Debug builds can be installed directly from Android Studio or via `adb`.

## Building a release APK

To build a release APK with your API key, first create `local.properties` in the project root:

```
photos_api_key=YOUR_API_KEY_HERE
```

Then build:

```bash
./gradlew assembleRelease
```

The unsigned APK will be at `app/build/outputs/apk/release/app-release-unsigned.apk`. See [signing.md](signing.md) for how to sign and install it.

## CI

Builds run on [CircleCI](https://app.circleci.com/pipelines/gh/lucas42/lucos_photos_android). The pipeline:

1. Runs unit tests
2. Builds a release APK (without the real API key — a placeholder is used)
3. Stores the APK as a build artifact

Because the CI APK uses a placeholder API key, it cannot be installed and used directly — you must build locally with the real key, or sign a locally-rebuilt APK as described in [signing.md](signing.md).
