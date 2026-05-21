package com.zebra.rfid.demo.sdksample

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.zebra.rfid.api3.*
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

class RFIDHandler : Readers.RFIDReaderEventHandler, DefaultLifecycleObserver {

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private var currentState = ConnectionState.DISCONNECTED
    private val TAG = "RFID_HANDLER"
    private var readers: Readers? = null
    private var availableRFIDReaderList: ArrayList<ReaderDevice>? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null
    var textView: TextView? = null
    private var eventHandler: EventHandler? = null
    private var context: MainActivity? = null

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()

    var readerName = "-G"
    private var lStart: Long = 0
    private var lAPIConnectTime: Long = 0

    @Volatile
    private var bInit = false

    private val toneG = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

    @Synchronized
    private fun updateState(newState: ConnectionState) {
        if (currentState == newState) {
            return
        }
        currentState = newState
        val statusMsg = when (newState) {
            ConnectionState.CONNECTING -> context?.getString(R.string.status_connecting) ?: "Connecting..."
            ConnectionState.CONNECTED -> {
                beep()
                val base = if (reader != null) {
                    "${context?.getString(R.string.status_connected)}: ${reader?.hostName}"
                } else {
                    context?.getString(R.string.status_connected) ?: "Connected"
                }

                "$base ($lAPIConnectTime ms)"
            }
            ConnectionState.ERROR -> context?.getString(R.string.status_error) ?: "Error"
            ConnectionState.DISCONNECTED -> context?.getString(R.string.status_disconnected) ?: "Disconnected"
        }

        context?.runOnUiThread {
            textView?.text = statusMsg
            when (newState) {
                ConnectionState.CONNECTING -> {
                    context?.showLoading(statusMsg)
                }
                ConnectionState.CONNECTED -> {
                    context?.hideLoading()
                    context?.textRFIDStatusLabel?.text = "RFID Status: Connected"
                }
                ConnectionState.ERROR -> {
                    context?.hideLoading()
                }
                ConnectionState.DISCONNECTED -> {
                    context?.hideLoading()
                    context?.textRFIDStatusLabel?.text = "RFID Status: Disconnected"
                }
            }
        }
    }

    fun onCreate(activity: MainActivity) {
        context = activity
        textView = activity.statusTextViewRFID
        context?.runOnUiThread { activity.lifecycle.addObserver(this) }
        initSDK()
    }

    override fun onResume(owner: LifecycleOwner) {
        Log.d(TAG, "onResume")
        connectReader()
    }

