package de.seemoo.at_tracking_detection.database.models.device.types

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import androidx.annotation.DrawableRes
import de.seemoo.at_tracking_detection.ATTrackingDetectionApplication
import de.seemoo.at_tracking_detection.R
import de.seemoo.at_tracking_detection.database.models.device.*
import de.seemoo.at_tracking_detection.util.ble.BluetoothConstants
import timber.log.Timber
import java.util.*

class AirTag(val id: Int) : Device(), Connectable {

    override val imageResource: Int
        @DrawableRes
        get() = R.drawable.ic_airtag

    override val defaultDeviceNameWithId: String
        get() = ATTrackingDetectionApplication.getAppContext().resources.getString(R.string.device_name_airtag)
            .format(id)

    override val deviceContext: DeviceContext
        get() = AirTag

    override val bluetoothGattCallback: BluetoothGattCallback
        get() = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            // Connect permission is checked before this function is called
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                            BluetoothProfile.STATE_DISCONNECTED -> broadcastUpdate(
                                BluetoothConstants.ACTION_GATT_DISCONNECTED
                            )
                            else -> {
                                Timber.d("Connection state changed to $newState")
                            }
                        }
                    }
                    19 -> {
                        broadcastUpdate(BluetoothConstants.ACTION_EVENT_COMPLETED)
                    }
                    else -> broadcastUpdate(BluetoothConstants.ACTION_EVENT_FAILED)
                }
            }

            @SuppressLint("MissingPermission")
            // Connect permission is checked before this function is called
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(AIR_TAG_SOUND_SERVICE)
                if (service == null) {
                    Timber.e("AirTag sound service not found!")
                    disconnect(gatt)
                    broadcastUpdate(BluetoothConstants.ACTION_EVENT_FAILED)
                } else {
                    service.getCharacteristic(AIR_TAG_SOUND_CHARACTERISTIC)
                        .let {
                            it.setValue(175, BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                            gatt.writeCharacteristic(it)
                            broadcastUpdate(BluetoothConstants.ACTION_EVENT_RUNNING)
                            Timber.d("Playing sound...")
                        }
                }
                super.onServicesDiscovered(gatt, status)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    when (characteristic.properties and AIR_TAG_EVENT_CALLBACK) {
                        AIR_TAG_EVENT_CALLBACK -> {
                            broadcastUpdate(
                                BluetoothConstants.ACTION_EVENT_COMPLETED
                            )
                            disconnect(gatt)
                        }
                    }
                }
                else if (status == 133) { // GATT_ERROR, Timeout
                    broadcastUpdate(BluetoothConstants.ACTION_EVENT_FAILED)
                    disconnect(gatt)
                }else {
                    disconnect(gatt)
                }
                super.onCharacteristicWrite(gatt, characteristic, status)
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                    when (characteristic.properties and AIR_TAG_EVENT_CALLBACK) {
                        AIR_TAG_EVENT_CALLBACK -> {
                            broadcastUpdate(
                                BluetoothConstants.ACTION_EVENT_COMPLETED
                            )
                            disconnect(gatt)
                        }
                    }
                }
            }
        }

    companion object : DeviceContext {
        private val AIR_TAG_SOUND_SERVICE = UUID.fromString("7DFC9000-7D1C-4951-86AA-8D9728F8D66C")
        private val AIR_TAG_SOUND_CHARACTERISTIC =
            UUID.fromString("7DFC9001-7D1C-4951-86AA-8D9728F8D66C")
        private const val AIR_TAG_EVENT_CALLBACK = 0x302

        /**
         * Byte offset in the Apple Find My manufacturer data (company ID 0x4C) where the rotating
         * public key payload begins. Bytes 0–2 are type (0x12), length (0x19), and status; the key
         * starts at byte 3.
         */
        private const val FIND_MY_ADV_KEY_OFFSET = 3

        override val bluetoothFilter: ScanFilter
            get() = ScanFilter.Builder()
                .setManufacturerData(
                    0x4C,
                    // Only Offline Devices:
                    // byteArrayOf((0x12).toByte(), (0x19).toByte(), (0x10).toByte()),
                    // byteArrayOf((0xFF).toByte(), (0xFF).toByte(), (0x18).toByte())
                    // All Devices:
                    byteArrayOf((0x12).toByte(), (0x19).toByte(), (0x10).toByte()),
                    byteArrayOf((0xFF).toByte(), (0x00).toByte(), (0x18).toByte())
                )
                .build()

        override val deviceType: DeviceType
            get() = DeviceType.AIRTAG

        override val defaultDeviceName: String
            get() = ATTrackingDetectionApplication.getAppContext().resources.getString(R.string.airtag_default_name)

        override val statusByteDeviceType: UInt
            get() = 1u

        override val websiteManufacturer: String
            get() = "https://www.apple.com/airtag/"

        override fun getBatteryState(scanResult: ScanResult): BatteryState {
            val mfg: ByteArray? = scanResult.scanRecord?.getManufacturerSpecificData(0x4C)

            if (mfg != null && mfg.size >= 3) {
                val status = mfg[2] // Extract the status byte

                // Bits 6-7: Battery level
                val batteryLevel = (status.toInt() shr 6) and 0x03

                // Full: 0, Medium 1, Low 2, Very Low 3
                when (batteryLevel) {
                    0x00 -> return BatteryState.FULL
                    0x01 -> return BatteryState.MEDIUM
                    0x02 -> return BatteryState.LOW
                    0x03 -> return BatteryState.VERY_LOW
                }
            }

            return BatteryState.UNKNOWN
        }

        /**
         * Extracts the rotating public key payload from the Apple Find My advertisement.
         *
         * Apple AirTag and Find My accessories include the first 22 bytes of their
         * current rotating public key (EC key) in the manufacturer-specific data (0x4C)
         * starting at byte offset 3 (after type, length, and status bytes).
         * This key rotates roughly every 15 minutes together with the Bluetooth MAC address.
         *
         * Within a single rotation window the key is stable, so it can be used for
         * exact-match identification — more precise than the time-window algorithm.
         *
         * @return hex-encoded key bytes, or null if the advertisement does not contain
         *         enough data (e.g. the device is currently connected to its owner).
         */
        fun getAlternativeIdentifier(scanResult: ScanResult): String? {
            try {
                val mfg: ByteArray? = scanResult.scanRecord?.getManufacturerSpecificData(0x4C)
                // Bytes 0-2: type (0x12), length (0x19), status — skip them.
                // Bytes FIND_MY_ADV_KEY_OFFSET+: rotating public key payload.
                // In the standard offline-finding advertisement this is 22 bytes (bytes 3–24),
                // giving a total manufacturer data length of 25 bytes. We accept any length > 3
                // so that minor variations in the advertisement format (e.g. Find My accessories
                // that advertise fewer key bytes) are still captured. The full slice is used as
                // the identifier so payloads of different lengths cannot collide.
                if (mfg != null && mfg.size > FIND_MY_ADV_KEY_OFFSET) {
                    return mfg.sliceArray(FIND_MY_ADV_KEY_OFFSET until mfg.size)
                        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error extracting alternative identifier for Apple device")
            }
            return null
        }
    }
}