package com.ztec.cplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.ztec.cplay.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class NexusCpSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        // Notification channels and Notification.Builder(Context, String) are API 26.
        // Android 7/7.1 posts the notification on the legacy channel-less path instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notification_channel_carplay), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, CarPlayHostActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NexusCpSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_carplay_running))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_disconnect), stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.ztec.cplay.DISCONNECT"
        private const val CHANNEL = "nexuscp_connection"
    }
}
