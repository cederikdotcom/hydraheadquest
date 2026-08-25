package com.limelight.hydra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that pins this process alive while the WireGuard
 * tunnel is up. Hardware-verified need (2026-08-25): when another app
 * (the ALVR client) goes fullscreen, Android reaps the backgrounded
 * kiosk process and the in-process GoBackend tunnel dies with it,
 * cutting the head off the mesh mid-handoff. A foreground service
 * raises the process priority so the tunnel survives XR handoffs.
 *
 * Started by [HydraWireGuard] when the tunnel comes up, stopped when it
 * goes down. The notification is mandatory for foreground services; on
 * the Quest it is invisible in normal kiosk use.
 */
class HydraTunnelService : Service() {

    companion object {
        private const val CHANNEL_ID = "hydra_tunnel"
        private const val NOTIFICATION_ID = 4721

        fun start(context: Context) {
            val intent = Intent(context, HydraTunnelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HydraTunnelService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hydra mesh tunnel",
                NotificationManager.IMPORTANCE_MIN
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HydraLaunchActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Hydra mesh tunnel active")
            .setContentText("Keeping the WireGuard tunnel alive")
            .setSmallIcon(android.R.drawable.stat_sys_vpn_ic)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
