package com.jmf.autohook

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val projectionRequest = 100
    private val notificationRequest = 101
    // 下拉框初始化/程序化设置时，忽略 onItemSelected 回调，避免把已选地图写回默认值
    private var suppressAfkWrite = false
    // root 探测结果缓存（只探一次）
    @Volatile private var rootProbeDone = false
    @Volatile private var rootAvailable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tvVersion).text =
            "版本 " + packageManager.getPackageInfo(packageName, 0).versionName
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val etInterval = findViewById<EditText>(R.id.etInterval)
        if (!prefs.getBoolean("intervalMigrated300", false)) {
            if (prefs.getInt("interval", 300) == 15) prefs.edit().putInt("interval", 300).apply()
            prefs.edit().putBoolean("intervalMigrated300", true).apply()
        }
        if (!prefs.getBoolean("captureResMigratedAuto", false)) {
            prefs.edit().putString("captureRes", "自动跟随屏幕").putBoolean("captureResMigratedAuto", true).apply()
        }
        etInterval.setText(prefs.getInt("interval", 300).toString())
        val etIntervalBuy = findViewById<EditText>(R.id.etIntervalBuy)
        etIntervalBuy.setText(prefs.getInt("intervalBuy", 60).toString())
        val etIntervalDecomp = findViewById<EditText>(R.id.etIntervalDecomp)
        etIntervalDecomp.setText(prefs.getInt("intervalDecompose", 24).toString())
        val cbDecompose = findViewById<android.widget.CheckBox>(R.id.cbDecompose)
        cbDecompose.isChecked = prefs.getBoolean("enableDecompose", true)
        val cbBuy = findViewById<android.widget.CheckBox>(R.id.cbBuy)
        cbBuy.isChecked = prefs.getBoolean("enableBuy", true)

        val btnProjection = findViewById<Button>(R.id.btnProjection)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val spRole = findViewById<android.widget.Spinner>(R.id.spRole)
        val roles = listOf("队长", "队员")
        spRole.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roles)
        spRole.setSelection(prefs.getString("role", "队长")?.let { roles.indexOf(it).coerceAtLeast(0) } ?: 0)
        spRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("role", roles[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 挂机地图选择
        val spAfk = findViewById<android.widget.Spinner>(R.id.spAfkMap)
        val afkMaps = HookService.afkProfiles().map { it.label }
        spAfk.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, afkMaps)
        val savedAfk = prefs.getString("afkProfile", null)
        val afkIndex = if (savedAfk != null && afkMaps.indexOf(savedAfk) >= 0) afkMaps.indexOf(savedAfk) else 0
        suppressAfkWrite = true
        spAfk.setSelection(afkIndex, false)
        spAfk.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (suppressAfkWrite) return
                prefs.edit().putString("afkProfile", afkMaps[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spAfk.postDelayed({ suppressAfkWrite = false }, 500)

        // 截屏分辨率选择（默认 720*1280）
        val spCapture = findViewById<android.widget.Spinner>(R.id.spCapture)
        val capResList = listOf("720*1280", "自动跟随屏幕")
        spCapture.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, capResList)
        val savedCap = prefs.getString("captureRes", "自动跟随屏幕") ?: "自动跟随屏幕"
        spCapture.setSelection(capResList.indexOf(savedCap).coerceAtLeast(0), false)
        spCapture.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("captureRes", capResList[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnProjection.setOnClickListener {
            if (HookService.running) {
                HookService.stop()
                btnProjection.text = "启动监测"
                tvStatus.text = "状态：已停止"
                return@setOnClickListener
            }
            saveCfg(etInterval)
            saveIntervalBuy(etIntervalBuy)
            saveIntervalDecomp(etIntervalDecomp)
            prefs.edit().putBoolean("enableDecompose", cbDecompose.isChecked).apply()
            prefs.edit().putBoolean("enableBuy", cbBuy.isChecked).apply()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
                return@setOnClickListener
            }
            askProjection()
        }
        findViewById<Button>(R.id.btnStop).visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        val btnProjection = findViewById<Button>(R.id.btnProjection)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val spRole = findViewById<android.widget.Spinner>(R.id.spRole)
        val savedRole = prefs.getString("role", "队长")
        val roles = listOf("队长", "队员")
        if (roles.indexOf(savedRole) >= 0 && spRole.selectedItemPosition != roles.indexOf(savedRole)) {
            spRole.setSelection(roles.indexOf(savedRole), false)
        }
        // 面板里改过地图后，回到主页同步显示（不写回配置）
        val spAfk = findViewById<android.widget.Spinner>(R.id.spAfkMap)
        val afkMaps = HookService.afkProfiles().map { it.label }
        val savedAfk = prefs.getString("afkProfile", null)
        val afkIndex = if (savedAfk != null) afkMaps.indexOf(savedAfk) else -1
        if (afkIndex >= 0 && spAfk.selectedItemPosition != afkIndex) {
            suppressAfkWrite = true
            spAfk.setSelection(afkIndex, false)
            spAfk.postDelayed({ suppressAfkWrite = false }, 500)
        }
        if (HookService.running) {
            btnProjection.text = "结束监测"
            tvStatus.text = "状态：监控中"
        } else {
            btnProjection.text = "启动监测"
        }
    }

    private fun saveCfg(etInterval: EditText) {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putInt("interval", etInterval.text.toString().toIntOrNull() ?: 300)
            .apply()
    }

    private fun saveIntervalBuy(et: EditText) {
        val v = (et.text.toString().toIntOrNull() ?: 60).coerceAtLeast(1)
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putInt("intervalBuy", v).apply()
    }

    private fun saveIntervalDecomp(et: EditText) {
        val v = (et.text.toString().toIntOrNull() ?: 24).coerceIn(1, 24)
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putInt("intervalDecompose", v).apply()
    }

    private fun askProjection() {
        if (Settings.canDrawOverlays(this) && isAccessibilityEnabledNow()) {
            afterPermissionsReady()
            return
        }
        Toast.makeText(this, "正在通过 root 开启无障碍/悬浮窗…", Toast.LENGTH_SHORT).show()
        Thread {
            val rootOk = RootShell.ensure()
            if (rootOk) {
                RootShell.exec("appops set $packageName SYSTEM_ALERT_WINDOW allow")
                val me = "$packageName/$packageName.HookService"
                val cur = RootShell.exec("settings get secure enabled_accessibility_services")?.trim().orEmpty()
                val list = if (cur.isEmpty() || cur == "null") mutableListOf<String>() else cur.split(":").toMutableList()
                if (!list.contains(me)) list.add(me)
                RootShell.exec("settings put secure enabled_accessibility_services '${list.joinToString(":")}'")
                RootShell.exec("settings put secure accessibility_enabled 1")
            }
            // 系统绑定无障碍服务需要几秒，最多等 15 秒
            val deadline = System.currentTimeMillis() + 15_000
            while (rootOk && System.currentTimeMillis() < deadline && !isAccessibilityEnabledNow()) {
                Thread.sleep(500)
            }
            runOnUiThread {
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "悬浮窗未授权，请手动授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                    return@runOnUiThread
                }
                if (!isAccessibilityEnabledNow()) {
                    Toast.makeText(this, "无障碍未授权，请手动开启无障碍服务", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@runOnUiThread
                }
                afterPermissionsReady()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 无障碍是否已启用：系统列表里有，或设置项里已写入（服务尚未绑定时也能认） */
    private fun isAccessibilityEnabledNow(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val inList = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id?.startsWith(packageName) == true }
        if (inList) return true
        return try {
            val v = Settings.Secure.getString(contentResolver, "enabled_accessibility_services")
            v != null && v.contains(packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** 权限就绪：有 root 走 root 截图，没有就走录屏授权 */
    private fun afterPermissionsReady() {
        probeRoot { ok ->
            if (ok) {
                HookService.startRoot(this)
                findViewById<Button>(R.id.btnProjection).text = "结束监测"
                findViewById<TextView>(R.id.tvStatus).text = "状态：监控中"
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } else {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), projectionRequest)
            }
        }
    }

    /** 探测 root：复用同一个常驻会话，只有第一次会真正申请授权；回调在主线程 */
    private fun probeRoot(onResult: (Boolean) -> Unit) {
        if (rootProbeDone) {
            onResult(rootAvailable)
            return
        }
        Thread {
            val ok = RootShell.ensure()
            rootAvailable = ok
            // 只缓存成功：失败（含授权超时被自动拒绝）就等下次点击再给一次机会
            rootProbeDone = ok
            runOnUiThread { onResult(ok) }
        }.apply { isDaemon = true }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequest && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) askProjection()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest && resultCode == Activity.RESULT_OK && data != null) {
            HookService.start(this, resultCode, data)
            findViewById<Button>(R.id.btnProjection).text = "结束监测"
            findViewById<TextView>(R.id.tvStatus).text = "状态：监控中"
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
    }
}
