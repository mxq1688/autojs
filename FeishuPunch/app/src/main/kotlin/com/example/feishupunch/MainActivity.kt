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
import com.example.feishupunch.util.CloseTime
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
            // 记录用户已手动操作过
            prefs.setUserHasToggled(true)
            
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
        
        // 星期选择监听
        val dayChipListener = { _: android.widget.CompoundButton, _: Boolean ->
            saveDaySelection()
        }
        binding.chipMonday.setOnCheckedChangeListener(dayChipListener)
        binding.chipTuesday.setOnCheckedChangeListener(dayChipListener)
        binding.chipWednesday.setOnCheckedChangeListener(dayChipListener)
        binding.chipThursday.setOnCheckedChangeListener(dayChipListener)
        binding.chipFriday.setOnCheckedChangeListener(dayChipListener)
        binding.chipSaturday.setOnCheckedChangeListener(dayChipListener)
        binding.chipSunday.setOnCheckedChangeListener(dayChipListener)
        
        // 添加关闭时间按钮
        binding.btnAddCloseTime.setOnClickListener {
            showAddCloseTimePicker()
        }
        
        // APP选择监听
        binding.radioGroupApp.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_feishu -> {
                    prefs.setTargetAppType(PreferenceHelper.APP_TYPE_FEISHU)
                    binding.layoutCustomPackage.visibility = android.view.View.GONE
                }
                R.id.radio_dingtalk -> {
                    prefs.setTargetAppType(PreferenceHelper.APP_TYPE_DINGTALK)
                    binding.layoutCustomPackage.visibility = android.view.View.GONE
                }
                R.id.radio_custom -> {
                    prefs.setTargetAppType(PreferenceHelper.APP_TYPE_CUSTOM)
                    binding.layoutCustomPackage.visibility = android.view.View.VISIBLE
                }
            }
            updateCurrentPackageDisplay()
        }
        
        // 自定义包名输入监听
        binding.etCustomPackage.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.setCustomPackage(s?.toString() ?: "")
                updateCurrentPackageDisplay()
            }
        })
        
        // 选择APP按钮
        binding.btnSelectApp.setOnClickListener {
            showAppListDialog()
        }
    }
    
    /**
     * APP信息数据类
     */
    data class AppInfo(
        val name: String,
        val packageName: String,
        val icon: android.graphics.drawable.Drawable?
    )
    
    /**
     * 显示已安装APP列表对话框
     */
    private fun showAppListDialog() {
        // 显示加载提示
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("加载中...")
            .setMessage("正在获取已安装的APP列表")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        // 在后台线程获取APP列表
        Thread {
            val apps = getInstalledApps()
            
            runOnUiThread {
                progressDialog.dismiss()
                
                if (apps.isEmpty()) {
                    Toast.makeText(this, "未找到已安装的APP", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                
                // 创建APP名称数组
                val appNames = apps.map { "${it.name}\n${it.packageName}" }.toTypedArray()
                
                AlertDialog.Builder(this)
                    .setTitle("选择目标APP (${apps.size}个)")
                    .setItems(appNames) { _, which ->
                        val selectedApp = apps[which]
                        binding.etCustomPackage.setText(selectedApp.packageName)
                        Toast.makeText(this, "已选择: ${selectedApp.name}", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }
    
    /**
     * 获取已安装的APP列表（排除系统APP，按名称排序）
     */
    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager
        val apps = mutableListOf<AppInfo>()
        
        try {
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            
            for (appInfo in packages) {
                // 排除系统APP（可选：保留用户可能需要的系统APP）
                val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                
                // 只保留非系统APP或已更新的系统APP（如微信、支付宝等预装但用户更新过的APP）
                if (!isSystemApp || isUpdatedSystemApp) {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = try {
                        pm.getApplicationIcon(appInfo)
                    } catch (e: Exception) {
                        null
                    }
                    apps.add(AppInfo(name, appInfo.packageName, icon))
                }
            }
            
            // 按名称排序
            apps.sortBy { it.name }
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "获取APP列表失败: ${e.message}")
        }
        
        return apps
    }

    private fun loadSettings() {
        // 加载保存的时间设置（范围）
        updateMorningTimeDisplay()
        updateEveningTimeDisplay()
        
        // 加载关闭飞书时间
        updateCloseTimeDisplay()
        
        // 加载星期选择
        loadDaySelection()
        
        // 加载目标APP选择
        loadAppSelection()
        
        // 加载开关状态
        binding.switchSchedule.isChecked = prefs.isScheduleEnabled()
    }
    
    /**
     * 加载目标APP选择
     */
    private fun loadAppSelection() {
        when (prefs.getTargetAppType()) {
            PreferenceHelper.APP_TYPE_FEISHU -> {
                binding.radioFeishu.isChecked = true
                binding.layoutCustomPackage.visibility = android.view.View.GONE
            }
            PreferenceHelper.APP_TYPE_DINGTALK -> {
                binding.radioDingtalk.isChecked = true
                binding.layoutCustomPackage.visibility = android.view.View.GONE
            }
            PreferenceHelper.APP_TYPE_CUSTOM -> {
                binding.radioCustom.isChecked = true
                binding.layoutCustomPackage.visibility = android.view.View.VISIBLE
                binding.etCustomPackage.setText(prefs.getCustomPackage())
            }
        }
        updateCurrentPackageDisplay()
    }
    
    /**
     * 更新当前包名显示
     */
    private fun updateCurrentPackageDisplay() {
        binding.tvCurrentPackage.text = "当前: ${prefs.getTargetPackage()}"
    }
    
    /**
     * 加载星期选择状态
     */
    private fun loadDaySelection() {
        val selectedDays = prefs.getSelectedDays()
        // Calendar: 1=周日, 2=周一, 3=周二, 4=周三, 5=周四, 6=周五, 7=周六
        binding.chipSunday.isChecked = selectedDays.contains(1)
        binding.chipMonday.isChecked = selectedDays.contains(2)
        binding.chipTuesday.isChecked = selectedDays.contains(3)
        binding.chipWednesday.isChecked = selectedDays.contains(4)
        binding.chipThursday.isChecked = selectedDays.contains(5)
        binding.chipFriday.isChecked = selectedDays.contains(6)
        binding.chipSaturday.isChecked = selectedDays.contains(7)
    }
    
    /**
     * 保存星期选择
     */
    private fun saveDaySelection() {
        val selectedDays = mutableSetOf<Int>()
        // Calendar: 1=周日, 2=周一, 3=周二, 4=周三, 5=周四, 6=周五, 7=周六
        if (binding.chipSunday.isChecked) selectedDays.add(1)
        if (binding.chipMonday.isChecked) selectedDays.add(2)
        if (binding.chipTuesday.isChecked) selectedDays.add(3)
        if (binding.chipWednesday.isChecked) selectedDays.add(4)
        if (binding.chipThursday.isChecked) selectedDays.add(5)
        if (binding.chipFriday.isChecked) selectedDays.add(6)
        if (binding.chipSaturday.isChecked) selectedDays.add(7)
        
        prefs.setSelectedDays(selectedDays)
        updateDaySelectionStatus()
    }
    
    /**
     * 更新星期选择显示状态
     */
    private fun updateDaySelectionStatus() {
        val selectedDays = prefs.getSelectedDays()
        val dayNames = mutableListOf<String>()
        if (selectedDays.contains(2)) dayNames.add("周一")
        if (selectedDays.contains(3)) dayNames.add("周二")
        if (selectedDays.contains(4)) dayNames.add("周三")
        if (selectedDays.contains(5)) dayNames.add("周四")
        if (selectedDays.contains(6)) dayNames.add("周五")
        if (selectedDays.contains(7)) dayNames.add("周六")
        if (selectedDays.contains(1)) dayNames.add("周日")
        
        val statusText = if (dayNames.isEmpty()) {
            "未选择执行日期"
        } else if (dayNames.size == 7) {
            "每天执行"
        } else if (selectedDays.containsAll(listOf(2, 3, 4, 5, 6)) && selectedDays.size == 5) {
            "工作日执行"
        } else {
            dayNames.joinToString("、") + " 执行"
        }
        updateStatus(statusText)
    }

    /**
     * 自动开启定时功能（仅首次）
     */
    private fun autoEnableSchedule() {
        // 如果用户手动操作过，不再自动开启
        if (prefs.hasUserToggled()) {
            return
        }
        
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
    
    private fun updateCloseTimeDisplay() {
        val container = binding.containerCloseTimes
        container.removeAllViews()
        
        val times = prefs.getCloseTimes()
        for (time in times) {
            addCloseTimeItemView(time)
        }
    }
    
    private fun addCloseTimeItemView(time: CloseTime) {
        val container = binding.containerCloseTimes
        
        val itemLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * resources.displayMetrics.density).toInt()
            )
            setPadding(
                (8 * resources.displayMetrics.density).toInt(), 0,
                (8 * resources.displayMetrics.density).toInt(), 0
            )
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        
        val timeText = android.widget.TextView(this).apply {
            text = time.toDisplayString()
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#E91E63"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        
        val deleteBtn = android.widget.TextView(this).apply {
            text = "删除"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#F44336"))
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt()
            )
            setOnClickListener {
                prefs.removeCloseTime(time)
                updateCloseTimeDisplay()
                if (prefs.isScheduleEnabled()) {
                    updateAlarms()
                }
                Toast.makeText(this@MainActivity, "已删除 ${time.toDisplayString()}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // 点击时间可以修改
        itemLayout.setOnClickListener {
            TimePickerDialog(this, { _, selectedHour, selectedMinute ->
                val newTime = CloseTime(selectedHour, selectedMinute)
                prefs.updateCloseTime(time, newTime)
                updateCloseTimeDisplay()
                if (prefs.isScheduleEnabled()) {
                    updateAlarms()
                }
                Toast.makeText(this, "时间已更新", Toast.LENGTH_SHORT).show()
            }, time.hour, time.minute, true).apply {
                setTitle("修改关闭时间")
            }.show()
        }
        
        itemLayout.addView(timeText)
        itemLayout.addView(deleteBtn)
        container.addView(itemLayout)
    }
    
    private fun showAddCloseTimePicker() {
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            val newTime = CloseTime(selectedHour, selectedMinute)
            prefs.addCloseTime(newTime)
            updateCloseTimeDisplay()
            
            if (prefs.isScheduleEnabled()) {
                updateAlarms()
            }
            
            Toast.makeText(this, "已添加 ${newTime.toDisplayString()}", Toast.LENGTH_SHORT).show()
        }, 12, 0, true).apply {
            setTitle("添加关闭时间")
        }.show()
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
        // 关闭飞书（使用配置的时间列表）
        alarmHelper.setCloseAppAlarms(prefs.getCloseTimes())
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
        val targetPackage = prefs.getTargetPackage()
        val targetName = prefs.getTargetAppName()
        
        if (targetPackage.isEmpty()) {
            Toast.makeText(this, "❌ 请先设置目标APP包名", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            packageManager.getPackageInfo(targetPackage, 0)
            Toast.makeText(this, "✅ $targetName 已安装 ($targetPackage)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val downloadUrl = when (prefs.getTargetAppType()) {
                PreferenceHelper.APP_TYPE_FEISHU -> "https://www.feishu.cn/download"
                PreferenceHelper.APP_TYPE_DINGTALK -> "https://www.dingtalk.com/download"
                else -> null
            }
            
            val builder = AlertDialog.Builder(this)
                .setTitle("未检测到$targetName")
                .setMessage("包名: $targetPackage\n\n请先安装该APP")
                .setNegativeButton("取消", null)
            
            if (downloadUrl != null) {
                builder.setPositiveButton("去下载") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse(downloadUrl)
                    }
                    startActivity(intent)
                }
            }
            
            builder.show()
        }
    }
}

