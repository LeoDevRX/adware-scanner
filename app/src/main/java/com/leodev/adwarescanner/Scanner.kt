package com.leodev.adwarescanner

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import java.security.KeyStore

/** One red flag found on an app. points = how much it adds to the risk score (0 = info only). */
data class Finding(val text: String, val points: Int)

data class AppReport(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val firstInstall: Long,
    val findings: List<Finding>
) {
    val score: Int get() = findings.sumOf { it.points }.coerceAtMost(100)
    val level: Level
        get() = when {
            score >= 60 -> Level.HIGH
            score >= 30 -> Level.MEDIUM
            score > 0 -> Level.LOW
            else -> Level.CLEAN
        }
}

enum class Level { HIGH, MEDIUM, LOW, CLEAN }

/** A phone-wide check (not tied to one app). */
data class DeviceCheck(val title: String, val detail: String, val warn: Boolean)

data class ScanResult(
    val apps: List<AppReport>,
    val device: List<DeviceCheck>,
    val scannedCount: Int
)

class Scanner(private val ctx: Context) {

    private val pm: PackageManager = ctx.packageManager
    private val dayMs = 24L * 60 * 60 * 1000

    private val playStore = "com.android.vending"
    private val knownStores = mapOf(
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.huawei.appmarket" to "Huawei AppGallery",
        "com.xiaomi.market" to "Xiaomi GetApps",
        "com.xiaomi.discover" to "Xiaomi GetApps",
        "com.amazon.venezia" to "Amazon Appstore",
        "com.heytap.market" to "OPPO App Market",
        "com.oppo.market" to "OPPO App Market",
        "com.bbk.appstore" to "vivo App Store",
        "com.transsion.phoenix" to "Palm Store",
        "com.google.android.apps.work.clouddpc" to "work profile"
    )
    // Installers that mean "someone opened an APK file" (sideloaded)
    private val manualInstallers = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )
    // Categories adware very often disguises itself as. Weak signal on its own.
    private val baitWords = listOf(
        "cleaner", "clean master", "booster", "speed up", "flashlight", "torch",
        "battery saver", "battery doctor", "junk", "phone cooler", "cpu cooler",
        "wallpaper", "qr scanner", "free vpn", "file manager pro", "virus cleaner"
    )

    fun scan(includeSystem: Boolean): ScanResult {
        val packages = installedPackages()
        val launcherPkgs = launcherPackages()
        val admins = activeAdmins()
        val accessibility = enabledAccessibility()
        val notifListeners = enabledNotificationListeners()
        val defaultBrowser = defaultHandler(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")))
        val defaultHome = defaultHandler(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        val now = System.currentTimeMillis()

        val reports = mutableListOf<AppReport>()
        var scanned = 0

        for (p in packages) {
            val ai = p.applicationInfo ?: continue
            if (p.packageName == ctx.packageName) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val isUpdatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !includeSystem) continue
            scanned++

            val f = mutableListOf<Finding>()
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName)
            val perms = p.requestedPermissions?.toSet() ?: emptySet()
            val userApp = !isSystem && !isUpdatedSystem

            // --- Powerful access the user has switched on ---
            if (p.packageName in admins) f += Finding("Is an active DEVICE ADMIN (can block its own uninstall)", 35)
            if (p.packageName in accessibility) f += Finding("Has ACCESSIBILITY access (can read the screen and tap for you)", 30)
            if (p.packageName in notifListeners) f += Finding("Can read all your notifications", 15)

            if ("android.permission.SYSTEM_ALERT_WINDOW" in perms) {
                when (overlayAllowed(ai.uid, p.packageName)) {
                    true -> f += Finding("Allowed to DRAW OVER other apps (how pop-up ads appear)", 25)
                    null -> f += Finding("Asks to draw over other apps (pop-up ads use this)", 10)
                    false -> {}
                }
            }

            if (userApp) {
                // --- Where it came from ---
                val installer = installerOf(p.packageName)
                if (installer == null || installer in manualInstallers) {
                    f += Finding("Not from the Play Store (installed from an APK file)", 20)
                } else if (installer != playStore) {
                    val store = knownStores[installer]
                    if (store != null) {
                        f += Finding("Installed from $store", 3)
                    } else {
                        f += Finding("Installed by another app: ${labelOf(installer)}", 15)
                    }
                }

                // --- Hiding ---
                if (p.packageName !in launcherPkgs) {
                    f += Finding("Has NO icon in the app drawer (adware often hides itself)", 20)
                }

                // --- Risky permissions ---
                if ("android.permission.REQUEST_INSTALL_PACKAGES" in perms) f += Finding("Can install other apps", 15)
                if ("android.permission.PACKAGE_USAGE_STATS" in perms) f += Finding("Asks to watch which apps you use", 8)
                if ("android.permission.RECEIVE_BOOT_COMPLETED" in perms) f += Finding("Starts itself when the phone boots", 5)
                if ("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in perms) f += Finding("Asks to never be put to sleep", 5)
                if ("android.permission.QUERY_ALL_PACKAGES" in perms) f += Finding("Lists every app on your phone", 3)
                if (perms.any { it == "android.permission.READ_SMS" || it == "android.permission.RECEIVE_SMS" }) {
                    f += Finding("Can read your SMS messages", 10)
                }
                if (ai.targetSdkVersion in 1..25) {
                    f += Finding("Built for very old Android (${ai.targetSdkVersion}), dodges newer protections", 5)
                }

                // --- Disguise / timing ---
                val lower = label.lowercase()
                baitWords.firstOrNull { lower.contains(it) }?.let {
                    f += Finding("Is a \"$it\" type app, a category adware often pretends to be", 5)
                }
                val ageDays = (now - p.firstInstallTime) / dayMs
                if (ageDays <= 14) f += Finding("Installed recently ($ageDays day${if (ageDays == 1L) "" else "s"} ago)", 8)
            }

            // --- Defaults (info only) ---
            if (p.packageName == defaultBrowser) f += Finding("Set as your default browser", if (userApp) 5 else 0)
            if (p.packageName == defaultHome && userApp) f += Finding("Set as your home screen (launcher)", 5)

            reports += AppReport(p.packageName, label, isSystem, p.firstInstallTime, f)
        }

        reports.sortWith(compareByDescending<AppReport> { it.score }.thenBy { it.label.lowercase() })
        return ScanResult(reports, deviceChecks(admins, accessibility, defaultBrowser, defaultHome), scanned)
    }

    // ---------------- phone-wide checks ----------------

    private fun deviceChecks(
        admins: Set<String>,
        accessibility: Set<String>,
        browser: String?,
        home: String?
    ): List<DeviceCheck> {
        val out = mutableListOf<DeviceCheck>()

        val userCerts = userCaCertCount()
        out += when {
            userCerts == null -> DeviceCheck("Extra security certificates", "Couldn't check", false)
            userCerts > 0 -> DeviceCheck(
                "Extra security certificates",
                "$userCerts user-installed certificate(s). These let someone read your encrypted traffic. " +
                    "Remove them in Settings > Security > Encryption & credentials > User credentials, unless you added them yourself (school/work).",
                true
            )
            else -> DeviceCheck("Extra security certificates", "None installed", false)
        }

        val adminNames = admins.map { labelOf(it) }
        out += DeviceCheck(
            "Device admin apps",
            if (adminNames.isEmpty()) "None" else adminNames.joinToString(", ") +
                ". Find My Device / work apps are normal; anything else is suspicious.",
            adminNames.any { it != "Find My Device" && !it.contains("Google", true) }
        )

        val accNames = accessibility.map { labelOf(it) }
        out += DeviceCheck(
            "Accessibility services turned on",
            if (accNames.isEmpty()) "None" else accNames.joinToString(", ") +
                ". Only keep ones you deliberately turned on.",
            accNames.isNotEmpty()
        )

        out += DeviceCheck(
            "VPN",
            if (vpnActive()) "A VPN is active right now. Fine if it's yours; otherwise an app may be routing your traffic." else "Not active",
            vpnActive()
        )

        val adb = globalInt(Settings.Global.ADB_ENABLED) == 1
        out += DeviceCheck(
            "USB debugging",
            if (adb) "ON. Turn it off in Developer options unless you're using it." else "Off",
            adb
        )

        val dev = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1
        out += DeviceCheck("Developer options", if (dev) "Enabled" else "Disabled", false)

        out += DeviceCheck("Default browser", browser?.let { labelOf(it) } ?: "None set (asks each time)", false)
        out += DeviceCheck("Home screen app", home?.let { labelOf(it) } ?: "Unknown", false)

        return out
    }

    // ---------------- helpers ----------------

    @Suppress("DEPRECATION")
    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    @Suppress("DEPRECATION")
    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        return list.mapNotNull { it.activityInfo?.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun defaultHandler(intent: Intent): String? {
        val ri: ResolveInfo? = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
        val pkg = ri?.activityInfo?.packageName ?: return null
        // "android" means the system chooser: no default set
        return if (pkg == "android") null else pkg
    }

    @Suppress("DEPRECATION")
    private fun installerOf(pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            val info = pm.getInstallSourceInfo(pkg)
            info.installingPackageName ?: info.initiatingPackageName
        } else {
            pm.getInstallerPackageName(pkg)
        }
    }.getOrNull()

    private fun activeAdmins(): Set<String> = runCatching {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    private fun enabledAccessibility(): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNullTo(out) { it.resolveInfo?.serviceInfo?.packageName }
        }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')
                ?.mapNotNullTo(out) { ComponentName.unflattenFromString(it)?.packageName }
        }
        return out
    }

    private fun enabledNotificationListeners(): Set<String> = runCatching {
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            ?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    /** true = allowed, false = denied, null = couldn't tell */
    @Suppress("DEPRECATION")
    private fun overlayAllowed(uid: Int, pkg: String): Boolean? = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, pkg)
        }
        when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_IGNORED -> false
            else -> null // MODE_DEFAULT / MODE_ERRORED: unknown
        }
    }.getOrNull()

    private fun userCaCertCount(): Int? = runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore")
        ks.load(null, null)
        ks.aliases().toList().count { it.startsWith("user:") }
    }.getOrNull()

    private fun vpnActive(): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrDefault(false)

    private fun globalInt(key: String): Int =
        runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) }.getOrDefault(0)

    private fun labelOf(pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
