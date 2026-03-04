# lucos Photos Android

An Android app that automatically backs up photos from your device to a [lucos_photos](https://github.com/lucas42/lucos_photos) server.

## Installing the app

The app is not published to the Play Store. It is installed by sideloading a signed APK directly onto your device.

### What you need before you start

- An Android device running Android 8.0 (Oreo) or later
- An API key for the lucos_photos server (contact the server owner)
- A computer with Java and the Android SDK installed (to build and sign the APK)

### Steps

**1. Get the API key**

Ask the server owner for an API key. You will use this in the next step.

**2. Build and sign the APK**

The app is not available as a ready-to-install download, because the API key is baked in at build time and is unique to each user. You need to build the APK locally with your API key, then sign it.

See [docs/signing.md](docs/signing.md) for step-by-step instructions.

**3. Transfer the signed APK to your phone**

Copy the signed APK to your Android device — for example via USB, cloud storage, or email.

**4. Allow installation from unknown sources**

Android blocks app installs from outside the Play Store by default. You need to grant permission for whichever app you use to open the APK (e.g. your file manager or browser):

1. Open **Settings** on your phone
2. Go to **Apps** (or **Apps & notifications**)
3. Tap the menu and choose **Special app access** (or search for "Install unknown apps")
4. Find the app you will use to open the APK (e.g. Files, Chrome)
5. Enable **Allow from this source**

**5. Install the APK**

Open the APK file on your device and tap **Install** when prompted.

**6. Grant media access**

On first launch, the app will ask for permission to access your photos. Tap **Allow** (or **Allow all photos**) so it can find and upload your images.

The app will then run in the background and automatically upload new photos to the server roughly once per hour.

---

For technical details (building from source, how signing works, CI setup), see the [docs/](docs/) directory.
