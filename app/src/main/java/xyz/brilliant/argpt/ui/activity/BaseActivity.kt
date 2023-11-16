package xyz.brilliant.argpt.ui.activity
//imports
import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import xyz.brilliant.argpt.R
import xyz.brilliant.argpt.service.ForegroundService
import xyz.brilliant.argpt.ui.fragment.ChatGptFragment
import xyz.brilliant.argpt.ui.fragment.ScanningFragment
import xyz.brilliant.argpt.ui.model.ChatModel
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import kotlin.math.ceil

//baseactivity
class BaseActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BaseActivity"
        private const val REQUEST_FINE_LOCATION = 1001
        private const val PERMISSION_REQUEST_CODE = 5001
        private val REQUEST_ENABLE_BLUETOOTH = 1002
        private val REQUEST_ENABLE_GPS = 1003
        private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private const val RX_UUID = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        private const val TX_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        private val RAW_SERVICE_UUID = UUID.fromString("e5700001-7bac-429a-b4ce-57ff900f479d")
        private const val RAW_RX_UUID = "e5700002-7bac-429a-b4ce-57ff900f479d"
        private const val RAW_TX_UUID = "e5700003-7bac-429a-b4ce-57ff900f479d"

        private val NORDIC_SERVICE_UUID = UUID.fromString("0000fe59-0000-1000-8000-00805f9b34fb")
        private const val NORDIC_CONTROL_UUID = "8ec90001-f315-4f60-9fb8-838830daea50"
        private const val NORDIC_PACKET_UUID = "8ec90002-f315-4f60-9fb8-838830daea50"
        private val FILES = arrayListOf<String>("states.py", "graphics.py", "main.py", "audio.py", "photo.py")
        private const val GATT_MAX_MTU_SIZE = 256
        private const val sampleRate = 8000
        private const val bitPerSample = 16
        private const val channels = 1

        private val client = OkHttpClient()

        // For Debugging
        // For Debugging
        private const val NRFKIT = false
        private const val FIRMWARE_TEST = false
        private const val FPGA_TEST = false
        private const val BACKEND_URL = ""
        private const val USE_CUSTOM_SERVER = false
        fun pushFragmentsStatic(
            fragmentManager: FragmentManager,
            fragment: Fragment,
            shouldAdd: Boolean,
            tag: String?
        ) {
            val ft: FragmentTransaction = fragmentManager.beginTransaction()
            //ft.setCustomAnimations(R.anim.enter_from_left, R.anim.exit_to_right);
            ft.replace(R.id.fragmentContainer, fragment, tag)
            if (shouldAdd) {
                ft.addToBackStack("ScreenStack")
            } else {
                fragmentManager.popBackStack(null, 0)
            }
            ft.commit()
        }    private const val VOICE_RECOGNITION_REQUEST_CODE = 1001 // Ensure this doesn't conflict with other request codes

    }

    public lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var recyclerView: RecyclerView
    var apiKey = ""
    var stabilityApiKey = ""
    private val handler = Handler(Looper.getMainLooper())
    private var scanning: Boolean = false
    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var writingREPLProgress: Boolean = false
    val fragmentManager = supportFragmentManager
    var translateEnabled: Boolean = false

    private var rawRxCharacteristic: BluetoothGattCharacteristic? = null
    private var rawTxCharacteristic: BluetoothGattCharacteristic? = null

    private var nordicControlCharacteristic: BluetoothGattCharacteristic? = null
    private var nordicPacketCharacteristic: BluetoothGattCharacteristic? = null
    var rawReplResponseCallback: ((String) -> Unit)? = null
    var controlResponseCallback: ((ByteArray) -> Unit)? = null

    data class Fpga(val bin: ByteArray?, val version: String?)
    data class ExtractedData(
        val datBytes: ByteArray?,
        val binBytes: ByteArray?,
        val version: String?
    )

    enum class AppState {
        FIRST_PAIR, SOFTWARE_UPDATE, FPGA_UPDATE, SCRIPT_UPDATE, RUNNING
    }

    var currentAppState = AppState.FIRST_PAIR
    private val mArrayList = ArrayList<ScanResult>()
    private var currentDevice: String = ""
    private var audioBuffer: ByteArray = byteArrayOf(0)
    private var imageBuffer: ByteArray = byteArrayOf(0)
    var audioJob: Job? = null
    var lastResponse: String = ""
    private val PREFS_FILE_NAME = "MyPrefs"
    private val PREFS_FILE_NAME2 = "ApiKey"
    private val PREFS_KEY_DEVICE_ADDRESS = "DeviceAddress"
    private val PREFS_OPEN_API_KEY = "OpenAi"
    private val PREFS_STABILITY_API_KEY = "stability"
    private var currentScannedDevice: BluetoothDevice? = null
    private var overlallSoftwareProgress = 0
    private var overlallSoftwareSize = 0
    private var currentConnectionStatus = false
    private fun getStoredDeviceAddress(): String {
        val prefs = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREFS_KEY_DEVICE_ADDRESS, "") ?: ""
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_START_SCAN") {
                if (bluetoothGatt == null) {
                    val storedDeviceAddress = getStoredDeviceAddress()
                    if (!storedDeviceAddress.isNullOrEmpty()) {
                        connectDevice(storedDeviceAddress)
                        println("[trying to connect in background]")
                    }

                }
            }
        }
    }


    //grab API keys
    fun getStoredApiKey(): String {
        val prefs = getSharedPreferences(PREFS_FILE_NAME2, Context.MODE_PRIVATE)
        return prefs.getString(PREFS_OPEN_API_KEY, "") ?: ""
    }

    fun getStoredStabilityApiKey(): String {
        val prefs = getSharedPreferences(PREFS_FILE_NAME2, Context.MODE_PRIVATE)
        return prefs.getString(PREFS_STABILITY_API_KEY, "") ?: ""
    }

    private fun storeDeviceAddress(deviceAddress: String) {
        val prefs = getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(PREFS_KEY_DEVICE_ADDRESS, deviceAddress)
        editor.apply()
    }

    fun unpairMonocle() {
        storeDeviceAddress("")
        disconnectGatt()
        currentAppState = AppState.FIRST_PAIR
        currentDevice = ""
        currentScannedDevice = null
        overlallSoftwareProgress = 0
        finish()
        startActivity(intent)
    }

    fun storeApiKey(_apiKey: String) {
        val prefs = getSharedPreferences(PREFS_FILE_NAME2, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(PREFS_OPEN_API_KEY, _apiKey)
        editor.apply()
    }


    fun storeStabilityApiKey(_apiKey: String) {
        val prefs = getSharedPreferences(PREFS_FILE_NAME2, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(PREFS_STABILITY_API_KEY, _apiKey)
        editor.apply()
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentFilter = IntentFilter("ACTION_START_SCAN")
        registerReceiver(scanReceiver, intentFilter)
        setContentView(R.layout.activity_base)
        getAllPermission()
    }

    private fun firstCodeExecute() {
        try {
            val storedDeviceAddress = getStoredDeviceAddress()
            if (apiKey.isEmpty()) {
                apiKey = getStoredApiKey()
            }
            if(stabilityApiKey.isEmpty()){
                stabilityApiKey =  getStoredStabilityApiKey()
            }
            if (storedDeviceAddress.isNullOrBlank()) {
                currentAppState = AppState.FIRST_PAIR
                val fragment = ScanningFragment()
                pushFragmentsStatic(fragmentManager, fragment, false, "start_scan")
            } else {
                currentAppState = AppState.RUNNING
                val fragment = ChatGptFragment()
                pushFragmentsStatic(fragmentManager, fragment, false, "chat_gpt")
            }

            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            if (bluetoothAdapter == null) {

                finish()
                return
            }

            if (currentAppState == AppState.FIRST_PAIR) {
                updateProgressDialog("Bring your device close.", "Searching")
                startScan()
            } else if (currentAppState == AppState.SCRIPT_UPDATE || currentAppState == AppState.RUNNING) {
                startBluetoothBackground()
                connectDevice(storedDeviceAddress)
                if (bluetoothGatt != null) {
                    updateConnectionStatus("not connected")
                } else {
                    updateConnectionStatus("")
                }
            }

        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    private fun startBluetoothBackground() {
        val foregroundServiceIntent = Intent(this, ForegroundService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(foregroundServiceIntent)
        } else {
            startService(foregroundServiceIntent)
        }
    }

    private val permissionsSDK33 = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.FOREGROUND_SERVICE,
        //Manifest.permission.WRITE_EXTERNAL_STORAGE

    )


    private val permissionsSDK29 = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.FOREGROUND_SERVICE,
        // Manifest.permission.WRITE_EXTERNAL_STORAGE

    )


    private fun getAllPermission() {
        try {

            var permissions: Array<String>;
            val androidVersion = android.os.Build.VERSION.SDK_INT

            if (androidVersion <= android.os.Build.VERSION_CODES.R) {
                // Targeting Android 11 or lower
                // Use permissionsSDK29 array
                permissions = permissionsSDK29
            } else {
                // Targeting Android 12 or higher
                // Use permissions array
                permissions = permissionsSDK33
            }


            val permissionsToRequest = mutableListOf<String>()
            for (permission in permissions) {
                val result = ContextCompat.checkSelfPermission(this, permission)
                if (result != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(permission)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                // All permissions are already granted. You can proceed with your operation here.
                Log.d(TAG, "getAllPermission: 12")



                checkBluetoothAndGps()

            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothAlertDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage("Bluetooth is disabled. Do you want to enable it?")
            .setPositiveButton("Yes") { _, _ ->
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(intent, REQUEST_ENABLE_BLUETOOTH)
            }
            .setNegativeButton("No") { dialog: DialogInterface, _ ->
                dialog.dismiss()


                finish()
                //  checkBluetoothAndGps()
            }
        builder.setCancelable(false)
        builder.create().show()
    }

    private fun showGpsAlertDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage("GPS is disabled. Do you want to enable it?")
            .setPositiveButton("Yes") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivityForResult(intent, REQUEST_ENABLE_GPS)
            }
            .setNegativeButton("No") { dialog: DialogInterface, _ ->
                dialog.dismiss()
                finish()
                // checkBluetoothAndGps()
            }
        builder.setCancelable(false)
        builder.create().show()
    }


    private fun isGpsEnabled(): Boolean {

        try {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

            var resultl = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                var result3 = locationManager.isLocationEnabled
                return locationManager.isLocationEnabled
            } else {
                return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }

        } catch (ex: Exception) {
            var exception = ex.message
            return false
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        return bluetoothAdapter?.isEnabled == true
    }

    private fun checkBluetoothAndGps() {


        val bluetoothEnabled = isBluetoothEnabled()
        val gpsEnabled = isGpsEnabled()

        if (bluetoothEnabled && gpsEnabled) {
            // Both Bluetooth and GPS are enabled, execute your first code
            firstCodeExecute()
        } else {

            if (!bluetoothEnabled) {
                showBluetoothAlertDialog()
                // showGpsAlertDialog()
            } else if (!gpsEnabled) {
                showGpsAlertDialog()
            }
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        val VOICE_RECOGNITION_REQUEST_CODE = 101
        when (requestCode) {
            VOICE_RECOGNITION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK && data != null) {
                    val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    val searchQuery = matches?.get(0) ?: ""
                    checkBluetoothAndGps()
                } else {
                    checkBluetoothAndGps()
                    // Optional: Uncomment to show a toast message
                    // Toast.makeText(this, "Please turn on bluetooth!", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_ENABLE_GPS -> {
                if (resultCode == RESULT_OK) {
                    checkBluetoothAndGps()
                } else {
                    checkBluetoothAndGps()
                    // Optional: Uncomment to show a toast message
                    // Toast.makeText(this, "Please turn on GPS!", Toast.LENGTH_SHORT).show()
                }
            }
            PERMISSION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivityForResult(intent, REQUEST_ENABLE_GPS)
                    checkBluetoothAndGps()
                } else {
                    getAllPermission()
                }
            }
            VOICE_RECOGNITION_REQUEST_CODE -> {
                if (resultCode == RESULT_OK) {
                    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { results ->
                        val searchQuery = results[0] // Take the first result
                        // Handle the search query as needed
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if all permissions are granted
            var allPermissionsGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false
                    break
                }
            }

            if (allPermissionsGranted) {
                checkBluetoothAndGps()
                // All permissions are granted. You can proceed with your operation here.
                Log.d(TAG, "onRequestPermissionsResult: ")
                // firstCodeExecute()
            } else {
                // Handle the case where some permissions are not granted.
                // You may want to inform the user or handle the missing permissions accordingly.
                Log.d("BASE ACTIVITY", "Permission Not given")
                showPermissionPopup()

            }
        }
    }

    private fun showPermissionPopup() {
        try {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("Please grant all the required permissions to use this app.")
                .setPositiveButton("OK") { dialog, which ->
                    // You can take appropriate action here, such as redirecting the user to the app settings page.
                    // For example:
                    openAppSettings()
                }
                .setCancelable(false)
                .show()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

    }

    private fun openAppSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }
        startActivity(intent)
    }

    var connectionStatus = ""
    fun updateConnectionStatus(status: String) {
        connectionStatus = status
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ChatGptFragment) {
            fragment.updateConnectionStatus(status)
        }
    }

    fun updatechatList(type: String, msg: String) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ChatGptFragment) {
            fragment.updatechatList(type, msg)
        }
    }

    fun updatechatList(id: Int, type: String, msg: String, image: String) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ChatGptFragment) {
            fragment.updatechatList(id, type, msg, image)
        }
    }

    fun connectDevice() {
        try {
            if (currentScannedDevice != null) {
                connectDevice(currentScannedDevice!!.address)
//                currentScannedDevice = null
            }
//            val firstItem: ScanResult? = if (mArrayList.size == 1) {
//                mArrayList[0]
//            } else if (mArrayList.size > 1) {
//                mArrayList.minByOrNull { it.rssi }
//            } else {
//                null
//            }
//            if (firstItem != null) {
//                stopScan()
//                connectDevice(firstItem.device.address)
//            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
    }

    fun updateProgressDialog(deviceCloseText: String, btnText: String) {

        val newDeviceCloseText = deviceCloseText
        runOnUiThread {
            val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
            if (fragment is ScanningFragment) {
                fragment.updatePopUp(deviceCloseText, btnText)
            }
        }

    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        disconnectGatt()
        val stopServiceIntent = Intent(this, ForegroundService::class.java)
        stopService(stopServiceIntent)
        unregisterReceiver(scanReceiver)
        super.onDestroy()


    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission", "SuspiciousIndentation")
        override fun onScanResult(callbackType: Int, result: ScanResult) {

            val deviceName = result.device.name
            println(deviceName)
            if (currentAppState == AppState.SOFTWARE_UPDATE || deviceName == "DfuTarg") {
                connectDevice(result.device.address)
                stopScan()
                return
            }
            val storedDeviceAddress = getStoredDeviceAddress()
            if (currentDevice.isNotEmpty()) {
                if (currentDevice == result.device.address) {
                    connectDevice(result.device.address)
                    stopScan()
                }
                return
            } else if (storedDeviceAddress.isNotEmpty()) {
                if (storedDeviceAddress == result.device.address) {
                    connectDevice(result.device.address)
                    stopScan()
                }
                return
            }
            println(result.device.name + result.rssi)
            if (result.rssi > -65) {
                currentScannedDevice = result.device
                updateProgressDialog(
                    "Bring your device close.",
                    "${currentScannedDevice?.name?.capitalize()}. Connect"
                )

            } else if (result.rssi < -80) {
                currentScannedDevice = null
            }
            if (currentScannedDevice == null) {
                updateProgressDialog("Bring your device close.", "Searching")
            }

            //
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) {
            return
        }
        if (hasLocationPermission()) {
            val serviceUuids = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(SERVICE_UUID))
                    .build(),
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(NORDIC_SERVICE_UUID))
                    .build()

            )
            scanning = true
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            bluetoothAdapter.bluetoothLeScanner.startScan(
                serviceUuids,
                scanSettings,
                scanCallback
            )
        } else {
            requestLocationPermission()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (scanning) {
            scanning = false
            bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
            currentScannedDevice = null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            REQUEST_FINE_LOCATION
        )
    }

    var popuptxt: String = "Bring your Device Close."
    var popUpbtntxt = "Searching"
    var showPopUp: Boolean = false
    var filesUploadStr: String = ""
    fun isAppInBackground(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = activityManager.runningAppProcesses
        val packageName = context.packageName

        for (appProcess in appProcesses) {
            if (appProcess.processName == packageName) {
                // Check if the app is in the foreground or background
                return appProcess.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        }

        return true // App is considered in the background if the process is not found
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(deviceAddress: String) {

        if (currentConnectionStatus) {
            return
        }
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)

        // Check if the device is already connected
        if (bluetoothGatt == null || bluetoothGatt?.device != device) {
            // Close the existing BluetoothGatt instance if any
            bluetoothGatt?.close()

            // Connect to the device
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } else {
            println("Device is already connected")
        }
    }


    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            if (newState == BluetoothGatt.STATE_CONNECTED) {
                writingREPLProgress = false
                currentScannedDevice = null
                currentConnectionStatus = true
                val intent = Intent("ACTION_CONNECTION_STATUS")
                intent.putExtra("EXTRA_CONNECTION_STATUS", true)
                sendBroadcast(intent)
                gatt.requestMtu(GATT_MAX_MTU_SIZE)
                // Handler(Looper.getMainLooper()).post {
                if (currentAppState == AppState.FIRST_PAIR) {
                    updateProgressDialog("Checking software update.", "Keep the app open")
                }
                if (currentAppState == AppState.SOFTWARE_UPDATE || currentAppState == AppState.FPGA_UPDATE) {
                    updateProgressDialog(
                        "Updating software $overlallSoftwareProgress%",
                        "Keep the app open"
                    )
                }
                if (currentAppState == AppState.RUNNING) {
                    updateConnectionStatus("")


                }
                // }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {

                disconnectGatt()
                if (currentAppState == AppState.FIRST_PAIR || currentAppState == AppState.RUNNING) {

                    updateProgressDialog("Bring your device close.", "Searching")

                }

                if (currentAppState == AppState.RUNNING) {

                    updateConnectionStatus("not connected")

                }
                if (isAppInBackground(applicationContext)) {
                    val triggerIntent = Intent("ACTION_START_SCAN")
                    sendBroadcast(triggerIntent)

                } else {
                    startScan()
                }
                if (currentConnectionStatus) {
                    val intent = Intent("ACTION_CONNECTION_STATUS")
                    intent.putExtra("EXTRA_CONNECTION_STATUS", false)
                    sendBroadcast(intent)
                }

                currentConnectionStatus = false


            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                lifecycleScope.launch {
                    val service = gatt.getService(SERVICE_UUID)
                    val rawService = gatt.getService(RAW_SERVICE_UUID)
                    val nordicService = gatt.getService(NORDIC_SERVICE_UUID)
                    if (service != null && rawService != null) {
                        // for REPL and data of monocle
                        println("[REPL SERVICE DISCOVERED] : ${service.uuid}\n")
                        println("[DATA SERVICE DISCOVERED] :  ${rawService.uuid}\n")
                        connectMonocleServices(service, rawService, gatt)
                        startBleProcess()
                    }
                    if (nordicService != null) {
                        // for firmware update
                        println("[NORDIC SERVICE UUID DISCOVERED] : ${nordicService.uuid}\n")
                        connectNordicService(nordicService, gatt)
                        startDfuProcess()
                    }
                }
            } else {
                println("[DISCOVERY FAILED]\n")
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun connectMonocleServices(
            service: BluetoothGattService,
            rawService: BluetoothGattService,
            gatt: BluetoothGatt
        ) {
            return coroutineScope {
                val resultDeferred = async {

                    rxCharacteristic = service.getCharacteristic(UUID.fromString(RX_UUID))
                    txCharacteristic = service.getCharacteristic(UUID.fromString(TX_UUID))

                    if (rxCharacteristic != null && txCharacteristic != null) {
                        println("[REPL RX CHARACTERISTICS CONNECTED ] : ${rxCharacteristic!!.uuid}\n")
                        println("[REPL TX CHARACTERISTICS CONNECTED ] : ${txCharacteristic!!.uuid}\n")
                        gatt.setCharacteristicNotification(txCharacteristic, true)
                        val descriptor =
                            txCharacteristic!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        println("[REPL TX CHARACTERISTICS NOTIFICATION ENABLED ] : ${txCharacteristic!!.uuid}\n")
                    }
                    rawRxCharacteristic = rawService.getCharacteristic(UUID.fromString(RAW_RX_UUID))
                    rawTxCharacteristic = rawService.getCharacteristic(UUID.fromString(RAW_TX_UUID))
                    delay(500)
                    if (rawRxCharacteristic != null && rawTxCharacteristic != null) {
                        println("[DATA RX CHARACTERISTICS CONNECTED ] : ${rawRxCharacteristic!!.uuid}\n")
                        println("[DATA TX CHARACTERISTICS CONNECTED ] : ${rawTxCharacteristic!!.uuid}\n")
                        gatt.setCharacteristicNotification(rawTxCharacteristic, true)
                        val descriptor =
                            rawTxCharacteristic!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        println("[DATA TX CHARACTERISTICS NOTIFICATION ENABLED ] : ${rawTxCharacteristic!!.uuid}\n")
                    }
                    delay(500)

                }
                resultDeferred.await()
            }
        }

        @SuppressLint("MissingPermission")
        suspend fun connectNordicService(service: BluetoothGattService, gatt: BluetoothGatt) {
            return coroutineScope {
                val resultDeferred = async {
                    nordicControlCharacteristic = service.getCharacteristic(
                        UUID.fromString(
                            NORDIC_CONTROL_UUID
                        )
                    )
                    nordicPacketCharacteristic = service.getCharacteristic(
                        UUID.fromString(
                            NORDIC_PACKET_UUID
                        )
                    )
                    if (nordicControlCharacteristic != null && nordicPacketCharacteristic != null) {
                        println("[NORDIC CONTROL CHARACTERISTICS CONNECTED ] : ${nordicControlCharacteristic!!.uuid}\n")
                        println("[NORDIC PACKET CHARACTERISTICS CONNECTED ] : ${nordicPacketCharacteristic!!.uuid}\n")
                        gatt.setCharacteristicNotification(nordicControlCharacteristic, true)
                        val descriptor =
                            nordicControlCharacteristic!!.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        println("[NORDIC CONTROL CHARACTERISTICS NOTIFICATION ENABLED ] : ${nordicControlCharacteristic!!.uuid}\n")
                    }
                }
                delay(200)
                resultDeferred.await()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS)
                gatt.discoverServices()

        }


        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)

            if (status == BluetoothGatt.GATT_SUCCESS) {
                writingREPLProgress = false
//                println("[WRITE] ${characteristic.value}")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            CoroutineScope(Dispatchers.Default).launch {
                if (characteristic.uuid == rawTxCharacteristic?.uuid) {
                    handleAudioData(characteristic.value)
                    return@launch
                }
                if (characteristic.uuid == txCharacteristic?.uuid) {
                    handleReplData(characteristic.value)
                    return@launch
                }
                if (characteristic.uuid == nordicControlCharacteristic?.uuid) {
                    handleNordicControlData(characteristic.value)
                    return@launch
                }
            }

        }

        suspend fun handleAudioData(value: ByteArray) {
            monocleRecieved(value)
        }

        private fun handleReplData(value: ByteArray) {
            val receivedData = String(value)
            if (rawReplResponseCallback != null) {
                lastResponse += receivedData
                if (lastResponse.endsWith(">")) {
                    rawReplResponseCallback!!(lastResponse)
                    lastResponse = ""
                } else if (receivedData.startsWith("OK")) {
                    lastResponse = receivedData
                } else {
                    println("$receivedData\n")
                }
            }
        }

        private fun handleNordicControlData(value: ByteArray) {
            controlResponseCallback!!(value)
        }

    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt() {
        if (bluetoothGatt != null) {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            rxCharacteristic = null
            txCharacteristic = null
            rawTxCharacteristic = null
            rawRxCharacteristic = null
            nordicControlCharacteristic = null
            nordicPacketCharacteristic = null
            writingREPLProgress = false
            currentScannedDevice = null
        }
    }

    //   MONOCLE AUDIO
    private var globalJpegFilePath: String? = null
    private var bitmap: Bitmap? = null
    suspend fun monocleRecieved(data: ByteArray) {
        if (data.size < 4) {
            println("Received on data " + String(data))
            return
        }
        val status = String(data.slice(0 until 4).toByteArray())


        if (status == "ist:") {
            imageBuffer = byteArrayOf(0)
            audioBuffer = byteArrayOf(0)
            println("[NEW_Image Starting to come]\n")
        }
        if (status == "idt:") {
            println("[NEW_Image RECEIVING]\n")
            imageBuffer += data.slice(4 until data.size).toByteArray()
        }
        if (status == "ien:") {
            println("[NEW_Image RECEIVED]\n")

            // create jpeg file .... Then ---
            val outputPath = "output.jpg" // The desired output file path
            bitmap = BitmapFactory.decodeByteArray(imageBuffer, 1, imageBuffer.size - 1)

            if (bitmap != null) {
//                val jpegFile = File(cacheDir, "image.png")
//
//                val fos = FileOutputStream(jpegFile)

// compress the bitmap to a PNG file
//                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, fos)

// close the stream
//                fos.close()
                bitmap = resizeBitmapToMultipleOf64(bitmap!!)
                val jpegFile = saveBitmapAsJPEG(bitmap!!)
                if (jpegFile != null) {
                    globalJpegFilePath = jpegFile.absolutePath
                }
                updatechatList(1, "S", "", bitmap!!)
                if (stabilityApiKey.isNullOrEmpty()) {
                    globalJpegFilePath = null
                    updatechatList(
                        1,
                        "R",
                        "You have to enter stability API key for this feature!",
                        ""
                    )
                }
            }

            var responseData = "ick:"

            dataSendBle(responseData)
        }


        if (status == "ast:") {
//            new audio buffer start, delete old one
            println("[NEW_AUDIO STARTS]\n")
            audioBuffer = byteArrayOf(0)
            audioJob?.cancel()
        }
        if (status == "dat:") {
//            audio buffer append
            audioBuffer += data.slice(4 until data.size).toByteArray()
        }
        if (status == "aen:") {
            println("[AUDIO RECEIVED]\n")
//            process audio buffer
            var responseData = "pin:" + "hello"

            dataSendBle(responseData)
            // Start new coroutine
            audioJob = CoroutineScope(Dispatchers.IO).launch {
                val path = applicationContext.filesDir
                val f2 = File(path, "test.wav")
                if(f2.exists()){
                    f2.delete()
                }
                try {
                    rawToWave(
                        signed8ToUnsigned16(audioBuffer),
                        f2,
                        sampleRate,
                        bitPerSample,
                        channels
                    ) { success ->
                        if (success) {
                            println("[AUDIO PARSED SENDING TO CHATGPT]\n")
                            if (USE_CUSTOM_SERVER) {
                                getGPTResult(f2)
                            } else {


                                uploadAudioFile(f2, byteCallback)


                            }

                        }
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        }
        if (status == "pon:") {
//            var responseData = "res:"+"Got response"
//
//            dataSendBle(responseData)
        }
    }

    fun saveBitmapAsJPEG(bitmap: Bitmap, quality: Int = 100): File? {
        try {
            // Create a directory for saving the JPEG file (you can change the directory path as needed)
//            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MyAppImages")
//            if (!directory.exists()) {
//                directory.mkdirs()
//            }

            // Generate a unique file name using a timestamp

            val fileName = "Output.jpg"

            // Create the JPEG file
            val file = File(cacheDir, fileName)
            val fileOutputStream = FileOutputStream(file)

            // Compress the Bitmap to JPEG format and save it to the file
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fileOutputStream)

            // Close the FileOutputStream
            fileOutputStream.close()

            return file
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }


    private fun updatechatList(id: Int, type: String, msg: String, image: Bitmap?) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ChatGptFragment) {
            fragment.updatechatList(id, type, msg, image)
        }
    }
    private fun updatechatListWithNetworkImg(id: Int,type: String, msg: String, image: String) {
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment is ChatGptFragment) {
            fragment.updatechatList(id, type, msg,image)
        }
    }

    private fun resizeBitmapToMultipleOf64(bitmap: Bitmap): Bitmap {
// Get the original width and height of the bitmap
        val oldWidth = bitmap.width
        val oldHeight = bitmap.height

// Calculate the new width and height that are multiples of 64
        val newWidth = ceil(oldWidth / 64.0).toInt() * 64
        val newHeight = ceil(oldHeight / 64.0).toInt() * 64

// Create a new bitmap with the new dimensions
        val newBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)

