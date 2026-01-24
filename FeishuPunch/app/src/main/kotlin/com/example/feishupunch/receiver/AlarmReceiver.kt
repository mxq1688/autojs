package com.example.feishupunch.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.feishupunch.R
import com.example.feishupunch.WakeUpActivity
import com.example.feishupunch.service.PunchAccessibilityService
import com.example.feishupunch.util.AlarmHelper
import com.example.feishupunch.util.PreferenceHelper
import java.util.Calendar

/**
 * 闹钟广播接收器 - 触发工作
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        const val ACTION_MORNING_PUNCH = "com.example.feishupunch.MORNING_PUNCH"
        const val ACTION_EVENING_PUNCH = "com.example.feishupunch.EVENING_PUNCH"
        const val ACTION_CLOSE_APP = "com.example.feishupunch.CLOSE_APP"
        private const val CHANNEL_ID = "punch_alarm_channel"
        private const val NOTIFICATION_ID = 9999
        
        // 静态 WakeLock 防止被回收
        @Volatile
        private var wakeLock: PowerManager.WakeLock? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到闹钟广播: $action")
        
        // 获取静态 WakeLock 保持唤醒
        acquireWakeLock(context)
        
        // 处理不同类型的闹钟
        val prefs = PreferenceHelper(context)
        val alarmHelper = AlarmHelper(context)
        
        // 检查今天是否是选中的星期
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val shouldExecuteToday = prefs.isDaySelected(today)
        Log.d(TAG, "今天是星期 $today，是否执行: $shouldExecuteToday")
        
        when (action) {
            ACTION_MORNING_PUNCH -> {
                if (shouldExecuteToday) {
                    // 使用全屏通知唤醒屏幕
                    showFullScreenNotification(context)
                } else {
                    Log.d(TAG, "今天不执行上班打卡")
                }
                // 重新设置下一次的闹钟（时间范围随机）
                alarmHelper.setMorningAlarm(
                    prefs.getMorningStartHour(), prefs.getMorningStartMinute(),
                    prefs.getMorningEndHour(), prefs.getMorningEndMinute()
                )
            }
            ACTION_EVENING_PUNCH -> {
                if (shouldExecuteToday) {
                    // 使用全屏通知唤醒屏幕
                    showFullScreenNotification(context)
                } else {
                    Log.d(TAG, "今天不执行下班打卡")
                }
                // 重新设置下一次的闹钟（时间范围随机）
                alarmHelper.setEveningAlarm(
                    prefs.getEveningStartHour(), prefs.getEveningStartMinute(),
                    prefs.getEveningEndHour(), prefs.getEveningEndMinute()
                )
            }
            ACTION_CLOSE_APP -> {
                if (shouldExecuteToday) {
                    // 关闭目标 APP
                    closeTargetApp(context, prefs)
                } else {
                    Log.d(TAG, "今天不执行关闭APP")
                }
                // 重新设置下一次的闹钟（使用配置的时间列表）
                alarmHelper.setCloseAppAlarms(prefs.getCloseTimes())
            }
        }
    }

    /**
     * 关闭目标 APP
     */
    private fun closeTargetApp(context: Context, prefs: PreferenceHelper) {
        val targetPackage = prefs.getTargetPackage()
        val targetName = prefs.getTargetAppName()
        Log.d(TAG, "执行关闭$targetName: $targetPackage")
        
        try {
            // 使用无障碍服务关闭 APP
            PunchAccessibilityService.instance?.closeApp(targetPackage)
            Log.d(TAG, "已请求关闭$targetName")
        } catch (e: Exception) {
            Log.e(TAG, "关闭$targetName 失败: ${e.message}")
        }
    }

    /**
     * 获取 WakeLock 保持屏幕唤醒
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null || wakeLock?.isHeld != true) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "FeishuPunch:AlarmWakeLock"
                )
                wakeLock?.acquire(120000) // 2分钟
                Log.d(TAG, "WakeLock 已获取")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    /**
     * 显示全屏通知（类似闹钟/来电，可以在锁屏时点亮屏幕）
     */
    private fun showFullScreenNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 创建通知渠道
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "定时任务",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "用于定时任务的通知"
                setBypassDnd(true)  // 绕过勿扰模式
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // 创建全屏 Intent
        val fullScreenIntent = Intent(context, WakeUpActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("乐逍遥")
            .setContentText("正在执行定时任务...")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)  // 关键：全屏 Intent
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "已发送全屏通知")
        
        // 同时尝试直接启动 Activity
        try {
            context.startActivity(fullScreenIntent)
            Log.d(TAG, "已启动 WakeUpActivity")
        } catch (e: Exception) {
            Log.e(TAG, "启动 WakeUpActivity 失败: ${e.message}")
        }
    }
}

