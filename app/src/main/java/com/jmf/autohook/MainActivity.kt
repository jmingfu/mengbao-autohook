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
        if (savedAfk != null && afkMaps.indexOf(savedAfk) >= 0) {
            spAfk.setSelection(afkMaps.indexOf(savedAfk), false)
        } else {
            spAfk.setSelection(0, false)
        }
        spAfk.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("afkProfile", afkMaps[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

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

    private fun askProjection() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            return
        }
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.startsWith(packageName) }
        if (!enabled) { Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show(); return }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), projectionRequest)
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
