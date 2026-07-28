package com.example.cachecleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var summaryText: TextView
    private lateinit var guidedButton: Button
    private lateinit var autoClickButton: Button
    private lateinit var adapter: AppListAdapter

    private var appList = mutableListOf<AppCacheInfo>()

    // Guided-clean walkthrough state
    private var guidedMode = false
    private var guidedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        summaryText = findViewById(R.id.summaryText)
        guidedButton = findViewById(R.id.guidedCleanButton)
        autoClickButton = findViewById(R.id.autoClickButton)
        autoClickButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        adapter = AppListAdapter(appList) { item, position ->
            openAppInfo(item.packageName)
            guidedMode = false
            AutoClickState.enabled = false
            guidedIndex = position
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        guidedButton.setOnClickListener { startGuidedClean() }

        if (!hasUsageAccess()) {
            showUsageAccessDialog()
        } else {
            loadApps()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutoClickButtonLabel()

        // Returning from Settings > App Info (or from Usage Access / Accessibility settings).
        if (appList.isEmpty() && hasUsageAccess()) {
            loadApps()
            return
        }
        if (guidedIndex >= 0 && guidedIndex < appList.size) {
            adapter.markVisited(guidedIndex)
            if (guidedMode) {
                advanceGuidedClean()
            }
        }
    }

    // ---- Accessibility service status (drives the auto-click behavior) ----

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${CacheClearAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        for (service in splitter) {
            if (service.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun updateAutoClickButtonLabel() {
        autoClickButton.text = if (isAccessibilityServiceEnabled()) {
            getString(R.string.auto_click_enabled)
        } else {
            getString(R.string.enable_auto_click)
        }
    }

    // ---- Usage access (special permission, no runtime dialog exists for it) ----

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun showUsageAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.grant_usage_access))
            .setMessage(getString(R.string.usage_access_explanation))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.grant_usage_access)) { _, _ ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            .show()
    }

    // ---- Scan installed apps + their cache sizes ----

    private fun loadApps() {
        summaryText.text = getString(R.string.scanning)
        lifecycleScope.launch {
            val scanned = withContext(Dispatchers.IO) { scanInstalledApps() }
            appList.clear()
            appList.addAll(scanned)
            adapter.updateList(appList)

            val totalBytes = scanned.sumOf { it.cacheBytes }
            summaryText.text = "${scanned.size} apps found • ${
                AppCacheInfo("", "", null, totalBytes).formattedSize()
            } of cache total. Tap an app to clear its cache, or use guided mode below."
            guidedButton.isEnabled = scanned.isNotEmpty()
        }
    }

    private fun scanInstalledApps(): List<AppCacheInfo> {
        val pm = packageManager
        val storageStatsManager = getSystemService(STORAGE_STATS_SERVICE) as StorageStatsManager
        val storageManager = getSystemService(STORAGE_SERVICE) as StorageManager

        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
        val result = mutableListOf<AppCacheInfo>()

        for (appInfo in apps) {
            if (appInfo.packageName == packageName) continue // skip ourselves

            var cacheBytes = 0L
            try {
                val uuid = storageManager.getUuidForPath(getExternalFilesDir(null) ?: filesDir)
                val stats = storageStatsManager.queryStatsForUid(uuid, appInfo.uid)
                cacheBytes = stats.cacheBytes
            } catch (e: Exception) {
                // Some system packages throw SecurityException/NameNotFoundException; skip size, keep entry.
            }

            result.add(
                AppCacheInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null },
                    cacheBytes = cacheBytes
                )
            )
        }

        return result.sortedByDescending { it.cacheBytes }
    }

    // ---- Navigate to each app's App Info > Storage screen ----

    private fun openAppInfo(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open settings for $packageName", Toast.LENGTH_SHORT).show()
        }
    }

    /** Same as [openAppInfo], but also tells the accessibility service that a
     *  brand-new App Info screen is about to open, so it restarts its
     *  Storage -> Clear cache click sequence from the top for this app. */
    private fun openAppInfoForGuidedStep(packageName: String) {
        if (AutoClickState.enabled) {
            AutoClickState.resetForNewApp = true
        }
        openAppInfo(packageName)
    }

    // ---- Guided walkthrough: auto-advance to the next app after each visit ----

    private fun startGuidedClean() {
        if (appList.isEmpty()) return
        guidedMode = true
        guidedIndex = 0

        val autoClickOn = isAccessibilityServiceEnabled()
        AutoClickState.enabled = autoClickOn
        Toast.makeText(
            this,
            if (autoClickOn)
                getString(R.string.auto_click_enabled)
            else
                "Tap Storage, then 'Clear cache', then go Back twice — the next app opens automatically.",
            Toast.LENGTH_LONG
        ).show()
        openAppInfoForGuidedStep(appList[guidedIndex].packageName)
    }

    private fun advanceGuidedClean() {
        guidedIndex++
        if (guidedIndex >= appList.size) {
            guidedMode = false
            AutoClickState.enabled = false
            guidedIndex = -1
            Toast.makeText(this, "Done — walked through every app.", Toast.LENGTH_LONG).show()
            return
        }
        recyclerView.scrollToPosition(guidedIndex)
        openAppInfoForGuidedStep(appList[guidedIndex].packageName)
    }
}