// Create a canvas with the new bitmap as its target
        val canvas = Canvas(newBitmap)

// Calculate the x and y coordinates of the original bitmap on the new bitmap
        val x = (newWidth - oldWidth) / 2
        val y = (newHeight - oldHeight) / 2

// Draw the original bitmap on the new bitmap
        canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)

// Return or use the new bitmap as needed
        return newBitmap
    }

    @Throws(IOException::class)
    private fun createJpegFromByteArray(imageBytes: ByteArray, outputPath: String): String {
        val jpegFile = File(cacheDir, outputPath)

        val fos = FileOutputStream(jpegFile)
        fos.write(imageBytes)
        fos.close()

        println("JPEG image saved to: ${jpegFile.absolutePath}")
        return jpegFile.absolutePath
    }
    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now to search")
        }
        val VOICE_RECOGNITION_REQUEST_CODE = 101
        startActivityForResult(intent, VOICE_RECOGNITION_REQUEST_CODE)
    }


    @Throws(IOException::class)
    private fun rawToWave(
        rawPCMBytes: ByteArray,
        waveFile: File,
        sample_rate: Int,
        bit_per_sample: Int,
        channel: Int,
        callback: (Boolean) -> Unit
    ) {
        var output: DataOutputStream? = null

        @Throws(IOException::class)
        fun writeIntToWave(output: DataOutputStream, value: Int) {
            output.write(value shr 0)
            output.write(value shr 8)
            output.write(value shr 16)
            output.write(value shr 24)
        }

        @Throws(IOException::class)
        fun writeShort(output: DataOutputStream, value: Short) {
            output.write(value.toInt() shr 0)
            output.write(value.toInt() shr 8)
        }

        @Throws(IOException::class)
        fun writeString(output: DataOutputStream, value: String) {
            for (i in 0 until value.length) {
                output.write(value[i].code)
            }
        }
        try {
            // Write the audio bytes to a wav file with the appropriate header
            output = DataOutputStream(FileOutputStream(waveFile))
// WAVE header
            writeString(output, "RIFF") // chunk id
            writeIntToWave(output, 36 + rawPCMBytes.size) // chunk size
            writeString(output, "WAVE") // format
            writeString(output, "fmt ") // subchunk 1 id
            writeIntToWave(output, 16) // subchunk 1 size
            writeShort(output, 1.toShort()) // audio format (1 = PCM)
            writeShort(output, channel.toShort()) // number of channels
            writeIntToWave(output, sample_rate) // sample rate
            writeIntToWave(
                output,
                sample_rate * channel * bit_per_sample / 8
            ) // byte rate = sample rate * channels * bytes per sample
            writeShort(
                output,
                (channel * bit_per_sample / 8).toShort()
            ) // block align = channels * bytes per sample
            writeShort(output, bit_per_sample.toShort()) // bits per sample
            writeString(output, "data") // subchunk 2 id
            writeIntToWave(output, rawPCMBytes.size) // subchunk 2 size
// Audio data (conversion big endian -> little endian)
            val shorts = ShortArray(rawPCMBytes.size / 2)
            ByteBuffer.wrap(rawPCMBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            val bytes = ByteBuffer.allocate(shorts.size * 2)
            for (s in shorts) {
                bytes.putShort(s)
            }
            output.write(bytes.array())

        } finally {
            output?.close()
            audioBuffer = byteArrayOf(0)
            callback(true)
        }

    }

    private fun signed8ToUnsigned16(byteArray: ByteArray): ByteArray {
        val unsignedArray = ByteArray(byteArray.size * 2)
        for (i in byteArray.indices) {
            val unsigned = (byteArray[i].toInt() and 0xFF) shl 8
            unsignedArray[i * 2] = (unsigned ushr 8).toByte()
            unsignedArray[i * 2 + 1] = unsigned.toByte()
        }
        return unsignedArray
    }


    // OPEN AI
    private fun uploadAudioFile(audioFile: File, byteCallback: Callback) {
        val client = OkHttpClient()

        // Replace 'YOUR_API_KEY' with your actual OpenAI API key
//        val apiKey = "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"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "audio.wav", audioFile.asRequestBody())
            .addFormDataPart("model", "whisper-1")
            // Add additional parameters if required
            .build()


        Log.d("TAG", "uploadAudioFile: " + requestBody.toString())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/translations")
            //.addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(byteCallback)
    }


    val byteCallback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            // Handle request failure
            updatechatList("S", e.message.toString())
            e.printStackTrace()
        }

        override fun onResponse(call: Call, response: Response) {
            val body = response.body?.string()
            if (body != null) {
                Log.v("data", body)
            } else {
                Log.v("data", "empty")
            }
            val jsonObject = JSONObject(body)
            Log.d("TAG", "onResponse: " + jsonObject)
            if (jsonObject.has("text")) {

                val textResult = jsonObject.getString("text")
                if (textResult.isNullOrEmpty()) {
//                    Toast.makeText(this@BaseActivity,"blank text", Toast.LENGTH_SHORT).show()
                    // updatechatList("S","Text not readable... try again!!")

                    if (translateEnabled) {
                        sendTranslatedResponce("Couldn't translate....try again!", "err:")
                    } else {
                        updatechatList("S", " ")
                        getResponse(" ")
                    }
                } else {
                    if (translateEnabled) {

                        // updatechatList("S",textResult.trim())
                        sendTranslatedResponce(textResult.trim(), "res:")
                    } else {
                        updatechatList("S", textResult.trim())


                        if (globalJpegFilePath.isNullOrEmpty()) {
                            getResponse(textResult)
                        } else {
                            callStabilityAiImagetoImage(textResult.trim())
                        }
                    }

                }

            } else {
                val error: JSONObject = jsonObject.getJSONObject("error")
                val msg: String = error.getString("message")

                sendChatGptResponce(msg, "err:")

            }
        }
    }

    private fun callStabilityAiImagetoImage(prompt: String) {
        val apiKey = stabilityApiKey//"sk-sb1h86seqfVQrvwIT6MNX4Y82SFmiurrhBSwXLlaFPCbt4cb"

        val imageFilePath = globalJpegFilePath // Replace with the actual path to your image file
        val prompt = prompt
        val strength = 0.5f
        val guidance = 1

        globalJpegFilePath = null

        val client = OkHttpClient()

        // Create a request body with multipart form data
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "init_image",
                "Output.jpg",
                File(imageFilePath).asRequestBody("image/jpg".toMediaTypeOrNull())
            )
            .addFormDataPart("text_prompts[0][text]", prompt)
            .addFormDataPart("init_image_mode", "IMAGE_STRENGTH")
            .addFormDataPart("image_strength", strength.toString())
            .addFormDataPart("cfg_scale", guidance.toString())
            .addFormDataPart("samples", "1")
            .build()

        // Create a request with headers and the prepared request body
        val request = Request.Builder()
            .url("https://api.stability.ai/v1/generation/stable-diffusion-v1-5/image-to-image")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Stability-Client-ID", "Noa/Android")
            .post(requestBody)
            .build()

        // Make the network request asynchronously
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {

//                val responseData = response.body?.bytes()
                println("response styability")
                println(response.body)
                if (response.isSuccessful) {
                    val responseData = response.body?.string()


                    val jsonObject = JSONObject(responseData)
                    val artifactsArray = jsonObject.getJSONArray("artifacts")
                    if (artifactsArray.length() > 0) {
                        val base64Value = artifactsArray.getJSONObject(0).getString("base64")

                        // Now you have the base64Value, which you can decode if needed
                        val decodedBytes = Base64.decode(base64Value, Base64.DEFAULT)
                        println("decoded bytes")
                        bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        println(bitmap)
                        updatechatList(1, "R", "", bitmap!!)
                    } else {
                        // Handle the API error here
                        // Log error or show an error message to the user
                    }

                } else {
                    val responseData = response.body?.string()

                    try {
                        val jsonObject = JSONObject(responseData)
                        if (jsonObject.has("message")) {
                            println(jsonObject.get("message"))
                        }
                    } catch (e: java.lang.Exception) {
                        println(responseData)
                        println(e.printStackTrace())
                    }

                    // Handle the API error here
                    // Log error or show an error message to the user
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                println(e)
                // Handle the network error here
                // Log error or show an error message to the user
            }
        })

    }

    fun getResponse(question: String) {
        if(globalJpegFilePath.isNullOrEmpty()) {
            try {

                val url = "https://api.openai.com/v1/engines/text-davinci-003/completions"

                val requestBody = """
            {
            "prompt": "$question",
            "max_tokens": 500,
            "temperature": 0
            }
        """.trimIndent()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("error", "API failed", e)
                        updatechatList("R", e.message.toString())

                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (body != null) {
                            Log.v("data", body)
                        } else {
                            Log.v("data", "empty")
                        }
                        val jsonObject = JSONObject(body)
                        Log.d("TAG", "onResponse: " + jsonObject)

                        if (jsonObject.has("id")) {
                            val jsonArray: JSONArray = jsonObject.getJSONArray("choices")
                            val textResult = jsonArray.getJSONObject(0).getString("text")

                            sendChatGptResponce(textResult, "res:")
//                        callback(textResult)
                        } else {
                            val error: JSONObject = jsonObject.getJSONObject("error")
                            val msg: String = error.getString("message")

                            sendChatGptResponce(msg, "err:")

                        }
                    }
                })
            } catch (ex: Exception) {
                sendChatGptResponce("getResponse: ${ex.message}", "err:")
                Log.d("ChatGpt", "getResponse: $ex")
            }
        }else{
            callStabilityAiImagetoImage(question)
        }
    }

    fun sendChatGptResponce(data: String, prefix: String) {
        updatechatList("R", data)
        val data = prefix + data //err:
        dataSendBle(data)
    }


    fun sendTranslatedResponce(data: String, prefix: String) {
        updatechatList("S", data)
        val data = prefix + data //err:
        dataSendBle(data)
    }


    // MONOCLE COMMUNICATION
    @SuppressLint("MissingPermission")
    private fun rawBleWrite(data: ByteArray) {
        val characteristic = rawRxCharacteristic
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = data
            if (bluetoothGatt != null) {

                bluetoothGatt!!.writeCharacteristic(characteristic)
            }
        }
    }

    private fun dataSendBle(data: String) {
        thread {
            val chunkSize = 90
            var offset = 0
            var actualData = data.substring(4)
            var command = data.substring(0, 4)
            println(actualData)
            writingREPLProgress = false
            while (offset < actualData.length) {
                if (writingREPLProgress) {
                    continue
                }
                val length = kotlin.math.min(chunkSize, actualData.length - offset)
                val chunk = command + actualData.substring(offset, offset + length)
                writingREPLProgress = true
                rawBleWrite(chunk.toByteArray())
                offset += length

            }
        }


    }


    @SuppressLint("MissingPermission")
    private fun replWrite(data: ByteArray, resultDeferred: CompletableDeferred<String>) {

        val characteristic = rxCharacteristic
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            var offset = 0
            val chunkSize = 100
            while (offset < data.size) {
                if (writingREPLProgress) {
                    continue
                }
                val length = minOf(chunkSize, data.size - offset)
                val chunkData = data.slice(offset until offset + length)

                characteristic.value = chunkData.toByteArray()
                writingREPLProgress = true
                if (bluetoothGatt != null) {

                    bluetoothGatt!!.writeCharacteristic(characteristic)
                } else {
                    break
                    resultDeferred.complete("Done")
                }
                offset += length
            }

        }
        resultDeferred.complete("Done")
    }

    private suspend fun replSendBle(data: String): String {
        return coroutineScope {


            val resultDeferred = CompletableDeferred<String>()
            val handler = Handler(Looper.getMainLooper())
            thread {
                replWrite(data.toByteArray() + byteArrayOf(0x04), resultDeferred)
            }

            // Set up the response handler callback
            val bleWriteComplete = CompletableDeferred<String>()
            rawReplResponseCallback = { responseString ->
                println("[RECEIVED]: $responseString\n")
                if (bleWriteComplete.isActive) {
                    bleWriteComplete.complete(responseString)
                }
//
            }

            // Resolve if the response handler callback isn't called
            launch {
                handler.postDelayed({
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete("")
                    }
                    if (!bleWriteComplete.isCompleted) {
                        bleWriteComplete.complete("")
                    }
                }, 3000)
            }
            resultDeferred.await()
            bleWriteComplete.await()
        }
    }

    private suspend fun replSendBle(data: ByteArray): String {
        return coroutineScope {


            val resultDeferred = CompletableDeferred<String>()
            val handler = Handler(Looper.getMainLooper())
            thread {
                replWrite(data, resultDeferred)
            }

            // Set up the response handler callback
            val bleWriteComplete = CompletableDeferred<String>()
            rawReplResponseCallback = { responseString ->
                println("[RECEIVED]: $responseString\n")
                if (bleWriteComplete.isActive) {
                    bleWriteComplete.complete(responseString)
                }
//
            }

            // Resolve if the response handler callback isn't called
            launch {
                handler.postDelayed({
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete("")
                    }
                    if (!bleWriteComplete.isCompleted) {
                        bleWriteComplete.complete("")
                    }
                }, 3000)
            }
            resultDeferred.await()
            bleWriteComplete.await()
        }
    }


    // MAIN FLOW AFTER CONNECTION TO MONOCLE
    suspend fun startBleProcess() {
        replSendBle(byteArrayOf(0x3, 0x3, 0x1))
        //   firmware check and update
        if (currentAppState == BaseActivity.AppState.FIRST_PAIR || currentAppState == BaseActivity.AppState.FPGA_UPDATE) {
            if (currentAppState != BaseActivity.AppState.FPGA_UPDATE && firmwareCheckUpdate() != "Updated") {
                currentAppState = BaseActivity.AppState.SOFTWARE_UPDATE
                println("[STARTED FIRMWARE UPDATE]\n")
                return
            }
            if (currentAppState != BaseActivity.AppState.FPGA_UPDATE) {
                updateProgressDialog("Checking software update..", "Keep the app open")
            }

            if (!NRFKIT && fpgaCheckUpdate() != "Updated") {
                return
            }

            currentDevice = ""
            currentAppState = BaseActivity.AppState.SCRIPT_UPDATE
            startBluetoothBackground()
//            updateProgressDialog("Checking Sofware Update...", "Keep the app open")
            println("[FIRMWARE STABLE]\n")

        }
        //    file upload

        if (NRFKIT) {
            currentAppState =
                if (currentAppState == BaseActivity.AppState.SOFTWARE_UPDATE || currentAppState == AppState.SCRIPT_UPDATE) AppState.SCRIPT_UPDATE else AppState.RUNNING

        } else {
            startFileUpload()
        }
        replSendBle(byteArrayOf(0x3, 0x4))
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment !is ChatGptFragment) {
            val fragment = ChatGptFragment()
            pushFragmentsStatic(fragmentManager, fragment, false, "chat_gpt")

            val apikeyStored = getStoredApiKey()
            if (!apikeyStored.isNullOrEmpty()) {
                apiKey = apikeyStored
            }
            updateConnectionStatus("")
            println("[CHAT READY]\n")
            currentAppState = AppState.RUNNING

            handler.postDelayed({
                showIntroMessages()
            }, 1000)


            if (bluetoothGatt != null) {
                storeDeviceAddress(bluetoothGatt!!.device.address)
            }



        }
        currentAppState = AppState.RUNNING
    }

    fun getThumbnailUrl(url: String): String {
        try {
            val document: Document = Jsoup.connect(url).get()

            // Try to extract Open Graph Protocol image
            val ogImageUrl = document.select("meta[property=og:image]").attr("content")
            if (ogImageUrl.isNotEmpty()) {
                return ogImageUrl
            }

            // Try to extract Twitter Card image
            val twitterImageUrl = document.select("meta[name=twitter:image]").attr("content")
            if (twitterImageUrl.isNotEmpty()) {
                return twitterImageUrl
            }

            // Try to extract other common meta tags for images
            val commonImageUrls = document.select("meta[name=image], meta[itemprop=image]")
            for (element in commonImageUrls) {
                val imageUrl = element.attr("content")
                if (imageUrl.isNotEmpty()) {
                    return imageUrl
                }
            }

            // If no suitable metadata found, you might want to fallback to a default image
            // return a default URL here

        } catch (e: Exception) {
            e.printStackTrace()
            return "";
        }

        // Return null if no thumbnail URL found or an error occurred
        return "";
    }

    /// Changes done private to public for test by ayan



    fun showIntroMessages() {
        val coroutineScope = CoroutineScope(Dispatchers.Default)
        Log.d(
            "Drawable Image====>>>>>",
            BitmapFactory.decodeResource(
                this@BaseActivity.resources,
                R.drawable.openai_website
            ).toString()
        )

        val messagelist = listOf(
            ChatModel(1, "R", "Hi, I’m Noa. Let’s show you around 🙂", false, ""),

            ChatModel(
                1, "R", "Tap either of the touch pads and speak.\n\n" +
                        "Ask me any question, and I’ll then respond directly on your Monocle.", false, getThumbnailUrl("https://platform.openai.com"),
                BitmapFactory.decodeResource(
                    this@BaseActivity.resources,
                    R.drawable.tutorial_image_one
                )

            ),
            ChatModel(
                1,
                "R",
                "I can also translate whatever I hear into English.\n\nToggle the translator mode from the menu like so.",
                false,
                getThumbnailUrl("https://platform.openai.com"),
                BitmapFactory.decodeResource(
                    this@BaseActivity.resources,
                    R.drawable.tutorial_image_single_tap_monocle
                )

            ),
            ChatModel(
                1,
                "R",
                "Did you know that I'm a fantastic artist?\nHold any touch pad, and Monocle will take a picture before listening.\n\nAsk me how to change the image and I'll return back a new image right here in the chat.",
                false,
                getThumbnailUrl("https://platform.openai.com"),
                BitmapFactory.decodeResource(
                    this@BaseActivity.resources,
                    R.drawable.tutorial_image_with_cam
                )

            ),
            ChatModel(
                2,
                "R",
                "To get started, you'll need an OpenAI Api key. To create one visit:\n\nhttps://platform.openai.com\n" +
                        "\nAdditionally, to use the AI art feature, You'll need a stability key. Get it here:\n" +
                        "\nhttps://platform.stability.ai/",
                false,
                getThumbnailUrl("https://platform.openai.com"),
                BitmapFactory.decodeResource(
                    this@BaseActivity.resources,
                    R.drawable.tutorial_monocle
                )
            ),
            ChatModel(1, "R", "Looks like you’re all set!\n" +
                    "\n" +
                    "Go ahead. Ask me anything you’d like ☺️", false, ""),

            )


        // Call updateChatList three times with different messages
        coroutineScope.launch {
            for (message in messagelist) {
                if(!message.image.isNullOrEmpty())
                    updatechatListWithNetworkImg(message.id,message.userInfo,message.message,message.image)
                else
                    updatechatList(message.id,message.userInfo,message.message,message.bitmap)
                delay(1000) // Delay for 1 second between calls
            }
        }
    }

    // SCRIPTS UPLOAD
    private fun readScriptFileFromAssets(fileName: String): String {
        val assetManager: AssetManager = applicationContext.assets
        return assetManager.open("Scripts/$fileName").bufferedReader().use {
            it.readText()
        }
    }

    @SuppressLint("MissingPermission")
    fun fileUploadOne() {
        lifecycleScope.launch {
            startBleProcess()
        }
    }

    private suspend fun startFileUpload(): String {

        val fileNames = FILES

        val files: MutableList<Pair<String, String>> = mutableListOf()
        for (filename in fileNames) {
            val contents = readScriptFileFromAssets(filename)
            files.add(filename to contents)
        }
        val version = generateProgramVersionString(files)
        //        first version check if not matched then upload
        println("[VERSION]: $version\n")
        val response = replSendBle("'NOA_VERSION' in globals() and print(NOA_VERSION)")

        if (!response.contains(version)) {
            println("[FILE  UPLOADING]\n")
            return fileUpload(files, version)  //  TO WORK WITH nrf52DK comment this line
        }
        println("[FILE ALREADY UPLOADED]\n")
        currentAppState = AppState.SCRIPT_UPDATE
        return "Done"


    }

    @SuppressLint("MissingPermission")
    private suspend fun fileUpload(
        files: MutableList<Pair<String, String>>,
        version: String
    ): String {

        val finalResults = mutableListOf<Int>()
        coroutineScope {
            for (file in files) {
                val deferItem = async {
                    var dataSend =
                        "f=open('${file.first}','w');f.write('''${file.second}''');f.close();"
                    if (file.first == "main.py") {
                        dataSend =
                            "f=open('${file.first}','w');f.write('''NOA_VERSION='$version'\n${file.second}''');f.close();"
                    }
                    println("[FILE  UPLOADING] ${file.first}\n")
                    var response = replSendBle(dataSend)
                    if (response.contains("OK") && !response.contains("Error") && !response.contains(
                            "Trace"
                        )
                    ) {
                        println("[FILE  UPLOADED] ${file.first}\n")
                        finalResults.add(1)
                        finalResults.add(1)
                    } else {
                        println("[FILE  UPLOAD FAILED] ${file.first}\n")
                        finalResults.add(0)
                    }
                }
                deferItem.await()

            }
        }
//        println(finalResults)
        if (finalResults.contains(0)) {
            println("[FILE  UPLOADING FAILED]\n")
            return "Failed"
        }
        println("[FILE  UPLOADING DONE]\n")
        currentAppState = AppState.SCRIPT_UPDATE
        return "Done"

    }

    private fun generateProgramVersionString(files: List<Pair<String, String>>): String {
        val concatenatedScripts = files.joinToString("") { (filename, contents) ->
            "$filename$contents"
        }

        val data = concatenatedScripts.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val versionString = StringBuilder()

        for (byte in digest) {
            versionString.append(String.format("%02x", byte))
        }

        return versionString.toString()
    }

    private suspend fun firmwareCheckUpdate(): String {

        var response = replSendBle("import device;print(device.VERSION)")
//        check version if not matched update
        val firmwareData = readFirmwareFromAssets()
        if (firmwareData.binBytes != null && firmwareData.datBytes != null) {
            if (response.contains("Error") || !response.contains(firmwareData.version.toString()) || FIRMWARE_TEST) {
                //            start firmware update
                currentDevice = bluetoothGatt!!.device.address
                replSendBle("import update;update.micropython();")
                return "Failed"
            } else {
                return "Updated"
            }

        }

        return "Updated"
    }

    private suspend fun fpgaCheckUpdate(): String {
        val fpga = readFPGAFromAssets()
        val zipData = readFirmwareFromAssets()
        var response = replSendBle("import fpga;print(fpga.read(2,12));del(fpga)")
        if (fpga.bin != null && fpga.version != null) {
            if (response.contains("Error") || !response.contains(fpga.version.toString()) || FPGA_TEST) {
                currentAppState = AppState.FPGA_UPDATE
                val asciiFile = Base64.encodeToString(fpga.bin, Base64.NO_WRAP)
                var dfuSize = 0
                if (zipData.binBytes != null && zipData.datBytes != null) {
                    dfuSize = zipData.binBytes.size + zipData.datBytes.size
                    overlallSoftwareSize = dfuSize + asciiFile.length
                }
                // perform fpga update
                println("[UPDATING TO VERSION] : ${fpga.version} [FILE SIZE] : ${asciiFile.length}")
                return updateFPGA(asciiFile!!, dfuSize)
            }
        }
        return "Updated"
    }

    private fun readFPGAFromAssets(): Fpga {
        val assetManager: AssetManager = applicationContext.assets
        val packageZips = assetManager.list("FPGA/")
        if (!packageZips.isNullOrEmpty()) {
            val fileName: String = packageZips.first()
            val binFile = assetManager.open("FPGA/${fileName}")
            val pattern = Regex("monocle-fpga-v(\\d+\\.\\d+\\.\\d+)\\.bin")
            val matchResult = pattern.find(fileName)
            var version: String? = matchResult?.groupValues?.get(1)
            val byteData = binFile.readBytes()
            binFile.close()
            return Fpga(byteData, version)
        }
        return Fpga(null, null)
    }

    suspend fun updateFPGA(asciiFile: String, dfuSize: Int): String {
        println("[Starting FPGA update]")
        replSendBle("import ubinascii, update, device, bluetooth, fpga")

        val response = replSendBle("print(bluetooth.max_length())")
        val maxMtu = response.replace("\\D+".toRegex(), "").toInt()

        val chunkSize = ((maxMtu - 45) / 3 / 4 * 4 * 3)
        val chunks = kotlin.math.ceil(asciiFile.length.toDouble() / chunkSize).toInt()
        println("[Chunk] [size] = $chunkSize. [Total chunks] = $chunks")

        replSendBle("fpga.run(False)")
        replSendBle("update.Fpga.erase()")
        var chk = 0
        while (chk < chunks) {
            // last chunk can be small
            var thisChunk = chunkSize
            if (chk == chunks - 1 && asciiFile.length % chunkSize != 0) {
                thisChunk = asciiFile.length % chunkSize
            }
            var chunk = asciiFile.slice(chk * chunkSize until (chk * chunkSize) + thisChunk)

            var response = replSendBle("update.Fpga.write(ubinascii.a2b_base64(b'$chunk'))")

            if (response.contains("Error")) {
                println("Retrying this chunk")
                continue
            }
            if (response == "") {
                break
                return "Failed"
            }

            chk++
            val perc = (100 / asciiFile.length.toDouble()) * chk * chunkSize
            println("[ PERCENT DONE ]: $perc")
            firmwareUpdateProgress(perc, dfuSize, chk * chunkSize)
        }

        replSendBle("update.Fpga.write(b'done')")
        replSendBle("device.reset()")

        println("[Completed FPGA update. Resetting]")
        return "Updated"
    }

    // NORDIC DFU

    // DFU COMMUNICATION
    @SuppressLint("MissingPermission")
    private fun nordicControlWrite(data: ByteArray, resultDeferred: CompletableDeferred<String>) {

        val characteristic = nordicControlCharacteristic
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            var offset = 0
            val chunkSize = 100
            while (offset < data.size) {
                if (writingREPLProgress) {
                    continue
                }
                val length = minOf(chunkSize, data.size - offset)
                val chunkData = data.slice(offset until offset + length)

                characteristic.value = chunkData.toByteArray()
                writingREPLProgress = true
                if (bluetoothGatt != null) {

                    bluetoothGatt!!.writeCharacteristic(characteristic)
                } else {
                    break
                    resultDeferred.complete("Done")
                }
                offset += length
            }

        }
        resultDeferred.complete("Done")
    }

    @SuppressLint("MissingPermission")
    private fun nordicPacketWrite(data: ByteArray, resultDeferred: CompletableDeferred<String>) {

        val characteristic = nordicPacketCharacteristic
        if (bluetoothGatt != null && characteristic != null) {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            var offset = 0
            val chunkSize = 100
            while (offset < data.size) {
                if (writingREPLProgress) {
                    continue
                }
                val length = minOf(chunkSize, data.size - offset)
                val chunkData = data.slice(offset until offset + length)

                characteristic.value = chunkData.toByteArray()
                writingREPLProgress = true
                if (bluetoothGatt != null) {

                    bluetoothGatt!!.writeCharacteristic(characteristic)
                } else {
                    break
                    resultDeferred.complete("Done")
                }
                offset += length
            }

        }
        resultDeferred.complete("Done")
    }

    private suspend fun nordicControlSend(data: ByteArray): ByteArray {
        return coroutineScope {


            val resultDeferred = CompletableDeferred<String>()
            val handler = Handler(Looper.getMainLooper())
            thread {
                nordicControlWrite(data, resultDeferred)
                val formattedString = data.joinToString(", ", "[", "]") { it.toString() }
                println("[NORDIC CONTROL SENT]: $formattedString\n")
            }

            // Set up the response handler callback
            val bleWriteComplete = CompletableDeferred<ByteArray>()
            controlResponseCallback = { controlChar ->
                val formattedString = controlChar.joinToString(", ", "[", "]") { it.toString() }
                println("[NORDIC CONTROL RECEIVED]: $formattedString\n")
                if (bleWriteComplete.isActive) {
                    bleWriteComplete.complete(controlChar)
                }
//
            }

            // Resolve if the response handler callback isn't called
            launch {
                handler.postDelayed({
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete("")
                    }
                    if (!bleWriteComplete.isCompleted) {
                        bleWriteComplete.complete(byteArrayOf())
                    }
                }, 3000)
            }
            resultDeferred.await()
            bleWriteComplete.await()
        }
    }

    private suspend fun nordicPacketSend(data: ByteArray): String {
        return coroutineScope {
            val resultDeferred = CompletableDeferred<String>()
            val handler = Handler(Looper.getMainLooper())
            thread {
                nordicPacketWrite(data, resultDeferred)
            }
            // Resolve if the response handler callback isn't called
            launch {
                handler.postDelayed({
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete("")
                    }
                }, 3000)
            }
            resultDeferred.await()
        }
    }


    // MAIN FLOW AFTER CONNECTION TO DFU
    @SuppressLint("MissingPermission")
    suspend fun startDfuProcess() {
        val zipData = readFirmwareFromAssets()
        val fpgaData = readFPGAFromAssets()

//        currentDevice = bluetoothGatt!!.device.address
        if (zipData.datBytes != null && zipData.binBytes != null) {
            if (fpgaData.bin != null) {
                val asciiFile = Base64.encodeToString(fpgaData.bin, Base64.NO_WRAP)
                overlallSoftwareSize =
                    zipData.binBytes.size + asciiFile.length + zipData.datBytes.size
            }
            transferFile(zipData.datBytes, "init")
            transferFile(zipData.binBytes, "image")
            println("[NORDIC FIRMWARE UPDATE COMPLETE]")

//            updateProgressDialog("Monocle Found", "Connect")
            bluetoothGatt?.disconnect()
            currentAppState = AppState.FPGA_UPDATE
        }
    }

    private fun firmwareUpdateProgress(perc: Double, fileSize: Int = 0, offset: Int) {
        var chunkComplete = offset + fileSize
        overlallSoftwareProgress = ((100.0 / overlallSoftwareSize) * chunkComplete).toInt()
        updateProgressDialog("Updating software ${overlallSoftwareProgress}%", "Keep the app open")
    }

    private fun readFirmwareFromAssets(): ExtractedData {
        val assetManager: AssetManager = applicationContext.assets
        val packageZips = assetManager.list("Firmware/")
        if (!packageZips.isNullOrEmpty()) {
            val zipFile = assetManager.open("Firmware/${packageZips.first()}")
            val zipInputStream = ZipInputStream(zipFile)
            var entry: ZipEntry?
            var datBytes: ByteArray? = null
            var binBytes: ByteArray? = null
            val fileName: String = packageZips.first()
            val pattern = Regex("monocle-micropython-v(\\d+\\.\\d+\\.\\d+)\\.zip")
            val matchResult = pattern.find(fileName)
            var version: String? = matchResult?.groupValues?.get(1)
            while (zipInputStream.nextEntry.also { entry = it } != null) {

                when (entry!!.name) {
                    "manifest.json" -> {
                        val manifestBytes = zipInputStream.readBytes()
                        val manifest = String(manifestBytes, Charsets.UTF_8)
                        // Process the manifest JSON string
                    }

                    "application.dat" -> {
                        datBytes = zipInputStream.readBytes()
                        // Process the datBytes array
                    }

                    "application.bin" -> {
                        binBytes = zipInputStream.readBytes()
                        // Process the binBytes array
                    }

                    else -> {
                        // Handle other files if needed
                    }
                }

                zipInputStream.closeEntry()
            }

            zipInputStream.close()
            return ExtractedData(datBytes, binBytes, version)
        }
        return ExtractedData(null, null, null)
    }

    suspend fun transferFile(data: ByteArray, fileType: String) {
        var response: ByteArray
        // Select command
        response = when (fileType) {
            "init" -> {
                println("[Transferring init file]")
                nordicControlSend(byteArrayOf(0x06, 0x01))
            }

            "image" -> {
                println("[Transferring image file]")
                nordicControlSend(byteArrayOf(0x06, 0x02))
            }

            else -> return // Invalid file type
        }
        if (response.isEmpty()) {
            println("[TRANSFER FAILED]:$fileType")
            return
        }
        val fileSize = data.size

        println("fileSize: $fileSize")
        val responseBuffer: ByteBuffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
        val maxSize = responseBuffer.getInt(3)
        val offset = responseBuffer.getInt(7)
        val crc = responseBuffer.getInt(11)

        println("maxSize: $maxSize, offset: $offset, crc: $crc")

        val chunks = kotlin.math.ceil(fileSize.toDouble() / maxSize).toInt()
        println("Sending file as $chunks chunks")

        var fileOffset = 0
        var chk = 0
        while (chk < chunks) {

            var chunkSize = Math.min(fileSize - fileOffset, maxSize)

            // The last chunk could be smaller
            if (chk == chunks - 1 && fileSize % maxSize != 0) {
                chunkSize = fileSize % maxSize
            }

            val chunkCrc = crc32(data.sliceArray(0 until (fileOffset + chunkSize))).toInt()
            println("[chunk] $chk, [fileOffset]: $fileOffset, [chunkSize]: $chunkSize, [chunkCrc]: $chunkCrc")

            // Create command with size
            val chunkSizeAsBytes = listOf(
                (chunkSize and 0xFF).toByte(),
                ((chunkSize shr 8) and 0xFF).toByte(),
                ((chunkSize shr 16) and 0xFF).toByte(),
                ((chunkSize shr 24) and 0xFF).toByte()
            )

            when (fileType) {
                "init" -> nordicControlSend(byteArrayOf(0x01, 0x01) + chunkSizeAsBytes)
                "image" -> nordicControlSend(byteArrayOf(0x01, 0x02) + chunkSizeAsBytes)
            }

            val currentOffset = fileOffset

            val fileSlice = data.sliceArray(fileOffset until (fileOffset + chunkSize))
            fileOffset += fileSlice.size
            nordicPacketSend(fileSlice)
            response = nordicControlSend(byteArrayOf(0x03))
            if (response.isEmpty()) {
                println("[TRANSFER FAILED]:$fileType")
                return
            }
            val responseBuffer = ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN)
            val returnedOffset = responseBuffer.getInt(3)
            val returnedCrc = responseBuffer.getInt(7)

            println("returnedOffset: $returnedOffset, returnedCrc: $returnedCrc")

            if (returnedCrc != chunkCrc) {
                fileOffset = currentOffset
            } else {
                val perc = (100.0 / fileSize) * fileOffset
                println("[ PERCENT DONE ]: $perc")
                firmwareUpdateProgress(perc, 0, fileOffset)
                chk++
                nordicControlSend(byteArrayOf(0x04))
            }
            // Execute command
        }
    }

    private fun crc32(data: ByteArray): Long {
        val crc32 = CRC32()
        crc32.update(data)
        return crc32.value
    }


    // for server api
    private fun getGPTResult(file: File) {
        var client = OkHttpClient()
        val mediaType = "application/octet-stream".toMediaType()
        println("[SERVER GPT: start]")
        val body = MultipartBody.Builder().setType((MultipartBody.FORM))
            .addFormDataPart("audio", file.absolutePath, file.asRequestBody(mediaType))
            .addFormDataPart("apiKey", apiKey)
            .build()
        val req = Request.Builder().url(BACKEND_URL).post(body).build()
        val response = client.newCall(req).execute()
        println("[SERVER GPT: complete]")
        if (response.isSuccessful && response.body != null) {

            val jsonResponse: String = response.body!!.string()
            if (jsonResponse != null) {
                val jsonObject = JSONObject(jsonResponse)
                if (jsonObject.has("message")) {
                    sendChatGptResponce(jsonObject.get("message").toString(), "err:")
                }
                if (jsonObject.has("transcript")) {
                    updatechatList("S", jsonObject.get("transcript").toString())
                }
                if (jsonObject.has("reply")) {
                    sendChatGptResponce(jsonObject.get("reply").toString(), "res:")
                }
            }
        } else {
            val jsonResponse: String = response.body!!.string()
            if (jsonResponse != null) {
                val jsonObject = JSONObject(jsonResponse)
                if (jsonObject.has("message")) {
                    var msg = jsonObject.get("message")
                    try {
                        // Code that might throw an exception
                        msg = JSONObject(jsonObject.get("message").toString()).get("message")

                    } catch (e: Exception) {
                        // Code to handle the exception
                    } finally {
                        sendChatGptResponce(msg.toString(), "err:")
                        // Code that will be executed regardless of whether an exception occurred or not
                    }

                }
            }
        }
    }
    private fun getGoogleResult(file:File){
        var client = OkHttpClient()
    }
}