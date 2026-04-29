# TC22R, EM45, eConnex: Test Connect RFID Sample (v1.0.0)

A specialized Android application for Zebra TC22 mobile computers equipped with RFD40 RFID Sleds. Written in **Kotlin** for enhanced stability and modern lifecycle management.

## Key Features

- **Kotlin**: Fully written in Kotlin 1.8.20, leveraging Coroutines-ready patterns and `DefaultLifecycleObserver`.
- **Auto-Connect Logic**: Automatically detects and connects to Zebra RFID sleds via USB or Bluetooth.
- **Connection Benchmarking**: Real-time display of connection latency (ms) directly on the UI status bar.
- **Thread-Safe UI**: Implements robust UI updates using `runOnUiThread` and Kotlin's property accessors to prevent `ConcurrentModificationException`.
- **Material Design 3**: Modern UI featuring Material Cards, pill-shaped buttons, and reactive button states.
- **Multi-Column Tag Display**: Real-time list view showing Tag ID (monospace), Read Count, and Peak RSSI.
- **Stability Testing**: Built-in "Connection Test Loop" to stress-test and benchmark reader attachment/detachment performance.
- **Permission Handling**: Seamlessly manages Bluetooth (Android 12+) or USB permissions.

## Technical Highlights

- **Lifecycle-Aware RFID Management**: `RFIDHandler` implements `DefaultLifecycleObserver`, automatically managing connection states during Android Activity transitions (`onResume`, `onDestroy`).
- **Robust Threading Model**: Replaced deprecated `AsyncTask` with a centralized `SingleThreadExecutor` in `RFIDHandler` to ensure sequential, non-blocking execution of SDK commands.
- **Idempotent Connection Logic**: Advanced state guarding prevents race conditions and redundant connection attempts during rapid USB attach/detach events.
- **Thread-Safe UI Updates**: All SDK callbacks are marshaled to the UI thread via `runOnUiThread`, ensuring stability even under high-volume tag reads.
- **Zebra RFID SDK Integration**: Abstracts complex reader lifecycle events (Appeared, Disappeared, Status notifications) into a simplified internal state machine.

## Setup

1. Clone the repository.
2. Open in Android Studio **or** use the provided build script (see below).
3. Ensure Zebra RFID SDK libraries are present in the `app/libs/` folder.
4. Connect a Zebra TC22/TC27 device via USB with ADB enabled.

## Build & Run Script

A convenience shell script `build_run.sh` automates the full workflow from the project root:

```bash
./build_run.sh
```

Steps performed:
1. `./gradlew clean` — removes all previous build artifacts.
2. `./gradlew assembleDebug` — compiles and packages the debug APK.
3. Verifies an ADB device is connected (exits with an error if none found).
4. `adb install -r` — installs (or re-installs) the APK on the device.
5. `adb shell am start` — launches `MainActivity` automatically.

**Requirements**: Android SDK Platform-Tools (`adb`) must be on your `PATH`.

## Usage

- **Connect/Disconnect**: Use the top-right menu to manually manage reader state.
- **Inventory**: Press the physical trigger on the RFD40 sled or use the "Start/Stop" buttons in the app.
- **Loop Test**: Use the "Run Connection Test Loop" menu item to perform an automated stress test of the connection logic. See [TestConnect.md](TestConnect.md) for details.

---
*Developed as a sample for high-reliability RFID connectivity on Zebra mobile devices.*
