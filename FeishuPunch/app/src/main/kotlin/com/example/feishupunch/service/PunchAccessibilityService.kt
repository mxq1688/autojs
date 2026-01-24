package com.example.feishupunch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.example.feishupunch.util.PreferenceHelper

/**
 * 无障碍服务 - 用于自动操作打卡
 */
class PunchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "PunchService"
        
        // 服务实例（用于外部调用）
        var instance: PunchAccessibilityService? = null
            private set
        
        // 是否正在执行工作
        var isPunching = false
        
        // 工作步骤
        private const val STEP_IDLE = 0
        private const val STEP_LAUNCH_APP = 1
        private const val STEP_FIND_WORK_TAB = 2
        private const val STEP_FIND_ATTENDANCE = 3
        private const val STEP_DO_PUNCH = 4
        private const val STEP_DONE = 5
    }
    
    private lateinit var prefs: PreferenceHelper
    
    // 当前目标包名
    private var targetPackage: String = PreferenceHelper.PACKAGE_FEISHU

    private val handler = Handler(Looper.getMainLooper())
    private var currentStep = STEP_IDLE
    private var retryCount = 0
    private val maxRetry = 3

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PreferenceHelper(this)
        Log.d(TAG, "无障碍服务已连接")
        Toast.makeText(this, "乐逍遥服务已启动", Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isPunching) return
        
        event?.let {
            if (it.packageName == targetPackage) {
                // 延迟处理，等待页面加载
                handler.postDelayed({
                    processCurrentStep()
                }, 1500)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "无障碍服务已销毁")
    }

    /**
     * 开始执行工作流程
     */
    fun startPunchProcess() {
        if (isPunching) {
            Log.d(TAG, "正在工作中，请勿重复操作")
            return
        }
        
        // 获取当前目标包名
        targetPackage = prefs.getTargetPackage()
        Log.d(TAG, "目标APP包名: $targetPackage")
        
        if (targetPackage.isEmpty()) {
            finishPunch(false, "未设置目标APP")
            return
        }
        
        isPunching = true
        currentStep = STEP_LAUNCH_APP
        retryCount = 0
        
        Log.d(TAG, "开始工作流程，目标: ${prefs.getTargetAppName()}")
        
        // 启动目标 APP（屏幕唤醒由 WakeUpActivity 负责）
        launchTargetApp()
        
        // 设置超时
        handler.postDelayed({
            if (isPunching) {
                Log.d(TAG, "工作超时")
                finishPunch(false, "工作超时")
            }
        }, 60000) // 60秒超时
    }

    /**
     * 启动目标APP
     */
    private fun launchTargetApp() {
        try {
            // 先唤醒屏幕
            wakeUpScreen()
            
            val intent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (intent != null) {
                // 添加多个flags确保APP被带到前台
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
                Log.d(TAG, "${prefs.getTargetAppName()} 启动成功")
                
                currentStep = STEP_FIND_WORK_TAB
                
                // 等待APP启动后开始查找
                handler.postDelayed({
                    processCurrentStep()
                }, 5000)
            } else {
                finishPunch(false, "未找到目标APP: $targetPackage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动APP失败: ${e.message}")
            finishPunch(false, "启动APP失败")
        }
    }
    
    /**
     * 唤醒屏幕
     */
    @Suppress("DEPRECATION")
    private fun wakeUpScreen() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isInteractive) {
                val wakeLock = powerManager.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "FeishuPunch:WakeLock"
                )
                wakeLock.acquire(30000) // 30秒
                Log.d(TAG, "屏幕已唤醒")
            }
        } catch (e: Exception) {
            Log.e(TAG, "唤醒屏幕失败: ${e.message}")
        }
    }

    /**
     * 处理当前步骤
     */
    private fun processCurrentStep() {
        if (!isPunching) return
        
        val rootNode = rootInActiveWindow ?: run {
            retryStep()
            return
        }

        when (currentStep) {
            STEP_FIND_WORK_TAB -> findAndClickWorkTab(rootNode)
            STEP_FIND_ATTENDANCE -> findAndClickAttendance(rootNode)
            STEP_DO_PUNCH -> findAndClickPunch(rootNode)
        }
        
        rootNode.recycle()
    }

    /**
     * 查找并点击"工作台"
     */
    private fun findAndClickWorkTab(rootNode: AccessibilityNodeInfo) {
        Log.d(TAG, "查找工作台...")
        
        // 根据不同 APP 使用不同关键词
        val workTabTexts = when (prefs.getTargetAppType()) {
            PreferenceHelper.APP_TYPE_DINGTALK -> listOf("工作台", "工作", "Workbench")
            else -> listOf("工作台", "工作", "Workplace")  // 飞书和自定义
        }
        
        for (text in workTabTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (clickNode(node)) {
                    Log.d(TAG, "点击工作台成功")
                    currentStep = STEP_FIND_ATTENDANCE
                    handler.postDelayed({ processCurrentStep() }, 3000)
                    return
                }
            }
        }
        
        retryStep()
    }

    /**
     * 查找并点击考勤入口
     */
    private fun findAndClickAttendance(rootNode: AccessibilityNodeInfo) {
        Log.d(TAG, "查找考勤入口...")
        
        // 根据不同 APP 使用不同关键词
        val attendanceTexts = when (prefs.getTargetAppType()) {
            PreferenceHelper.APP_TYPE_DINGTALK -> listOf("考勤打卡", "智能考勤", "考勤")
            else -> listOf("假勤", "考勤打卡", "考勤")  // 飞书和自定义
        }
        
        for (text in attendanceTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (clickNode(node)) {
                    Log.d(TAG, "点击考勤入口成功: $text")
                    currentStep = STEP_DO_PUNCH
                    handler.postDelayed({ processCurrentStep() }, 5000)
                    return
                }
            }
        }
        
        // 尝试滚动查找
        scrollDown(rootNode)
        retryStep()
    }

    /**
     * 执行工作
     */
    private fun findAndClickPunch(rootNode: AccessibilityNodeInfo) {
        Log.d(TAG, "执行工作...")
        
        // 查找打卡按钮（按优先级：更新打卡 > 下班打卡 > 上班打卡）
        val punchTexts = listOf("更新打卡", "下班打卡", "上班打卡")
        
        for (text in punchTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (clickNode(node)) {
                    Log.d(TAG, "点击工作按钮成功: $text")
                    handler.postDelayed({
                        finishPunch(true, "工作成功")
                    }, 2000)
                    return
                }
            }
        }
        
        // 检查是否已工作（没有可点击按钮时）
        val alreadyPunchedTexts = listOf("已打卡", "已签到", "打卡成功")
        for (text in alreadyPunchedTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "检测到已工作状态")
                finishPunch(true, "已工作")
                return
            }
        }
        
        retryStep()
    }

    /**
     * 点击节点
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        // 尝试直接点击
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        
        // 尝试点击父节点
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val temp = parent.parent
            parent.recycle()
            parent = temp
        }
        
        // 使用手势点击
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return performClick(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /**
     * 使用手势执行点击
     */
    private fun performClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        
        val path = Path()
        path.moveTo(x, y)
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 向下滚动
     */
    private fun scrollDown(rootNode: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val startX = displayMetrics.widthPixels / 2f
            val startY = displayMetrics.heightPixels * 0.7f
            val endY = displayMetrics.heightPixels * 0.3f
            
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(startX, endY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build()
            
            dispatchGesture(gesture, null, null)
        }
    }

    /**
     * 双击屏幕唤醒
     */
    fun doubleTapToWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "执行双击唤醒")
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val centerY = displayMetrics.heightPixels / 2f
            
            // 第一次点击
            val path1 = Path()
            path1.moveTo(centerX, centerY)
            
            val gesture1 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0, 50))
                .build()
            
            dispatchGesture(gesture1, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "第一次点击完成")
                    
                    // 短暂延迟后第二次点击
                    handler.postDelayed({
                        val path2 = Path()
                        path2.moveTo(centerX, centerY)
                        
                        val gesture2 = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path2, 0, 50))
                            .build()
                        
                        dispatchGesture(gesture2, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                Log.d(TAG, "双击唤醒完成")
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                Log.d(TAG, "第二次点击取消")
                            }
                        }, null)
                    }, 100) // 100ms间隔
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "第一次点击取消")
                }
            }, null)
        } else {
            Log.d(TAG, "系统版本过低，不支持手势操作")
        }
    }

    /**
     * 关闭指定包名的应用
     */
    fun closeApp(packageName: String) {
        Log.d(TAG, "关闭应用: $packageName")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // 使用全局操作返回主屏幕
            performGlobalAction(GLOBAL_ACTION_HOME)
            Log.d(TAG, "已返回主屏幕")
            
            // 然后打开最近任务
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.d(TAG, "已打开最近任务")
                
                // 然后清除所有任务（关闭所有应用）
                handler.postDelayed({
                    // 尝试找到并点击"全部清除"按钮
                    val rootNode = rootInActiveWindow
                    if (rootNode != null) {
                        val clearAllTexts = listOf("全部清除", "清除全部", "全部结束", "一键清理", "清理全部")
                        var clicked = false
                        for (text in clearAllTexts) {
                            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                            if (nodes.isNotEmpty()) {
                                for (node in nodes) {
                                    if (node.isClickable) {
                                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        clicked = true
                                        Log.d(TAG, "点击了: $text")
                                        break
                                    }
                                }
                                if (clicked) break
                            }
                        }
                        
                        if (!clicked) {
                            // 找不到清除按钮，滑动关闭当前应用
                            Log.d(TAG, "未找到清除按钮，尝试滑动关闭")
                            swipeUpToClose()
                        }
                    }
                }, 1500)
            }, 500)
        }
    }

    /**
     * 上滑关闭当前应用卡片
     */
    private fun swipeUpToClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val startY = displayMetrics.heightPixels / 2f
            val endY = displayMetrics.heightPixels * 0.1f
            
            val path = Path()
            path.moveTo(centerX, startY)
            path.lineTo(centerX, endY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "上滑关闭完成")
                    // 返回主屏幕
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 500)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "上滑关闭取消")
                }
            }, null)
        }
    }

    /**
     * 上滑解锁屏幕
     */
    private fun swipeUpToUnlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(TAG, "执行上滑解锁")
            val displayMetrics = resources.displayMetrics
            val startX = displayMetrics.widthPixels / 2f
            val startY = displayMetrics.heightPixels * 0.85f  // 从屏幕底部开始
            val endY = displayMetrics.heightPixels * 0.2f     // 滑到屏幕上方
            
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(startX, endY)
            
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "上滑解锁手势完成")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "上滑解锁手势取消")
                }
            }, null)
        } else {
            Log.d(TAG, "系统版本过低，不支持手势操作")
        }
    }

    /**
     * 重试当前步骤
     */
    private fun retryStep() {
        retryCount++
        if (retryCount >= maxRetry) {
            Log.d(TAG, "重试次数过多，进入下一步")
            currentStep++
            retryCount = 0
            
            if (currentStep > STEP_DO_PUNCH) {
                finishPunch(false, "未找到工作按钮")
                return
            }
        }
        
        handler.postDelayed({
            processCurrentStep()
        }, 2000)
    }

    /**
     * 完成工作
     */
    private fun finishPunch(success: Boolean, message: String) {
        isPunching = false
        currentStep = STEP_IDLE
        retryCount = 0
        
        handler.removeCallbacksAndMessages(null)
        
        val text = if (success) "✅ $message" else "❌ $message"
        Log.d(TAG, text)
        
        handler.post {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
        
        // 发送广播通知主界面
        val intent = Intent("com.example.feishupunch.PUNCH_RESULT")
        intent.putExtra("success", success)
        intent.putExtra("message", message)
        sendBroadcast(intent)
        
        // 工作完成后返回桌面
        handler.postDelayed({
            goHome()
        }, 2000)
    }
    
    /**
     * 返回桌面
     */
    private fun goHome() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "返回桌面失败: ${e.message}")
        }
    }
}

