# RFID SDK Lifecycle & Connection Design (v1.0.0)

This document describes the design for initializing, connecting, and disconnecting the Zebra RFID SDK within the AutoConnect RFID application, written in Kotlin.

## 1. SDK Initialization

Initialization is managed through `RFIDHandler.kt`, which acts as a lifecycle-aware controller.

### Entry Point — `InitRfidSDK()` (MainActivity)
`InitRfidSDK()` is the top-level factory method called from `MainActivity`. It always tears down any existing handler before creating a new one:
1. Calls `rfidHandler?.onDestroy()` and sets `rfidHandler = null`.
2. Instantiates a fresh `RFIDHandler`.
3. Calls `rfidHandler?.onCreate(this)` to wire up the activity context.

### `RFIDHandler.onCreate(activity)`
1. Stores the `MainActivity` reference and the `statusTextViewRFID` `TextView`.
2. Registers itself as a `DefaultLifecycleObserver` on the UI thread via `activity.lifecycle.addObserver(this)`.
3. Calls `initSDK()`.

### `RFIDHandler.initSDK()`
- Guarded by `@Volatile bInit` and `@Synchronized` to prevent double-initialization.
- Re-creates a shut-down `SingleThreadExecutor` if needed.
- On the executor background thread:
  - If `readers == null`: calls `createInstance()` (transitions state to `CONNECTING`, waits 1 s for USB subsystem, then creates the `Readers` object with `ENUM_TRANSPORT.ALL` and attaches the event handler).
  - If `readers != null`: calls `connectReader()` directly.

## 2. Connection State Machine

`RFIDHandler` maintains a formal `ConnectionState` enum:

| State | Meaning |
|---|---|
| `DISCONNECTED` | No active reader connection |
| `CONNECTING` | Connection attempt in progress |
| `CONNECTED` | Reader successfully connected |
| `ERROR` | SDK or connection error |

All state transitions go through `updateState(newState)`, which is `@Synchronized` and drives all UI side-effects:
- **`CONNECTING`**: calls `MainActivity.showLoading(msg)`.
- **`CONNECTED`**: calls `beep()`, hides loading overlay, sets `textRFIDStatusLabel` to "RFID Status: Connected", appends latency (`lAPIConnectTime ms`) to the status string.
- **`DISCONNECTED`**: hides loading overlay, sets `textRFIDStatusLabel` to "RFID Status: Disconnected".
- **`ERROR`**: hides loading overlay.

## 3. Connection Logic

### Reader Discovery — `getAvailableReader()`
Queries `readers.GetAvailableRFIDReaderList()`. Selection priority:
- If only one reader is found → use it.
- If multiple readers exist → pick the first whose name contains any of: `-G`, `RFID`, `TC27R`, or `TC22R` (covers eConnex/USB-CIO, EM45RFID, TC53RFID, TC22R, TC27R integrated readers).
- Non-matching readers are skipped and logged.

### `connectReader()`
- `@Synchronized`; double-checked locking: skips if already connected.
- Re-creates a shut-down executor if needed.
- Dispatches a background task that calls `getAvailableReader()` then `connectMethod()`.

### `connectMethod()`
1. Calls `reader.connect()`, timing the call with `System.currentTimeMillis()` → stored in `lAPIConnectTime`.
2. On success calls `configureReader()` then `updateState(CONNECTED)` and shows a toast with latency.

### `configureReader()`
Registers an `EventHandler` and enables:
- `setHandheldEvent(true)` — physical trigger events.
- `setTagReadEvent(true)` — tag read notifications.
- `setAttachTagDataWithReadEvent(false)` — tag data delivered via `getReadTags()` callback only.
- `setReaderDisconnectEvent(true)` — hardware disconnect notifications.

## 4. Disconnection & Cleanup

### `dispose(silent)`
1. Transitions state to `DISCONNECTED`.
2. Calls `reader.disconnect()` if connected (shows "Disconnected" toast unless `silent = true`).
3. Calls `readers.Dispose()` and nulls both `reader` and `readers`.
4. Resets `bInit = false`.

### `onDestroy(silent)` (programmatic)
Calls `dispose(silent)` then `executor.shutdownNow()`.

### `onDestroy(owner)` (lifecycle callback)
Removes the lifecycle observer, then calls `onDestroy(false)`.

## 5. Hardware Event Handling

### SDK-level events (via `Readers.RFIDReaderEventHandler`)
- **`RFIDReaderAppeared`**: plays a beep, shows a toast, calls `connectReader()`.
- **`RFIDReaderDisappeared`**: shows a toast, transitions state to `DISCONNECTED`.

