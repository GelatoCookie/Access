package com.zebra.rfid.demo.sdksample

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.zebra.rfid.api3.MEMORY_BANK
import com.zebra.rfid.api3.TagData
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity(), RFIDHandler.ResponseHandlerInterface {

    companion object {
        private const val ACTION_USB_PERMISSION = "android.hardware.usb.action.USB_PERMISSION"
        private const val SLED_ZEBRA_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        private const val SLED_ZEBRA_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        private const val RFID_VID = 1504
        private const val TAG = "RFID_SAMPLE"
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 100
    }

    var statusTextViewRFID: TextView? = null
    lateinit var scanResult: TextView
    lateinit var textViewStatusrfid: TextView
    lateinit var textRFIDStatusLabel: TextView
    private lateinit var tagList: ListView
    private val tagItems = ArrayList<TagItem>()
    private var tagAdapter: TagAdapter? = null
    private val tagMap = HashMap<String, TagItem>()

    private inner class TagItem(val tagID: String, var count: Int, var rssi: String)

    private inner class TagAdapter(context: Context, items: ArrayList<TagItem>) :
        ArrayAdapter<TagItem>(context, R.layout.tag_list_item, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.tag_list_item, parent, false)
            val item = getItem(position)
            if (item != null) {
                view.findViewById<TextView>(R.id.tag_id).text = item.tagID
                view.findViewById<TextView>(R.id.tag_count).text = item.count.toString()
                view.findViewById<TextView>(R.id.tag_rssi).text = item.rssi
            }
            return view
        }
    }

    private lateinit var loadingOverlay: View
    private lateinit var loadingText: TextView
    var rfidHandler: RFIDHandler? = null
    private var bTesting = false

    @SuppressLint("CutPasteId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        statusTextViewRFID = findViewById(R.id.textViewStatusrfid)
        tagList = findViewById(R.id.tagList)
        tagAdapter = TagAdapter(this, tagItems)
        tagList.adapter = tagAdapter

        tagList.setOnItemClickListener { _, _, position, _ ->
            val selectedItem = tagItems[position]
            showWriteDialog(selectedItem.tagID)
        }

        scanResult = findViewById(R.id.scanResult)
        textViewStatusrfid = findViewById(R.id.textViewStatusrfid)
        textRFIDStatusLabel = findViewById(R.id.textrfid)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    BLUETOOTH_PERMISSION_REQUEST_CODE
                )
            } else {
                InitRfidSDK()
            }
        } else {
            InitRfidSDK()
        }

        requestPermission(null)
        bTesting = false
    }

    fun checkUSBSize(): Int {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager?
        return manager?.deviceList?.size ?: 0
    }

    fun requestPermission(view: View?) {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager?
        val deviceList = manager?.deviceList
        if (deviceList != null && deviceList.isNotEmpty()) {
            val filter = IntentFilter().apply {
                addAction(SLED_ZEBRA_ATTACHED)
                addAction(SLED_ZEBRA_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }

            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }

            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            )

            var foundZebraDevice = false
            for (device in deviceList.values) {
                if (device.vendorId == RFID_VID) {
                    foundZebraDevice = true
                    if (manager.hasPermission(device)) {
                        Toast.makeText(applicationContext, "USB permission already granted", Toast.LENGTH_SHORT).show()
                    } else {
                        manager.requestPermission(device, permissionIntent)
                    }
                }
            }

            if (!foundZebraDevice) {
                Toast.makeText(applicationContext, "No USB eConnex devices detected\nNo Auto-Connect Support", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(applicationContext, "No USB eConnex devices detected\nNo Auto-Connect Support", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearTagData() {
        runOnUiThread {
            tagItems.clear()
            tagMap.clear()
            tagAdapter?.notifyDataSetChanged()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                InitRfidSDK()
            } else {
                Toast.makeText(this, "Bluetooth Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Toast.makeText(applicationContext, "USB permission GRANTED", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(applicationContext, "USB permission DENIED", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            if (SLED_ZEBRA_ATTACHED == action) {
                val device = intent.getParcelableExtra<UsbDevice>("device")
                if (device != null && device.vendorId == RFID_VID) {
                    Log.d(TAG, "SLED_ZEBRA_ATTACHED")
                    InitRfidSDK()
                }
            }
            if (SLED_ZEBRA_DETACHED == action) {
                synchronized(this) {
                    rfidHandler?.let {
                        Log.d(TAG, "SLED_ZEBRA_DETACHED - Disposing Handler")
                        it.onDestroy()
                        rfidHandler = null
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.Connect -> {
                rfidHandler?.setSingulationForFilter(false)
                InitRfidSDK()
                true
            }

            R.id.Disconnect -> {
                rfidHandler?.let {
                    it.onDestroy()
                    textViewStatusrfid.text = "Disconnected"
                }
                true
            }

            R.id.TestConnectDisconectLoop -> {
                if (!bTesting) {
                    runStressTest()
                }
                true
            }

            R.id.SetPrefitlerTID_S0_INVB -> {
                val input = EditText(this)
                input.setText("E280119120007676B2EF034A000000009000")
                AlertDialog.Builder(this)
                    .setTitle("Set Tag Filter")
                    .setMessage("Enter Tag ID:")
                    .setView(input)
                    .setPositiveButton("Set") { _, _ ->
                        val tagId = input.text.toString()
                        clearTagData()
                        rfidHandler?.setSingulationForFilter(true)
                        rfidHandler?.applyTagFilter(tagId)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }

            R.id.ClearPrefitler -> {
                clearTagData()
                rfidHandler?.setSingulationForFilter(false)
                true
            }

            R.id.ResetPCto3000 -> {
                if(rfidHandler?.getPrefilterLength()==1) {
                    rfidHandler?.resetPC("3000")
                }
                true
            }

            R.id.Clear_List -> {
                clearTagData()
                true
            }

            else -> super.onOptionsItemSelected(item)
        } as Boolean
    }

    private fun runStressTest() {
        bTesting = true
        loadingOverlay.visibility = View.VISIBLE
        Thread {
            var totalConnectTime: Long = 0
            var successfulConnections = 0
            try {
                for (i in 1..10) {
                    if (rfidHandler == null) break
                    updateLoadingText("Iteration $i / 10\nDisconnecting...")
                    rfidHandler?.onDestroy(true)
                    Thread.sleep(3000)

                    updateLoadingText("Iteration $i / 10\nConnecting...")
                    InitRfidSDK()

                    for (j in 0 until 10) {
                        Thread.sleep(1000)
                        val handler = rfidHandler
                        if (handler != null && handler.isReaderConnected()) {
                            val connectTime = handler.getlAPIConnectTime()
                            if (connectTime > 0) {
                                totalConnectTime += connectTime
                                successfulConnections++
                                break
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Log.e(TAG, "Test interrupted", e)
            } finally {
                val avgTime = if (successfulConnections > 0) totalConnectTime / successfulConnections else 0
                runOnUiThread {
                    loadingOverlay.visibility = View.GONE
                    bTesting = false
                    textRFIDStatusLabel.text = "RFID Status: Avg Connect Time: ${avgTime}ms"
                    sendToast("Test Done. Avg: ${avgTime}ms")
                }
            }
        }.start()
    }

    fun StartInventory(view: View) {
        findViewById<Button>(R.id.TestButton).isEnabled = false
//        runOnUiThread {
//            tagAdapter!!.clear()
//        }

        rfidHandler?.performInventory()
    }

    fun StopInventory(view: View) {
        findViewById<Button>(R.id.TestButton).isEnabled = true
        rfidHandler?.stopInventory()
    }

    override fun handleTagdata(tagData: Array<TagData>) {
        runOnUiThread {
            for (tagDatum in tagData) {
                val tagID = tagDatum.tagID
                val rssi = tagDatum.peakRSSI.toString()
                if (tagMap.containsKey(tagID)) {
                    val item = tagMap[tagID]
                    if (item != null) {
                        item.count++
                        item.rssi = rssi
                    }
                } else {
                    val newItem = TagItem(tagID, 1, rssi)
                    tagMap[tagID] = newItem
                    tagItems.add(newItem)
                }
            }
            tagAdapter?.notifyDataSetChanged()
        }
    }

    override fun handleTriggerPress(pressed: Boolean) {
        Log.d(TAG, "handleTriggerPress: $pressed")
        if (pressed) {
            runOnUiThread {
                tagItems.clear()
                tagMap.clear()
                tagAdapter?.notifyDataSetChanged()
                findViewById<Button>(R.id.TestButton).isEnabled = false
            }
            rfidHandler?.performInventory()
        } else {
            runOnUiThread {
                findViewById<Button>(R.id.TestButton).isEnabled = true
            }
            rfidHandler?.stopInventory()
        }
    }

    override fun barcodeData(valStr: String) {
        runOnUiThread { scanResult.text = "Scan Result : $valStr" }
    }

    override fun sendToast(valStr: String) {
        runOnUiThread { Toast.makeText(this@MainActivity, valStr, Toast.LENGTH_SHORT).show() }
    }

    fun sendStatusText(valStr: String) {
        runOnUiThread { textViewStatusrfid.text = valStr }
    }

    private fun updateLoadingText(text: String) {
        runOnUiThread {
            loadingText.text = text
        }
    }

    fun showLoading(message: String) {
        runOnUiThread {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
        }
    }

    fun hideLoading() {
        runOnUiThread {
            loadingOverlay.visibility = View.GONE
        }
    }

    private fun buildTidFilterHex(rawTid: String): String {
        val hexOnly = rawTid
            .replace(" ", "")
            .uppercase(Locale.US)
            .filter { it in "0123456789ABCDEF" }

        // 32 hex chars = 128 bits
        return if (hexOnly.length > 32) hexOnly.substring(0, 32) else hexOnly
    }

    private fun writeWithTidPrefilter(
        targetTagId: String,
        data: String,
        selectedBank: MEMORY_BANK,
        offset: Int,
        tidWordCount: Int = 8
    ) {
        val handler = rfidHandler
        if (handler == null) {
            sendToast("RFID handler unavailable")
            return
        }

        if (targetTagId.isBlank()) {
            sendToast("Tag ID is empty")
            return
        }

        if (data.isBlank() || data.length % 4 != 0) {
            sendToast("Write data must be hex and length multiple of 4")
            return
        }

        handler.write(targetTagId, data, selectedBank, offset)

//        handler.read(targetTagId, MEMORY_BANK.MEMORY_BANK_TID, 0, tidWordCount) { tid ->
//            if (tid.isNullOrBlank()) {
//                sendToast("Read TID failed")
//                return@read
//            }
//
//            val tidFilter = buildTidFilterHex(tid)
//            if (tidFilter.isBlank()) {
//                sendToast("Invalid TID for prefilter")
//                return@read
//            }
//
//            sendToast("TID prefilter applied, writing tag...")
//            handler.setSingulationForFilter(true)
//            handler.applyTagFilter(tidFilter)
//            handler.write(targetTagId, data, selectedBank, offset)
//        }
    }

    private fun showWriteDialog(tagId: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }

        val etTagId = EditText(this).apply {
            hint = "Tag ID"
            setText(tagId)
        }
        val etData = EditText(this).apply {
            hint = "Data to Write"
            setText("333333333333333333333333")
        }

        val banks = arrayOf("EPC", "TID", "USER", "RESERVED")
        val spinnerBank = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, banks)
            setSelection(0)
        }

        val etOffset = EditText(this).apply {
            hint = "Offset"
            setText("2")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        val tvReadResult = TextView(this).apply {
            text = "Read Result: "
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                val resultText = text.toString().removePrefix("Read Result: ")
                if (resultText.isNotBlank() && resultText != "Failed") {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("TID", resultText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "TID copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnReadTid = Button(this).apply {
            text = "Read TID"
            setOnClickListener {
                val targetTagId = etTagId.text.toString()
                rfidHandler?.read(targetTagId, MEMORY_BANK.MEMORY_BANK_TID, 0, 6) { result ->
                    tvReadResult.text = "Read Result: ${result ?: "Failed"}"
                }
            }
        }

        layout.addView(TextView(this).apply { text = "Tag ID:" })
        layout.addView(etTagId)
        layout.addView(TextView(this).apply { text = "Data to Write:" })
        layout.addView(etData)
        layout.addView(TextView(this).apply { text = "Memory Bank:" })
        layout.addView(spinnerBank)
        layout.addView(TextView(this).apply { text = "Offset (Words):" })
        layout.addView(etOffset)
        layout.addView(tvReadResult)
        layout.addView(btnReadTid)

        AlertDialog.Builder(this)
            .setTitle("Write Tag Data (TID Prefilter)")
            .setView(layout)
            .setPositiveButton("Write with TID Filter") { _, _ ->
                val targetTagId = etTagId.text.toString()
                val data = etData.text.toString()
                val offset = etOffset.text.toString().toIntOrNull() ?: 2
                val selectedBank = when (spinnerBank.selectedItem.toString()) {
                    "EPC" -> MEMORY_BANK.MEMORY_BANK_EPC
                    "TID" -> MEMORY_BANK.MEMORY_BANK_TID
                    "USER" -> MEMORY_BANK.MEMORY_BANK_USER
                    "RESERVED" -> MEMORY_BANK.MEMORY_BANK_RESERVED
                    else -> MEMORY_BANK.MEMORY_BANK_EPC
                }
                writeWithTidPrefilter(targetTagId, data, selectedBank, offset)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Synchronized
    fun InitRfidSDK() {
        Log.d(TAG, "InitRfidSDK...")
        Log.d(TAG, "USB Size1 = " + checkUSBSize())
        rfidHandler?.let {
            it.onDestroy()
            rfidHandler = null
        }
        rfidHandler = RFIDHandler()
        Log.d(TAG, "USB Size2 = " + checkUSBSize())
        rfidHandler?.onCreate(this)
        Log.d(TAG, "Done: InitRfidSDK")
    }
}
