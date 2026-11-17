package com.jmf.autohook

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

class HookService : AccessibilityService() {
    companion object {
        @Volatile var instance: HookService? = null
        @Volatile var running = false
        private var pendingResultCode = 0
        private var pendingData: Intent? = null

        fun start(context: Context, resultCode: Int, data: Intent) {
            pendingResultCode = resultCode
            pendingData = data
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, HookService::class.java))
        }

        fun stop() {
            running = false
            instance?.teardown()
        }

        fun afkProfiles(): List<AfkProfile> = AfkProfile.values().toList()
    }

    private fun teardown() {
        running = false
        monitorJob?.cancel()
        monitorJob = null
        lastName = null
        lastNameHash = 0
        setStatus("监测已结束")
        hideRunningHint()
        refreshLogWindow()
        handler.postDelayed({ setLogTouchable(true) }, 400)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: kotlinx.coroutines.Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private var inputW = 0
    private var inputH = 0
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }

    // ===== 主城识别（模板优先，懒加载） =====
    private data class HomeTpl(val label: String, val bmp: Bitmap, val gray: ByteArray, val mean: Float, val std: Float)
    private var homeTpls: List<HomeTpl>? = null
    // 每个主城模板自己的区域（720/1080 布局不同，不能共用比例）
    private val homeRects = mapOf(
        "digebao" to floatArrayOf(187f / 2400f, 4f / 1080f, 370f / 2400f, 47f / 1080f),
        "ziyouzhidu" to floatArrayOf(187f / 2400f, 4f / 1080f, 370f / 2400f, 47f / 1080f),
        // 按用户新裁的 720 模板（贴字裁切）对齐：迪戈堡 84x28、自由之都 109x28
        "digebao_720" to floatArrayOf(75f / 1280f, 7f / 720f, 155f / 1280f, 38f / 720f),
        "ziyouzhidu_720" to floatArrayOf(75f / 1280f, 7f / 720f, 180f / 1280f, 38f / 720f),
    )
    private val homeScaledCache = HashMap<String, ByteArray>()
    private var gameRectKey: String? = null
    private var homeRoiKey: String? = null

    // ===== 界面状态模板（改良页/分解弹框/战斗页），懒加载 =====
    private data class RegionTpl(val name: String, val bmp: Bitmap, val gray: ByteArray)
    private var regionTpls: Map<String, RegionTpl>? = null
    private val regionGrayCache = HashMap<String, ByteArray>()
    private val regionRects = mapOf(
        "improve" to floatArrayOf(1087f / 2400f, 12f / 1080f, 1265f / 2400f, 77f / 1080f),
        "improve_720" to floatArrayOf(1087f / 2400f, 12f / 1080f, 1265f / 2400f, 77f / 1080f),
        "popup" to floatArrayOf(840f / 2400f, 420f / 1080f, 1575f / 2400f, 513f / 1080f),
        "popup_720" to floatArrayOf(840f / 2400f, 420f / 1080f, 1575f / 2400f, 513f / 1080f),
        // 战斗判定只看「特技」按钮（圆形木箱图标），720/1080 各自一套位置
        "teji" to floatArrayOf(2176f / 2400f, 792f / 1080f, 2306f / 2400f, 922f / 1080f),
        "teji_720" to floatArrayOf(1187f / 1280f, 520f / 720f, 1272f / 1280f, 603f / 720f),
    )

    // ===== 坐标配置（比例按真实横屏分辨率 2400x1080 测得）=====
private val mapNameBox = floatArrayOf(0.0999f, 0.058f)
private val worldBtn = floatArrayOf(0.2335f, 0.2275f)
private val swipeFrom = floatArrayOf(0.7755f, 0.2778f)
private val swipeTo = floatArrayOf(0.2929f, 0.2778f)
private val chaosIcon = floatArrayOf(0.5975f, 0.55f)
private val chaosTeleport = floatArrayOf(0.5930f, 0.635f)
private val laijiRow = floatArrayOf(0.6250f, 0.839f)
    private val regionCloseBtn = floatArrayOf(0.8013f, 0.0852f)
private val laijiSwipeFrom = floatArrayOf(0.7000f, 0.80f)
private val laijiSwipeTo = floatArrayOf(0.7000f, 0.25f)
private val hookBtn = floatArrayOf(0.3775f, 0.0476f)

// 校准项：key -> 界面上显示的名称
private val coordKeys = listOf(
    "mapNameBox" to "地图名",
    "worldBtn" to "世界按钮",
    "swipeFrom" to "大地图滑动起点",
    "swipeTo" to "大地图滑动终点",
    "__afk__" to "",
    "laijiRow" to "来吉虾",
    "laijiSwipeFrom" to "区域列表滑动起点",
    "laijiSwipeTo" to "区域列表滑动终点",
    "hookBtn" to "挂机按钮",
    "regionCloseBtn" to "关闭叉（右上角）",
    "improveEntry" to "改良图标（右下角）",
    "nameCropTL" to "识别框左上角",
    "nameCropBR" to "识别框右下角",
    "avatar" to "头像",
    "bag" to "包裹",
    "shopBtn" to "商店按钮",
    "sellAllBtn" to "一键出售",
    "sellConfirm" to "出售确定",
    "med1" to "红药50级",
    "med3" to "蓝药50级",
    "combatArrow" to "战斗页展开箭头",
    "combatImprove" to "战斗菜单改良",
    "improveRepairTab" to "改良-装备修理页签",
    "improveDecomposeTab" to "改良-装备分解页签",
    "improveRepairNow" to "改良-立即修理",
    "improveFullRepair" to "改良-全身修理",
    "improveDecomposeNow" to "改良-立即分解",
    "improveCloseX" to "改良-关闭叉",
    "improvePopupX" to "改良-弹框关闭叉",
    "improvePopupConfirm" to "改良-弹框确认按钮",
    "improveItem" to "改良-装备格",
    "buyBtn" to "购买按钮",
    "buyNumBox" to "购买数量框",
    "buyNumConfirm" to "数量确认",
    "shopClose" to "关闭商店",
)

private val zeroCoord = floatArrayOf(0f, 0f)

private val coordDefaults = mapOf(
    "mapNameBox" to mapNameBox,
    "worldBtn" to worldBtn,
    "swipeFrom" to swipeFrom,
    "swipeTo" to swipeTo,
    "icon" to floatArrayOf(0.5975f, 0.55f),
    "teleport" to floatArrayOf(0.5930f, 0.635f),
    "laijiRow" to laijiRow,
    "laijiSwipeFrom" to laijiSwipeFrom,
    "laijiSwipeTo" to laijiSwipeTo,
    "hookBtn" to hookBtn,
    "regionCloseBtn" to regionCloseBtn,
    "improveEntry" to zeroCoord,
    "nameCropTL" to floatArrayOf(90f / 2192f, 0f),
    "nameCropBR" to floatArrayOf(360f / 2192f, 55f / 1080f),
    "avatar" to zeroCoord,
    "bag" to zeroCoord,
    "shopBtn" to zeroCoord,
    "sellAllBtn" to zeroCoord,
    "sellConfirm" to zeroCoord,
    "buyBtn" to zeroCoord,
    "buyNumBox" to zeroCoord,
    "buyNumConfirm" to zeroCoord,
    "combatArrow" to zeroCoord,
    "combatImprove" to zeroCoord,
    "improveRepairTab" to zeroCoord,
    "improveDecomposeTab" to zeroCoord,
    "improveRepairNow" to zeroCoord,
    "improveFullRepair" to zeroCoord,
    "improveDecomposeNow" to zeroCoord,
    "improveCloseX" to zeroCoord,
    "improvePopupX" to zeroCoord,
    "improvePopupConfirm" to zeroCoord,
    "improveItem" to zeroCoord,
    "med1" to zeroCoord,
    "med3" to zeroCoord,
    "shopClose" to zeroCoord,
)

// 设备坐标配置枚举：新增设备时在这里加一项，key 为 "长边x短边"
// 优先级：手动校准值 > 所选设备配置（按主页下拉框） > 通用默认值
enum class DeviceProfile(val label: String, val key: String, val coords: Map<String, FloatArray>) {
    YUN_720_1280("720*1280", "1280x720", mapOf(
        "worldBtn" to floatArrayOf(0.17003125f, 0.22638389f),
        "chaos_icon" to floatArrayOf(0.5210937f, 0.49166667f),
        "chaos_teleport" to floatArrayOf(0.6171875f, 0.57916665f),
        // 720 专用：以下地图不需要在大地图上滑动（起点=终点，代表不滑动）
        "luanshigang_swipeFrom" to floatArrayOf(0.7755f, 0.2778f),
        "luanshigang_swipeTo" to floatArrayOf(0.7755f, 0.2778f),
        "luanshigang_icon" to floatArrayOf(0.6492f, 0.7556f),
        "luanshigang_teleport" to floatArrayOf(0.7391f, 0.8472f),
        "fenqiling_swipeFrom" to floatArrayOf(0.7755f, 0.2778f),
        "fenqiling_swipeTo" to floatArrayOf(0.7755f, 0.2778f),
        "fenqiling_icon" to floatArrayOf(0.6570f, 0.5236f),
        "fenqiling_teleport" to floatArrayOf(0.7312f, 0.5944f),
        "wangchenhu_swipeFrom" to floatArrayOf(0.7755f, 0.2778f),
        "wangchenhu_swipeTo" to floatArrayOf(0.7755f, 0.2778f),
        "wangchenhu_icon" to floatArrayOf(0.6289f, 0.2778f),
        "wangchenhu_teleport" to floatArrayOf(0.7447f, 0.3653f),
        "heliuzhidi_swipeFrom" to floatArrayOf(0.7755f, 0.2778f),
        "heliuzhidi_swipeTo" to floatArrayOf(0.7755f, 0.2778f),
        "heliuzhidi_icon" to floatArrayOf(0.7789f, 0.5208f),
        "heliuzhidi_teleport" to floatArrayOf(0.8696f, 0.5778f),
        "avatar" to floatArrayOf(0.9625f, 0.0736f),
        "bag" to floatArrayOf(0.8914f, 0.3792f),
        "shopBtn" to floatArrayOf(0.8250f, 0.9236f),
        "sellAllBtn" to floatArrayOf(0.7953f, 0.9278f),
        "sellConfirm" to floatArrayOf(0.5820f, 0.6403f),
        "med1" to floatArrayOf(0.1578f, 0.5125f),
        "med3" to floatArrayOf(0.2156f, 0.6736f),
        "buyBtn" to floatArrayOf(0.7570f, 0.3750f),
        "buyNumBox" to floatArrayOf(0.4953f, 0.5903f),
        "buyNumConfirm" to floatArrayOf(0.5227f, 0.7028f),
        "shopClose" to floatArrayOf(0.8789f, 0.0639f),
        "combatArrow" to floatArrayOf(0.0188f, 0.0458f),
        "combatImprove" to floatArrayOf(0.1063f, 0.2264f),
        "improveRepairTab" to floatArrayOf(0.1820f, 0.1528f),
        "improveDecomposeTab" to floatArrayOf(0.7742f, 0.1361f),
        "improveRepairNow" to floatArrayOf(0.8008f, 0.9014f),
        "improveFullRepair" to floatArrayOf(0.7305f, 0.7694f),
        "improveDecomposeNow" to floatArrayOf(0.7953f, 0.8931f),
        "improveCloseX" to floatArrayOf(0.8742f, 0.0667f),
        "improvePopupX" to floatArrayOf(0.6484f, 0.2625f),
        "improvePopupConfirm" to floatArrayOf(0.5430f, 0.5681f),
        "improveItem" to floatArrayOf(0.2977f, 0.2958f),
        "improveEntry" to floatArrayOf(0.5922f, 0.9361f),
        "hookBtn" to floatArrayOf(0.3945f, 0.0556f),
        "regionCloseBtn" to floatArrayOf(0.8898f, 0.0847f),
    )),
    YUN_1080_2400("1080*2400", "2400x1080", mapOf(
        "mapNameBox" to floatArrayOf(0.0938f, 0.0475f),
        "worldBtn" to floatArrayOf(0.2239f, 0.2162f),
        "swipeFrom" to floatArrayOf(0.7755f, 0.2778f),
        "swipeTo" to floatArrayOf(0.2929f, 0.2778f),
        "chaos_icon" to floatArrayOf(0.5211f, 0.4917f),
        "chaos_teleport" to floatArrayOf(0.6172f, 0.5792f),
        "laijiRow" to floatArrayOf(0.6250f, 0.8390f),
        "laijiSwipeFrom" to floatArrayOf(0.7000f, 0.8000f),
        "laijiSwipeTo" to floatArrayOf(0.7000f, 0.2500f),
        "hookBtn" to floatArrayOf(0.3597f, 0.0812f),
        "regionCloseBtn" to floatArrayOf(0.8013f, 0.0852f),
        "nameCropTL" to floatArrayOf(0.0411f, 0.0000f),
        "nameCropBR" to floatArrayOf(0.1642f, 0.0509f),
        "avatar" to floatArrayOf(0.9625f, 0.0736f),
        "bag" to floatArrayOf(0.8107f, 0.3634f),
        "shopBtn" to floatArrayOf(0.7517f, 0.9137f),
        "sellAllBtn" to floatArrayOf(0.7330f, 0.9143f),
        "sellConfirm" to floatArrayOf(0.5678f, 0.6520f),
        "med1" to floatArrayOf(0.2210f, 0.5038f),
        "med3" to floatArrayOf(0.2814f, 0.6667f),
        "buyBtn" to floatArrayOf(0.6119f, 0.3947f),
        "buyNumBox" to floatArrayOf(0.5016f, 0.5781f),
        "buyNumConfirm" to floatArrayOf(0.5122f, 0.7043f),
        "shopClose" to floatArrayOf(0.7990f, 0.0590f),
        "combatArrow" to floatArrayOf(0.2710f, 0.0620f),
        "combatImprove" to floatArrayOf(0.1125f, 0.2430f),
        "improveRepairTab" to floatArrayOf(0.2296f, 0.1556f),
        "improveDecomposeTab" to floatArrayOf(0.6540f, 0.1556f),
        "improveRepairNow" to floatArrayOf(0.6420f, 0.9010f),
        "improveFullRepair" to floatArrayOf(0.7030f, 0.7590f),
        "improveDecomposeNow" to floatArrayOf(0.6420f, 0.9010f),
        "improveCloseX" to floatArrayOf(0.7810f, 0.0490f),
        "improvePopupX" to floatArrayOf(0.6130f, 0.2570f),
        "improvePopupConfirm" to floatArrayOf(0.4830f, 0.5560f),
        "improveItem" to floatArrayOf(0.3314f, 0.2963f),
        "improveEntry" to floatArrayOf(0.6456f, 0.9325f),
    )),
}

