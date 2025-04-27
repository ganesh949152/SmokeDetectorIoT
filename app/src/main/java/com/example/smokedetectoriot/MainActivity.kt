package com.example.smokedetectoriot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context.BLUETOOTH_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    // Bluetooth setup
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var selectedDeviceAddress: String? = null
    private lateinit var enableBluetoothLauncher: ActivityResultLauncher<Intent>
    private val pairedDevices = mutableSetOf<BluetoothDevice>()

    // UI Elements
    private lateinit var gasLevelTextView: TextView
    private lateinit var temperatureTextView: TextView
    private lateinit var humidityTextView: TextView
    private lateinit var locationTextView: TextView
    private lateinit var forwardButton: Button
    private lateinit var backwardButton: Button
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var speedUpButton: Button
    private lateinit var speedDownButton: Button
    private lateinit var getDataButton: Button
    private lateinit var alertTextView: TextView
    private lateinit var pairedDevicesListView: ListView
    private lateinit var mq7TextView: TextView

    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 1
        private val UUID_SERIAL: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "MainActivity"
        private const val DATA_READ_DELAY_MS = 1000L // Delay for reading
        private const val GET_DATA_DELAY_MS = 1000L; // Delay after sending GETDATA command.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        gasLevelTextView = findViewById(R.id.gas_level_value)
        temperatureTextView = findViewById(R.id.temperature_value)
        humidityTextView = findViewById(R.id.humidity_value)
        locationTextView = findViewById(R.id.location_value)
        forwardButton = findViewById(R.id.forward_button)
        backwardButton = findViewById(R.id.backward_button)
        leftButton = findViewById(R.id.left_button)
        rightButton = findViewById(R.id.right_button)
        speedUpButton = findViewById(R.id.speed_up_button)
        speedDownButton = findViewById(R.id.speed_down_button)
        getDataButton = findViewById(R.id.get_data_button)
        alertTextView = findViewById(R.id.alert_text_view)
        pairedDevicesListView = findViewById(R.id.paired_devices_list)
        mq7TextView = findViewById(R.id.mq7_value)

        // Get Bluetooth Adapter
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Initialize ActivityResultLauncher for enabling Bluetooth
        enableBluetoothLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                if (result.resultCode == RESULT_OK) {
                    // Bluetooth was enabled
                    getPairedDevices()
                } else {
                    // Bluetooth enabling was refused
                    Toast.makeText(this, "Bluetooth enabling was refused", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }

        // Initially hide control buttons and sensor data
        setControlVisibility(false)
        alertTextView.visibility = View.GONE

        if (!bluetoothAdapter?.isEnabled!!) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        } else {
            getPairedDevices()
        }

        pairedDevicesListView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val selectedDevice = pairedDevices.elementAt(position)
                selectedDeviceAddress = selectedDevice.address
                connectBluetooth(selectedDevice)
            }

        // Set up button touch listeners for continuous sending
        setupButtonListeners()

        getDataButton.setOnClickListener {
            Log.d(TAG, "GetData button clicked")
            sendCommand("GETDATA\n")
            handler.postDelayed({
                receiveData()
            }, GET_DATA_DELAY_MS)
        }
    }

    private fun setupButtonListeners() {
        forwardButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "FORWARD\n")
        }
        backwardButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "BACKWARD\n")
        }
        leftButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "LEFT\n")
        }
        rightButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "RIGHT\n")
        }
        speedUpButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "SPEEDUP\n")
        }
        speedDownButton.setOnTouchListener { view, event ->
            handleButtonTouchEvent(view, event, "SPEEDDOWN\n")
        }
    }

    private fun handleButtonTouchEvent(view: View, event: MotionEvent, command: String): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> sendCommand(command)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> sendCommand("STOP\n")
        }
        return true
    }

    private fun getPairedDevices() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_CONNECT)
            return
        }

        val bondedDevices = bluetoothAdapter?.bondedDevices
        if (bondedDevices != null && bondedDevices.isNotEmpty()) {
            pairedDevices.addAll(bondedDevices)
            val deviceNames = bondedDevices.map { it.name }.toTypedArray()
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)
            pairedDevicesListView.adapter = adapter
        } else {
            Toast.makeText(this, "No paired devices found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_CONNECT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getPairedDevices()
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun connectBluetooth(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH_CONNECT)
            return
        }

        try {
            bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID_SERIAL)
            bluetoothSocket?.connect()
            isConnected = true
            inputStream = bluetoothSocket?.inputStream
            outputStream = bluetoothSocket?.outputStream
            Toast.makeText(this, "Connected to ${device.name}", Toast.LENGTH_SHORT).show()
            setControlVisibility(true)
            receiveData()
        } catch (e: IOException) {
            isConnected = false
            e.printStackTrace()
            Log.e(TAG, "Connection failed: ${e.message}")
            handler.post { Toast.makeText(this, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show() }
            setControlVisibility(false)
        }
    }

    private fun setControlVisibility(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        forwardButton.visibility = visibility
        backwardButton.visibility = visibility
        leftButton.visibility = visibility
        rightButton.visibility = visibility
        speedUpButton.visibility = visibility
        speedDownButton.visibility = visibility
        getDataButton.visibility = visibility
        gasLevelTextView.visibility = visibility
        temperatureTextView.visibility = visibility
        humidityTextView.visibility = visibility
        locationTextView.visibility = visibility
        mq7TextView.visibility = visibility
    }

    private fun sendCommand(command: String) {
        if (isConnected && outputStream != null) {
            try {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                Log.d(TAG, "Sent command: $command")
            } catch (e: IOException) {
                e.printStackTrace()
                Log.e(TAG, "Failed to send command: ${e.message}")
                handler.post { Toast.makeText(this, "Failed to send command: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        } else {
            Toast.makeText(this, "Not connected to a device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun receiveData() {
        Thread {
            val buffer = ByteArray(1024)
            var bytes: Int
            while (isConnected && inputStream != null) {
                try {
                    bytes = inputStream!!.read(buffer)
                    if (bytes > 0) {
                        val data = String(buffer, 0, bytes)
                        Log.d(TAG, "Received data: $data")
                        handler.postDelayed({ processReceivedData(data) }, DATA_READ_DELAY_MS)
                    }
                } catch (e: IOException) {
                    isConnected = false
                    e.printStackTrace()
                    Log.e(TAG, "Error receiving data: ${e.message}")
                    handler.post {
                        Toast.makeText(this, "Disconnected from device: ${e.message}", Toast.LENGTH_LONG).show()
                        setControlVisibility(false)
                    }
                    break
                }
            }
        }.start()
    }

    private fun processReceivedData(data: String) {
        if (data.startsWith("ALERT:")) {
            val parts = data.split(",")
            val alertMessage = parts.getOrNull(0)?.substringAfter("ALERT:") ?: "Unknown Alert"
            val latitude = parts.getOrNull(1)?.substringAfter("Latitude:") ?: "N/A"
            val longitude = parts.getOrNull(2)?.substringAfter("Longitude:") ?: "N/A"
            val humidity = parts.getOrNull(3)?.substringAfter("Humidity:") ?: "N/A"
            val temperature = parts.getOrNull(4)?.substringAfter("Temp:") ?: "N/A"

            handler.post {
                alertTextView.text = "Alert: $alertMessage, Lat: $latitude, Lon: $longitude"
                alertTextView.visibility = View.VISIBLE
                Log.d(TAG, "Displaying Alert")
            }
        } else if (data.startsWith("DATA:")) {
            val dataWithoutPrefix = data.substringAfter("DATA:")
            val sensorValues = dataWithoutPrefix.split(",")
            var mq2Value: String? = null
            var mq7Value: String? = null
            var humidity: String? = null
            var temperature: String? = null
            var latitude: String? = null
            var longitude: String? = null

            for (value in sensorValues) {
                when {
                    value.startsWith("MQ2Value:") -> mq2Value = value.substringAfter("MQ2Value:").trim()
                    value.startsWith("MQ7Value:") -> mq7Value = value.substringAfter("MQ7Value:").trim()
                    value.startsWith("Humidity:") -> humidity = value.substringAfter("Humidity:").trim()
                    value.startsWith("Temp:") -> temperature = value.substringAfter("Temp:")?.trim()
                    value.startsWith("Latitude:") -> latitude = value.substringAfter("Latitude:")?.trim()
                    value.startsWith("Longitude:") -> longitude = value.substringAfter("Longitude:")?.trim()
                }
            }

            Log.d(TAG, "Parsed Data - MQ2:$mq2Value,MQ7:$mq7Value,Humidity:$humidity,Temp:$temperature,Lat:$latitude,Lon:$longitude")

            handler.post {
                gasLevelTextView.text = "Methane Level: ${mq2Value ?: "N/A"}"
                mq7TextView.text = "Carbon Monoxide Level: ${mq7Value ?: "N/A"}"
                humidityTextView.text = "Humidity: ${humidity ?: "N/A"}"
                temperatureTextView.text = "Temperature: ${temperature ?: "N/A"}"
                locationTextView.text = "Location: Lat: ${latitude ?: "N/A"}, Lon: ${longitude ?: "N/A"}"
                alertTextView.visibility = View.GONE
            }
        } else {
            Log.w(TAG, "Received data format not recognized: $data")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isConnected = false
        try {
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e(TAG, "Error closing socket: ${e.message}")
        }
    }
}
