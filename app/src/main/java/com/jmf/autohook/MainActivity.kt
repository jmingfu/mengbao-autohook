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
        // 预热 root 探测：有 root 时点开始监测不再弹录屏授权
        probeRoot { }
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
        tryRootEnableIfNeeded()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "悬浮窗未授权，且 root 授权失败，请手动授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            return
        }
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.startsWith(packageName) }
        if (!enabled) { Toast.makeText(this, "无障碍未授权，且 root 授权失败，请手动开启无障碍服务", Toast.LENGTH_LONG).show(); return }
        // 有 root 就走 root 截图，不弹"立即开始录制"授权框
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

    /** 探测一次 root（su -c id），结果缓存；回调一定在主线程 */
    private fun probeRoot(onResult: (Boolean) -> Unit) {
        if (rootProbeDone) {
            onResult(rootAvailable)
            return
        }
        Thread {
            val ok = try {
                val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                // su 弹窗没点的话会一直挂住，最多等 2.5 秒
                val finished = p.waitFor(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                val out = if (finished) p.inputStream.bufferedReader().readText() else ""
                if (!finished) p.destroy()
                out.contains("uid=0")
            } catch (_: Exception) {
                false
            }
            rootAvailable = ok
            rootProbeDone = true
            runOnUiThread { onResult(ok) }
        }.apply { isDaemon = true }.start()
    }

    /** 用 root 直接写系统设置，自动开启无障碍服务和悬浮窗权限（云手机/已 root 设备适用） */
    private fun tryRootEnableIfNeeded() {
        val overlayOk = Settings.canDrawOverlays(this)
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibilityOk = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.startsWith(packageName) }
        if (overlayOk && accessibilityOk) return

        try {
            val su = Runtime.getRuntime().exec("su")
            su.outputStream.use { os ->
                // 悬浮窗：appops 直接放行
                os.write("appops set $packageName SYSTEM_ALERT_WINDOW allow\n".toByteArray())
                // 无障碍：读出已有服务列表，追加自己，不覆盖其他应用
                val d = "$"
                os.write("""current=${d}(settings get secure enabled_accessibility_services);
me=$packageName/$packageName.HookService;
case ";${d}current" in *";${d}me"*) ;; *) if [ -z "${d}current" ] || [ "${d}current" = "null" ]; then new="${d}me"; else new="${d}current:${d}me"; fi; settings put secure enabled_accessibility_services "${d}new";; esac
settings put secure accessibility_enabled 1
""".toByteArray())
                os.write("exit\n".toByteArray())
                os.flush()
            }
            su.waitFor()
            su.destroy()
            // 给系统一点时间完成服务绑定
            if (!accessibilityOk) {
                for (i in 1..10) {
                    Thread.sleep(300)
                    val ok = (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                        .any { it.id.startsWith(packageName) }
                    if (ok) break
                }
            }
        } catch (_: Exception) {
            // 无 root 或执行失败：走原有手动授权提示
        }
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
