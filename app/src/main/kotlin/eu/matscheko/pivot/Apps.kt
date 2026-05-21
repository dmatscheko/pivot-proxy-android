package eu.matscheko.pivot

import android.content.Context
import android.content.pm.PackageManager

/** A user-facing app entry for the per-app capture picker. */
data class AppInfo(val packageName: String, val label: String)

object Apps {

    /**
     * Installed apps that hold the INTERNET permission (the only ones whose traffic a
     * capture filter is meaningful for), sorted by display label. Our own package is
     * excluded — it must always stay on the VPN.
     */
    fun networkApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val self = context.packageName
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return packages.asSequence()
            .filter { it.packageName != self }
            .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
            .map { pkg ->
                val label = pkg.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
                    ?: pkg.packageName
                AppInfo(pkg.packageName, label)
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
