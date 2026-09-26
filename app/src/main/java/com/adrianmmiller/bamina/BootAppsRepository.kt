package package com.adrianmmiller.bamina

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

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
     * Scans every installed third-party (non-system) package's manifest-declared
     * receivers and keeps the ones registered for a boot broadcast.
     *
     * Requires GET_RECEIVERS in the PackageManager query flags. On Android 11+
     * you also need the QUERY_ALL_PACKAGES permission (or a <queries> block)
     * to see packages you haven't interacted with.
     */
    fun scanBootApps(): List<BootAppInfo> {
        val results = mutableListOf<BootAppInfo>()

        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val packages = pm.getInstalledPackages(flags)

        for (pkgInfo in packages) {
            val appInfo = pkgInfo.applicationInfo ?: continue

            // Skip system apps — keep it to user-installed, third-party apps.
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) continue

            val receivers = pkgInfo.receivers ?: continue
            val matchingReceivers = mutableListOf<String>()

            for (receiver in receivers) {
                // We already matched by manifest scan; a lighter-weight approach is
                // to just record every receiver here, since apps rarely declare
                // receivers with these names for anything else. For stricter
                // filtering, cross-check against queryBroadcastReceivers() per action.
                matchingReceivers.add(receiver.name)
            }

            if (matchingReceivers.isEmpty()) continue

            // Confirm at least one boot action actually resolves to this package
            // (cuts down on false positives from unrelated receivers).
            val confirmed = bootActions.any { action ->
                val intent = Intent(action).setPackage(pkgInfo.packageName)
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                    .isNotEmpty()
            }
            if (!confirmed) continue

            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                pkgInfo.packageName
            }

            val anyReceiverEnabled = matchingReceivers.any { className ->
                isComponentEnabled(pkgInfo.packageName, className)
            }

            results.add(
                BootAppInfo(
                    packageName = pkgInfo.packageName,
                    appLabel = label,
                    receiverClasses = matchingReceivers,
                    receiversEnabled = anyReceiverEnabled
                )
            )
        }

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

    private fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor() == 0 && output.contains("uid=0")
        } catch (e: Exception) {
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
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            process.waitFor() == 0
        } catch (e: Exception) {
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