    @JvmOverloads
    fun onDestroy(silent: Boolean = false) {
        dispose(silent)
        executor.shutdownNow()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "onDestroy")
        owner.lifecycle.removeObserver(this)
        onDestroy(false)
    }

    fun getlAPIConnectTime(): Long = lAPIConnectTime

    fun isReaderConnected(): Boolean = reader?.isConnected ?: false

    @Synchronized
    fun initSDK() {
        if (bInit && readers != null) {
            Log.d(TAG, "Already initialized")
            connectReader()
            return
        }
        bInit = true
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            try {
                if (readers == null) {
                    updateState(ConnectionState.CONNECTING)
                    Thread.sleep(1000)
                    createInstance()
                } else {
                    connectReader()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.e(TAG, "initSDK failed", e)
                updateState(ConnectionState.ERROR)
            }
        }
    }

    private fun createInstance() {
        Log.d(TAG, "createInstance")
        if (readers == null) {
            readers = Readers(context, ENUM_TRANSPORT.ALL)
            Readers.attach(this)
        }
        connectReader()
    }

    @Synchronized
    private fun connectReader() {
        if (isReaderConnected()) {
            return
        }
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        try {
            executor.execute {
                if (isReaderConnected()) return@execute
                try {
                    updateState(ConnectionState.CONNECTING)
                    getAvailableReader()
                    if (reader != null) {
                        connectMethod()
                    } else {
                        Log.e(TAG, "Failed to find reader")
                        updateState(ConnectionState.ERROR)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection task failed", e)
                    updateState(ConnectionState.ERROR)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.e(TAG, "Task execution rejected", e)
            updateState(ConnectionState.ERROR)
        }
    }

    @Synchronized
    private fun getAvailableReader() {
        readerDevice = null
        reader = null
        readers?.let {
            try {
                availableRFIDReaderList = it.GetAvailableRFIDReaderList()
                val list = availableRFIDReaderList
                if (list != null && list.size > 0) {
                    if (list.size == 1) {
                        readerDevice = list[0]
                    } else {
                        for (device in list) {
                            //Check for EM45RFID, TC53RFID, TC22R, TC27R and eConnnex +G
                            if (device.name.contains(readerName) ||
                                device.name.contains("RFID") ||
                                device.name.contains("TC27R") ||
                                device.name.contains("TC22R")) {
                                readerDevice = device
                                Log.d(TAG, "Found eConnex-USB-CIO interface reader: ${device.name}")
                                break
                            }
                            Log.d(TAG, "Ignore non-eConnex-USB-CIO interface reader: ${device.name}")
                        }
                    }
                    readerDevice?.let { device ->
                        reader = device.rfidReader
                    }
                } else {
                    Log.e(TAG, "No available readers found")
                    updateState(ConnectionState.ERROR)
                }
            } catch (e: InvalidUsageException) {
                Log.e(TAG, "Error in getAvailableReader", e)
            }
        }
    }

    override fun RFIDReaderAppeared(device: ReaderDevice) {
        Log.d(TAG, "RFIDReaderAppeared: ${device.name}")
        context?.sendToast("RFIDReaderAppeared")
        beep()
        connectReader()
    }

    override fun RFIDReaderDisappeared(device: ReaderDevice) {
        Log.d(TAG, "RFIDReaderDisappeared: ${device.name}")
        context?.sendToast("RFIDReaderDisappeared")
        updateState(ConnectionState.DISCONNECTED)
    }

    @Synchronized
    private fun connectMethod(): String {
        val r = reader
        if (r != null) {
            try {
                if (!r.isConnected) {
                    lStart = System.currentTimeMillis()
                    r.connect()
                    lAPIConnectTime = System.currentTimeMillis() - lStart
                    configureReader()
                    if (r.isConnected) {
                        updateState(ConnectionState.CONNECTED)
                        context?.sendToast("Connected in $lAPIConnectTime ms")
                        return "Connected: ${r.hostName}"
                    }
                } else {
                    updateState(ConnectionState.CONNECTED)
                    return "Connected"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                updateState(ConnectionState.ERROR)
            }
        }
        return "Failed"
    }

    private fun configureReader() {
        val r = reader
        if (r != null && r.isConnected) {
            try {
                if (eventHandler == null) eventHandler = EventHandler()
                r.Events.addEventsListener(eventHandler)
                r.Events.setHandheldEvent(true)
                r.Events.setTagReadEvent(true)
                r.Events.setAttachTagDataWithReadEvent(false)
                r.Events.setReaderDisconnectEvent(true)
            } catch (e: Exception) {
                Log.e(TAG, "Configuration failed", e)
            }
        }
    }

    fun beep() {
        Log.d(TAG, "beep() called")
        Thread {
            try {
                for (i in 0..1) {
                    toneG.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
                    try {
                        Thread.sleep(400)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing beep", e)
            }
        }.start()
    }

    @kotlin.jvm.JvmOverloads
    @Synchronized
    fun dispose(silent: Boolean = false) {
        updateState(ConnectionState.DISCONNECTED)
        try {
            reader?.let {
                if (it.isConnected) {
                    it.disconnect()
                    if (!silent) context?.sendToast("Disconnected")
                }
            }
            readers?.let {
                it.Dispose()
            }
            readers = null
            reader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispose", e)
        }
        bInit = false
    }

    @Synchronized
    fun performInventory() {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            try {
                if (isReaderConnected()) reader?.Actions?.Inventory?.perform()
            } catch (e: Exception) {
                Log.e(TAG, "Inventory failed", e)
            }
        }
    }

    @Synchronized
    fun stopInventory() {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            try {
                if (isReaderConnected()) reader?.Actions?.Inventory?.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Stop inventory failed", e)
            }
        }
    }

    /**
     * Add state aware pre-filter for given EPC or Tag ID
     * @param tag The hexadecimal string pattern of the tag to filter.
     */
    public fun applyTagFilter(tag: String) {
        val currentReader = reader
        if (currentReader == null) {
            Log.e(TAG, "RFIDReader is null")
            return
        }
        //E280119120007676B2EF034A000000009000

        // Add state aware pre-filter
        val filters = PreFilters()
        // In Kotlin, inner classes are instantiated by calling the constructor on the outer instance
        val filter = filters.PreFilter()

        filter.setAntennaID(1.toShort()) // Explicitly cast to Short in Kotlin
        filter.setTagPattern(tag)
        if(tag.length*4 > 128)
            filter.setTagPatternBitCount(128)
        else
            filter.setTagPatternBitCount(tag.length * 4)

        filter.setBitOffset(0) // skip PC bits (always it should be in bit length)
        filter.setMemoryBank(MEMORY_BANK.MEMORY_BANK_TID)
        filter.setFilterAction(FILTER_ACTION.FILTER_ACTION_STATE_AWARE)

        // inventoried flag of session S1 of matching tags to B
        filter.StateAwareAction.setTarget(TARGET.TARGET_INVENTORIED_STATE_S0)

        // not to select tags that match the criteria
        filter.StateAwareAction.setStateAwareAction(STATE_AWARE_ACTION.STATE_AWARE_ACTION_INV_B_NOT_INV_A)

        try {
            // Clear existing pre-filters before adding new ones
            currentReader.Actions.PreFilters.deleteAll()

            // Add the new filter
            currentReader.Actions.PreFilters.add(filter)
            Log.i(TAG, "State-aware pre-filter added successfully for tag pattern: $tag")

        } catch (e: InvalidUsageException) {
            Log.e(TAG, "InvalidUsageException: ${e.vendorMessage}")
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            Log.e(TAG, "OperationFailureException: ${e.vendorMessage} Status: ${e.results}")
            e.printStackTrace()
        }
    }

    public fun setSingulationForFilter(bSet: Boolean) {
        try {
            // Use safe call ?. to ensure reader is not null
            val s0SingulationControl = reader?.Config?.Antennas?.getSingulationControl(1) ?: return

            // Kotlin property access for setters
            s0SingulationControl.session = SESSION.SESSION_S0

            // Accessing the Action nested object
            if(bSet)
                s0SingulationControl.Action.inventoryState = INVENTORY_STATE.INVENTORY_STATE_B
            else
                s0SingulationControl.Action.inventoryState = INVENTORY_STATE.INVENTORY_STATE_A

            s0SingulationControl.Action.setPerformStateAwareSingulationAction(true)
            // Apply the configuration back to the reader
            reader?.Config?.Antennas?.setSingulationControl(1, s0SingulationControl)

        } catch (e: InvalidUsageException) {
            e.printStackTrace()
        } catch (e: OperationFailureException) {
            e.printStackTrace()
        }
    }

    public fun resetPC(dataToWrite: String) {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            try {
                if (isReaderConnected()) {
                    reader?.Actions?.Inventory?.stop()
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop inventory failed", e)
            }

            val tagAccess = TagAccess()
            val writeAccessParams = tagAccess.WriteAccessParams()

            writeAccessParams.apply {
                accessPassword = 0
                memoryBank = MEMORY_BANK.MEMORY_BANK_EPC
                offset = 1 // start writing from word offset 1 for PC
                setWriteData(dataToWrite)

                // data length in words
                writeDataLength = dataToWrite.length / 4
            }

            try {
                reader?.Actions?.TagAccess?.writeEvent(writeAccessParams, null, null)
                context?.sendToast("Reset PC Started")
            } catch (e: Exception) {
                Log.e(TAG, "Reset PC failed", e)
                context?.sendToast("Reset PC Failed")
            }
        }
    }

    public fun write(selectTagID: String, dataToWrite: String, bank: MEMORY_BANK, wordOffSet: Int) {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            if (dataToWrite.isEmpty() || dataToWrite.length % 4 != 0) {
                context?.sendToast("Write Failed: Invalid data length")
                return@execute
            }
            try {
                if (isReaderConnected()) {
                    reader?.Actions?.Inventory?.stop()
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop inventory failed", e)
            }

            val tagData = TagData()
            val tagAccess = TagAccess()
            val writeAccessParams = tagAccess.WriteAccessParams()

            writeAccessParams.apply {
                accessPassword = 0
                memoryBank = bank
                offset = wordOffSet
                setWriteData(dataToWrite)

                // data length in words
                writeDataLength = (dataToWrite.length / 4)
            }

            try {
                val effectiveTagID = if (selectTagID.isEmpty()) null else selectTagID
                reader?.Actions?.TagAccess?.writeWait(effectiveTagID, writeAccessParams, null, tagData)
                if (writeAccessParams.writeDataLength == tagData.numberOfWords) {
                    context?.sendToast("Write Successful for lengh=" + tagData.numberOfWords)
                }
                else{
                    context?.sendToast("Partial Write for lengh=" + tagData.numberOfWords)
                }
            } catch (e: OperationFailureException) {
                Log.e(TAG, "Write failed: ${e.vendorMessage} ${e.results}", e)
                context?.sendToast("Write failed: ${e.vendorMessage} ${e.results}")
            } catch (e: Exception) {
                Log.e(TAG, "Write failed", e)
                context?.sendToast("Write Failed: ${e.message}")
            }
        }
    }

    public fun read(selectTagID: String, bank: MEMORY_BANK, wordOffSet: Int, wordCount: Int, callback: (String?) -> Unit) {
        if (executor.isShutdown) {
            executor = Executors.newSingleThreadExecutor()
        }
        executor.execute {
            try {
                if (isReaderConnected()) {
                    reader?.Actions?.Inventory?.stop()
                    Thread.sleep(100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop inventory failed", e)
            }

            val tagAccess = TagAccess()
            val readAccessParams = tagAccess.ReadAccessParams()

            readAccessParams.apply {
                accessPassword = 0
                memoryBank = bank
                offset = wordOffSet
                count = wordCount
            }

            try {
                Log.d(TAG, "Start readWait for tagEPC=$selectTagID, bank=${readAccessParams.memoryBank}, offset=$wordOffSet, wordCount=${readAccessParams.count}")
                // Set useInventoryFilter to true to respect Pre-Filters and Singulation settings (e.g., Session S0, State B)
                val effectiveTagID = if (selectTagID.isEmpty()) null else selectTagID
                val tagData = reader?.Actions?.TagAccess?.readWait(effectiveTagID, readAccessParams, null, true)
                val result = tagData?.memoryBankData
                context?.runOnUiThread { callback(result) }
            } catch (e: OperationFailureException) {
                Log.e(TAG, "Read failed: ${e.vendorMessage} ${e.results}", e)
                context?.sendToast("Read Failed: ${e.results}")
                context?.runOnUiThread { callback(null) }
            } catch (e: Exception) {
                Log.e(TAG, "Read failed", e)
                context?.sendToast("Read Failed: ${e.message}")
                context?.runOnUiThread { callback(null) }
            }
        }
    }

    public fun getPrefilterLength(): Int {
        return reader?.Actions?.PreFilters?.length() ?: 0
    }



    private inner class EventHandler : RfidEventsListener {
        override fun eventReadNotify(e: RfidReadEvents) {
            val myTags = reader?.Actions?.getReadTags(100)
            if (myTags != null) {
                context?.handleTagdata(myTags)
            }
        }

        override fun eventStatusNotify(e: RfidStatusEvents) {
            Log.d(TAG, "Status Notification: ${e.StatusEventData.statusEventType}")
            if (e.StatusEventData.statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
                val triggerType = e.StatusEventData.HandheldTriggerEventData.handheldEvent
                if (triggerType == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                    context?.handleTriggerPress(true)
                }
                if (triggerType == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                    context?.handleTriggerPress(false)
                }
            } else if (e.StatusEventData.statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
                Log.d(TAG, "BLUETOOTH DISCONNECTION_EVENT")
                reader?.disconnect()
                updateState(ConnectionState.DISCONNECTED)
            }
        }
    }

    interface ResponseHandlerInterface {
        fun handleTagdata(tagData: Array<TagData>)
        fun handleTriggerPress(pressed: Boolean)
        fun barcodeData(valStr: String)
        fun sendToast(valStr: String)
    }
}
