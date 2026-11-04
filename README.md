# R1 Art-Net Monitor

Art-Net DMX monitor for the Rabbit R1 (CipherOS), sideloaded with adb. Colors follow the DMX tab of
consolelink.

- 4x8 grid = 32 channels per page, 16 pages per universe (sized for the R1's 44 mm wide screen); the
  page bar (2 rows of 8) shows the highest level on each page so you can see where activity is.
- Universe: `−` / `+`, or tap the universe chip for a list of active universes (with fps and source IP)
  or to type one (`12` or `net:sub:uni`, e.g. `0:0:12`). Universes are Art-Net port addresses (0-based).
- Values: tap the mode chip to cycle `%` (F = full) / `255` / `HEX`. Zero is blank, as in consolelink.
- Tap a cell for a live readout of that channel; tap the readout line to clear it.
- Pages: swipe left/right, tap or drag along the page bar, or use the scroll wheel (it sends volume keys).
- No data for 2.5 s: the grid dims and holds the last look, and the status line counts the time.
- Answers ArtPoll, so consoles that only send to discovered nodes find it as "R1 Art-Net" with one output
  on the selected universe.

## Build and install

```sh
./gradlew assembleDebug                      # add --offline when the Gradle cache is warm
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gaeljaffrain.r1artnet/.MainActivity
```

## Unit tests

```sh
./gradlew testDebugUnitTest
```

JVM tests (no device needed) cover universe parsing/labels, value formatting, ArtDmx decoding,
the ArtPollReply layout, and a loopback UDP round trip. GitHub Actions runs them, plus a debug
build, on every push to `main` and every pull request (`.github/workflows/test.yml`).

## Test without a console

```sh
python3 tools/artnet_test_sender.py                       # broadcast universes 0 and 1
python3 tools/artnet_test_sender.py --ip <R1-IP> -u 0 3   # unicast
```

The idle status line shows the R1's IP. Note the API 37 emulator does not receive host UDP via
`adb emu redir`; test on the device, or inject from inside the emulator with `nc -u 127.0.0.1 6454`.

## R1 hardware keys

Unmapped keys are logged; to see what the wheel/buttons send:

```sh
adb logcat -s R1ArtNet
```