### OS-level USB events (via `BroadcastReceiver` in `MainActivity`)
- **`USB_DEVICE_ATTACHED`**: checks vendor ID against `RFID_VID = 1504`; if matched, calls `InitRfidSDK()`.
- **`USB_DEVICE_DETACHED`**: calls `rfidHandler?.onDestroy()` and nulls the handler.
- **`USB_PERMISSION`**: shows a granted/denied toast.

The receiver is registered only when USB devices are already present at startup (`requestPermission()`), with `RECEIVER_EXPORTED` on Android 14+.

### `DISCONNECTION_EVENT` (inside `EventHandler.eventStatusNotify`)
When the SDK fires a software disconnect notification, the handler explicitly calls `reader.disconnect()` then `updateState(DISCONNECTED)`.

## 6. Lifecycle Awareness

`RFIDHandler` implements `DefaultLifecycleObserver`:
- **`onResume()`**: calls `connectReader()` — ensures the reader is ready whenever the user returns to the app.
- **`onDestroy()`**: removes the observer and triggers full cleanup.

## 7. Threading Model

All blocking SDK calls run on a `SingleThreadExecutor`:
1. Prevents UI hangs (`NetworkOnMainThreadException`).
2. Ensures sequential execution during stress testing and rapid USB events.
3. The executor is re-created after `shutdownNow()` if a new connection is needed (e.g., after stress test cleanup).

All UI mutations are dispatched via `context.runOnUiThread()`.

`beep()` runs on its own short-lived `Thread` (two 200 ms tones, 400 ms apart) to avoid blocking the executor.

## 8. Inventory & Tag Display

- **`performInventory()` / `stopInventory()`**: submitted to the `SingleThreadExecutor`; call `reader.Actions.Inventory.perform/stop()`.
- **`StartInventory` / `StopInventory`** (button callbacks in `MainActivity`): disable/re-enable the `TestButton` and delegate to `rfidHandler`.
- **`handleTriggerPress(pressed)`**: physical trigger press clears the tag list, disables `TestButton`, and starts inventory; release re-enables the button and stops inventory.
- **Tag list**: `TagItem(tagID, count, rssi)` entries backed by a `HashMap<String, TagItem>` for O(1) dedup; rendered via a custom `TagAdapter` into `R.layout.tag_list_item` (three columns: Tag ID, Count, Peak RSSI).
- Tag data is read in batches of 100 via `reader.Actions.getReadTags(100)` inside `EventHandler.eventReadNotify`.

## 9. ResponseHandlerInterface

Defined inside `RFIDHandler`, implemented by `MainActivity`:

```kotlin
interface ResponseHandlerInterface {
    fun handleTagdata(tagData: Array<TagData>)
    fun handleTriggerPress(pressed: Boolean)
    fun barcodeData(valStr: String)
    fun sendToast(valStr: String)
}
```

## 10. Permission Handling

- **Bluetooth** (Android 12+): requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` at startup; `InitRfidSDK()` is deferred until granted.
- **USB**: managed via the `BroadcastReceiver` described in §5.

---

## Change Log
## 11. Flowcharts

All source files are in `diagrams/` (`.mmd`). PNG renders are alongside each source file.

### 11.1 SDK Initialization

![SDK Init](diagrams/01_sdk_init.png)

```mermaid
flowchart TD
  A([App Launch / InitRfidSDK]) --> B{rfidHandler\nnot null?}
  B -- Yes --> C[rfidHandler.onDestroy\nrfidHandler = null]
  C --> D[Create new RFIDHandler]
  B -- No --> D
  D --> E[rfidHandler.onCreate activity]
  E --> F[Store context + textView\nlifecycle.addObserver on UI thread]
  F --> G[initSDK]
  G --> H{bInit AND\nreaders != null?}
  H -- Yes --> I[connectReader]
  H -- No --> J[Set bInit=true\nRe-create executor if shutdown]
  J --> K{readers == null?}
  K -- Yes --> L[updateState CONNECTING\nSleep 1s\ncreateInstance]
  L --> M[readers = Readers ENUM_TRANSPORT.ALL\nReaders.attach this]
  M --> I
  K -- No --> I
  I --> Z([SDK Ready])
```

---

### 11.2 Bluetooth Connection

![Bluetooth Connect](diagrams/02_bluetooth_connect.png)

```mermaid
flowchart TD
  A([MainActivity.onCreate]) --> B{Android 12+?}
  B -- No --> C[InitRfidSDK]
  B -- Yes --> D{BLUETOOTH_CONNECT\npermission granted?}
  D -- Yes --> C
  D -- No --> E[requestPermissions\nBLUETOOTH_SCAN +\nBLUETOOTH_CONNECT]
  E --> F{Permission\nResult}
  F -- Granted --> C
  F -- Denied --> G([Toast: Permissions\nnot granted])
  C --> H[RFIDHandler created\nENUM_TRANSPORT.ALL]
  H --> I[SDK discovers BT readers\nGetAvailableRFIDReaderList]
  I --> J{Reader found?}
  J -- No --> K([updateState ERROR])
  J -- Yes --> L[reader.connect\nrecord lAPIConnectTime]
  L --> M[configureReader\nEvents enabled]
  M --> N([updateState CONNECTED\nbeep + toast])
