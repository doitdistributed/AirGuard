package de.seemoo.at_tracking_detection.ui.tracking

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.*
import de.seemoo.at_tracking_detection.database.models.Beacon
import de.seemoo.at_tracking_detection.database.models.device.BaseDevice
import de.seemoo.at_tracking_detection.database.models.device.Connectable
import de.seemoo.at_tracking_detection.database.models.device.ConnectionState
import de.seemoo.at_tracking_detection.database.models.device.DeviceManager
import de.seemoo.at_tracking_detection.database.models.device.DeviceType
import de.seemoo.at_tracking_detection.database.repository.BeaconRepository
import de.seemoo.at_tracking_detection.database.repository.DeviceRepository
import de.seemoo.at_tracking_detection.database.repository.NotificationRepository
import de.seemoo.at_tracking_detection.util.SharedPrefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import javax.inject.Inject
import androidx.core.net.toUri

class TrackingViewModel @Inject constructor(
    private val notificationRepository: NotificationRepository,
    private val beaconRepository: BeaconRepository,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    val deviceAddress = MutableLiveData<String>()

    val notificationId = MutableLiveData<Int>()

    val noLocationsYet = MutableLiveData(true)

    val manufacturerWebsiteUrl = MutableLiveData<String>()

    var deviceType = MutableLiveData<DeviceType>(DeviceType.UNKNOWN)

    val error = MutableLiveData(false)

    val falseAlarm = MutableLiveData(false)
    val deviceIgnored = MutableLiveData(false)
    val deviceSafeTracker = MutableLiveData(false)
    val trackerObserved = MutableLiveData(false)

    val soundPlaying = MutableLiveData(false)
    val connecting = MutableLiveData(false)

    val device = MutableLiveData<BaseDevice?>()
    val connectable = MutableLiveData(false)

    val canBeIgnored = MutableLiveData(false)

    val showNfcHint = MutableLiveData(false)

    val isMapLoading = MutableLiveData(false)

    // Reactively update markers when beacons are written
    // This is relevant for the case when a user very quickly opens the map from the manual scan while the device and beacons are still beeing written
    val markerLocations: LiveData<List<Beacon>> = deviceAddress.switchMap { address ->
        beaconRepository.getDeviceBeaconsFlow(address).asLiveData()
    }

    val amountBeacons: LiveData<String> = markerLocations.map {
        it.size.toString()
    }

    val lastSeenTimes: MutableLiveData<List<String>> = MutableLiveData(emptyList())
    val lastSeenTimesString: LiveData<String> = lastSeenTimes.map {
        it.joinToString(separator = "\n")
    }

    val expertMode = MutableLiveData(false)

    val deviceComment = MutableLiveData<String>("")

    /**
     * The identifier to display in the device detail UI. Returns null for device types where
     * the identifier should not be exposed (Samsung trackers use an internal public key).
     * - Apple (AirTag / FindMy): rotating advertisement key hex when available, else MAC address
     * - All others: Bluetooth MAC address
     */
    val deviceIdentifier: LiveData<String?> = device.map { dev ->
        if (dev == null) return@map null
        when (dev.deviceType) {
            DeviceType.SAMSUNG_TRACKER, DeviceType.SAMSUNG_FIND_MY_MOBILE -> null
            DeviceType.AIRTAG, DeviceType.FIND_MY -> dev.alternativeIdentifier ?: dev.address
            else -> dev.address
        }
    }

    fun loadDevice(address: String, deviceTypeOverride: DeviceType) {
        deviceAddress.postValue(address)
        deviceType.postValue(deviceTypeOverride)

        viewModelScope.launch {
            deviceRepository.observeDevice(address).collectLatest { dev ->
                this@TrackingViewModel.device.postValue(dev)

                if (dev != null) {
                    deviceType.value = dev.device.deviceContext.deviceType
                    val deviceObserved = dev.nextObservationNotification != null && dev.nextObservationNotification!!.isAfter(
                        LocalDateTime.now())
                    trackerObserved.postValue(deviceObserved)
                    deviceIgnored.postValue(dev.ignore)
                    deviceSafeTracker.postValue(dev.safeTracker)
                    noLocationsYet.postValue(false)
                    connectable.postValue(dev.device is Connectable)
                    canBeIgnored.postValue(deviceType.value!!.canBeIgnored(ConnectionState.OVERMATURE_OFFLINE))
                    val notification = notificationRepository.notificationForDevice(dev).firstOrNull()
                    notification?.let { notificationId.postValue(it.notificationId) }
                    falseAlarm.postValue(notification?.falseAlarm ?: false)

                    // Update last seen times based on current beacons
                    val beacons = beaconRepository.getDeviceBeacons(dev.address)
                    val lastSeenList = beacons.sortedByDescending { it.receivedAt }.take(5).map { beacon ->
                        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(beacon.receivedAt)
                    }
                    lastSeenTimes.postValue(lastSeenList)

                    expertMode.postValue(SharedPrefs.advancedMode)
                    deviceComment.postValue(dev.comment ?: "")
                } else {
                    noLocationsYet.postValue(true)
                    deviceComment.postValue("")
                }

                showNfcHint.postValue(deviceType.value == DeviceType.AIRTAG)
                manufacturerWebsiteUrl.postValue(DeviceManager.getWebsiteURL(deviceType.value!!))
            }
        }
    }

    fun toggleIgnoreDevice() {
        if (deviceAddress.value != null) {
            val newState = !deviceIgnored.value!!
            viewModelScope.launch {
                deviceRepository.setIgnoreFlag(deviceAddress.value!!, newState)
            }
            deviceIgnored.postValue(newState)
            Timber.d("Toggle ignore device - new State: $newState")
        }
    }

    fun toggleSafeTracker() {
        val address = deviceAddress.value ?: return
        val newState = !(deviceSafeTracker.value ?: false)
        viewModelScope.launch {
            if (device.value == null) {
                // Device not yet in the database (e.g. opened from a live scan result).
                // Create a minimal entry so the flag can be persisted.
                val now = LocalDateTime.now()
                val newDevice = BaseDevice(
                    deviceId = 0,
                    uniqueId = UUID.randomUUID().toString(),
                    address = address,
                    name = null,
                    ignore = false,
                    connectable = null, // unknown — prevents participation in connectable-based device matching
                    payloadData = null,
                    firstDiscovery = now,
                    lastSeen = now,
                    notificationSent = false,
                    lastNotificationSent = null,
                    deviceType = deviceType.value ?: DeviceType.UNKNOWN,
                    riskLevel = 0,
                    lastCalculatedRiskDate = now,
                    nextObservationNotification = null,
                    currentObservationDuration = null,
                    additionalData = null,
                )
                deviceRepository.insert(newDevice)
                Timber.d("Created device entry for $address before toggling safe-tracker flag")
            }
            deviceRepository.setSafeTrackerFlag(address, newState)
            deviceSafeTracker.postValue(newState)
            Timber.d("Toggle safe tracker - new State: $newState")
        }
    }

    fun toggleFalseAlarm() {
        if (notificationId.value != null) {
            val newState = !falseAlarm.value!!
            viewModelScope.launch {
                notificationRepository.setFalseAlarm(notificationId.value!!, newState)
            }
            falseAlarm.postValue(newState)
        }
    }

    fun clickOnWebsite(context: android.content.Context) {
        if (manufacturerWebsiteUrl.value != null) {
            Timber.d("Click on website: ${manufacturerWebsiteUrl.value}")
            val webpage: Uri = manufacturerWebsiteUrl.value!!.toUri()
            val intent = Intent(Intent.ACTION_VIEW, webpage)
            context.startActivity(intent)
        }
    }

    fun updateDeviceComment(newComment: String) {
        device.value?.let { baseDevice ->
            if (baseDevice.comment != newComment) {
                baseDevice.comment = newComment.ifBlank { null }
                viewModelScope.launch {
                    deviceRepository.update(baseDevice)
                }
            }
        }
        deviceComment.postValue(newComment)
    }
}