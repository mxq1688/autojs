package com.example.feishupunch.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 关闭时间数据类
 */
data class CloseTime(val hour: Int, val minute: Int) {
    fun toDisplayString(): String = String.format("%02d:%02d", hour, minute)
    fun toMinutes(): Int = hour * 60 + minute
    
    companion object {
        fun fromString(str: String): CloseTime? {
            return try {
                val parts = str.split(":")
                CloseTime(parts[0].toInt(), parts[1].toInt())
            } catch (e: Exception) {
                null
            }
        }
    }
    
    override fun toString(): String = "$hour:$minute"
}

/**
 * SharedPreferences 工具类
 */
class PreferenceHelper(context: Context) {

    companion object {
        private const val PREF_NAME = "punch_prefs"
        
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_USER_HAS_TOGGLED = "user_has_toggled"  // 用户是否手动操作过
        // 上班时间范围
        private const val KEY_MORNING_START_HOUR = "morning_start_hour"
        private const val KEY_MORNING_START_MINUTE = "morning_start_minute"
        private const val KEY_MORNING_END_HOUR = "morning_end_hour"
        private const val KEY_MORNING_END_MINUTE = "morning_end_minute"
        // 下班时间范围
        private const val KEY_EVENING_START_HOUR = "evening_start_hour"
        private const val KEY_EVENING_START_MINUTE = "evening_start_minute"
        private const val KEY_EVENING_END_HOUR = "evening_end_hour"
        private const val KEY_EVENING_END_MINUTE = "evening_end_minute"
        
        // 默认时间范围
        private const val DEFAULT_MORNING_START_HOUR = 8
        private const val DEFAULT_MORNING_START_MINUTE = 50
        private const val DEFAULT_MORNING_END_HOUR = 9
        private const val DEFAULT_MORNING_END_MINUTE = 20
        private const val DEFAULT_EVENING_START_HOUR = 18
        private const val DEFAULT_EVENING_START_MINUTE = 40
        private const val DEFAULT_EVENING_END_HOUR = 19
        private const val DEFAULT_EVENING_END_MINUTE = 10
        
        // 关闭飞书时间列表
        private const val KEY_CLOSE_TIMES = "close_times_list"
        private const val DEFAULT_CLOSE_TIMES = "9:30,18:20,19:20"
        
        // 执行的星期（1=周日，2=周一，...，7=周六）
        private const val KEY_SELECTED_DAYS = "selected_days"
        // 默认周一到周五
        private const val DEFAULT_SELECTED_DAYS = "2,3,4,5,6"
        
        // 目标 APP
        private const val KEY_TARGET_APP_TYPE = "target_app_type"
        private const val KEY_CUSTOM_PACKAGE = "custom_package"
        
        // 预设的 APP 包名
        const val APP_TYPE_FEISHU = 0
        const val APP_TYPE_DINGTALK = 1
        const val APP_TYPE_CUSTOM = 2
        
        const val PACKAGE_FEISHU = "com.ss.android.lark"
        const val PACKAGE_DINGTALK = "com.alibaba.android.rimet"
    }

