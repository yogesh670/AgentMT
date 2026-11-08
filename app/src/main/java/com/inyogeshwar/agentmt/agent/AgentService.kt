package com.inyogeshwar.agentmt.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.inyogeshwar.agentmt.MainActivity
import com.inyogeshwar.agentmt.R

/**
 * Keeps the process alive while an agent turn or a Linux job runs, with an ongoing notification.
 *
 * Android kills the app (ForegroundServiceDidNotStartInTimeException) if a service started with
 * startForegroundService() is stopped before it calls startForeground(). So every start reaches onStartCommand,
 * which always calls startForeground() first; stopping is a "stop" intent handled there, never stopService().
 * Several holders (agent runs, Linux jobs) share the service; it stops when the last one releases it.
 */
class AgentService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP && holders.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wake == null) wake = runCatching {
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agentmt:run").apply { acquire(30 * 60 * 1000L) }
        }.getOrNull()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Agent activity", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n: Notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("AgentMT is working")
                .setContentText(if (holders.contains(HOLD_JOBS) && !holders.contains(HOLD_AGENT)) "Installing in Linux" else "Your agent is running a task")
                .setOngoing(true).setContentIntent(open).setSilent(true)
                .build()
            ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }
    }

    override fun onDestroy() { wake?.takeIf { it.isHeld }?.release(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "agent"
        private const val ACTION_STOP = "stop"
        const val HOLD_AGENT = "agent"
        const val HOLD_JOBS = "jobs"
        private val holders = java.util.Collections.synchronizedSet(HashSet<String>())

        fun start(c: Context, holder: String = HOLD_AGENT) {
            holders += holder
            runCatching { ContextCompat.startForegroundService(c, Intent(c, AgentService::class.java)) }
        }

        fun stop(c: Context, holder: String = HOLD_AGENT) {
            holders -= holder
            if (holders.isNotEmpty()) return
            // Goes through onStartCommand so startForeground() always runs before the service stops.
            runCatching { ContextCompat.startForegroundService(c, Intent(c, AgentService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
