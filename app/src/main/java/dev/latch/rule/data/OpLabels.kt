package dev.latch.rule.data

/** Chinese labels for the AppOps tokens used by the rule editor. */
object OpLabels {

    private val labels: Map<String, String> = mapOf(
        "CAMERA" to "相机",
        "RECORD_AUDIO" to "麦克风",
        "FINE_LOCATION" to "精确定位",
        "COARSE_LOCATION" to "粗略定位",
        "BODY_SENSORS" to "身体传感器",
        "ACTIVITY_RECOGNITION" to "活动识别",
        "READ_CONTACTS" to "联系人",
        "WRITE_CONTACTS" to "联系人写入",
        "READ_SMS" to "短信",
        "SEND_SMS" to "发送短信",
        "RECEIVE_SMS" to "接收短信",
        "READ_CALL_LOG" to "通话记录",
        "WRITE_CALL_LOG" to "通话记录写入",
        "READ_PHONE_STATE" to "电话状态",
        "READ_PHONE_NUMBERS" to "电话号码",
        "READ_CALENDAR" to "日历",
        "WRITE_CALENDAR" to "日历写入",
        "READ_MEDIA_IMAGES" to "照片",
        "READ_MEDIA_VIDEO" to "视频",
        "READ_MEDIA_AUDIO" to "音频",
        "READ_MEDIA_VISUAL_USER_SELECTED" to "照片选择器",
        "READ_EXTERNAL_STORAGE" to "存储读取",
        "WRITE_EXTERNAL_STORAGE" to "存储写入",
        "BLUETOOTH_CONNECT" to "蓝牙连接",
        "BLUETOOTH_SCAN" to "蓝牙扫描",
        "NEARBY_WIFI_DEVICES" to "附近设备",
        "READ_CLIPBOARD" to "剪贴板",
        "SYSTEM_ALERT_WINDOW" to "悬浮窗",
        "GET_USAGE_STATS" to "使用统计",
        "REQUEST_INSTALL_PACKAGES" to "安装应用",
        "POST_NOTIFICATION" to "通知",
        "RUN_ANY_IN_BACKGROUND" to "后台运行",
        "START_FOREGROUND" to "前台服务",
        "WAKE_LOCK" to "唤醒锁",
    )

    fun label(op: String): String = labels[op] ?: op

    fun knownOps(): List<String> = labels.keys.toList()
}
