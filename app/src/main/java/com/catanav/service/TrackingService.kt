package com.catanav.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.catanav.CataNavApp
import com.catanav.R
import com.catanav.pdr.SensorHub
import com.catanav.trip.BatteryEstimator
import com.catanav.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service for background tracking: keeps the process alive with the screen
 * off (PARTIAL_WAKE_LOCK — sensors keep sampling), owns the SensorHub, and surfaces
 * the persistent notification. All trip STATE lives in the shared TripSession; killing
 * and recreating this service never loses data (autosave is per point).
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.catanav.action.START_TRACKING"
        const val ACTION_STOP = "com.catanav.action.STOP_TRACKING"
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1

        /** Health-state flows the UI observes; service-scoped is enough for v1. */
        val sensorHealth = MutableStateFlow<SensorHub.Health?>(null)
        val batteryPercent = MutableStateFlow<Int?>(null)
        val batteryEstimateMs = MutableStateFlow<Long?>(null)
        var isRunning = false
            private set
    }

    private var scope: CoroutineScope? = null
    private var sensorHub: SensorHub? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val batteryEstimator = BatteryEstimator()
    private var batteryReceiver: android.content.BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        if (isRunning) return
        isRunning = true
        val app = CataNavApp.instance
        val session = app.locator.tripSession

        createChannel()
        val notification = buildNotification(0)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // "health" FGS type exists from API 34; its while-in-use prerequisite
                // is satisfied by HIGH_SAMPLING_RATE_SENSORS (install-time).
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // FGS prerequisites unexpectedly missing: fail closed, never track silently.
            isRunning = false
            stopSelf()
            return
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CataNav:tracking").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // hard cap: 12 h, longer than any battery
        }

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        sc.launch {
            val source = app.locator.settings.headingSource.first()
            val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val hub = SensorHub(sm, source)
            sensorHub = hub
            hub.onStep = { session.onSensorStep(it) }
            hub.onHeading = { session.onSensorHeading(it) }
            sc.launch {
                hub.health.collect { session.onSensorConfidence(it.headingConfidence) }
            }
            val stepPermission = ContextCompat.checkSelfPermission(
                this@TrackingService, Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED
            hub.start(stepPermission)
            session.manualMode =
                hub.capabilityFor(stepPermission) != SensorHub.Capability.FULL_PDR
            sc.launch { hub.health.collect { sensorHealth.value = it } }
            sc.launch {
                session.stepCount.collect { steps ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, buildNotification(steps))
                }
            }
        }

        batteryEstimator.reset()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    val pct = level * 100 / scale
                    batteryPercent.value = pct
                    batteryEstimator.onSample(System.currentTimeMillis(), pct)
                    batteryEstimateMs.value = batteryEstimator.estimateRemainingMs()
                }
            }
        }
        batteryReceiver = receiver
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun stopTracking() {
        if (!isRunning) return
        isRunning = false
        sensorHub?.stop()
        sensorHub = null
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope?.cancel()
        scope = null
        sensorHealth.value = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_tracking),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(steps: Int): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text, steps))
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setOnlyAlertOnce(true)
            .build()
    }
}