```

---

### 11.3 USB Connection & Disconnection

![USB Connect](diagrams/03_usb_connect.png)

```mermaid
flowchart TD
  A([USB Cable Plugged In]) --> B[OS fires USB_DEVICE_ATTACHED]
  B --> C{BroadcastReceiver\nvendorId == 1504?}
  C -- No --> D([Ignore device])
  C -- Yes --> E[InitRfidSDK]
  E --> F[createInstance\nreaders = Readers ENUM_TRANSPORT.ALL\nReaders.attach]
  F --> G[RFIDReaderAppeared callback]
  G --> H[beep + Toast\nconnectReader]
  H --> I[getAvailableReader\nselects -G / RFID / TC22R / TC27R]
  I --> J[connectMethod\nreader.connect\nrecord lAPIConnectTime]
  J --> K[configureReader]
  K --> L([updateState CONNECTED])

  M([USB Cable Unplugged]) --> N[OS fires USB_DEVICE_DETACHED]
  N --> O[BroadcastReceiver\nrfidHandler.onDestroy\nrfidHandler = null]
  O --> P[dispose\nreader.disconnect\nreaders.Dispose]
  P --> Q([updateState DISCONNECTED])
```

---

### 11.4 Transport Interface Selection

![Transport Interface](diagrams/04_transport_interface.png)

```mermaid
flowchart TD
  A([createInstance]) --> B[readers = Readers context\nENUM_TRANSPORT.ALL]
  B --> C[Readers.attach eventHandler]
  C --> D[SDK scans ALL transports\nUSB + Bluetooth + Serial]
  D --> E[GetAvailableRFIDReaderList]
  E --> F{Single reader\nin list?}
  F -- Yes --> G[Use it directly]
  F -- No --> H{Name contains\n-G or RFID or\nTC22R or TC27R?}
  H -- Yes --> I[Select matched reader\nLog: Found eConnex-USB-CIO]
  H -- No --> J[Skip + Log: Ignore non-eConnex]
  J --> H
  G --> K[reader = readerDevice.rfidReader]
  I --> K
  K --> L([connectReader called])
```

---

### 11.5 getAvailableReader

![getAvailableReader](diagrams/05_get_available_reader.png)

```mermaid
flowchart TD
  A([getAvailableReader]) --> B[Reset readerDevice = null\nreader = null]
  B --> C{readers != null?}
  C -- No --> Z([reader remains null])
  C -- Yes --> D[availableRFIDReaderList =\nGetAvailableRFIDReaderList]
  D --> E{list not null\nAND size > 0?}
  E -- No --> F[Log: No available readers\nupdateState ERROR]
  F --> Z
  E -- Yes --> G{list.size == 1?}
  G -- Yes --> H[readerDevice = list 0]
  G -- No --> I{device.name contains\n-G or RFID or\nTC27R or TC22R?}
  I -- Yes --> J[readerDevice = device\nLog: Found eConnex-USB-CIO\nbreak loop]
  I -- No --> K[Log: Ignore non-eConnex\nnext device]
  K --> I
  H --> L[reader = readerDevice.rfidReader]
  J --> L
  L --> Z2([reader reference set])
```

---

### 11.6 Connect

![Connect](diagrams/06_connect.png)

```mermaid
flowchart TD
  A([connectReader]) --> B{isReaderConnected?}
  B -- Yes --> Z([Already connected])
  B -- No --> C{executor shutdown?}
  C -- Yes --> D[Re-create\nSingleThreadExecutor]
  D --> E
  C -- No --> E[Submit background task]
  E --> F{isReaderConnected?\ndouble-check in task}
  F -- Yes --> Z
  F -- No --> G[updateState CONNECTING]
  G --> H[getAvailableReader]
  H --> I{reader != null?}
  I -- No --> J([Log: Failed\nupdateState ERROR])
  I -- Yes --> K[connectMethod]
  K --> L{r.isConnected\nalready?}
  L -- Yes --> M([updateState CONNECTED])
  L -- No --> N[lStart = currentTimeMillis\nr.connect\nlAPIConnectTime = elapsed]
  N --> O[configureReader\naddEventsListener\nsetHandheldEvent true\nsetTagReadEvent true\nsetReaderDisconnectEvent true]
  O --> P{r.isConnected\nafter connect?}
  P -- Yes --> Q([updateState CONNECTED\nToast: Connected Xms\nbeep])
  P -- No --> R([updateState ERROR])
