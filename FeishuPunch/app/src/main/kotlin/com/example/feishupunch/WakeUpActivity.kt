package com.example.feishupunch

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.feishupunch.service.PunchAccessibilityService
import com.example.feishupunch.util.PreferenceHelper

/**
 * 唤醒屏幕并打开目标APP
 */
class WakeUpActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WakeUpActivity"
        
        // 防止重复执行的标志
        @Volatile
        private var isRunning = false
        
        // 上次执行时间，60秒内不重复执行
        @Volatile
        private var lastExecuteTime = 0L
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: PreferenceHelper

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在 super.onCreate 之前获取 WakeLock
        acquireWakeLock()
        
        super.onCreate(savedInstanceState)
        
        prefs = PreferenceHelper(this)
        val targetName = prefs.getTargetAppName()
        
        Log.d(TAG, "WakeUpActivity 启动 - 息屏唤醒，目标: $targetName")
        
        // 检查是否在短时间内重复执行（60秒内）
        val now = System.currentTimeMillis()
        if (isRunning || (now - lastExecuteTime < 60000)) {
            Log.d(TAG, "60秒内已执行过，跳过本次")
            finishAndRemoveTask()
            return
        }
        
        isRunning = true
        lastExecuteTime = now
        
        // 设置所有可能的窗口标志来唤醒屏幕
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        
        // 设置一个简单的视图
        val textView = TextView(this).apply {
            text = "正在打开$targetName..."
            textSize = 24f
            setPadding(100, 200, 100, 200)
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        setContentView(textView)
        
        // 先让无障碍服务双击唤醒屏幕
        PunchAccessibilityService.instance?.doubleTapToWake()
        
        // 2秒后打开目标APP
        handler.postDelayed({
            launchTargetApp()
        }, 2000)
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "FeishuPunch:WakeUpActivity"
            )
            wakeLock?.acquire(60000) // 1分钟
            Log.d(TAG, "WakeLock 已获取")
        } catch (e: Exception) {
            Log.e(TAG, "获取 WakeLock 失败: ${e.message}")
        }
    }

    private fun launchTargetApp() {
        val targetPackage = prefs.getTargetPackage()
        val targetName = prefs.getTargetAppName()
        Log.d(TAG, "打开$targetName: $targetPackage")
        
        try {
            val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                Log.d(TAG, "$targetName 已启动")
            } else {
                Log.e(TAG, "未找到$targetName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动$targetName 失败: ${e.message}")
        }
        
        // 关闭自己
        handler.postDelayed({
            finishAndRemoveTask()
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isRunning = false
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // ignored
        }
    }
}

