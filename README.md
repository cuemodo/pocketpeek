<img src="docs/images/pocketpeek-icon.png" alt="The PocketPeek app icon: a grid of green channel levels with one warm lit cell" width="128">

# PocketPeek

by [Cuemodo](https://cuemodo.io)

Art-Net DMX monitor for small Android screens, built for the Rabbit R1 (CipherOS). It runs on
Android 5.1 (API 22) and later, phones and tablets included. Colors follow the DMX tab of consolelink.

## Install

No computer needed. On the Android device:

1. Open **[github.com/cuemodo/pocketpeek/releases/latest](https://github.com/cuemodo/pocketpeek/releases/latest)**
   in the browser.
2. Under **Assets**, tap `pocketpeek-<version>.apk` to download it.
3. Open the downloaded file. If Android asks, allow your browser to **install unknown apps**, then go back
   and tap **Install**.
4. If Play Protect warns about an unknown developer, tap **More details** → **Install anyway**.

Alternatively, you can sideload the apk from a computer with : `adb install -r <path to apk>`

To update, install the newer APK the same way; your settings are kept. To be notified of new versions,
add `https://github.com/cuemodo/pocketpeek` to [Obtainium](https://obtainium.imranr.dev/).

The device must be on the same network as the console (Art-Net uses UDP port 6454).

## Features

- The grid adapts to the screen: it shows as many channels per page as it can while keeping each cell
  readable (at least 56 x 32 dp), choosing from 32x16 (a whole universe), 16x32, 16x16, 16x8, 8x16,
  8x8, 4x16 and 4x8. Wide cells show the channel number and level side by side; squarer cells stack them.
- The page bar shows the highest level on each page so you can see where activity is (up to 8 tabs per
  row; hidden when the whole universe fits on one page). Changing layout keeps the same first channel.
- The app follows the device's natural orientation (portrait on the R1 and phones, landscape on the frame).
- Universe: `−` / `+`, or tap the universe chip for a list of active universes (with fps and source IP)
  or to type one (`12` or `net:sub:uni`, e.g. `0:0:12`). Universes are Art-Net port addresses (0-based).
- Values: tap the mode chip to cycle `%` (F = full) / `255` / `HEX`. Zero is blank, as in consolelink.
- Tap a cell for a live readout of that channel; tap the readout line to clear it.
- Pages: swipe left/right, tap or drag along the page bar, or use the scroll wheel (it sends volume keys).
- No data for 2.5 s: the grid dims and holds the last look, and the status line counts the time.
- Answers ArtPoll, so consoles that only send to discovered nodes find it as "PocketPeek" with one output
  on the selected universe.

## Tested devices

| Device | Android | Screen | Grid |
|---|---|---|---|
| Rabbit R1 (CipherOS) | 16 (API 36) | 480x640 portrait, density 190 | 4x8, 16 pages |
| Phone emulator (Medium Phone) | 17 (API 37) | 1080x2400 portrait, density 420 | 4x16, 8 pages |
| Rockchip rk312x photo frame | 5.1 (API 22) | 1280x800 landscape, density 160 | 16x16, 2 pages |

The layout choice is `GridShape.choose()` in `Format.kt`; unit tests pin these three cases.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/r1.png" alt="Rabbit R1: 4x8 grid" height="400"><br>Rabbit R1 (4x8)</td>
    <td align="center"><img src="docs/screenshots/phone.png" alt="Phone emulator: 4x16 grid" height="400"><br>Phone emulator (4x16)</td>
  </tr>
  <tr>
    <td colspan="2" align="center"><img src="docs/screenshots/photo-frame.png" alt="Photo frame: 16x16 grid" width="640"><br>Photo frame (16x16)</td>
  </tr>
</table>

On the photo frame, the floating ◁ ○ □ buttons come from its launcher, not from this app. The phone
emulator receives packets sent from inside itself (127.0.0.1), hence the low frame rate.

## Build from source

```sh
./gradlew assembleDebug                      # add --offline when the Gradle cache is warm
adb install -r app/build/outputs/apk/debug/pocketpeek-*-debug.apk
adb shell am start -n io.cuemodo.pocketpeek/.MainActivity
```

Local builds are signed with your machine's debug key, so they can't install over a release APK (or the
reverse): `adb uninstall io.cuemodo.pocketpeek` first. Uninstalling clears the app's settings.

## Releasing

1. Set `versionName` in `app/build.gradle.kts` (`versionCode` follows it: `0.2` → 200) and commit.
2. Create an annotated tag and push it with `main`:

   ```sh
   git tag -a v0.2 -m "PocketPeek 0.2"
   git push --follow-tags origin main
   ```

`.github/workflows/release.yml` checks that the tag matches `versionName`, runs the unit tests, builds a
signed APK, refuses it if it is debug-signed, and publishes a GitHub Release with `pocketpeek-0.2.apk`
and its SHA-256. If a run fails, no release is created: delete the tag
(`git push --delete origin v0.2 && git tag -d v0.2`), fix, and tag again.

The release key lives in repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `POCKETPEEK_KEYSTORE_BASE64` | output of `base64 -i pocketpeek-release.jks` |
| `POCKETPEEK_KEYSTORE_PASSWORD` | keystore password |
| `POCKETPEEK_KEY_ALIAS` | `pocketpeek` |
| `POCKETPEEK_KEY_PASSWORD` | key password: same as the keystore password (`keytool` asks for only one) |

The keystore is created once with:

```sh
keytool -genkeypair -keystore pocketpeek-release.jks -alias pocketpeek \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cuemodo"
```

Keep it backed up offline, outside the repo: without it, no update can ever install over an existing
PocketPeek. To sign a release locally, export the same variables with `POCKETPEEK_KEYSTORE` set to the
keystore path instead of `POCKETPEEK_KEYSTORE_BASE64`. Without them, `assembleRelease` falls back to the
debug key; don't distribute those APKs.

## Unit tests

```sh
./gradlew testDebugUnitTest
```

JVM tests (no device needed) cover universe parsing/labels, value formatting, the grid layout choice,
ArtDmx decoding, the ArtPollReply layout, and a loopback UDP round trip. GitHub Actions runs them, plus a debug
build, on every push to `main` and every pull request (`.github/workflows/test.yml`), and again before each release.

## Test without a console

```sh
python3 tools/artnet_test_sender.py                       # broadcast universes 0 and 1
python3 tools/artnet_test_sender.py --ip <R1-IP> -u 0 3   # unicast
```

The idle status line shows the device's IP. The API 37 emulator does not receive host UDP via
`adb emu redir`; inject packets from inside it instead (plain `nc -u` waits forever, so kill it):

```sh
adb push artdmx.bin /data/local/tmp/       # an ArtDmx packet saved to a file
adb shell 'for i in $(seq 1 100); do nc -u 127.0.0.1 6454 < /data/local/tmp/artdmx.bin & p=$!; sleep 0.1; kill $p; done'
```

## R1 hardware keys

The scroll wheel sends volume up/down keys, which change page. The side button is the power key: Android
handles it (sleep/lock) and apps can't remap it. Unmapped keys are logged; to see what other hardware sends:

```sh
adb logcat -s PocketPeek
```

## Credits

Art-Net™ Designed by and Copyright Artistic Licence Engineering Ltd

## License

MIT. See [LICENSE](LICENSE).