/** 挂机地图配置：icon=大地图里的地图图标，teleport=传送按钮 */
// 每个挂机地图一套独立坐标，未覆盖的步骤回退到通用默认值
enum class AfkProfile(val label: String, val key: String, val coords: Map<String, FloatArray>) {
    CHAOS("混沌森林", "chaos", mapOf(
        "icon" to floatArrayOf(0.5975f, 0.55f),
        "teleport" to floatArrayOf(0.5930f, 0.635f),
    )),
    DANGRI("荡日绿洲", "dangri", mapOf(
        "icon" to floatArrayOf(0.6657f, 0.5875f),
        "teleport" to floatArrayOf(0.7516f, 0.6750f),
    )),
    NIFENG("逆风丘陵", "nifeng", mapOf(
        "swipeFrom" to floatArrayOf(0.4563f, 0.5139f),
        "swipeTo" to floatArrayOf(0.4094f, 0.5361f),
        "icon" to floatArrayOf(0.3305f, 0.4722f),
        "teleport" to floatArrayOf(0.4164f, 0.6097f),
    )),
    // 幽暗森林：按校准面板实测值内置（滑动起点/终点、挂机图标、传送按钮）
    // 注意：滑动起点是面板里的「大地图滑动起点」0.5133,0.5181，不是「地图名」0.0999,0.0580
    YOUAN("幽暗森林", "youan", mapOf(
        "swipeFrom" to floatArrayOf(0.5133f, 0.5181f),
        "swipeTo" to floatArrayOf(0.5203f, 0.5361f),
        "icon" to floatArrayOf(0.5141f, 0.7472f),
        "teleport" to floatArrayOf(0.6070f, 0.8389f),
    )),
    // 以下地图暂时只在 720 设备配置里内置坐标；1080 配置不新增（选中时回落到通用值）
    LUANSHIGANG("乱石岗", "luanshigang", mapOf()),
    FENQILING("分歧岭", "fenqiling", mapOf()),
    WANGCHENHU("忘尘湖", "wangchenhu", mapOf()),
    HELIUZHIDI("合流之地", "heliuzhidi", mapOf()),
}

private fun selectedAfkProfile(prefs: android.content.SharedPreferences): AfkProfile {
    val label = prefs.getString("afkProfile", null) ?: return AfkProfile.CHAOS
    return AfkProfile.values().firstOrNull { it.label == label } ?: AfkProfile.CHAOS
}

private val devicePresets: Map<String, Map<String, FloatArray>> =
    DeviceProfile.values().associateBy({ it.key }, { it.coords })

private fun selectedDeviceKey(prefs: android.content.SharedPreferences): String? {
    val label = prefs.getString("deviceProfile", null) ?: return null
    return DeviceProfile.values().firstOrNull { it.label == label }?.key
}

private fun deviceKey(): String = "${maxOf(inputW, inputH)}x${minOf(inputW, inputH)}"

    /** 非标准分辨率兜底：短边相同就复用长边最接近的预设（如 2192x1080 复用 2400x1080） */
    private fun devicePresetFor(key: String): Map<String, FloatArray>? {
        devicePresets[key]?.let { return it }
        val short = key.substringAfter('x').toIntOrNull() ?: return null
        val long = key.substringBefore('x').toIntOrNull() ?: return null
        return devicePresets.entries
            .filter { (it.key.substringAfter('x').toIntOrNull() ?: -1) == short }
            .minByOrNull {
                kotlin.math.abs((it.key.substringBefore('x').toIntOrNull() ?: 0) - long)
            }?.value
    }

private val globalCoordKeys = setOf(
    "nameCropTL", "nameCropBR",
    "avatar", "bag", "shopBtn", "sellAllBtn", "sellConfirm",
    "buyBtn", "buyNumBox", "buyNumConfirm",
    "med1", "med2", "med3", "med4", "med5", "med6", "med7", "med8"
    , "shopClose"
)

private fun coordPrefix(name: String): String =
    if (name in globalCoordKeys) "" else selectedAfkProfile(getSharedPreferences("cfg", Context.MODE_PRIVATE)).key + "_"

