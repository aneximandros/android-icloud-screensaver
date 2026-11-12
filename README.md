# iCloud Photos Screensaver for Android TV

A native Android screensaver ([DreamService](https://developer.android.com/reference/android/service/dreams/DreamService)) that plays a fullscreen photo slideshow from any **iCloud Shared Album**.

No Apple account, no iCloud credentials, and no third-party services are required at runtime — the app talks directly to Apple's CloudKit Web Services API using the anonymous public access that Apple grants to anyone with the album link.

---

## Features

- Fade-in/fade-out transitions between photos (10-second slides, 1.5-second fades)
- Automatic pagination — works with albums of any size
- Photo cache: once a photo is downloaded it is not re-downloaded while the app is installed
- Simple in-app settings screen to paste the album URL
- Tested on Sony Android TV (Android 10/11)

---

## Prerequisites

### On your TV — enable ADB over the network

ADB (Android Debug Bridge) is how you install apps that are not on the Play Store. You only need to do this once.

1. **Find your TV's IP address**
   Settings → Network & Internet → (your Wi-Fi network) → the IP address is listed there.

2. **Unlock Developer Options**
   Settings → Device Preferences → About → scroll down to **Build number** and click it **7 times** in a row. You'll see a message saying "You are now a developer."

3. **Enable Network debugging**
   Settings → Device Preferences → Developer options → turn on **Network debugging** (sometimes called *ADB over network* or *USB debugging*). The screen will show your TV's IP address and port (usually `:5555`).

### On your computer — install ADB

ADB is a small command-line tool that comes with the Android SDK Platform Tools.

- **Mac:** `brew install android-platform-tools`
- **Windows:** Download [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) and unzip it. Add the folder to your PATH or run commands from inside it.
- **Linux:** `sudo apt install adb` (Debian/Ubuntu) or equivalent.

Verify it works:
```bash
adb version
```

---

## Setup

### 1. Connect to your TV

```bash
adb connect <TV_IP_ADDRESS>:5555
```

Replace `<TV_IP_ADDRESS>` with the IP shown in Developer options. Your TV may show a prompt asking you to allow the connection — accept it.

Verify the connection:
```bash
adb devices
# Should show something like: 192.168.1.42:5555  device
```

### 2. Get your iCloud Shared Album URL

1. Open **Photos** on your iPhone or iPad.
2. Open the album you want to share, tap the person icon (or Share button), and enable **Shared Album**.
3. Tap **Copy Link** — the URL looks like:
   ```
   https://photos.icloud.com/shared/album/06cFVANQcG8qXbo8OjtnvmhQw
   ```
   > The URL above is a live example album of public-domain paintings — paste it in to try the app immediately without creating your own album first.

4. The album must be set to **"Anyone with the link"** (the default for new Shared Albums).

> **Note:** This app requires the new-format `photos.icloud.com/shared/album/…` URL.  
> Old-format `icloud.com/photos/0…` links are not supported.

### 3. Install the APK

Build from source (see below) or download a pre-built APK from [Releases](../../releases).

```bash
adb install icloud-screensaver.apk
```

### 4. Enter the album URL

The **iCloud Screensaver** app will appear in your TV's app drawer. Open it, paste the album URL into the text field, and tap **Save**.

### 5. Enable the screensaver

Settings → Device Preferences → Screen saver → select **iCloud Photos** → tap **Start Now** to try it immediately.

The first time it runs there will be a few seconds of black screen while photos download. Subsequent runs use the local cache and start instantly.

---

## Building from source

Requirements: Android Studio (Hedgehog or later) or the Android command-line tools.

```bash
git clone https://github.com/aneximandros/android-icloud-screensaver.git
cd android-icloud-screensaver
./gradlew assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

Install to a connected TV:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## How it works — CloudKit API

Apple's iCloud Photos web app (`photos.icloud.com`) is backed by **CloudKit Web Services**. Public Shared Albums use an anonymous-access endpoint that requires no authentication, only the album token.

### Step 1 — Resolve the share token

```
POST https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production
     /public/records/resolve
     ?remapEnums=true&clientBuildNumber=2642Build18&clientMasteringNumber=2642Build18
     &sharing_url_key=<ALBUM_TOKEN>
Content-Type: text/plain
Origin: https://www.icloud.com

{"shortGUIDs":[{"value":"<ALBUM_TOKEN>"}]}
```

**Response** (abbreviated):
```json
{
  "results": [{
    "zoneID": {
      "zoneName": "SharedCollection-<UUID>",
      "ownerRecordName": "_<hex>"
    },
    "anonymousPublicAccess": {
      "token": "<ANON_TOKEN>",
      "tokenTTL": 1200,
      "databasePartition": "https://p48-ckdatabasews.icloud.com:443/"
    }
  }]
}
```

### Step 2 — Query photos

```
POST <databasePartition>/database/1/com.apple.photos.cloud/production
     /shared/records/query
     ?remapEnums=true&clientBuildNumber=2642Build18&clientMasteringNumber=2642Build18
     &publicAccessAuthToken=<ANON_TOKEN>
Content-Type: text/plain
Origin: https://www.icloud.com

{
  "query": {"recordType": "CPLAssetAndMasterByAddedDate"},
  "zoneID": {"zoneName": "SharedCollection-<UUID>", "ownerRecordName": "_<hex>"},
  "resultsLimit": 200
}
```

**Key discovery:** `CPLAssetAndMasterByAddedDate` is the only indexable record type for shared album zones. `CPLAsset` and `CPLMaster` queries by themselves fail with *"Type is not marked indexable"*.

**Response** contains alternating `CPLAsset` and `CPLMaster` records. Each `CPLMaster` has:

```json
"resJPEGMedRes": {
  "type": "ASSETID",
  "value": {
    "fileChecksum": "...",
    "size": 610103,
    "wrappingKey": "...",
    "referenceChecksum": "...",
    "downloadURL": "https://cvws-h2.icloud-content.com/B/.../${f}?..."
  }
}
```

Replace `${f}` in the `downloadURL` with any filename (e.g. `photo.jpeg`) to get the final URL. Apple's CDN serves a **plain JPEG** at ~1536 px wide, no client-side decryption needed.

### Notes

- `anonymousPublicAccess.tokenTTL` is 1200 seconds (20 min). The download URL expiry (`e=` param) is typically ~1 hour. For long screensaver sessions, re-fetch URLs periodically.
- `clientBuildNumber` comes from the `data-cw-private-build-number` attribute in `photos.icloud.com/shared/album/<token>` page HTML. It may change when Apple updates the web app, but the API appears to accept older values.
- Pagination: if the response includes a `continuationMarker` field, pass it in the next request body to retrieve additional photos.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `adb connect` times out | TV's Network debugging is off, or firewall is blocking port 5555 |
| `adb connect` says "connection refused" | TV and computer are on different networks or VLANs |
| TV shows an "Allow ADB?" prompt | Accept it; tick "Always allow from this computer" |
| Screensaver starts then immediately stops | Album URL not saved, or album is private |
| Black screen for a few seconds then photos appear | Normal — first-run download; subsequent runs use the cache |
| `fetchPhotoUrls: no anonymousPublicAccess` in logcat | Album is set to invite-only, not "Anyone with the link" |
| `httpPost -> 401` | `clientBuildNumber` may need updating to the latest value |

Enable logcat filtering with tag `ICloudPhotosDream` for detailed diagnostics:
```bash
adb logcat -s ICloudPhotosDream
```

---

## License

MIT — see [LICENSE](LICENSE).