```

---

### 11.7 Disconnect

![Disconnect](diagrams/07_disconnect.png)

```mermaid
flowchart TD
  A([Disconnect Triggered]) --> B{Trigger Source}
  B --> C[Manual: Disconnect menu]
  B --> D[Lifecycle: onDestroy owner]
  B --> E[USB Detach BroadcastReceiver]
  B --> F[SDK: DISCONNECTION_EVENT]
  B --> G[Stress Test: onDestroy silent=true]

  C --> H[onDestroy silent=false]
  D --> I[lifecycle.removeObserver\nonDestroy false]
  E --> J[rfidHandler.onDestroy\nrfidHandler = null]
  F --> K[reader.disconnect\nupdateState DISCONNECTED]
  G --> L[onDestroy silent=true]

  H --> M[dispose silent=false]
  I --> M
  J --> M
  L --> N[dispose silent=true]

  M --> O[updateState DISCONNECTED]
  N --> O
  O --> P{reader.isConnected?}
  P -- Yes --> Q{silent?}
  Q -- No --> R[reader.disconnect\nToast: Disconnected]
  Q -- Yes --> S[reader.disconnect\nno toast]
  P -- No --> T
  R --> T[readers.Dispose\nreaders=null\nreader=null\nbInit=false]
  S --> T
  T --> U[executor.shutdownNow]
  U --> Z([Fully Disconnected])
```

---

### 11.8 Interface Changing

![Interface Change](diagrams/08_interface_change.png)

```mermaid
flowchart TD
  A([Interface Change Event]) --> B{Event Source}
  B --> C[USB DETACH\nBroadcastReceiver]
  B --> D[SDK: RFIDReaderDisappeared]
  B --> E[SDK: DISCONNECTION_EVENT]

  C --> F[rfidHandler.onDestroy\nrfidHandler = null]
  D --> G[Toast RFIDReaderDisappeared\nupdateState DISCONNECTED]
  E --> H[reader.disconnect\nupdateState DISCONNECTED]

  F --> I([DISCONNECTED State])
  G --> I
  H --> I

  I --> J{New Interface\nArrives}
  J --> K[USB ATTACH\nvendorId == 1504\nInitRfidSDK]
  J --> L[SDK: RFIDReaderAppeared\nbeep + connectReader]
  J --> M[Activity onResume\nconnectReader]

  K --> N[New RFIDHandler\nENUM_TRANSPORT.ALL\ncreateInstance]
  N --> O
  L --> O[getAvailableReader\nselects new interface by name]
  M --> O

  O --> P[connectMethod\nreader.connect\nlAPIConnectTime recorded]
  P --> Q[configureReader\nEvents re-registered on new reader]
  Q --> R([updateState CONNECTED\nNew interface active])
```

---

## Change Log

### v1.0.0
- Added `build_run.sh` for automated clean/build/install/launch workflow.
- Formalized `ConnectionState` enum and centralized `updateState()` state machine.
- Expanded reader selection to include `RFID`, `TC27R`, `TC22R` device name patterns.
- Added OS-level USB `BroadcastReceiver` for `ATTACHED`/`DETACHED` events (vendor ID `1504`).
- `configureReader()` now enables `setReaderDisconnectEvent(true)` and disables `setAttachTagDataWithReadEvent`.
- `showLoading()` / `hideLoading()` overlay managed by `updateState()`.
- Tag list backed by `HashMap` for dedup; custom `TagAdapter` with three-column layout.
- `InitRfidSDK()` always tears down existing handler before creating a new one.

### v0.1.0
- Written in Kotlin 1.8.20.
- Implemented Reactive UI: "START" button auto-disables during inventory.
- Fixed UI synchronization: Primary "RFID Status" label now correctly syncs with detailed connection status.
- Added explicit `DISCONNECTION_EVENT` handling.
- Optimized threading with `SingleThreadExecutor`.

### v0.0.2
- Enhanced UI to show individual connection time in milliseconds.
- Switched to `ENUM_TRANSPORT.ALL` for better hardware compatibility.
- Improved reader selection logic to prefer eConnex interfaces.

### v0.0.1
- Fixed thread safety crash in stress test loop (Lifecycle observer registration).
- Added comprehensive documentation for the connection test logic.

### v0.0.0
- Initial release of the Auto-Connect sample.
- Added Stress Test Loop for connection benchmarking.
- Implemented `DefaultLifecycleObserver` for SDK management.
- Migrated all blocking operations to `ExecutorService`.

---
