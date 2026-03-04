# Signing the APK

The CI build produces an unsigned APK. Before it can be installed on an Android device, it must be signed. The signing step is also where the API key is embedded into the app.

## Why signing is done locally

The API key is injected at build time from a `local.properties` file in the project root. This file is gitignored and never committed to the repository. The CI build does not have access to this file, so it produces a placeholder APK that would fail authentication. Signing locally gives you the opportunity to substitute the real key.

## Prerequisites

- Java JDK installed (any recent version — JDK 17 recommended)
- The `keytool` and `apksigner` command-line tools (included with the Android SDK build tools, or available via your JDK)

## Step 1: Create a signing keystore (first time only)

You only need to do this once. Keep the keystore file somewhere safe — if you lose it, you will need to uninstall and reinstall the app on your device.

```bash
keytool -genkey -v -keystore lucos-photos.keystore \
    -alias lucos-photos \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000
```

You will be prompted to set a keystore password, key password, and your name/organisation. These can be anything — they are not verified externally.

## Step 2: Build a new APK with your API key

Rather than patching the unsigned APK from CI, the cleanest approach is to build locally with your key set:

1. Clone the repository:

    ```bash
    git clone https://github.com/lucas42/lucos_photos_android.git
    cd lucos_photos_android
    ```

2. Create `local.properties` in the project root with your API key:

    ```
    photos_api_key=YOUR_API_KEY_HERE
    ```

3. Build the release APK:

    ```bash
    ./gradlew assembleRelease
    ```

    The unsigned APK will be at `app/build/outputs/apk/release/app-release-unsigned.apk`.

## Step 3: Sign the APK

```bash
apksigner sign \
    --ks lucos-photos.keystore \
    --ks-key-alias lucos-photos \
    --out app-release-signed.apk \
    app/build/outputs/apk/release/app-release-unsigned.apk
```

You will be prompted for the keystore and key passwords you set in Step 1.

## Step 4: Verify the signature (optional)

```bash
apksigner verify --verbose app-release-signed.apk
```

## Step 5: Install

Transfer `app-release-signed.apk` to your Android device and install it as described in the main [README](../README.md).

---

## Updating the app

When a new version is released, repeat steps 2–4 using the **same keystore**. Android requires updates to be signed with the same key as the original installation — if you use a different keystore, you will need to uninstall the old version first (which will not affect your photos on the server).
