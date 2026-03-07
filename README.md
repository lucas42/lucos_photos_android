# lucos Photos Android

An Android app that automatically backs up photos from your device to a [lucos_photos](https://github.com/lucas42/lucos_photos) server.

## Installing the app

The app is not published to the Play Store. It is installed by sideloading a signed APK directly onto your device.

### What you need before you start

- An Android device running Android 8.0 (Oreo) or later

### Steps

**1. Download the signed APK**

A signed APK is built automatically by CircleCI on every merge to `main`. Download the latest one from the [CircleCI artifacts](https://app.circleci.com/pipelines/github/lucas42/lucos_photos_android) for the `production-build-apk` job.

The API key for the lucos_photos server is baked in at build time, so no manual key handling is needed.

**2. Transfer the APK to your phone**

Copy the downloaded APK to your Android device — for example via USB, cloud storage, or email.

**3. Allow installation from unknown sources**

Android blocks app installs from outside the Play Store by default. You need to grant permission for whichever app you use to open the APK (e.g. your file manager or browser):

1. Open **Settings** on your phone
2. Go to **Apps** (or **Apps & notifications**)
3. Tap the menu and choose **Special app access** (or search for "Install unknown apps")
4. Find the app you will use to open the APK (e.g. Files, Chrome)
5. Enable **Allow from this source**

**4. Install the APK**

Open the APK file on your device and tap **Install** when prompted.

**5. Grant media access**

On first launch, the app will ask for permission to access your photos. Tap **Allow** (or **Allow all photos**) so it can find and upload your images.

The app will then run in the background and automatically upload new photos to the server roughly once per hour.

---

For technical details (building from source, how signing works, CI setup), see the [docs/](docs/) directory.
