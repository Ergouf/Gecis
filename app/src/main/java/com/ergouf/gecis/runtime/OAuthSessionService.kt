package com.ergouf.gecis.runtime

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.ergouf.gecis.MainActivity

class OAuthSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(
            this,
            title = "正在连接 Google 账号",
            text = "登录完成后点这里返回 Gecis。Brave 等浏览器通常不会自动跳转。",
            ongoing = true,
        )
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                SESSION_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            )
        } else {
            startForeground(SESSION_NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL_ID = "gecis_oauth"
        const val APP_RETURN_URI = "gecis://oauth-complete"
        private const val SESSION_NOTIFICATION_ID = 21

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Google 登录",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "登录 Google 时保持 Gecis 在线，并在完成后提醒返回"
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context) {
            val app = context.applicationContext
            ensureChannel(app)
            val intent = Intent(app, OAuthSessionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).post {
                app.stopService(Intent(app, OAuthSessionService::class.java))
                app.getSystemService(NotificationManager::class.java)?.cancel(SESSION_NOTIFICATION_ID)
            }
        }

        fun bringAppToFront(context: Context) {
            val app = context.applicationContext
            runCatching {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.appTasks.firstOrNull()?.moveToFront()
            }
            runCatching { app.startActivity(returnIntent(app)) }
            markSuccess(app)
        }

        fun markSuccess(context: Context) {
            val app = context.applicationContext
            ensureChannel(app)
            app.getSystemService(NotificationManager::class.java)?.notify(
                SESSION_NOTIFICATION_ID,
                buildNotification(
                    app,
                    title = "Google 账号已连接",
                    text = "点此返回 Gecis 继续对话。Brave 不会自动跳转。",
                    ongoing = true,
                ),
            )
        }

        fun cancelReturn(context: Context) {
            stop(context)
        }

        fun returnIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(APP_RETURN_URI)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }

        private fun buildNotification(
            context: Context,
            title: String,
            text: String,
            ongoing: Boolean,
        ): Notification {
            val pending = PendingIntent.getActivity(
                context,
                0,
                returnIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(!ongoing)
                .setOngoing(ongoing)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build()
        }
    }
}
