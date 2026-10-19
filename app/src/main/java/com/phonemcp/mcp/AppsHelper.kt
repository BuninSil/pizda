package com.phonemcp.mcp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** open_app / list_apps support via the package manager. */
object AppsHelper {

    data class AppInfo(val label: String, val pkg: String)

    fun listLaunchable(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved.mapNotNull {
            val pkg = it.activityInfo?.packageName ?: return@mapNotNull null
            val label = it.loadLabel(pm)?.toString() ?: pkg
            AppInfo(label, pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    fun listAsJson(context: Context): JSONArray {
        val arr = JSONArray()
        for (app in listLaunchable(context)) {
            arr.put(JSONObject().put("label", app.label).put("package", app.pkg))
        }
        return arr
    }

    /**
     * Resolve a query (exact package or case-insensitive label substring) to a
     * launchable package name, WITHOUT launching it.
     */
    fun resolve(context: Context, query: String): String? {
        val pm = context.packageManager
        val q = query.trim()
        if (pm.getLaunchIntentForPackage(q) != null) return q
        val apps = listLaunchable(context)
        return (apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.pkg.contains(q, ignoreCase = true) })?.pkg
    }

    /** Launch a resolved package. @return the package, or null on failure. */
    fun launchPackage(context: Context, pkg: String): String? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return pkg
    }

    /**
     * Accepts an exact package name or a (case-insensitive) label substring.
     * @return the launched package name, or null if nothing matched / failed.
     */
    fun launch(context: Context, query: String): String? {
        val pkg = resolve(context, query) ?: return null
        return launchPackage(context, pkg)
    }
}
