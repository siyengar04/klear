package xyz.brilliant.argpt.ui.fragment

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import xyz.brilliant.argpt.R
import xyz.brilliant.argpt.ui.activity.BaseActivity
import java.util.*

class WeatherFragment : Fragment() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var weatherCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var weatherTextView: TextView

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is BaseActivity) {
            bluetoothAdapter = context.bluetoothAdapter
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_weather, container, false)
        weatherTextView = view.findViewById(R.id.weatherTextView)
        connectToBLEDevice()
        return view
    }

    @SuppressLint("MissingPermission")
    private fun connectToBLEDevice() {
        val deviceAddress = "YOUR_DEVICE_ADDRESS" // Replace with your device's address
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val serviceUUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") // Replace with your service UUID
            val charUUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Replace with your characteristic UUID
            val service = gatt.getService(serviceUUID)
            weatherCharacteristic = service?.getCharacteristic(charUUID)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == weatherCharacteristic?.uuid) {
                val weatherData = characteristic.value
                updateWeatherUI(weatherData)
            }
        }
    }

    private fun updateWeatherUI(data: ByteArray) {
        // Update your UI with new weather data
        val weatherDataString = String(data) // Convert data to string or your preferred format
        activity?.runOnUiThread {
            weatherTextView.text = weatherDataString
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bluetoothGatt?.close()
    }
}