    private val prefs: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isScheduleEnabled(): Boolean = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false)

    fun setScheduleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULE_ENABLED, enabled).apply()
    }
    
    // 用户是否手动操作过定时开关
    fun hasUserToggled(): Boolean = prefs.getBoolean(KEY_USER_HAS_TOGGLED, false)
    
    fun setUserHasToggled(toggled: Boolean) {
        prefs.edit().putBoolean(KEY_USER_HAS_TOGGLED, toggled).apply()
    }

    // 上班开始时间
    fun getMorningStartHour(): Int = prefs.getInt(KEY_MORNING_START_HOUR, DEFAULT_MORNING_START_HOUR)
    fun getMorningStartMinute(): Int = prefs.getInt(KEY_MORNING_START_MINUTE, DEFAULT_MORNING_START_MINUTE)
    
    // 上班结束时间
    fun getMorningEndHour(): Int = prefs.getInt(KEY_MORNING_END_HOUR, DEFAULT_MORNING_END_HOUR)
    fun getMorningEndMinute(): Int = prefs.getInt(KEY_MORNING_END_MINUTE, DEFAULT_MORNING_END_MINUTE)

    fun setMorningStartTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_MORNING_START_HOUR, hour)
            .putInt(KEY_MORNING_START_MINUTE, minute)
            .apply()
    }
    
    fun setMorningEndTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_MORNING_END_HOUR, hour)
            .putInt(KEY_MORNING_END_MINUTE, minute)
            .apply()
    }

    // 下班开始时间
    fun getEveningStartHour(): Int = prefs.getInt(KEY_EVENING_START_HOUR, DEFAULT_EVENING_START_HOUR)
    fun getEveningStartMinute(): Int = prefs.getInt(KEY_EVENING_START_MINUTE, DEFAULT_EVENING_START_MINUTE)
    
    // 下班结束时间
    fun getEveningEndHour(): Int = prefs.getInt(KEY_EVENING_END_HOUR, DEFAULT_EVENING_END_HOUR)
    fun getEveningEndMinute(): Int = prefs.getInt(KEY_EVENING_END_MINUTE, DEFAULT_EVENING_END_MINUTE)

    fun setEveningStartTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_EVENING_START_HOUR, hour)
            .putInt(KEY_EVENING_START_MINUTE, minute)
            .apply()
    }
    
    fun setEveningEndTime(hour: Int, minute: Int) {
        prefs.edit()
            .putInt(KEY_EVENING_END_HOUR, hour)
            .putInt(KEY_EVENING_END_MINUTE, minute)
            .apply()
    }
    
    // 兼容旧版本
    fun getMorningHour(): Int = getMorningStartHour()
    fun getMorningMinute(): Int = getMorningStartMinute()
    fun getEveningHour(): Int = getEveningStartHour()
    fun getEveningMinute(): Int = getEveningStartMinute()
    
    // 关闭飞书时间列表
    fun getCloseTimes(): List<CloseTime> {
        val str = prefs.getString(KEY_CLOSE_TIMES, DEFAULT_CLOSE_TIMES) ?: DEFAULT_CLOSE_TIMES
        return str.split(",")
            .mapNotNull { CloseTime.fromString(it.trim()) }
            .sortedBy { it.toMinutes() }
    }
    
    fun setCloseTimes(times: List<CloseTime>) {
        val str = times.joinToString(",") { it.toString() }
        prefs.edit().putString(KEY_CLOSE_TIMES, str).apply()
    }
    
    fun addCloseTime(time: CloseTime) {
        val times = getCloseTimes().toMutableList()
        // 避免重复
        if (times.none { it.hour == time.hour && it.minute == time.minute }) {
            times.add(time)
            setCloseTimes(times)
        }
    }
    
    fun removeCloseTime(time: CloseTime) {
        val times = getCloseTimes().toMutableList()
        times.removeAll { it.hour == time.hour && it.minute == time.minute }
        setCloseTimes(times)
    }
    
    fun updateCloseTime(oldTime: CloseTime, newTime: CloseTime) {
        val times = getCloseTimes().toMutableList()
        val index = times.indexOfFirst { it.hour == oldTime.hour && it.minute == oldTime.minute }
        if (index >= 0) {
            times[index] = newTime
            setCloseTimes(times)
        }
    }
    
    // 选中的星期 (1=周日, 2=周一, ..., 7=周六，对应 Calendar.SUNDAY ~ Calendar.SATURDAY)
    fun getSelectedDays(): Set<Int> {
        val str = prefs.getString(KEY_SELECTED_DAYS, DEFAULT_SELECTED_DAYS) ?: DEFAULT_SELECTED_DAYS
        return str.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
    }
    
    fun setSelectedDays(days: Set<Int>) {
        val str = days.sorted().joinToString(",")
        prefs.edit().putString(KEY_SELECTED_DAYS, str).apply()
    }
    
    fun isDaySelected(calendarDay: Int): Boolean {
        return getSelectedDays().contains(calendarDay)
    }
    
    // 目标 APP 类型
    fun getTargetAppType(): Int = prefs.getInt(KEY_TARGET_APP_TYPE, APP_TYPE_FEISHU)
    
    fun setTargetAppType(type: Int) {
        prefs.edit().putInt(KEY_TARGET_APP_TYPE, type).apply()
    }
    
    // 自定义包名
    fun getCustomPackage(): String = prefs.getString(KEY_CUSTOM_PACKAGE, "") ?: ""
    
    fun setCustomPackage(packageName: String) {
        prefs.edit().putString(KEY_CUSTOM_PACKAGE, packageName).apply()
    }
    
    // 获取当前目标包名
    fun getTargetPackage(): String {
        return when (getTargetAppType()) {
            APP_TYPE_FEISHU -> PACKAGE_FEISHU
            APP_TYPE_DINGTALK -> PACKAGE_DINGTALK
            APP_TYPE_CUSTOM -> getCustomPackage()
            else -> PACKAGE_FEISHU
        }
    }
    
    // 获取当前目标 APP 名称
    fun getTargetAppName(): String {
        return when (getTargetAppType()) {
            APP_TYPE_FEISHU -> "飞书"
            APP_TYPE_DINGTALK -> "钉钉"
            APP_TYPE_CUSTOM -> "自定义"
            else -> "飞书"
        }
    }
}