private fun coord(name: String, def: FloatArray): FloatArray {
    val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
    val afk = selectedAfkProfile(prefs)
    // 识别框是屏幕布局相关，不分地图；其余步骤坐标均按当前地图隔离
    val prefix = coordPrefix(name)
    // 按实际屏幕尺寸自动匹配分辨率配置
    val presetMap = devicePresetFor(deviceKey())
    val presetPrefixed = presetMap?.get(prefix + name)
    val presetGeneric = presetMap?.get(name)
    val afkDefault = afk.coords[name]
    // 优先级：手动校准 > 带地图前缀的设备预设 > 地图内置值 > 通用设备预设 > 通用默认值
    // 地图内置值必须压过通用设备预设，否则「不滑动」这类地图级配置会被设备默认滑动覆盖
    val s = prefs.getString("coord_" + prefix + name, null)
        ?: prefs.getString("coord_" + name, null)
        ?: presetPrefixed?.let { "${it[0]},${it[1]}" }
        ?: afkDefault?.let { "${it[0]},${it[1]}" }
        ?: presetGeneric?.let { "${it[0]},${it[1]}" }
        ?: return def
    return try {
        val parts = s.split(",").map { it.trim().toFloat() }
        if (parts.size == 2) floatArrayOf(parts[0], parts[1]) else def
    } catch (_: Exception) { def }
}
    private val nameCrop = Rect(90, 0, 360, 55)
    private val logLines = java.util.ArrayDeque<String>()
    private var logView: android.widget.TextView? = null
    private var logScroll: android.widget.ScrollView? = null
    private var role = "队长"

    private fun setStatus(msg: String) = logLine(msg)

    /** 屏幕滚动日志：所有日志同步显示在悬浮窗，最多保留 60 行 */
    private fun logLine(msg: String) {
        Log.i("AutoHook", msg)
        handler.post {
            try {
                val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                logLines.addLast("$ts $msg")
                while (logLines.size > 60) logLines.removeFirst()
                if (!debugLogOn()) return@post
                ensureLogView()
                logView?.text = logLines.joinToString("\n")
                logScroll?.post { logScroll?.fullScroll(android.view.View.FOCUS_DOWN) }
            } catch (_: Exception) {}
        }
    }

    private var lastIconClick = 0L

    /** 日志窗触摸状态：监测中不可触摸（不影响游戏点击），停止后允许滚动 */
    private fun setLogTouchable(touchable: Boolean) {
        val s = logScroll ?: return
        try {
            val lp = s.layoutParams as android.view.WindowManager.LayoutParams
            lp.flags = if (touchable) (lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv())
                else (lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(s, lp)
        } catch (_: Exception) {}
    }
    /** 按当前监测状态重建日志窗（监测中不可触摸，停止后可滚动查看历史） */
    private fun refreshLogWindow() {
        handler.postDelayed({
            removeLogView()
            if (debugLogOn()) {
                ensureLogView()
                logView?.text = logLines.joinToString("\n")
                logScroll?.post { logScroll?.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }, 150)
    }
    private fun logErr(msg: String, e: Throwable? = null) {
        Log.e("AutoHook", msg, e)
        logLine("错误: $msg" + (e?.message?.let { " ($it)" } ?: ""))
    }

    private fun debugLogOn(): Boolean =
        getSharedPreferences("cfg", Context.MODE_PRIVATE).getBoolean("debugLog", false)

    private fun removeLogView() {
        try {
            logScroll?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        } catch (_: Exception) {}
        logScroll = null
        logView = null
    }

    private fun ensureLogView() {
        if (!debugLogOn() || panelView != null) return
        if (logView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val dm = resources.displayMetrics
        val tv = android.widget.TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(4f * d, 1f, 1f, 0xFF000000.toInt())
            setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        }
        val scroll = android.widget.ScrollView(this)
        scroll.addView(tv)
        val lp = android.view.WindowManager.LayoutParams(
            (dm.widthPixels * 0.38f).toInt(),
            (dm.heightPixels * 0.24f).toInt(),
            if (android.os.Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).also {
            // 左下角：避开头像、改良页签、装备格、弹框叉、药品等所有脚本点击目标
            it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            it.alpha = 0.9f
        }
        wm.addView(scroll, lp)
        logScroll = scroll
        logView = tv
    }
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        logLine("无障碍服务已连接")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(pendingResultCode, pendingData!!)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                running = false
            }
        }, handler)
        val dm = resources.displayMetrics
        // 截屏分辨率：主页下拉框设置，默认 720*1280；选"自动"则跟随屏幕横屏尺寸
        val resStr = getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("captureRes", "自动跟随屏幕") ?: "自动跟随屏幕"
        if (resStr == "自动跟随屏幕") {
            screenW = maxOf(dm.widthPixels, dm.heightPixels)
            screenH = minOf(dm.widthPixels, dm.heightPixels)
        } else {
            val dims = resStr.split("*").mapNotNull { it.trim().toIntOrNull() }
            screenW = if (dims.size == 2) maxOf(dims[0], dims[1]) else maxOf(dm.widthPixels, dm.heightPixels)
            screenH = if (dims.size == 2) minOf(dims[0], dims[1]) else minOf(dm.widthPixels, dm.heightPixels)
        }
        logLine("截屏分辨率: " + screenW + "x" + screenH)
        val realSize = android.graphics.Point()
        (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealSize(realSize)
        inputW = realSize.x
        inputH = realSize.y
        currentGameRect = Rect(0, 0, screenW, screenH)
        logLine("input=" + inputW + "x" + inputH)
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection!!.createVirtualDisplay(
            "hook", screenW, screenH, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader!!.surface, null, handler
        )
        role = getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("role", "队长") ?: "队长"
        showOverlay()
        return START_STICKY
    }

    private var iconView: android.widget.ImageView? = null
    private var panelView: android.widget.LinearLayout? = null
    private var cachedPanel: android.widget.LinearLayout? = null
    private var cachedPanelLp: android.view.WindowManager.LayoutParams? = null
    private var panelDirty = true
    private var panelScrollY = 0

    private fun overlayLp(w: Int, h: Int, gravity: Int): android.view.WindowManager.LayoutParams {
        val type = if (android.os.Build.VERSION.SDK_INT >= 26) {
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            android.view.WindowManager.LayoutParams.TYPE_PHONE
        }
        val flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return android.view.WindowManager.LayoutParams(w, h, type, flags, android.graphics.PixelFormat.TRANSLUCENT).also {
            it.gravity = gravity
        }
    }

    private fun showOverlay() {
        handler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            if (!android.provider.Settings.canDrawOverlays(this)) {
                logErr("悬浮窗权限未授予，无法显示图标")
                return@post
            }
            // 先移除旧图标，避免重复启动监测时叠加多个小图标
            iconView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            iconView = null
            val icon = android.widget.ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_manage)
                setBackgroundColor(0xE6FF1744L.toInt())
                alpha = 0.9f
                setOnClickListener {
                    val now = System.currentTimeMillis()
                    if (running) {
                        // 监测中：单击提示，双击结束监测
                        if (now - lastIconClick < 600) {
                            lastIconClick = 0
                            logLine("双击图标：结束监测")
                            Companion.stop()
                            android.widget.Toast.makeText(this@HookService, "监测已暂停", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            lastIconClick = now
                            android.widget.Toast.makeText(this@HookService, "双击图标结束监测", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        lastIconClick = 0
                        togglePanel()
                    }
                }
            }
            val dm = resources.displayMetrics
            val iconSize = (40 * dm.density).toInt()
            // 日志窗占底部 24%，图标放到日志上方，避免横屏时重叠
            val logH = (dm.heightPixels * 0.24f).toInt()
            val iconLp = overlayLp(iconSize, iconSize,
                android.view.Gravity.BOTTOM or android.view.Gravity.START).also {
                it.x = (8 * dm.density).toInt()
                it.y = logH + (10 * dm.density).toInt()
            }
            var downRawX = 0f
            var downRawY = 0f
            var startX = 0
            var startY = 0
            var dragging = false
            icon.setOnTouchListener { v, ev ->
                when (ev.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = iconLp.x
                        startY = iconLp.y
                        dragging = false
                        false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (dragging || Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            dragging = true
                            iconLp.x = startX + dx.roundToInt()
                            iconLp.y = startY - dy.roundToInt()
                            wm.updateViewLayout(v, iconLp)
                        }
                        dragging
                    }
                    else -> dragging
                }
            }
            wm.addView(icon, iconLp)
            iconView = icon
        }
    }

    private fun togglePanel() {
        handler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            if (panelView != null) {
                panelView?.let { wm.removeView(it) }
                panelView = null
                // 保留 cachedPanel，下次打开直接复用，避免每次重建上百个控件造成延迟
                ensureLogView()
                logView?.text = logLines.joinToString("\n")
                return@post
            }
            val cached = cachedPanel
            if (cached != null && !panelDirty && cachedPanelLp != null) {
                try {
                    wm.addView(cached, cachedPanelLp)
                    panelView = cached
                    removeLogView()
                    val sc = cached.findViewWithTag<android.widget.ScrollView>("panelScroll")
                    sc?.postDelayed({ sc.scrollTo(0, panelScrollY) }, 60)
                    return@post
                } catch (_: Exception) {
                    cachedPanel = null
                    cachedPanelLp = null
                }
            }
            val d = resources.displayMetrics.density
            val dm = resources.displayMetrics
            val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val root = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(0xF2222222L.toInt())
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            }
            fun smallButton(t: String): android.widget.Button =
                android.widget.Button(this).apply {
                    text = t
                    textSize = 11f
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = (30 * d).toInt()
                    minimumHeight = (30 * d).toInt()
                    setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            fun bigButton(t: String): android.widget.Button =
                android.widget.Button(this).apply {
                    text = t
                    textSize = 15f
                    minWidth = 0
                    minimumWidth = 0
                    minHeight = (40 * d).toInt()
                    minimumHeight = (40 * d).toInt()
                    setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            val btnStart = smallButton(if (running) "结束监测" else "启动监测")
            btnStart.setOnClickListener {
                if (running) {
                    Companion.stop()
                } else {
                    saveCfgToPrefs()
                    startMonitor()
                }
                panelDirty = true
                togglePanel()
            }
            val btnRole = smallButton("身份: $role")
            btnRole.setOnClickListener {
                role = if (role == "队长") "队员" else "队长"
                prefs.edit().putString("role", role).apply()
                btnRole.text = "身份: $role"
            }
            val btnClearCalib = smallButton("清除校准")
            btnClearCalib.setOnClickListener {
                if (btnClearCalib.text != "再点确认") {
                    btnClearCalib.text = "再点确认"
                    handler.postDelayed({ btnClearCalib.text = "清除校准" }, 3000)
                    return@setOnClickListener
                }
                btnClearCalib.text = "清除校准"
                prefs.all.keys.filter { it.startsWith("coord_") }.forEach { prefs.edit().remove(it).apply() }
                panelDirty = true
                android.widget.Toast.makeText(this, "已清除全部校准，恢复内置坐标", android.widget.Toast.LENGTH_SHORT).show()
            }
            val btnHide = smallButton("隐藏")
            btnHide.setOnClickListener { togglePanel() }
            val head = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            head.addView(btnStart)
            head.addView(btnRole)
            head.addView(btnClearCalib)
            head.addView(btnHide)
            root.addView(head)

            val spMap = android.widget.Spinner(this)
            val maps = AfkProfile.values().map { it.label }
            spMap.adapter = object : android.widget.ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, maps) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val v = super.getView(position, convertView, parent) as android.widget.TextView
                    v.textSize = 16f
                    v.setTextColor(0xFFFFFF00.toInt())
                    v.setTypeface(null, android.graphics.Typeface.BOLD)
                    return v
                }
            }
            var mapReady = false
            spMap.setSelection(maps.indexOf(selectedAfkProfile(prefs).label), false)
            spMap.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (!mapReady) return
                    val label = maps[position]
                    if (label != selectedAfkProfile(prefs).label) {
                        prefs.edit().putString("afkProfile", label).apply()
                        panelDirty = true
                        togglePanel()
                        togglePanel()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
            spMap.postDelayed({ mapReady = true }, 500)
            val btnChores = bigButton("跑日常")
            btnChores.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            btnChores.setOnClickListener {
                if (!running) {
                    android.widget.Toast.makeText(this, "请先启动监测", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                togglePanel()
                handler.postDelayed({
                    scope.launch {
                        prefs.edit().putLong("lastChoresTry", System.currentTimeMillis()).apply()
                        val ok = runDailyChores()
                        if (ok) prefs.edit().putString("lastChoresDate", todayKey()).apply()
                        setStatus(if (ok) "日常测试完成" else "日常测试失败")
                        handler.postDelayed({ if (panelView == null) togglePanel() }, 800)
                    }
                }, 400)
            }
            val mapRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            mapRow.addView(spMap, android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            mapRow.addView(btnChores)
            val btnLog = bigButton(if (debugLogOn()) "日志:开" else "日志:关")
            btnLog.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            btnLog.setOnClickListener {
                val on = !debugLogOn()
                prefs.edit().putBoolean("debugLog", on).apply()
                btnLog.text = if (on) "日志:开" else "日志:关"
                if (on) {
                    ensureLogView()
                    logView?.text = logLines.joinToString("\n")
                } else {
                    removeLogView()
                }
            }
            mapRow.addView(btnLog)
            root.addView(mapRow)

            val scroll = android.widget.ScrollView(this)
            scroll.setOnScrollChangeListener { _, _, sy, _, _ -> panelScrollY = sy }
            val rows = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val afk = selectedAfkProfile(prefs)
            val displayKeys = mutableListOf<Pair<String, String>>()
            coordKeys.forEach { (k, l) ->
                if (k == "__afk__") {
                    displayKeys.add("icon" to "挂机图标(" + afk.label + ")")
                    displayKeys.add("teleport" to "传送按钮(" + afk.label + ")")
                } else {
                    displayKeys.add(k to l)
                }
            }
            val panelW = (dm.widthPixels * 0.96f).toInt()
            val panelH = (dm.heightPixels * 0.88f).toInt()
            val cols = if (panelW / d >= 520f) 3 else 2
            var rowView: android.widget.LinearLayout? = null
            var building = true
            displayKeys.forEachIndexed { index, pair ->
                val key = pair.first
                val label = pair.second
                if (index % cols == 0) {
                    rowView = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                    }
                    rows.addView(rowView, android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val cell = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                rowView!!.addView(cell)
                cell.addView(android.widget.TextView(this).apply {
                    text = label
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(0xFFCCCCCCL.toInt())
                })
                val line = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                cell.addView(line)
                val edit = android.widget.EditText(this).apply {
                    val saved = prefs.getString("coord_" + coordPrefix(key) + key, null) ?: prefs.getString("coord_$key", null)
                    val def = coord(key, coordDefaults[key]!!)
                    setText(saved ?: ("%.4f,%.4f".format(def[0], def[1])))
                    textSize = 11f
                    minWidth = 0
                    minimumWidth = 0
                    setPadding((2 * d).toInt(), 0, (2 * d).toInt(), 0)
                    setTextColor(0xFFFFFFFFL.toInt())
                    setHintTextColor(0xFF888888L.toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(0,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: android.text.Editable?) {
                            if (building) return
                            val parts = s?.toString()?.split(",")?.map { it.trim()?.toFloatOrNull() }
                            if (parts != null && parts.size == 2 && parts[0] != null && parts[1] != null) {
                                prefs.edit().putString("coord_" + coordPrefix(key) + key, "${parts[0]},${parts[1]}").apply()
                            }
                        }
                    })
                }
                line.addView(edit)
                val btnTest = smallButton("测试")
                btnTest.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                btnTest.setOnClickListener {
                    val parts = edit.text.toString().trim().split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (parts.size != 2) {
                        android.widget.Toast.makeText(this, "坐标格式应为 x,y", android.widget.Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    togglePanel()
                    handler.postDelayed({
                        testTap(floatArrayOf(parts[0], parts[1]))
                        handler.postDelayed({ if (panelView == null) togglePanel() }, 1400)
                    }, 400)
                }
                line.addView(btnTest)
                val btnCalib = smallButton("校准")
                btnCalib.layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                btnCalib.setOnClickListener {
                    togglePanel()
                    captureNext(key, label)
                }
                line.addView(btnCalib)
            }
            if (displayKeys.isNotEmpty() && displayKeys.size % cols != 0) {
                repeat(cols - displayKeys.size % cols) {
                    rowView!!.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            building = false
            scroll.addView(rows)
            root.addView(scroll, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            removeLogView()
            val lp = overlayLp(panelW, panelH, android.view.Gravity.CENTER)
            wm.addView(root, lp)
            panelView = root
            scroll.tag = "panelScroll"
            cachedPanel = root
            cachedPanelLp = lp
            panelDirty = false
            scroll.postDelayed({ scroll.scrollTo(0, panelScrollY) }, 150)
        }
    }
    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** 每天第一次启动脚本、以及跨天后的凌晨，各跑一次日常；失败后 10 分钟内不重试 */
    private fun localMidnightMillis(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun choresDue(prefs: android.content.SharedPreferences): Boolean {
        // 日常（清背包/修理/分解整块流程）间隔：单位小时，低于 1 按 1、高于 24 按 24
        // 默认 24 小时 = 每天一次，保持凌晨处理（当天 0 点 + 0~60 分钟随机偏移后到期）
        val hours = prefs.getInt("intervalDecompose", 24).coerceIn(1, 24)
        val lastTry = prefs.getLong("lastChoresTry", 0L)
        if (hours >= 24) {
            if (prefs.getString("lastChoresDate", null) == todayKey()) return false
            val today = todayKey()
            var offMin = prefs.getInt("choresOffsetMin", -1)
            if (prefs.getString("choresOffsetDate", null) != today || offMin < 0) {
                offMin = java.util.Random().nextInt(61)
                prefs.edit().putString("choresOffsetDate", today).putInt("choresOffsetMin", offMin).apply()
                logLine("日常随机偏移: " + offMin + " 分钟")
            }
            val dueAt = localMidnightMillis() + offMin * 60_000L
            if (System.currentTimeMillis() < dueAt) return false
            return System.currentTimeMillis() - lastTry > 10 * 60 * 1000L
        }
        return System.currentTimeMillis() - lastTry >= hours * 3600_000L
    }

    private fun coordOrNull(name: String): FloatArray? {
        val p = coord(name, zeroCoord)
        return if (p[0] == 0f && p[1] == 0f) null else p
    }

    /** 在输入法窗口里找「完成/确定/Done」并点击，避免按返回把输入取消 */
    /** 在活动窗口和所有窗口里找「完成/确定」这类确认按钮 */
    private fun findDoneNode(): android.view.accessibility.AccessibilityNodeInfo? {
        val keys = listOf("完成", "确定", "确认", "Done", "DONE", "OK", "发送", "前往", "搜索")
        try {
            val roots = mutableListOf<android.view.accessibility.AccessibilityNodeInfo?>()
            roots.add(rootInActiveWindow)
            for (w in windows) roots.add(w.root)
            for (root in roots) {
                if (root == null) continue
                val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 600) {
                    val n = queue.poll() ?: continue
                    visited++
                    val text = ((n.text?.toString() ?: "") + "|" + (n.contentDescription?.toString() ?: "")).trim()
                    if (keys.any { text.contains(it) }) return n
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 输入界面是否真的打开了：系统输入法窗口存在，或界面上出现了「完成/确定」按钮 */
    private fun inputUiOpen(): Boolean = hasImeWindow() || findDoneNode() != null

    private fun pressImeDone(): Boolean {
        val node = findDoneNode() ?: return false
        var target: android.view.accessibility.AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && !target.isClickable && depth < 4) {
            target = target.parent
            depth++
        }
        val ok = target?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
        if (ok) logLine("输入法：点击确认键「" + (node.text?.toString() ?: node.contentDescription?.toString() ?: "") + "」")
        return ok
    }
    /** 节点树里找可编辑输入框（Unity 游戏调起输入法时会有原生 EditText） */
    private fun findEditableNode(): android.view.accessibility.AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.poll() ?: continue
            visited++
            val cls = node.className?.toString() ?: ""
            if (node.isEditable || cls.contains("EditText")) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (r.width() > 4 && r.height() > 4 && r.bottom > 0 && r.right > 0) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        logLine("未找到可编辑输入框节点 visited=$visited")
        return null
    }

    /** 轮询等输入框出现：点输入框后输入法弹起有延迟，用节点存在与否判断是否真的打开了 */
    /** 系统输入法窗口是否真的弹出来了 */
    private fun hasImeWindow(): Boolean = try {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (_: Exception) { false }

    private suspend fun waitEditableNode(timeoutMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            val node = findEditableNode()
            if (node != null && inputUiOpen()) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                logLine("输入框已打开 bounds=$r 输入法=true")
                return true
            }
            delay(200)
        }
        return false
    }

    /** 直接给输入框写文本，不需要点屏幕数字键 */
    private fun setTextOnEditableNode(value: String): Boolean {
        if (!inputUiOpen()) {
            logLine("输入界面未打开，不写入")
            return false
        }
        val node = findEditableNode() ?: return false
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        val ok = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        logLine("输入框写入 $value => $ok")
        return ok
    }

    /** 战斗页判定：右下角四个标注里至少两个模板命中 */
    private fun isCombatPage(quiet: Boolean = false): Boolean {
        val t0 = System.currentTimeMillis()
        val bmp = capture(quiet) ?: return false
        return try {
            val g = ensureGameRect(bmp)
            // 只认「特技」圆形木箱按钮
            val s = matchRegion("teji", bmp, g)
            val hit = s >= 0.75f
            if (!quiet) {
                logLine("战斗判定(特技): score=" + java.lang.String.format("%.2f", s) + " -> $hit 用时 ${System.currentTimeMillis() - t0}ms")
            }
            hit
        } finally {
            bmp.recycle()
        }
    }

    /** 改良页判定：标题「改良」模板 */
    private fun isImproveOpen(quiet: Boolean = false): Boolean {
        val t0 = System.currentTimeMillis()
        val bmp = capture(quiet) ?: return false
        return try {
            val g = ensureGameRect(bmp)
            val s = matchRegion("improve", bmp, g)
            if (!quiet) {
                logLine("改良页校验: score=" + java.lang.String.format("%.2f", s) + " -> ${s >= 0.80f} 用时 ${System.currentTimeMillis() - t0}ms")
            }
            s >= 0.80f
        } finally {
            bmp.recycle()
        }
    }

    /** 改良校验失败后快速关掉误触弹出的一个弹窗，避免后续重试全被挡住 */
    private suspend fun quickClosePopup(): Boolean {
        val bmp = capture() ?: return false
        try {
            val cancel = findCancelButton(bmp)
            if (cancel != null) {
                val (rx, ry) = capToReal(cancel.first, cancel.second)
                val longSide = maxOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                val shortSide = minOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                logLine("检测到误触弹窗（取消），点击 ($rx,$ry)")
                tap(floatArrayOf(rx / longSide, ry / shortSide))
                delay(1500)
                return true
            }
            val xs = findGreenXs(bmp)
            if (xs.isNotEmpty()) {
                val (x, y) = xs.first()
                val (rx, ry) = capToReal(x, y)
                val longSide = maxOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                val shortSide = minOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                logLine("检测到误触弹窗（绿叉），点击 ($rx,$ry)")
                tap(floatArrayOf(rx / longSide, ry / shortSide))
                delay(1500)
                return true
            }
            return false
        } finally {
            bmp.recycle()
        }
    }

    /** 打开改良页：多轮监测，优先等进入战斗后用左上角箭头入口，直到打开成功 */
    private suspend fun openImprovePage(): Boolean {
        var fails = 0
        var ticks = 0
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            ticks++
            // 没战斗就等，检测到战斗才点击
            val verbose = ticks % 8 == 0
            if (!isCombatPage(quiet = !verbose)) {
                delay(300)
                continue
            }

            logLine("改良：检测到战斗，点箭头进改良（第 ${fails + 1} 次）")
            tap(coord("combatArrow", coordDefaults["combatArrow"]!!)); delay(2000)
            tap(coord("combatImprove", coordDefaults["combatImprove"]!!)); delay(3000)

            // 点完只校验一次：没进说明这次没成功，立刻重来，不在这里反复等
            if (isImproveOpen(quiet = true)) {
                logLine("改良页已打开")
                return true
            }
            fails++
            logLine("改良第 $fails 次没进入，立即重新开始改良流程")
            quickClosePopup()
            if (fails % 3 == 0) closeAllGreenX()
        }
        logLine("改良页未打开（累计失败 $fails 次）")
        return false
    }
    /** 关闭改良页弹框：点叉 → 再点一次叉 → 还不行点确认 */
    private suspend fun dismissImprovePopup() {
        if (!improvePopupVisible()) return
        tap(coord("improvePopupX", coordDefaults["improvePopupX"]!!)); delay(3000)
        if (!improvePopupVisible()) return
        logLine("弹框叉第一次未生效，再点一次")
        tap(coord("improvePopupX", coordDefaults["improvePopupX"]!!)); delay(3000)
        if (!improvePopupVisible()) return
        logLine("弹框叉两次未生效，改点确认按钮")
        tap(coord("improvePopupConfirm", coordDefaults["improvePopupConfirm"]!!)); delay(3000)
    }
    /** 是否出现“非自身”的弹框（排除改良关闭叉、不能为空弹框的叉） */
    private suspend fun hasForeignPopup(): Boolean {
        val bmp = capture() ?: return false
        val xs = try { findGreenXs(bmp) } catch (e: Exception) { emptyList() }
        bmp.recycle()
        if (xs.isEmpty()) return false
        val longSide = maxOf(inputW, inputH).toFloat().coerceAtLeast(1f)
        val shortSide = minOf(inputW, inputH).toFloat().coerceAtLeast(1f)
        val ignore = listOfNotNull(coordOrNull("improveCloseX"), coordOrNull("improvePopupX"))
        return xs.any { (x, y) ->
            val (rx, ry) = capToReal(x, y)
            ignore.none { p ->
                val ix = p[0] * longSide
                val iy = p[1] * shortSide
                kotlin.math.hypot((rx - ix).toDouble(), (ry - iy).toDouble()) < 80.0
            }
        }
    }
    /** 装备修理：切页签 → 立即修理 */
    private suspend fun repairEquipment(): Boolean {
        setStatus("清理背包：修理装备")
        if (hasForeignPopup()) { logLine("修理前发现弹框"); return false }
        // 进入改良页、点「装备修理」页签之前：从页签位置向右匀速拖 70% 屏幕宽，持续 3 秒
        val repairTab = coord("improveRepairTab", coordDefaults["improveRepairTab"]!!)
        swipeWithDuration(repairTab, floatArrayOf(minOf(repairTab[0] + 0.70f, 0.98f), repairTab[1]), 3000)
        // 必须等 3 秒手势完整结束（含回弹）再点，否则点击会被系统丢弃
        delay(6000)
        tap(coord("improveRepairTab", coordDefaults["improveRepairTab"]!!)); delay(3000)
        if (hasForeignPopup()) { logLine("修理：点全身修理前发现弹框"); return false }
        tap(coord("improveFullRepair", coordDefaults["improveFullRepair"]!!)); delay(3000)
        if (hasForeignPopup()) { logLine("修理：点立即修理前发现弹框"); return false }
        tap(coord("improveRepairNow", coordDefaults["improveRepairNow"]!!)); delay(3000)
        logLine("清理背包：全身修理 → 立即修理")
        return true
    }

    /** 相邻两字模糊匹配命中数 */
    private fun bigramHits(text: String, expected: String): Int {
        val t = text.filter { it.code in 0x4E00..0x9FFF }
        val e = expected.filter { it.code in 0x4E00..0x9FFF }
        if (e.length < 2) return 0
        var hits = 0
        for (i in 0 until e.length - 1) {
            if (t.contains(e.substring(i, i + 2))) hits++
        }
        return hits
    }

    /** 分解弹框是否出现：中间弹框文字模板 */
    private fun improvePopupVisible(): Boolean {
        val t0 = System.currentTimeMillis()
        val bmp = capture() ?: return false
        return try {
            val g = ensureGameRect(bmp)
            val s = matchRegion("popup", bmp, g)
            logLine("分解弹框校验: score=" + java.lang.String.format("%.2f", s) + " -> ${s >= 0.80f} 用时 ${System.currentTimeMillis() - t0}ms")
            s >= 0.80f
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 队长关闭挂机：点挂机按钮后，用战斗图标判断是否已停。
     * 15 秒内每 2 秒检查一次，连续 3 次都不在战斗，才认为挂机已停，然后点右下角改良图标。
     */
    private suspend fun stopAfkFlow(): Boolean {
        var attempt = 0
        while (running && attempt < 10) {
            attempt++
            // 每轮重试前先清弹窗，避免残留弹窗被误判成战斗
            closeAllGreenX()
            setStatus("队长：等待非战斗瞬间（第 $attempt 次）")
            if (!waitIdleForAfkStop(10000)) {
                logLine("10 秒内没等到非战斗，重试（第 $attempt 次）")
                continue
            }
            setStatus("队长：关闭挂机（第 $attempt 次）")
            tap(coord("hookBtn", hookBtn))
            var streak = 0
            val t0 = System.currentTimeMillis()
            while (running && System.currentTimeMillis() - t0 < 15000) {
                delay(2000)
                val combat = isCombatPage()
                if (combat) {
                    // 中途检测到战斗：本轮直接失败，重新等脱战、重新点挂机
                    streak = 0
                    logLine("关闭挂机校验: 检测到战斗，本轮失败，马上重试（第 $attempt 次）@${stamp()}")
                    break
                }
                streak++
                logLine("关闭挂机校验: 战斗中=$combat 连续非战斗=$streak/4（第 $attempt 次）@${stamp()}")
                if (streak >= 4) {
                    // 停挂机后可能弹出提示，先清掉再点改良
                    closeAllGreenX()
                    setStatus("挂机已停止，点击改良")
                    tap(coord("improveEntry", coordDefaults["improveEntry"]!!))
                    delay(3000)
                    return true
                }
            }
            logLine("本轮未连续 4 次非战斗，马上重试（第 $attempt 次）")
        }
        return false
    }

    /** 等一个“不在战斗”的瞬间；队长关闭挂机必须等脱战再点按钮 */
    private suspend fun waitIdleForAfkStop(timeoutMs: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (running && System.currentTimeMillis() - t0 < timeoutMs) {
            if (!isCombatPage()) return true
            delay(500)
        }
        return false
    }

    /** 装备分解：每次「点装备格 → 点立即分解」，直到出现「不能为空」弹框 */
    private suspend fun decomposeEquipment(): Boolean {
        setStatus("清理背包：分解装备")
        if (isNormalEndPopup()) return true
        if (hasForeignPopup()) { logLine("分解前发现弹框"); return false }
        tap(coord("improveDecomposeTab", coordDefaults["improveDecomposeTab"]!!)); delay(3000)
        val item = coordOrNull("improveItem")
        if (item == null) {
            logLine("装备格未校准，跳过分解")
            return true
        }
        for (round in 1..30) {
            // 每 5 件做一次完整弹框校验（绿叉扫描较重）
            if (round == 1 || round % 5 == 0) {
                if (isNormalEndPopup()) return true
                if (hasForeignPopup()) { logLine("分解第 $round 次：发现弹框"); return false }
            }
            tap(item); delay(3000)
            tap(coord("improveDecomposeNow", coordDefaults["improveDecomposeNow"]!!)); delay(3000)
            logLine("分解第 $round 次完成")
        }
        return true
    }
    /** 任意节点出现「不能为空」弹框：关掉并按正常结束处理 */
    private suspend fun isNormalEndPopup(): Boolean {
        if (!improvePopupVisible()) return false
        logLine("出现「不能为空」弹框，按正常结束处理")
        dismissImprovePopup()
        return true
    }
    /** 一次完整的清理尝试：开改良 → 修理 → 分解 → 关改良 */
    private suspend fun runImproveOnce(): Boolean {
        // 进入改良前先做弹窗/绿叉校验，避免带弹窗去点箭头或改良
        closeAllGreenX()
        val opened = if (role == "队长") {
            // 队长：校验失败就继续点右下角改良重试（最多 8 次，每次间隔 2 秒）
            var ok = isImproveOpen()
            var attempt = 0
            while (!ok && attempt < 8) {
                attempt++
                logLine("改良页未确认，点右下角改良（第 $attempt 次）")
                tap(coord("improveEntry", coordDefaults["improveEntry"]!!))
                delay(2000)
                ok = isImproveOpen()
                if (!ok) quickClosePopup()
            }
            ok
        } else {
            openImprovePage()
        }
        if (!opened) {
            logLine("清包：改良页打开失败")
            return false
        }
        dismissImprovePopup()
        if (improvePopupVisible()) {
            logLine("清包：起始弹框未关掉")
            return false
        }
        if (!repairEquipment()) return false
        if (!decomposeEquipment()) return false
        if (isImproveOpen()) closeImprove()
        return true
    }
    private suspend fun closeImprove() {
        tap(coord("improveCloseX", coordDefaults["improveCloseX"]!!)); delay(3000)
    }

    /**
     * 每日杂务：清背包 + 分解装备 + 买药。
     * 顺序：关弹窗 -> 头像 -> 包裹 -> 商店 -> 一键出售 -> 确定 -> 逐个药品(购买->输入框->500->确认)。
     * 坐标未校准时整段跳过。
     */
    /** 买药：每小时一次，只买 50 级红药和 50 级蓝药 */
    private suspend fun runBuyFlow(prefs: android.content.SharedPreferences) {
        val avatar = coordOrNull("avatar")
        val bag = coordOrNull("bag")
        val shop = coordOrNull("shopBtn")
        val sellAll = coordOrNull("sellAllBtn")
        val sellOk = coordOrNull("sellConfirm")
        val buy = coordOrNull("buyBtn")
        val numBox = coordOrNull("buyNumBox")
        val numOk = coordOrNull("buyNumConfirm")
        if (avatar == null || bag == null || shop == null || sellAll == null || sellOk == null ||
            buy == null || numBox == null || numOk == null) {
            val missing = buildList {
                if (avatar == null) add("avatar")
                if (bag == null) add("bag")
                if (shop == null) add("shopBtn")
                if (sellAll == null) add("sellAllBtn")
                if (sellOk == null) add("sellConfirm")
                if (buy == null) add("buyBtn")
                if (numBox == null) add("buyNumBox")
                if (numOk == null) add("buyNumConfirm")
            }.joinToString(",")
            logLine("买药跳过：坐标未校准（$missing），本轮不更新计时")
            return
        }
        setStatus("买药：开始")
        closeAllGreenX()
        tap(avatar); delay(2000)
        tap(bag); delay(2000)
        logLine("买药：包裹已打开")
        tap(shop); delay(2000)
        logLine("买药：商店已打开")
        tap(sellAll); delay(2000)
        tap(sellOk); delay(2000)
        logLine("买药：一键出售完成")
        var done = 0
        for (i in listOf(1, 3)) {
            val med = coordOrNull("med$i") ?: continue
            setStatus("买药：第 $i 种")
            tap(med); delay(2000)
            tap(buy); delay(2000)
            tap(numBox); delay(2000)
            var ready = waitEditableNode(3000)
            if (!ready) {
                logLine("第 $i 种药：输入框未打开，重点一次")
                tap(numBox); delay(2000)
                ready = waitEditableNode(2000)
            }
            if (!ready) {
                logLine("第 $i 种药：输入法未弹出，跳过")
                continue
            }
            if (!setTextOnEditableNode("500")) {
                logLine("第 $i 种药：写入失败，跳过")
                continue
            }
            delay(2000)
            if (!pressImeDone()) logLine("第 $i 种药：未找到输入法完成键")
            delay(3000)
            tap(numOk); delay(2000)
            logLine("第 $i 种药：数量 500 已确认")
            done++
        }
        coordOrNull("shopClose")?.let { logLine("买药：关闭商店"); tap(it); delay(2000) }
        // 再点一次关闭商店，防止第一次没关掉
        coordOrNull("shopClose")?.let { tap(it); delay(2000) }
        // 买完药可能还留着弹窗/商店残留，先统一关掉，避免后续战斗判定被挡住
        closeAllGreenX()
        logLine("买药完成，成功=$done")
        prefs.edit().putLong("lastBuyAt", System.currentTimeMillis()).apply()
    }
    private suspend fun runDailyChores(): Boolean {
        lastDailyStatus = "执行中"
        setStatus("开始日常：分解修理")
        // 进流程前先清干净弹窗，避免残留弹窗导致战斗判定/改良入口失效
        closeAllGreenX()
        // 队长：先关闭挂机，再进改良页（避免自动战斗把界面顶掉）
        if (role == "队长") {
            setStatus("队长：先关闭挂机")
            if (!stopAfkFlow()) {
                lastDailyStatus = "关挂机失败"
                setStatus("日常：关闭挂机失败")
                return false
            }
            delay(1000)
        }
        var cleanFail = 0
        var cleaned = false
        while (cleanFail < 10 && !cleaned) {
            closeAllGreenX()
            if (runImproveOnce()) {
                cleaned = true
            } else {
                cleanFail++
                logLine("清包失败第 $cleanFail/10 次，立即重试")
            }
        }
        if (!cleaned) logLine("清包累计失败 10 次，放弃本轮")
        // 队长：日常处理完、弹窗关完后，重新点一下挂机恢复挂机（不校验）
        if (cleaned && role == "队长") {
            closeAllGreenX()
            tap(coord("hookBtn", hookBtn))
            delay(1500)
            logLine("日常处理完成，已重新点挂机（不校验）")
        }
        lastDailyStatus = if (cleaned) "完成" else "失败"
        setStatus(if (cleaned) "日常完成" else "日常失败")
        return cleaned
    }

    /** 队长脱战监测：独立流程，分解改良结束后执行；不在战斗才点挂机按钮 */
    private suspend fun captainLeaveCombatCheck(stage: String) {
        if (!running || role != "队长") return
        closeAllGreenX()
        if (!isCombatPage()) {
            logLine("$stage 脱战监测：已脱战，点挂机按钮")
            tap(coord("hookBtn", hookBtn)); delay(3000)
        } else {
            logLine("$stage 脱战监测：仍在战斗，跳过点击")
        }
    }
    private var calibView: android.view.View? = null
    @Volatile private var lastDailyStatus = "未执行"

    /** 收起面板，全屏透明层等待用户点下一个位置，记录后自动回到面板 */
    private fun captureNext(key: String, label: String) {
        handler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val d = resources.displayMetrics.density
            val tipText = "校准：$label"
            val hintText = "点击目标位置，左下角取消"
            val cancelText = "取消"
            val view = object : android.view.View(this) {
                val tipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFF00.toInt()
                    textSize = 16f * resources.displayMetrics.scaledDensity
                    setShadowLayer(4f * d, 0f, 2f * d, 0xFF000000.toInt())
                }
                val hintPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 12f * resources.displayMetrics.scaledDensity
                    setShadowLayer(4f * d, 0f, 2f * d, 0xFF000000.toInt())
                }
                val cancelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    textSize = 14f * resources.displayMetrics.scaledDensity
                    setShadowLayer(4f * d, 0f, 2f * d, 0xFF000000.toInt())
                }
                val cancelRect = android.graphics.RectF()
                override fun onDraw(canvas: android.graphics.Canvas) {
                    val left = 12f * d
                    val top = 10f * d
                    val tipBase = top + tipPaint.textSize
                    val hintBase = tipBase + 6f * d + hintPaint.textSize
                    canvas.drawText(tipText, left, tipBase, tipPaint)
                    canvas.drawText(hintText, left, hintBase, hintPaint)
                    val cw = cancelPaint.measureText(cancelText)
                    val cancelBase = height - 14f * d
                    canvas.drawText(cancelText, 12f * d, cancelBase, cancelPaint)
                    cancelRect.set(0f, cancelBase - cancelPaint.textSize - 10f * d, cw + 32f * d, height.toFloat())
                }
                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
                        if (cancelRect.contains(ev.x, ev.y)) {
                            calibView?.let { wm.removeView(it) }
                            calibView = null
                            togglePanel()
                            return true
                        }
                        // 恢复原始坐标系：与 abs() 点击映射共用 inputW/inputH
                        val longSide = maxOf(inputW, inputH)
                        val shortSide = minOf(inputW, inputH)
                        val rx = ev.rawX / longSide
                        val ry = ev.rawY / shortSide
                        logLine(
                            "校准: input=${inputW}x${inputH} " +
                                "raw=(${ev.rawX},${ev.rawY}) -> (%.4f,%.4f)".format(rx, ry)
                        )
                        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                            .putString("coord_" + coordPrefix(key) + key, "%.4f,%.4f".format(rx, ry)).apply()
                        android.widget.Toast.makeText(this@HookService,
                            "已记录 $label=(" + "%.4f".format(rx) + "," + "%.4f".format(ry) + ")",
                            android.widget.Toast.LENGTH_SHORT).show()
                        calibView?.let { wm.removeView(it) }
                        calibView = null
                        // 校准值已写入 prefs，面板缓存必须失效，否则输入框还显示旧值
                        panelDirty = true
                        togglePanel()
                    }
                    return true
                }
            }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                if (android.os.Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            )
            wm.addView(view, lp)
            calibView = view
        }
    }
    private fun saveCfgToPrefs() {
        // 配置已在 MainActivity 保存，这里留空占位（后续把配置搬进悬浮面板时启用）
    }

    private fun startAsForeground() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                android.app.NotificationChannel("hook", "梦宝回挂防狗", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, "hook")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("梦宝回挂防狗运行中")
            .setContentIntent(pi)
            .build()
        startForeground(1, notification)
    }

    private var hintView: android.widget.TextView? = null

    private fun showRunningHint() {
        handler.post {
            if (hintView != null) return@post
            try {
                if (!android.provider.Settings.canDrawOverlays(this)) return@post
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val d = resources.displayMetrics.density
                val dm = resources.displayMetrics
                val tv = android.widget.TextView(this).apply {
                    text = "脚本进行中，请勿操作"
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xCC000000.toInt())
                    setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
                }
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (android.os.Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).also {
                    it.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                    it.x = (8 * d).toInt()
                    it.y = (dm.heightPixels * 0.25f).toInt()
                }
                wm.addView(tv, lp)
                hintView = tv
            } catch (_: Exception) {}
        }
    }

    private fun hideRunningHint() {
        handler.post {
            try {
                hintView?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
            } catch (_: Exception) {}
            hintView = null
        }
    }
    private fun startMonitor() {
        if (running) return
        android.widget.Toast.makeText(this, "脚本进行中，请勿操作！", android.widget.Toast.LENGTH_LONG).show()
        showRunningHint()
        running = true
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val homeMaps = prefs.getString("homeMaps", "迪戈堡,自由之都")!!
            .split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }
        // 归位确认按当前选择的挂机地图名判断（旧 afkMap 配置项已废弃）
        val afkMap = selectedAfkProfile(prefs).label
        val interval = (prefs.getInt("interval", 300) * 1000).toLong()
        role = prefs.getString("role", "队长") ?: "队长"
        logLine("分辨率配置: " + deviceKey())
        setLogTouchable(false)
        monitorJob = scope.launch {
            refreshLogWindow()
            // 手动点开始监测：无视所有计时，重置后强制跑一轮买药 + 归位 + 日常（方便测试）
            prefs.edit()
                .putLong("lastBuyAt", 0L)
                .putLong("lastChoresTry", 0L)
                .putString("choresOffsetDate", todayKey())
                .putInt("choresOffsetMin", 0)
                .remove("lastChoresDate")
                .apply()
            logLine("启动：已重置所有计时，强制跑一轮流程")
            // 固定启动流程：先归位，再买药/清背包，整段跑完才开始按间隔计时
            try {
                delay(3000)
                if (running) {
                    val buyGap = prefs.getInt("intervalBuy", 60).coerceAtLeast(1) * 60_000L
                    val lastBuy = prefs.getLong("lastBuyAt", 0L)
                    val enableBuy = prefs.getBoolean("enableBuy", true)
                    if (!enableBuy) {
                        logLine("启动流程：买药+出售未勾选，跳过")
                    } else if (System.currentTimeMillis() - lastBuy >= buyGap) {
                        logLine("启动流程：先买药")
                        runBuyFlow(prefs)
                    } else {
                        val leftMin = (buyGap - (System.currentTimeMillis() - lastBuy)) / 60_000L + 1
                        logLine("启动流程：买药未到期，剩余 $leftMin 分钟")
                    }
                }
                if (running) {
                    closeAllGreenX()
                    val startMap = readMapName()
                    if (startMap != null && isHomeMap(startMap, homeMaps)) {
                        setStatus("监测到主城：$startMap")
                        delay(3000)
                        setStatus("启动流程：归位")
                        goBack(afkMap)
                    } else {
                        setStatus("未监测到主城，跳过启动归位")
                        delay(3000)
                    }
                }
            } catch (e: Exception) {
                logErr("startup flow error", e)
            }
            // 日常与主城判断完全独立：启动时无论是否在主城都执行一次
            if (running) {
                if (prefs.getBoolean("enableDecompose", true)) {
                    logLine("启动流程：调用日常")
                    setStatus("启动流程：日常")
                    prefs.edit().putLong("lastChoresTry", System.currentTimeMillis()).apply()
                    try {
                        if (runDailyChores()) prefs.edit().putString("lastChoresDate", todayKey()).apply()
                    } catch (e: Exception) {
                        logErr("daily chores error", e)
                        lastDailyStatus = "异常:" + e.message
                        setStatus("日常异常：" + e.message)
                    }
                } else {
                    logLine("分解+修理未勾选，跳过日常")
                    lastDailyStatus = "未启用"
                }
            }
            captainLeaveCombatCheck("启动流程")
            while (running) {
                try {
                    val buyGap = prefs.getInt("intervalBuy", 60).coerceAtLeast(1) * 60_000L
                    val lastBuy = prefs.getLong("lastBuyAt", 0L)
                    if (!prefs.getBoolean("enableBuy", true)) {
                        logLine("买药+出售未勾选，跳过")
                    } else if (System.currentTimeMillis() - lastBuy >= buyGap) {
                        runBuyFlow(prefs)
                    } else {
                        val leftMin = (buyGap - (System.currentTimeMillis() - lastBuy)) / 60_000L + 1
                        logLine("买药未到期，剩余 $leftMin 分钟")
                    }
                    closeAllGreenX()
                    val mapName = readMapName()
                    if (mapName != null && isHomeMap(mapName, homeMaps)) {
                        setStatus("识别地图: $mapName（主城）｜日常:$lastDailyStatus")
                        logLine("在主城，开始回挂")
                        goBack(afkMap)
                    } else {
                        setStatus("识别地图: ${mapName ?: "未知"}（非主城）｜日常:$lastDailyStatus")
                    }
                    if (prefs.getBoolean("enableDecompose", true) && choresDue(prefs)) {
                        prefs.edit().putLong("lastChoresTry", System.currentTimeMillis()).apply()
                        try {
                            if (runDailyChores()) prefs.edit().putString("lastChoresDate", todayKey()).apply()
                        } catch (e: Exception) {
                            logErr("daily chores error", e)
                            lastDailyStatus = "异常:" + e.message
                            setStatus("日常异常：" + e.message)
                        }
                    }
                    captainLeaveCombatCheck("循环")
                } catch (e: Exception) {
                    logErr("loop error", e)
                }
                delay(interval)
            }
        }
    }
    /** 模糊匹配：OCR 结果只要包含主城名中任意字符即视为命中（容忍少字/单字识别） */
    private fun isHomeMap(name: String, homeMaps: List<String>): Boolean {
        // 只保留中文，过滤识别噪声；主城名与识别结果有任意一个公共字即命中
        val clean = name.filter { it.code in 0x4E00..0x9FFF }
        val hit = clean.isNotEmpty() && homeMaps.any { home -> home.any { ch -> clean.contains(ch) } }
        logLine("主城判定: name=$name hit=$hit")
        return hit
    }

    /** 主城名字模板懒加载：第一次做主城判定时才读 homes 目录下的 png */
    private fun homeTemplates(): List<HomeTpl> {
        homeTpls?.let { return it }
        val out = mutableListOf<HomeTpl>()
        try {
            assets.list("homes")?.sorted()?.forEach { name ->
                if (!name.endsWith(".png", true)) return@forEach
                try {
                    assets.open("homes/$name").use { input ->
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        if (bmp != null) {
                            val w = bmp.width
                            val h = bmp.height
                            val px = IntArray(w * h)
                            bmp.getPixels(px, 0, w, 0, 0, w, h)
                            val gray = ByteArray(px.size)
                            var sum = 0.0
                            for (i in px.indices) {
                                val c = px[i]
                                val v = (c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f
                                gray[i] = v.toInt().coerceIn(0, 255).toByte()
                                sum += v
                            }
                            val mean = (sum / px.size).toFloat()
                            var varSum = 0.0
                            for (b in gray) {
                                val d = (b.toInt() and 0xFF) - mean
                                varSum += d * d
                            }
                            val std = kotlin.math.sqrt(varSum / px.size).toFloat()
                            out.add(HomeTpl(name.substringBeforeLast('.'), bmp, gray, mean, std))
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        homeTpls = out
        logLine("主城模板: " + if (out.isEmpty()) "无（请放入 assets/homes/*.png）" else out.joinToString { it.label })
        return out
    }

    private fun scaledTplGray(t: HomeTpl, w: Int, h: Int): ByteArray {
        if (t.bmp.width == w && t.bmp.height == h) return t.gray
        val key = t.label + "@" + w + "x" + h
        homeScaledCache[key]?.let { return it }
        val scaled = Bitmap.createScaledBitmap(t.bmp, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        scaled.recycle()
        val g = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            g[i] = ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f).toInt().toByte()
        }
        homeScaledCache[key] = g
        return g
    }

    /** 零均值归一化互相关，radius 为像素级平移搜索范围 */
    private fun nccScore(img: ByteArray, tpl: ByteArray, w: Int, h: Int, radius: Int, circular: Boolean = false): Float {
        val ccx = w / 2f
        val ccy = h / 2f
        val rr = minOf(w, h) / 2f - 1f
        val r2 = rr * rr
        var best = -1f
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                var n = 0
                var sumI = 0.0; var sumI2 = 0.0
                var sumT = 0.0; var sumT2 = 0.0
                var sumIT = 0.0
                var y = 0
                while (y < h) {
                    val sy = y + dy
                    if (sy in 0 until h) {
                        val srow = sy * w
                        val trow = y * w
                        var x = 0
                        while (x < w) {
                            val sx = x + dx
                            val vx = x + 0.5f - ccx
                            val vy = y + 0.5f - ccy
                            val inCircle = !circular || vx * vx + vy * vy <= r2
                            if (sx in 0 until w && inCircle) {
                                val iv = (img[srow + sx].toInt() and 0xFF).toFloat()
                                val tv = (tpl[trow + x].toInt() and 0xFF).toFloat()
                                n++
                                sumI += iv; sumI2 += iv * iv
                                sumT += tv; sumT2 += tv * tv
                                sumIT += iv * tv
                            }
                            x++
                        }
                    }
                    y++
                }
                if (n < w * h / 3) continue
                val den = kotlin.math.sqrt((n * sumI2 - sumI * sumI) * (n * sumT2 - sumT * sumT))
                val s = if (den > 1e-6) ((n * sumIT - sumI * sumT) / den).toFloat() else 0f
                if (s > best) best = s
            }
        }
        return best
    }

    /** 界面状态模板懒加载 */
    private fun regionTemplates(): Map<String, RegionTpl> {
        regionTpls?.let { return it }
        val out = HashMap<String, RegionTpl>()
        regionRects.keys.forEach { name ->
            try {
                assets.open("tpl/$name.png").use { input ->
                    val bmp = android.graphics.BitmapFactory.decodeStream(input) ?: return@use
                    val w = bmp.width
                    val h = bmp.height
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    val gray = ByteArray(px.size)
                    for (i in px.indices) {
                        val c = px[i]
                        gray[i] = ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f).toInt().toByte()
                    }
                    out[name] = RegionTpl(name, bmp, gray)
                }
            } catch (_: Exception) {}
        }
        regionTpls = out
        logLine("界面模板: " + if (out.isEmpty()) "无" else out.keys.sorted().joinToString())
        return out
    }

    /** 按固定比例裁出区域，缩放到模板尺寸后做 NCC */
    private fun matchRegion(name: String, bmp: Bitmap, g: Rect): Float {
        // 支持同一区域的多个分辨率模板：每个模板用自己的区域比例，取最高分
        val candidates = regionTemplates().entries.filter { it.key == name || it.key.startsWith(name + "_") }
        if (candidates.isEmpty()) return -1f
        var best = -1f
        for ((key, t) in candidates) {
            val r = regionRects[key] ?: continue
            var x = g.left + (r[0] * g.width()).toInt()
            var y = g.top + (r[1] * g.height()).toInt()
            var w = ((r[2] - r[0]) * g.width()).toInt().coerceAtLeast(6)
            var h = ((r[3] - r[1]) * g.height()).toInt().coerceAtLeast(6)
            x = x.coerceAtLeast(0)
            y = y.coerceAtLeast(0)
            if (x + w > bmp.width) w = bmp.width - x
            if (y + h > bmp.height) h = bmp.height - y
            if (w < 6 || h < 6) continue
            val roi = Bitmap.createBitmap(bmp, x, y, w, h)
            val s = scoreRoi(roi, t, key)
            roi.recycle()
            if (s > best) best = s
        }
        return best
    }

    /** 画面 ROI 与单个模板做 NCC；小屏时把模板缩到 ROI 尺寸，避免放大画面导致细节丢失 */
    private fun scoreRoi(roi: Bitmap, t: RegionTpl, key: String): Float {
        val w = roi.width
        val h = roi.height
        // 小分辨率下不要把画面放大：取画面和模板里较小的尺寸做 1:1 比对，避免细节被抹平
        val useW = minOf(w, t.bmp.width).coerceAtLeast(6)
        val useH = minOf(h, t.bmp.height).coerceAtLeast(6)
        val roiScaled = if (w == useW && h == useH) roi
        else Bitmap.createScaledBitmap(roi, useW, useH, true)
        val px = IntArray(useW * useH)
        roiScaled.getPixels(px, 0, useW, 0, 0, useW, useH)
        if (roiScaled !== roi) roiScaled.recycle()
        val gray = ByteArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            gray[i] = ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f).toInt().toByte()
        }
        val cacheKey = key + "@" + useW + "x" + useH
        val tgray = regionGrayCache[cacheKey] ?: run {
            val tplScaled = if (t.bmp.width == useW && t.bmp.height == useH) t.bmp
            else Bitmap.createScaledBitmap(t.bmp, useW, useH, true)
            val tpx = IntArray(useW * useH)
            tplScaled.getPixels(tpx, 0, useW, 0, 0, useW, useH)
            if (tplScaled !== t.bmp) tplScaled.recycle()
            val g = ByteArray(tpx.size)
            for (i in tpx.indices) {
                val c = tpx[i]
                g[i] = ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f).toInt().toByte()
            }
            regionGrayCache[cacheKey] = g
            g
        }
        // 特技按钮按圆形比对（像绿叉一样，忽略四角背景）
        return nccScore(gray, tgray, useW, useH, 3, key.startsWith("teji"))
    }

    /** 主城名模板匹配：名字框相对游戏区固定，按比例裁出后和模板做 NCC */
    private fun matchHomeCity(bmp: Bitmap, g: Rect): Pair<String, Float>? {
        val tpls = homeTemplates()
        if (tpls.isEmpty()) return null
        var bestLabel = ""
        var best = -1f
        for (t in tpls) {
            val r = homeRects[t.label] ?: continue
            var x = g.left + (r[0] * g.width()).toInt()
            var y = g.top + (r[1] * g.height()).toInt()
            var w = ((r[2] - r[0]) * g.width()).toInt().coerceAtLeast(8)
            var h = ((r[3] - r[1]) * g.height()).toInt().coerceAtLeast(6)
            x = x.coerceAtLeast(0)
            y = y.coerceAtLeast(0)
            if (x + w > bmp.width) w = bmp.width - x
            if (y + h > bmp.height) h = bmp.height - y
            if (w < 8 || h < 6) continue
            val roi = Bitmap.createBitmap(bmp, x, y, w, h)
            val px = IntArray(w * h)
            roi.getPixels(px, 0, w, 0, 0, w, h)
            roi.recycle()
            val gray = ByteArray(px.size)
            for (i in px.indices) {
                val c = px[i]
                gray[i] = ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f).toInt().toByte()
            }
            val tg = scaledTplGray(t, w, h)
            val s = nccScore(gray, tg, w, h, 3)
            if (s > best) { best = s; bestLabel = t.label }
        }
        if (bestLabel.isEmpty()) return null
        return Pair(bestLabel, best)
    }

    /** 游戏区只算一次，后续按屏幕尺寸复用（省掉每次整屏扫描） */
    private fun ensureGameRect(bmp: Bitmap): Rect {
        val key = "${bmp.width}x${bmp.height}"
        if (gameRectKey != key) {
            val t0 = System.currentTimeMillis()
            currentGameRect = gameRect(bmp)
            gameRectKey = key
            logLine("游戏区定位: $currentGameRect 用时 ${System.currentTimeMillis() - t0}ms")
        }
        return currentGameRect
    }

    private suspend fun readMapName(): String? {
        val bmp = capture() ?: return null
        val game = gameRect(bmp)
        currentGameRect = game
        logLine("画面=${bmp.width}x${bmp.height} 游戏区=$game")
        // 识别框按原始横屏截图 2192x1080 像素测得
        val tl = coord("nameCropTL", floatArrayOf(90f / 2192f, 0f))
        val br = coord("nameCropBR", floatArrayOf(360f / 2192f, 55f / 1080f))
        var cx = game.left + (tl[0] * game.width()).roundToInt()
        var cy = game.top + (tl[1] * game.height()).roundToInt()
        var cw = maxOf(64, ((br[0] - tl[0]) * game.width()).roundToInt())
        var ch = maxOf(64, ((br[1] - tl[1]) * game.height()).roundToInt())
        if (cx + cw > bmp.width) cx = bmp.width - cw
        if (cy + ch > bmp.height) cy = bmp.height - ch
        val crop = Bitmap.createBitmap(bmp, cx, cy, cw, ch)
        // 地图名区域没变化就不再跑 OCR（云手机多开时 OCR 是常驻 CPU 开销）
        var hash = 17
        var hy = 0
        while (hy < ch) {
            var hx = 0
            while (hx < cw) {
                hash = hash * 31 + crop.getPixel(hx, hy)
                hx += 8
            }
            hy += 8
        }
        val cachedName = lastName
        if (hash == lastNameHash && cachedName != null) {
            crop.recycle()
            bmp.recycle()
            return cachedName
        }
        return try {
            val res = recognizer.process(InputImage.fromBitmap(crop, 0)).await()
            val text = res.text.replace(" ", "").replace("\n", "")
            lastNameHash = hash
            lastName = text
            text
        } finally {
            crop.recycle()
            bmp.recycle()
        }
    }

    /** 主城判定：模板匹配，带分阶段耗时日志 */
    private fun isHomeScreen(): Boolean {
        val t0 = System.currentTimeMillis()
        logLine("主城判定开始 @${stamp()}")
        val bmp = capture() ?: run {
            logLine("主城判定: 截图失败 @${stamp()}")
            return false
        }
        val tCap = System.currentTimeMillis()
        return try {
            val g = ensureGameRect(bmp)
            val tRect = System.currentTimeMillis()
            val m = matchHomeCity(bmp, g)
            val tMatch = System.currentTimeMillis()
            val hit = m != null && m.second >= 0.70f
            val score = if (m == null) "-" else java.lang.String.format("%.3f", m.second)
            logLine(
                "主城判定: ${m?.first ?: "无模板"} score=$score -> ${if (hit) "是(主城)" else "否(非主城)"} | " +
                    "截图 ${tCap - t0}ms 定位 ${tRect - tCap}ms 匹配 ${tMatch - tRect}ms 总计 ${tMatch - t0}ms @${stamp()}"
            )
            hit
        } finally {
            bmp.recycle()
        }
    }

    /** 在竖屏抓屏里自动检测横屏游戏画面条带（非黑色区域的包围盒） */
    private fun gameRect(bmp: Bitmap): Rect {
        var minX = bmp.width; var minY = bmp.height; var maxX = 0; var maxY = 0
        val step = 6
        val pixels = IntArray(bmp.width)
        for (y in 0 until bmp.height step step) {
            bmp.getPixels(pixels, 0, bmp.width, 0, y, bmp.width, 1)
            for (x in 0 until bmp.width step step) {
                val c = pixels[x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                if (r + g + b > 60) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX > minX && maxY > minY) Rect(minX, minY, maxX + step, maxY + step)
        else Rect(0, 0, bmp.width, bmp.height)
    }

    /** 画面静止时截屏流不产新帧，缓存最近一帧供复用（返回的都是独立副本，可安全 recycle） */
    @Volatile private var lastFrame: Bitmap? = null
    private var lastNameHash = 0
    private var lastName: String? = null

    private fun stamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    private fun capture(quiet: Boolean = false): Bitmap? {
        val t0 = System.currentTimeMillis()
        val img = imageReader?.acquireLatestImage()
        if (img == null) {
            val cached = lastFrame
            val dt = System.currentTimeMillis() - t0
            if (cached == null) {
                if (!quiet) logLine("截图: 无帧且无缓存 ${dt}ms @${stamp()}")
                return null
            }
            if (!quiet) logLine("截图: 无新帧，复用缓存 ${dt}ms @${stamp()}")
            return cached.copy(Bitmap.Config.ARGB_8888, false)
        }
        return try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val bmp = Bitmap.createBitmap(
                img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
            )
            bmp.copyPixelsFromBuffer(plane.buffer)
            val frame = if (rowPad == 0) bmp else {
                val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
                bmp.recycle()
                cropped
            }
            // 复用同一块缓存位图，避免每帧再分配一张整屏 bitmap
            val cached = lastFrame
            if (cached != null && cached.width == frame.width && cached.height == frame.height) {
                android.graphics.Canvas(cached).drawBitmap(frame, 0f, 0f, null)
            } else {
                lastFrame?.recycle()
                lastFrame = frame.copy(Bitmap.Config.ARGB_8888, true)
            }
            if (!quiet) logLine("截图: ${frame.width}x${frame.height} 用时 ${System.currentTimeMillis() - t0}ms @${stamp()}")
            frame
        } finally {
            img.close()
        }
    }

    /** 关闭屏幕上所有绿叉弹窗，全部关完才返回 */
    /** 中间弹框区域的画面采样，用于判断点击后画面变化比例 */
    private fun frameSample(): IntArray {
        val bmp = capture() ?: return IntArray(0)
        return try {
            val x0 = (bmp.width * 0.30f).toInt()
            val x1 = (bmp.width * 0.70f).toInt()
            val y0 = (bmp.height * 0.25f).toInt()
            val y1 = (bmp.height * 0.75f).toInt()
            val nx = 48
            val ny = 27
            val arr = IntArray(nx * ny)
            for (jy in 0 until ny) {
                for (jx in 0 until nx) {
                    val px = x0 + (x1 - x0) * jx / nx
                    val py = y0 + (y1 - y0) * jy / ny
                    arr[jy * nx + jx] = bmp.getPixel(px, py)
                }
            }
            arr
        } catch (_: Exception) {
            IntArray(0)
        } finally {
            bmp.recycle()
        }
    }

    /** 两帧采样中颜色明显变化的像素比例 */
    private fun changeRatio(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || b.size != a.size) return 0f
        var diff = 0
        for (i in a.indices) {
            val c1 = a[i]
            val c2 = b[i]
            val dr = kotlin.math.abs(((c1 shr 16) and 0xFF) - ((c2 shr 16) and 0xFF))
            val dg = kotlin.math.abs(((c1 shr 8) and 0xFF) - ((c2 shr 8) and 0xFF))
            val db = kotlin.math.abs((c1 and 0xFF) - (c2 and 0xFF))
            if (dr + dg + db > 60) diff++
        }
        return diff.toFloat() / a.size
    }
    /** 按钮橙色判定 */
    private fun isOrange(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 190 && g in 110..215 && b <= 120 && r > g && g > b
    }

    private var cancelBtnTemplateCache: Triple<Int, Int, FloatArray>? = null
    private val cancelBtnTemplate: Triple<Int, Int, FloatArray>?
        get() {
            cancelBtnTemplateCache?.let { return it }
            return try {
                val bytes = assets.open("cancelbtn.png").readBytes()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val w = bmp.width
                val h = bmp.height
                val data = FloatArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val c = bmp.getPixel(x, y)
                        data[y * w + x] = 0.299f * ((c shr 16) and 0xFF) +
                            0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    }
                }
                bmp.recycle()
                val t = Triple(w, h, data)
                cancelBtnTemplateCache = t
                t
            } catch (_: Exception) { null }
        }

    /**
     * 用 assets/cancelbtn.png 匹配弹框里的「取消」按钮（与绿叉同一套 NCC）。
     * 先在画面中部用橙色粗筛，再做模板匹配，返回截图坐标中心。
     */
    private fun findCancelButton(bmp: Bitmap): Pair<Int, Int>? {
        val tC0 = System.currentTimeMillis()
        val tpl = cancelBtnTemplate ?: return null
        val gx0 = (bmp.width * 0.20f).toInt()
        val gy0 = (bmp.height * 0.25f).toInt()
        val gx1 = (bmp.width * 0.85f).toInt()
        val gy1 = (bmp.height * 0.95f).toInt()
        val cw = gx1 - gx0
        val ch = gy1 - gy0
        if (cw <= 40 || ch <= 40) return null
        val region = Bitmap.createBitmap(bmp, gx0, gy0, cw, ch)
        val coarseW = 400
        val f = minOf(1f, coarseW.toFloat() / cw)
        val sw = (cw * f).toInt().coerceAtLeast(1)
        val sh = (ch * f).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(region, sw, sh, true)
        if (region !== scaled) region.recycle()
        val colors = IntArray(sw * sh)
        val gray = FloatArray(sw * sh)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val c = scaled.getPixel(x, y)
                colors[y * sw + x] = c
                gray[y * sw + x] = 0.299f * ((c shr 16) and 0xFF) +
                    0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
        }
        scaled.recycle()
        // 取消按钮是一大块橙色；先稀疏统计橙色占比，没有橙色按钮时直接跳过，省掉整区域匹配
        var orange = 0
        var sampled = 0
        var oy = 0
        while (oy < sh) {
            var ox = 0
            while (ox < sw) {
                if (isOrange(colors[oy * sw + ox])) orange++
                sampled++
                ox += 4
            }
            oy += 4
        }
        if (sampled == 0 || orange < sampled * 0.003f) {
            logLine("cancel btn skip ${System.currentTimeMillis() - tC0}ms（无橙色按钮）")
            return null
        }
        val base = coarseW * bmp.width / (2400f * cw)
        var bestScore = 0f
        var bestCx = -1
        var bestCy = -1
        var bestW = 0
        // 不同分辨率下游戏 UI 缩放不一致（720 的按钮明显比按宽度比例大），保留常用尺度
        for (mul in floatArrayOf(0.9f, 1f, 1.1f, 1.2f, 1.3f)) {
            val s = base * mul
            val tw = (tpl.first * s).toInt().coerceAtLeast(8)
            val th = (tpl.second * s).toInt().coerceAtLeast(8)
            if (tw >= sw || th >= sh) continue
            val n = tw * th
            val scaledTpl = FloatArray(n)
            for (ty in 0 until th) {
                val sy = ty * tpl.second / th
                for (tx in 0 until tw) {
                    scaledTpl[ty * tw + tx] = tpl.third[sy * tpl.first + tx * tpl.first / tw]
                }
            }
            val mask = BooleanArray(n)
            val rad = minOf(tw, th) / 2f
            var maskCount = 0
            for (ty in 0 until th) {
                for (tx in 0 until tw) {
                    val dx = tx + 0.5f - tw / 2f
                    val dy = ty + 0.5f - th / 2f
                    if (dx * dx + dy * dy <= rad * rad) {
                        mask[ty * tw + tx] = true
                        maskCount++
                    }
                }
            }
            var y = 0
            while (y + th < sh) {
                var x = 0
                while (x + tw < sw) {
                    val mid = (y + th / 2) * sw
                    if (isOrange(colors[mid + x + tw / 4]) || isOrange(colors[mid + x + tw * 3 / 4])) {
                        var sumI = 0f; var sumT = 0f; var sumII = 0f; var sumTT = 0f; var sumIT = 0f
                        var idx = 0
                        while (idx < n) {
                            if (mask[idx]) {
                                val ty = idx / tw
                                val tx = idx % tw
                                val iv = gray[(y + ty) * sw + (x + tx)]
                                val tv = scaledTpl[idx]
                                sumI += iv; sumT += tv; sumII += iv * iv; sumTT += tv * tv; sumIT += iv * tv
                            }
                            idx++
                        }
                        val meanI = sumI / maskCount
                        val varI = sumII / maskCount - meanI * meanI
                        if (varI >= 300f) {
                            val den = kotlin.math.sqrt(
                                (maskCount * sumII - sumI * sumI) * (maskCount * sumTT - sumT * sumT)
                            )
                            val score = if (den > 1e-3f) ((maskCount * sumIT - sumI * sumT) / den) else 0f
                            if (score > bestScore && score <= 1.0f) {
                                bestScore = score
                                bestCx = x + tw / 2
                                bestCy = y + th / 2
                                bestW = (tw / f).toInt().coerceAtLeast(24)
                            }
                        }
                    }
                    x += 2
                }
                y += 2
            }
        }
        if (bestScore < 0.65f || bestCx < 0) {
            logLine("cancel btn miss ${System.currentTimeMillis() - tC0}ms best=" + java.lang.String.format("%.3f", bestScore))
            return null
        }
        val cx = gx0 + (bestCx / f).toInt()
        val cy = gy0 + (bestCy / f).toInt()
        val nativeH = (bestW * tpl.second / tpl.first).coerceAtLeast(12)
        val center = refineCancelCenter(bmp, cx, cy, bestW, nativeH) ?: return null
        logLine("取消按钮匹配 score=" + java.lang.String.format("%.3f", bestScore) + " ($cx,$cy) -> 按钮中心(${center.first},${center.second}) 用时 ${System.currentTimeMillis() - tC0}ms")
        return center
    }

    /** 以匹配点为起点，在附近找橙色按钮块，返回块中心（避免匹配偏到按钮边缘） */
    private fun refineCancelCenter(bmp: Bitmap, cx: Int, cy: Int, nativeW: Int, nativeH: Int): Pair<Int, Int>? {
        val x0 = (cx - nativeW).coerceAtLeast(0)
        val x1 = (cx + nativeW).coerceAtMost(bmp.width - 1)
        val y0 = (cy - nativeH).coerceAtLeast(0)
        val y1 = (cy + nativeH).coerceAtMost(bmp.height - 1)
        val step = 2
        val gw = (x1 - x0) / step
        val gh = (y1 - y0) / step
        if (gw <= 0 || gh <= 0) return null
        val mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                if (isOrange(bmp.getPixel(x0 + gx * step, y0 + gy * step))) mask[gy * gw + gx] = true
            }
        }
        val seen = BooleanArray(gw * gh)
        val q = java.util.ArrayDeque<Int>()
        var best: Pair<Int, Int>? = null
        var bestDist = Double.MAX_VALUE
        for (i in mask.indices) {
            if (!mask[i] || seen[i]) continue
            seen[i] = true
            q.add(i)
            var minX = gw; var minY = gh; var maxX = 0; var maxY = 0
            while (q.isNotEmpty()) {
                val idx = q.poll() ?: continue
                val px = idx % gw
                val py = idx / gw
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = px + dx
                        val ny = py + dy
                        if (nx in 0 until gw && ny in 0 until gh) {
                            val ni = ny * gw + nx
                            if (mask[ni] && !seen[ni]) {
                                seen[ni] = true
                                q.add(ni)
                            }
                        }
                    }
                }
            }
            val bw = (maxX - minX + 1) * step
            val bh = (maxY - minY + 1) * step
            if (bw < nativeW * 0.6f || bw > nativeW * 1.6f || bh < nativeH * 0.5f || bh > nativeH * 2.0f) continue
            // 按按钮的长宽比再过一遍，避免命中方形图标
            val aspect = bw.toFloat() / bh.coerceAtLeast(1)
            val tplAspect = nativeW.toFloat() / nativeH.coerceAtLeast(1)
            if (aspect < tplAspect * 0.7f || aspect > tplAspect * 1.4f) continue
            val bx = x0 + (minX + maxX) / 2 * step
            val by = y0 + (minY + maxY) / 2 * step
            val dist = kotlin.math.hypot((bx - cx).toDouble(), (by - cy).toDouble())
            if (dist < bestDist) {
                bestDist = dist
                best = bx to by
            }
        }
        return best
    }
    private suspend fun closeAllGreenX(): Boolean {
        val closeT0 = System.currentTimeMillis()
        logLine("关闭弹窗：开始检测绿叉")
        var rounds = 0
        var remaining = false
        while (rounds < 4) {
            rounds++
            var bmp = capture()
            var tryN = 0
            while (bmp == null && tryN < 5) {
                delay(300)
                bmp = capture()
                tryN++
            }
            if (bmp == null) {
                setStatus("截屏无新帧，跳过绿叉检测")
                remaining = true
                break
            }
            val xs = findGreenXs(bmp)
            if (xs.isEmpty()) {
                val cancel = findCancelButton(bmp)
                bmp.recycle()
                if (cancel != null) {
                    val (rx, ry) = capToReal(cancel.first, cancel.second)
                    val longSide = maxOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                    val shortSide = minOf(inputW, inputH).toFloat().coerceAtLeast(1f)
                    logLine("发现弹框取消按钮，点击 ($rx,$ry)")
                    tap(floatArrayOf(rx / longSide, ry / shortSide))
                    remaining = true
                    setStatus("已点击弹框取消")
                    delay(3000)
                    continue
                }
                setStatus("绿叉未命中 最高分=" + java.lang.String.format("%.3f", lastGreenXBest))
                remaining = false
                break
            }
            bmp.recycle()
            remaining = true
            setStatus("发现绿叉 " + xs.size + " 个，逐个关闭")
            val longSide = maxOf(inputW, inputH).toFloat().coerceAtLeast(1f)
            val shortSide = minOf(inputW, inputH).toFloat().coerceAtLeast(1f)
            for (cand in xs.take(4)) {
                val (x, y) = cand
                val (rx, ry) = capToReal(x, y)
                logLine("绿叉点击 ($rx,$ry)")
                tap(floatArrayOf(rx / longSide, ry / shortSide))
                delay(3000)
            }
        }
        logLine("closeAllGreenX total " + (System.currentTimeMillis() - closeT0) + "ms rounds=" + rounds + " remaining=" + remaining)
        setStatus(if (remaining) "绿叉未关完" else "绿叉已关完，继续流程")
        return !remaining
    }
    private val SAVE_CAPTURE_DEBUG = false

    @Volatile private var lastGreenXBest = 0f
    private var greenXRotation = 0

    /** 调试用：把本轮截屏覆盖存到应用外部目录 xcapture.png，可 adb pull 查看 */
    private fun saveCaptureDebug(bmp: Bitmap) {
        try {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "xcapture.png")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } catch (e: Exception) {
            logErr("saveCaptureDebug failed", e)
        }
    }

    private class GreenXCand(val cx: Int, val cy: Int, val score: Float, val size: Int, val whiteRatio: Float = 0f)

    /**
     * Sliding-window NCC over [x0,x1]x[y0,y1]. Hits above scoreThreshold are appended to out.
     * Returns the best score seen in this pass.
     */
    private fun matchTemplate(
        gray: FloatArray, colors: IntArray, sw: Int, sh: Int,
        tplW: Int, tplH: Int, tplData: FloatArray,
        base: Float, scales: List<Float>, step: Int,
        x0: Int, y0: Int, x1: Int, y1: Int,
        scoreThreshold: Float,
        out: MutableList<GreenXCand>
    ): Float {
        var best = 0f
        for (scaleMul in scales) {
            val s = base * scaleMul
            val tw = (tplW * s).toInt().coerceAtLeast(8)
            val th = (tplH * s).toInt().coerceAtLeast(8)
            if (tw >= sw || th >= sh) continue
            val n = tw * th
            val scaledTpl = FloatArray(n)
            for (ty in 0 until th) {
                val sy = ty * tplH / th
                for (tx in 0 until tw) {
                    scaledTpl[ty * tw + tx] = tplData[sy * tplW + tx * tplW / tw]
                }
            }
            val mask = BooleanArray(n)
            val rad = minOf(tw, th) / 2f
            var maskCount = 0
            for (ty0 in 0 until th) {
                for (tx0 in 0 until tw) {
                    val dx0 = tx0 + 0.5f - tw / 2f
                    val dy0 = ty0 + 0.5f - th / 2f
                    if (dx0 * dx0 + dy0 * dy0 <= rad * rad) {
                        mask[ty0 * tw + tx0] = true
                        maskCount++
                    }
                }
            }
            val maxY = minOf(y1, sh - th - 1)
            val maxX = minOf(x1, sw - tw - 1)
            var y = maxOf(0, y0)
            while (y <= maxY) {
                var x = maxOf(0, x0)
                while (x <= maxX) {
                    var sumI = 0f; var sumT = 0f; var sumII = 0f; var sumTT = 0f; var sumIT = 0f
                    var white = 0
                    var idx = 0
                    while (idx < n) {
                        if (mask[idx]) {
                            val ty = idx / tw; val tx = idx % tw
                            val p = colors[(y + ty) * sw + (x + tx)]
                            val r = (p shr 16) and 0xFF; val gg = (p shr 8) and 0xFF; val b = p and 0xFF
                            if (r > 185 && gg > 185 && b > 185) white++
                            val iv = gray[(y + ty) * sw + (x + tx)]
                            val tv = scaledTpl[idx]
                            sumI += iv; sumT += tv; sumII += iv * iv; sumTT += tv * tv; sumIT += iv * tv
                        }
                        idx++
                    }
                    // 低方差区域（草地/水面/纯色块）NCC 会虚高，直接跳过，能砍掉大部分误报
                    val meanI = sumI / maskCount
                    val varI = sumII / maskCount - meanI * meanI
                    if (varI >= 300f) {
                        val den = kotlin.math.sqrt((maskCount * sumII - sumI * sumI) * (maskCount * sumTT - sumT * sumT))
                        val score = if (den > 1e-3f) ((maskCount * sumIT - sumI * sumT) / den) else 0f
                        if (score > best) best = score
                        if (score > scoreThreshold && white > maskCount * 0.10f) {
                            out.add(GreenXCand(x + tw / 2, y + th / 2, score, tw, white.toFloat() / maskCount.coerceAtLeast(1)))
                        }
                    }
                    x += step
                }
                y += step
            }
        }
        return best
    }

    /**
     * Template match for the green X button (assets/greenx.png), returns centers in capture coordinates.
     * Two passes: a 320px-wide coarse pass to reject the "no X" case cheaply (full-res 13-scale scan
     * takes tens of seconds on cloud phones), then a full-resolution pass only around the coarse hits.
     */
    private fun findGreenXs(bmp: Bitmap): List<Pair<Int, Int>> {
        val tpl = greenXTemplate ?: return emptyList()
        val g = currentGameRect
        val cw = g.width().coerceAtMost(bmp.width - g.left)
        val ch = g.height().coerceAtMost(bmp.height - g.top)
        if (cw <= 0 || ch <= 0) return emptyList()
        // 不再先拷一条整幅游戏带再裁黑边：直接算上下黑边偏移，只创建一次目标位图
        val (bandTop, bandBot) = trimDarkRowOffsets(bmp, g)
        val band = Bitmap.createBitmap(bmp, g.left, bandTop, cw, (bandBot - bandTop).coerceAtLeast(10))
        val bandW = band.width
        val bandH = band.height

        val coarseScale = minOf(1f, 320f / bandW)
        val cw2 = (bandW * coarseScale).toInt().coerceAtLeast(1)
        val ch2 = (bandH * coarseScale).toInt().coerceAtLeast(1)
        val coarseBmp = Bitmap.createScaledBitmap(band, cw2, ch2, true)
        val coarseGray = FloatArray(cw2 * ch2)
        val coarseColors = IntArray(cw2 * ch2)
        for (y in 0 until ch2) {
            for (x in 0 until cw2) {
                val c = coarseBmp.getPixel(x, y)
                coarseColors[y * cw2 + x] = c
                coarseGray[y * cw2 + x] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
        }
        coarseBmp.recycle()
        val coarseHits = mutableListOf<GreenXCand>()
        val tCoarse = System.currentTimeMillis()
        val coarseBest = matchTemplate(
            coarseGray, coarseColors, cw2, ch2,
            tpl.first, tpl.second, tpl.third,
            // 绿叉实际尺寸约为模板的 0.5~0.7 倍，尺度必须覆盖到 0.4，否则会漏检
            cw2 / 1280f, listOf(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f), 2,
            0, 0, cw2 - 1, ch2 - 1, 0.45f, coarseHits
        )
        logLine("greenx coarse " + (System.currentTimeMillis() - tCoarse) + "ms hits=" + coarseHits.size + " best=" + java.lang.String.format("%.3f", coarseBest))
        if (coarseHits.isEmpty()) {
            if (band !== bmp) band.recycle()
            lastGreenXBest = coarseBest
            return emptyList()
        }

        coarseHits.sortByDescending { it.score }
        val seeds = mutableListOf<GreenXCand>()
        for (h in coarseHits) {
            if (seeds.size >= 3) break
            val dup = seeds.any { k ->
                val dx = k.cx - h.cx; val dy = k.cy - h.cy
                dx * dx + dy * dy < (k.size * 3) * (k.size * 3)
            }
            if (!dup) seeds.add(h)
        }

        // 精扫不再把整条画面缩到 1280：围绕粗扫候选裁一小块原图做匹配。
        // 模板保持真实尺寸(~78px)，倍率恒定在 1.0 附近，不会因 0.53 倍率丢细节而误报。
        val fineHits = mutableListOf<GreenXCand>()
        var fineBest = 0f
        val tFine = System.currentTimeMillis()
        val coarseToFine = 1f / coarseScale
        val fineScales = listOf(0.85f, 0.95f, 1.05f, 1.15f)
        val pad = maxOf(24, (bandW / 320f * 3f).toInt())
        val winSize = (tpl.first * fineScales.maxOrNull()!!).toInt() + pad * 2
        for (seed in seeds) {
            val fx = (seed.cx * coarseToFine).toInt()
            val fy = (seed.cy * coarseToFine).toInt()
            val wx = (fx - winSize / 2).coerceIn(0, maxOf(0, bandW - winSize))
            val wy = (fy - winSize / 2).coerceIn(0, maxOf(0, bandH - winSize))
            val ww = minOf(winSize, bandW - wx)
            val wh = minOf(winSize, bandH - wy)
            if (ww < tpl.first || wh < tpl.second) continue
            val win = Bitmap.createBitmap(band, wx, wy, ww, wh)
            val wColors = IntArray(ww * wh)
            win.getPixels(wColors, 0, ww, 0, 0, ww, wh)
            win.recycle()
            val wGray = FloatArray(ww * wh)
            for (i in wColors.indices) {
                val c = wColors[i]
                wGray[i] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            }
            val before = fineHits.size
            val s = matchTemplate(
                wGray, wColors, ww, wh,
                tpl.first, tpl.second, tpl.third,
                1.0f, fineScales, 3,
                0, 0, ww - 1, wh - 1, 0.70f, fineHits
            )
            for (i in before until fineHits.size) {
                val h = fineHits[i]
                fineHits[i] = GreenXCand(h.cx + wx, h.cy + wy, h.score, h.size, h.whiteRatio)
            }
            if (s > fineBest) fineBest = s
        }
        if (band !== bmp) band.recycle()
        logLine("greenx fine " + (System.currentTimeMillis() - tFine) + "ms seeds=" + seeds.size + " hits=" + fineHits.size)

        fineHits.sortByDescending { it.score }
        val kept = mutableListOf<GreenXCand>()
        for (c in fineHits) {
            val dup = kept.any { k ->
                val dx = k.cx - c.cx; val dy = k.cy - c.cy
                dx * dx + dy * dy < (c.size / 2) * (c.size / 2) + (k.size / 2) * (k.size / 2)
            }
            if (!dup) kept.add(c)
        }
        if (kept.isEmpty()) {
            logLine("greenx miss fineBest=" + fineBest + " coarseBest=" + coarseBest)
        }
        lastGreenXBest = maxOf(fineBest, coarseBest)
        val result = mutableListOf<Pair<Int, Int>>()
        for (c in kept) {
            val px = c.cx + g.left
            val py = c.cy + bandTop
            val ratio = greenRatio(bmp, px, py, (c.size / 2).coerceAtLeast(10))
            logLine("绿叉候选 ($px,$py) score=" + java.lang.String.format("%.3f", c.score) + " green=" + java.lang.String.format("%.2f", ratio) + " white=" + java.lang.String.format("%.2f", c.whiteRatio))
            if (c.score <= 1.0f && ratio >= 0.05f && c.whiteRatio <= 0.5f) result.add(px to py)
        }
        return result
    }

    private fun greenRatio(bmp: Bitmap, cx: Int, cy: Int, radius: Int): Float {
        val x0 = (cx - radius).coerceAtLeast(0)
        val y0 = (cy - radius).coerceAtLeast(0)
        val x1 = (cx + radius).coerceAtMost(bmp.width - 1)
        val y1 = (cy + radius).coerceAtMost(bmp.height - 1)
        if (x1 <= x0 || y1 <= y0) return 0f
        var green = 0
        var total = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val c = bmp.getPixel(x, y)
                val rr = (c shr 16) and 0xFF
                val gg = (c shr 8) and 0xFF
                val bb = c and 0xFF
                if (gg >= 140 && gg > rr && gg > bb + 50) green++
                total++
                x += 2
            }
            y += 2
        }
        return if (total == 0) 0f else green.toFloat() / total
    }

    /** 裁掉上下整行偏暗的黑边行 */
    /** 只计算上下黑边偏移（不拷贝位图），供 findGreenXs 直接裁剪使用 */
    private fun trimDarkRowOffsets(bmp: Bitmap, g: Rect): Pair<Int, Int> {
        var top = g.top.coerceAtLeast(0)
        var bot = (g.top + g.height()).coerceAtMost(bmp.height)
        if (bot - top < 10) return Pair(top, bot)
        val left = g.left.coerceAtLeast(0)
        val right = (g.left + g.width()).coerceAtMost(bmp.width)
        fun rowDark(y: Int): Boolean {
            var maxSum = 0
            var x = left
            while (x < right) {
                val c = bmp.getPixel(x, y)
                val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (sum > maxSum) maxSum = sum
                x += 8
            }
            return maxSum < 90
        }
        while (top < bot - 10 && rowDark(top)) top++
        while (bot > top + 10 && rowDark(bot - 1)) bot--
        return Pair(top, bot)
    }

    private fun trimDarkRows(band: Bitmap): Pair<Bitmap, Int> {
        var top = 0
        var bot = band.height
        fun rowDark(y: Int): Boolean {
            var maxSum = 0
            var x = 0
            while (x < band.width) {
                val c = band.getPixel(x, y)
                val sum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (sum > maxSum) maxSum = sum
                x += 8
            }
            return maxSum < 90
        }
        while (top < bot - 10 && rowDark(top)) top++
        while (bot > top + 10 && rowDark(bot - 1)) bot--
        return if (top > 0 || bot < band.height) Pair(Bitmap.createBitmap(band, 0, top, band.width, bot - top), top) else Pair(band, 0)
    }
    private var greenXTemplateCache: Triple<Int, Int, FloatArray>? = null
    private val greenXTemplate: Triple<Int, Int, FloatArray>?
        get() {
            greenXTemplateCache?.let { return it }
            return try {
                val bytes = assets.open("greenx.png").readBytes()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val w = bmp.width; val h = bmp.height
                val data = FloatArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val c = bmp.getPixel(x, y)
                        data[y * w + x] = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    }
                }
                bmp.recycle()
                val t = Triple(w, h, data)
                greenXTemplateCache = t
                t
            } catch (_: Exception) { null }
        }
    /** 按屏幕绝对坐标点击（与 tap 相同的 50ms 手势） */
    private fun tapAt(x: Int, y: Int) {
        if (!running) return
        setStatus("点击绿叉 ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 900)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        handler.post {
            try {
                instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, handler)
            } catch (e: Exception) {
                logLine("手势发送失败: " + e.message)
            }
        }
        logLine("tapAt($x,$y)")
    }

    private suspend fun goBack(afkMap: String) {
        val backT0 = System.currentTimeMillis()
        // 先把所有绿叉弹窗关完，再执行回挂连点
        closeAllGreenX()
        val game = currentGameRect
        tap(coord("mapNameBox", mapNameBox)); delay(3000)
        tap(coord("worldBtn", worldBtn)); delay(3000)
        // 大地图滑动：仅幽暗森林/分歧岭/乱石岗/忘尘湖/合流之地取消，其余地图保留
        val noSwipe = selectedAfkProfile(getSharedPreferences("cfg", MODE_PRIVATE)) in
            setOf(AfkProfile.YOUAN, AfkProfile.FENQILING, AfkProfile.LUANSHIGANG, AfkProfile.WANGCHENHU, AfkProfile.HELIUZHIDI)
        if (!noSwipe) {
            swipe(coord("swipeFrom", swipeFrom), coord("swipeTo", swipeTo)); delay(3000)
        }
        tap(coord("icon", chaosIcon)); delay(3000)
        tap(coord("teleport", chaosTeleport)); delay(4500)
        if (role == "队员") {
            setStatus("队员：传送完成，流程结束")
            return
        }
        tap(coord("mapNameBox", mapNameBox)); delay(3000)
        swipe(coord("laijiSwipeFrom", laijiSwipeFrom), coord("laijiSwipeTo", laijiSwipeTo)); delay(4000)
        repeat(5) {
            tap(coord("laijiRow", laijiRow))
            delay(3000)
        }
        delay(3000)
        // 点完 5 次来吉虾后，先点右上角关闭叉关掉区域地图，再点挂机
        tap(coord("regionCloseBtn", regionCloseBtn)); delay(3000)
        // 兜底清掉其他绿叉弹窗
        closeAllGreenX()
        delay(3000)
        tap(coord("hookBtn", hookBtn)); delay(3000)
        val name = readMapName()
        logLine("回挂后识别: $name")
        // 归位后不再递归重试，交回主循环按间隔继续截图判断
        if (name != null && name.contains(afkMap)) {
            setStatus("已回到挂机地图")
        } else {
            setStatus("归位未确认，10秒后继续检查")
            delay(10000)
        }
        logLine("goBack total " + (System.currentTimeMillis() - backT0) + "ms")
    }

    @Volatile private var currentGameRect: Rect = Rect(0, 0, screenW, screenH)

    /** 截屏坐标（screenW×screenH）-> 真实屏幕坐标（inputW×inputH），横屏按长短边对应换算 */
    private fun capToReal(x: Int, y: Int): Pair<Int, Int> {
        val longReal = maxOf(inputW, inputH)
        val shortReal = minOf(inputW, inputH)
        var g = currentGameRect
        // 启动时还没识别过画面，游戏区默认是 0x0，直接用整屏，避免坐标被放大
        if (g.width() <= 1 || g.height() <= 1) g = Rect(0, 0, screenW, screenH)
        val gl = g.left.coerceAtLeast(0)
        val gt = g.top.coerceAtLeast(0)
        val gr = g.right.coerceAtMost(screenW)
        val gb = g.bottom.coerceAtMost(screenH)
        val gw = (gr - gl).coerceAtLeast(1)
        val gh = (gb - gt).coerceAtLeast(1)
        val rx = ((x - gl).toFloat() / gw * longReal).roundToInt()
        val ry = ((y - gt).toFloat() / gh * shortReal).roundToInt()
        logLine("截屏坐标($x,$y) -> 真实屏幕($rx,$ry)")
        return Pair(rx, ry)
    }

    private fun abs(p: FloatArray): Pair<Int, Int> {
        // 比例按真实屏幕横屏测得：x 沿长边，y 沿短边
        val longSide = maxOf(inputW, inputH)
        val shortSide = minOf(inputW, inputH)
        val lx = (p[0] * longSide).roundToInt()
        val ly = (p[1] * shortSide).roundToInt()
        logLine("映射 比例=(${p[0]},${p[1]}) -> 点击=($lx,$ly)")
        return Pair(lx, ly)
    }

    private fun tap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        setStatus("点击 ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 900)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        handler.post {
            try {
                instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, handler)
            } catch (e: Exception) {
                logLine("手势发送失败: " + e.message)
            }
        }
        logLine("tap($x,$y)")
    }
    private fun testSwipe() {
        val (x1, y1) = abs(swipeFrom)
        val (x2, y2) = abs(swipeTo)
        setStatus("滑动坐标: ($x1,$y1) -> ($x2,$y2)")
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        handler.post {
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { setStatus("滑动($x1,$y1)->($x2,$y2): 已执行") }
                override fun onCancelled(g: GestureDescription?) { setStatus("滑动($x1,$y1)->($x2,$y2): 被取消") }
            }
            instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, handler)
        }
    }
    private fun testTap(p: FloatArray) {
        val (x, y) = abs(p)
        val dm = resources.displayMetrics
        logLine("测试: p=(${p[0]},${p[1]}) dm=${dm.widthPixels}x${dm.heightPixels} input=${inputW}x${inputH} -> ($x,$y)")
        setStatus("点击坐标: ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 1400)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.5f, y.toFloat() + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        handler.post {
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { setStatus("点击($x,$y): 已执行") }
                override fun onCancelled(g: GestureDescription?) { setStatus("点击($x,$y): 被取消") }
            }
            instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, handler)
        }
    }
    private fun swipe(from: FloatArray, to: FloatArray) {
        if (!running) return
        val (x1, y1) = abs(from)
        val (x2, y2) = abs(to)
        // 起止点重合 = 该地图不需要滑动；直接跳过，否则会变成 500ms 长按
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx * dx + dy * dy <= 4) {
            logLine("滑动起止点相同，跳过滑动")
            setStatus("跳过滑动")
            return
        }
        setStatus("滑动 ($x1,$y1)->($x2,$y2)")
        scope.launch {
            val steps = 30
            for (i in 0..steps) {
                showGestureDot(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                delay(50)
            }
            handler.postDelayed({ hideGestureDot() }, 900)
        }
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 500)
        logLine("swipe($x1,$y1 -> $x2,$y2)")
        handler.post {
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    logLine("swipe 完成")
                    setStatus("滑动: 完成")
                }
                override fun onCancelled(g: GestureDescription?) {
                    logErr("swipe 被取消/未执行 ($x1,$y1 -> $x2,$y2)")
                    setStatus("滑动: 失败($x1,$y1)->($x2,$y2)")
                }
            }
            instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, handler)
        }
    }

    private var debugDot: android.view.View? = null

    /** 直线匀速滑动，可指定时长（轨迹圆点按时长均匀分布） */
    private fun swipeWithDuration(from: FloatArray, to: FloatArray, durationMs: Long) {
        if (!running) return
        val (x1, y1) = abs(from)
        val (x2, y2) = abs(to)
        if (x1 == x2 && y1 == y2) return
        setStatus("滑动 ($x1,$y1)->($x2,$y2) ${durationMs}ms")
        val steps = 30
        scope.launch {
            val stepDelay = (durationMs / steps).coerceAtLeast(16L)
            for (i in 0..steps) {
                showGestureDot(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                delay(stepDelay)
            }
            handler.postDelayed({ hideGestureDot() }, 900)
        }
        val path = android.graphics.Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        logLine("swipe ${durationMs}ms ($x1,$y1 -> $x2,$y2)")
        handler.post {
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    logLine("swipe 完成")
                    setStatus("滑动: 完成")
                }
                override fun onCancelled(g: GestureDescription?) {
                    logErr("swipe 被取消/未执行 ($x1,$y1 -> $x2,$y2)")
                    setStatus("滑动: 失败($x1,$y1)->($x2,$y2)")
                }
            }
            instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, handler)
        }
    }

    private fun showGestureDot(x: Int, y: Int) {
        handler.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                if (debugDot == null) {
                    val size = (20 * resources.displayMetrics.density).toInt()
                    val dotDensity = resources.displayMetrics.density
                    val v = android.view.View(this).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(0xFFFF1744.toInt())
                            setStroke((2 * dotDensity).toInt(), 0xFFFFFFFF.toInt())
                        }
                    }
                    val lp = android.view.WindowManager.LayoutParams(
                        size, size,
                        if (android.os.Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.graphics.PixelFormat.TRANSLUCENT
                    ).also {
                        it.gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            it.layoutInDisplayCutoutMode =
                                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        }
                        it.x = x - size / 2
                        it.y = y - size / 2
                    }
                    wm.addView(v, lp)
                    debugDot = v
                } else {
                    val lp = debugDot!!.layoutParams as android.view.WindowManager.LayoutParams
                    lp.x = x - lp.width / 2
                    lp.y = y - lp.height / 2
                    wm.updateViewLayout(debugDot, lp)
                }
            } catch (_: Exception) {}
        }
    }
    private fun hideGestureDot() {
        handler.post {
            try {
                debugDot?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
            } catch (_: Exception) {}
            debugDot = null
        }
    }

    private fun firmTap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        setStatus("重压点击 ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 900)
        val path = android.graphics.Path().apply {
            moveTo((x - 1.5f).toFloat(), y.toFloat())
            lineTo((x + 1.5f).toFloat(), y.toFloat())
        }
        fun dispatch(attempt: Int) {
            val stroke = GestureDescription.StrokeDescription(path, 0, 150)
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { logLine("firmTap($x,$y) 完成") }
                override fun onCancelled(g: GestureDescription?) {
                    if (attempt < 3) handler.postDelayed({ dispatch(attempt + 1) }, 400)
                    else setStatus("重压点击($x,$y) 多次被取消")
                }
            }
            instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, handler)
        }
        handler.post { dispatch(1) }
    }

    private fun silentTap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 0.5f, y.toFloat() + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        handler.post {
            try {
                instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, handler)
            } catch (e: Exception) {
                logLine("手势发送失败: " + e.message)
            }
        }
        logLine("silentTap($x,$y)")
    }

    private fun pureTap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo((x + 1f).toFloat(), (y + 1f).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        logLine("pureTap($x,$y)")
    }

    private fun wiggleTap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        setStatus("抖动点击 ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 900)
        val path = android.graphics.Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo((x + 2f).toFloat(), (y - 1f).toFloat())
            lineTo((x - 2f).toFloat(), (y + 1f).toFloat())
            lineTo((x + 1f).toFloat(), (y + 2f).toFloat())
            lineTo((x - 1f).toFloat(), (y - 2f).toFloat())
            lineTo(x.toFloat(), y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 600)
        handler.post {
            try {
                instance?.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, handler)
            } catch (e: Exception) {
                logLine("手势发送失败: " + e.message)
            }
        }
        logLine("wiggleTap($x,$y)")
    }

    private fun continuousTap(p: FloatArray) {
        if (!running) return
        val (x, y) = abs(p)
        setStatus("分段点击 ($x,$y)")
        showGestureDot(x, y)
        handler.postDelayed({ hideGestureDot() }, 1200)
        val down = GestureDescription.StrokeDescription(
            android.graphics.Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo((x + 1f).toFloat(), (y + 1f).toFloat())
            }, 0, 200, true
        )
        val cont = down.continueStroke(
            android.graphics.Path().apply {
                moveTo((x + 1f).toFloat(), (y + 1f).toFloat())
                lineTo((x - 1f).toFloat(), (y - 1f).toFloat())
                lineTo(x.toFloat(), y.toFloat())
            }, 0, 200, false
        )
        handler.post {
            instance?.dispatchGesture(
                GestureDescription.Builder().addStroke(down).build(), null, handler
            )
            handler.postDelayed({
                instance?.dispatchGesture(
                    GestureDescription.Builder().addStroke(cont).build(), null, handler
                )
            }, 180)
        }
        logLine("continuousTap($x,$y)")
    }

    private fun tryNodeClick(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: run {
            logLine("节点树为空，无法节点点击")
            return false
        }
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            visited++
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.contains(x, y)) {
                if (node.isClickable) {
                    val ok = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    logLine("节点点击($x,$y): $ok 节点=${node.viewIdResourceName ?: "?"} bounds=$r")
                    return ok
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        logLine("节点树遍历 $visited 个节点，未找到($x,$y)的可点击节点")
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        iconView?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        iconView = null
        panelView?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        logScroll?.let { (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).removeView(it) }
        logScroll = null
        logView = null
        projection?.stop()
        lastFrame?.recycle()
        lastFrame = null
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }
}
