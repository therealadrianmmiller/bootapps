package com.adrianmmiller.bamina

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

private const val TAG = "BootApps"

class BootAppsRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // Broadcast actions that commonly trigger "start on boot" behavior.
    private val bootActions = listOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.LOCKED_BOOT_COMPLETED",
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON"
    )

    /**
     * Scans for every third-party app that has a receiver resolving for a boot
     * broadcast, by asking the system directly (queryBroadcastReceivers per
     * action, system-wide) rather than manually walking each package's manifest
     * receiver list — the latter doesn't reliably populate on every API level.
     *
     * Requires the QUERY_ALL_PACKAGES permission (or a <queries> block) on
     * Android 11+ to see receivers belonging to packages you haven't interacted
     * with; without it, results silently come back empty.
     */
    fun scanBootApps(): List<BootAppInfo> {
        // packageName -> set of receiver class names that matched a boot action
        val grouped = LinkedHashMap<String, MutableSet<String>>()

        for (action in bootActions) {
            val intent = Intent(action)
            @Suppress("DEPRECATION")
            val matches = pm.queryBroadcastReceivers(
                intent,
                PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
            )
            Log.d(TAG, "action=$action matched ${matches.size} receiver(s)")
            for (resolveInfo in matches) {
                val info = resolveInfo.activityInfo ?: continue
                grouped.getOrPut(info.packageName) { mutableSetOf() }.add(info.name)
            }
        }
        Log.d(TAG, "total distinct packages with a boot receiver: ${grouped.size}")

        val results = mutableListOf<BootAppInfo>()
        for ((packageName, receiverClasses) in grouped) {
            if (packageName == context.packageName) continue // skip self

            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                continue
            }

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) continue

            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName
            }

            val anyReceiverEnabled = receiverClasses.any { isComponentEnabled(packageName, it) }

            results.add(
                BootAppInfo(
                    packageName = packageName,
                    appLabel = label,
                    receiverClasses = receiverClasses.toList(),
                    receiversEnabled = anyReceiverEnabled
                )
            )
        }
        Log.d(TAG, "third-party (non-system) results after filtering: ${results.size}")

        return results.sortedBy { it.appLabel.lowercase() }
    }

    // ---------- Disable / enable ----------

    sealed class ApplyResult {
        data class Success(val packageName: String) : ApplyResult()
        data class RequiresManualAction(val packageName: String) : ApplyResult()
        data class Failed(val packageName: String, val reason: String) : ApplyResult()
    }

    /**
     * Disables (or re-enables) ONLY the boot-listening receiver components of each
     * given app — the rest of the app is left completely untouched, same as what
     * AutoStarts-style tools do. Requires root: a component belonging to another
     * package can't be toggled without bypassing CHANGE_COMPONENT_ENABLED_STATE,
     * which `su` does by running as UID 0.
     */
    fun applyEnabledState(apps: List<BootAppInfo>, enable: Boolean): List<ApplyResult> {
        val rootAvailable = isRootAvailable()
        return apps.map { app ->
            if (!rootAvailable) {
                return@map ApplyResult.RequiresManualAction(app.packageName)
            }
            val allOk = app.receiverClasses.all { className ->
                setComponentEnabledViaRoot(app.packageName, className, enable)
            }
            if (allOk) ApplyResult.Success(app.packageName)
            else ApplyResult.Failed(app.packageName, "One or more `pm disable`/`pm enable` calls failed")
        }
    }

    fun openAppInfoScreen(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Runs a command as root by routing it through the system shell (`sh -c`)
     * rather than exec'ing `su` directly — Runtime.exec() does a raw execve()
     * against the JVM process's own PATH, which often doesn't include wherever
     * Magisk/KernelSU actually place `su`. The shell resolves PATH the same way
     * an interactive `adb shell` session would, which is what root managers hook.
     */
    private fun runAsRoot(innerCommand: String): Triple<Int, String, String> {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "su -c '$innerCommand'"))
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
        val exitCode = process.waitFor()
        return Triple(exitCode, stdout, stderr)
    }

    private fun isRootAvailable(): Boolean {
        return try {
            val (exitCode, stdout, stderr) = runAsRoot("id")
            val granted = exitCode == 0 && stdout.contains("uid=0")
            Log.d(TAG, "root check: exitCode=$exitCode stdout='${stdout.trim()}' stderr='${stderr.trim()}' -> granted=$granted")
            granted
        } catch (e: Exception) {
            Log.e(TAG, "root check threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Toggles a single component's enabled-state flag via `pm disable`/`pm enable`,
     * targeting `<package>/<fully-qualified-class>` — this is component-level, so
     * nothing else in the app is affected.
     */
    private fun setComponentEnabledViaRoot(
        packageName: String,
        className: String,
        enable: Boolean
    ): Boolean {
        val target = "$packageName/$className"
        val cmd = if (enable) "pm enable $target" else "pm disable $target"
        return try {
            val (exitCode, stdout, stderr) = runAsRoot(cmd)
            val ok = exitCode == 0
            Log.d(TAG, "cmd='$cmd' exitCode=$exitCode stdout='${stdout.trim()}' stderr='${stderr.trim()}' -> ok=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "cmd='$cmd' threw: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Reads a component's current enabled state directly (works without root —
     * COMPONENT_ENABLED_STATE_DEFAULT is treated as enabled, matching manifest default).
     */
    private fun isComponentEnabled(packageName: String, className: String): Boolean {
        return try {
            val component = ComponentName(packageName, className)
            val state = pm.getComponentEnabledSetting(component)
            state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        } catch (e: Exception) {
            true
        }
    }

    // ---------- Import / export ----------

    /**
     * Exports the packages that are CURRENTLY DISABLED (their boot receiver is off) —
     * i.e. the live block list, independent of whatever's checked in the UI right now.
     * Call this with the Uri obtained from ACTION_CREATE_DOCUMENT.
     */
    fun exportDisabledList(uri: Uri, apps: List<BootAppInfo>) {
        val disabled = apps.filter { !it.receiversEnabled }.map { it.packageName }
        val json = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("disabledPackages", JSONArray(disabled))
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray())
        }
    }

    /**
     * Reads a previously exported block list and returns the package names it contains.
     * Call this with the Uri obtained from ACTION_OPEN_DOCUMENT.
     */
    fun readDisabledListFromFile(uri: Uri): Set<String> {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        } ?: return emptySet()

        val json = JSONObject(text)
        // Back-compat: older exports used the key "packages" for the checked selection.
        val arr = json.optJSONArray("disabledPackages") ?: json.optJSONArray("packages") ?: JSONArray()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.getString(i))
        }
        return out
    }
}
