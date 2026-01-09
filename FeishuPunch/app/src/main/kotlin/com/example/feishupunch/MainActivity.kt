package com.example.feishupunch

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.feishupunch.databinding.ActivityMainBinding
import com.example.feishupunch.service.PunchAccessibilityService
import com.example.feishupunch.service.PunchForegroundService
import com.example.feishupunch.util.AlarmHelper
import com.example.feishupunch.util.PreferenceHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceHelper
    private lateinit var alarmHelper: AlarmHelper

    // 接收工作结果
    private val punchResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra("success", false) ?: false
            val message = intent?.getStringExtra("message") ?: ""
            
            runOnUiThread {
                updateStatus(if (success) "✅ $message" else "❌ $message")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 允许在锁屏上显示，用于唤醒工作
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferenceHelper(this)
        alarmHelper = AlarmHelper(this)

        initViews()
        loadSettings()
        checkPermissions()
        
        // 自动开启定时（如果无障碍服务已启用）
        autoEnableSchedule()
        
        // 检查是否是闹钟触发的自动工作
        handleAutoPunchIntent(intent)
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleAutoPunchIntent(it) }
    }
    
    /**
     * 处理自动工作意图
     */
    private fun handleAutoPunchIntent(intent: Intent) {
        if (intent.getBooleanExtra("auto_punch", false)) {
            android.util.Log.d("MainActivity", "收到自动工作请求")
            
            // 延迟2秒等待屏幕完全亮起后执行工作
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val service = PunchAccessibilityService.instance
                if (service != null) {
                    android.util.Log.d("MainActivity", "开始自动工作")
                    updateStatus("正在自动工作...")
                    service.startPunchProcess()
                } else {
                    android.util.Log.e("MainActivity", "无障碍服务未启动")
                    updateStatus("❌ 无障碍服务未启动")
                }
                
                // 工作后最小化窗口
                moveTaskToBack(true)
            }, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        
        // 从设置页面返回后，检查是否可以自动开启定时
        autoEnableSchedule()
        
        // 注册广播接收器
        val filter = IntentFilter("com.example.feishupunch.PUNCH_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(punchResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(punchResultReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(punchResultReceiver)
        } catch (e: Exception) {
            // ignored
        }
    }

    private fun initViews() {
        // 开启无障碍服务按钮
        binding.btnAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // 立即按钮
        binding.btnPunchNow.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            PunchAccessibilityService.instance?.startPunchProcess()
            updateStatus("正在执行工作...")
        }

        // 上班时间设置
        binding.layoutMorningTime.setOnClickListener {
            showTimePicker(true)
        }

        // 下班时间设置
        binding.layoutEveningTime.setOnClickListener {
            showTimePicker(false)
        }

        // 定时开关
        binding.switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!isAccessibilityServiceEnabled()) {
                    Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
                    binding.switchSchedule.isChecked = false
                    return@setOnCheckedChangeListener
                }
                enableSchedule()
            } else {
                disableSchedule()
            }
        }

        // 检查飞书是否安装
        binding.btnCheckFeishu.setOnClickListener {
            checkFeishuInstalled()
        }
    }

    private fun loadSettings() {
        // 加载保存的时间设置（范围）
        updateMorningTimeDisplay()
        updateEveningTimeDisplay()
        
        // 加载开关状态
        binding.switchSchedule.isChecked = prefs.isScheduleEnabled()
    }

    /**
     * 自动开启定时功能
     */
    private fun autoEnableSchedule() {
        // 如果已经开启了，不重复操作
        if (prefs.isScheduleEnabled()) {
            return
        }
        
        // 如果无障碍服务已开启，自动开启定时
        if (isAccessibilityServiceEnabled()) {
            binding.switchSchedule.isChecked = true
            // enableSchedule() 会通过 OnCheckedChangeListener 自动调用
        }
    }
    
    private fun updateMorningTimeDisplay() {
        val startTime = String.format("%02d:%02d", prefs.getMorningStartHour(), prefs.getMorningStartMinute())
        val endTime = String.format("%02d:%02d", prefs.getMorningEndHour(), prefs.getMorningEndMinute())
        binding.tvMorningTime.text = "$startTime-$endTime"
    }
    
    private fun updateEveningTimeDisplay() {
        val startTime = String.format("%02d:%02d", prefs.getEveningStartHour(), prefs.getEveningStartMinute())
        val endTime = String.format("%02d:%02d", prefs.getEveningEndHour(), prefs.getEveningEndMinute())
        binding.tvEveningTime.text = "$startTime-$endTime"
    }

    private fun checkPermissions() {
        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) 
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }

        // 检查精确闹钟权限 (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("需要权限")
                    .setMessage("为确保定时准确，需要开启精确闹钟权限")
                    .setPositiveButton("去设置") { _, _ ->
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun updateServiceStatus() {
        val isEnabled = isAccessibilityServiceEnabled()
        
        binding.tvServiceStatus.text = if (isEnabled) "已开启" else "未开启"
        binding.tvServiceStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (isEnabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            )
        )
        
        binding.btnAccessibility.text = if (isEnabled) "无障碍服务已开启" else "开启无障碍服务"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun openAccessibilitySettings() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage("请在设置中找到「乐逍遥」并开启无障碍服务权限")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTimePicker(isMorning: Boolean) {
        // 先选择开始时间
        val startHour = if (isMorning) prefs.getMorningStartHour() else prefs.getEveningStartHour()
        val startMinute = if (isMorning) prefs.getMorningStartMinute() else prefs.getEveningStartMinute()

        TimePickerDialog(this, { _, h1, m1 ->
            // 再选择结束时间
            val endHour = if (isMorning) prefs.getMorningEndHour() else prefs.getEveningEndHour()
            val endMinute = if (isMorning) prefs.getMorningEndMinute() else prefs.getEveningEndMinute()
            
            TimePickerDialog(this, { _, h2, m2 ->
                if (isMorning) {
                    prefs.setMorningStartTime(h1, m1)
                    prefs.setMorningEndTime(h2, m2)
                    updateMorningTimeDisplay()
                } else {
                    prefs.setEveningStartTime(h1, m1)
                    prefs.setEveningEndTime(h2, m2)
                    updateEveningTimeDisplay()
                }
                
                // 如果定时已开启，更新闹钟
                if (prefs.isScheduleEnabled()) {
                    updateAlarms()
                }
                
                Toast.makeText(this, "时间范围已设置，将在范围内随机触发", Toast.LENGTH_SHORT).show()
            }, endHour, endMinute, true).apply {
                setTitle("选择结束时间")
            }.show()
        }, startHour, startMinute, true).apply {
            setTitle("选择开始时间")
        }.show()
    }
    
    private fun updateAlarms() {
        alarmHelper.setMorningAlarm(
            prefs.getMorningStartHour(), prefs.getMorningStartMinute(),
            prefs.getMorningEndHour(), prefs.getMorningEndMinute()
        )
        alarmHelper.setEveningAlarm(
            prefs.getEveningStartHour(), prefs.getEveningStartMinute(),
            prefs.getEveningEndHour(), prefs.getEveningEndMinute()
        )
        // 18:20 关闭飞书（下班打卡前）
        alarmHelper.setCloseAppEveningAlarm(18, 20)
        // 19:20 关闭飞书（下班后）
        alarmHelper.setCloseAppAlarm(19, 20)
    }

    private fun enableSchedule() {
        // Android 12+ 检查精确闹钟权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("需要闹钟权限")
                    .setMessage("定时功能需要精确闹钟权限才能准时触发，请授予权限")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        startActivity(intent)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        binding.switchSchedule.isChecked = false
                    }
                    .show()
                return
            }
        }
        
        prefs.setScheduleEnabled(true)
        
        // 设置闹钟（时间范围随机）
        updateAlarms()
        
        // 启动前台服务
        val intent = Intent(this, PunchForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        val morningStart = String.format("%02d:%02d", prefs.getMorningStartHour(), prefs.getMorningStartMinute())
        val morningEnd = String.format("%02d:%02d", prefs.getMorningEndHour(), prefs.getMorningEndMinute())
        val eveningStart = String.format("%02d:%02d", prefs.getEveningStartHour(), prefs.getEveningStartMinute())
        val eveningEnd = String.format("%02d:%02d", prefs.getEveningEndHour(), prefs.getEveningEndMinute())
        Toast.makeText(this, "定时已开启(随机触发)\n$morningStart-$morningEnd\n$eveningStart-$eveningEnd", Toast.LENGTH_LONG).show()
        updateStatus("定时已开启，将在时间范围内随机触发")
    }

    private fun disableSchedule() {
        prefs.setScheduleEnabled(false)
        
        // 取消闹钟
        alarmHelper.cancelAllAlarms()
        
        // 停止前台服务
        stopService(Intent(this, PunchForegroundService::class.java))
        
        Toast.makeText(this, "定时已关闭", Toast.LENGTH_SHORT).show()
        updateStatus("定时已关闭")
    }

    private fun updateStatus(message: String) {
        binding.tvStatus.text = message
    }

    private fun checkFeishuInstalled() {
        try {
            packageManager.getPackageInfo(PunchAccessibilityService.FEISHU_PACKAGE, 0)
            Toast.makeText(this, "✅ 目标APP已安装", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("未检测到目标APP")
                .setMessage("请先安装飞书APP")
                .setPositiveButton("去下载") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://www.feishu.cn/download")
                    }
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
}

