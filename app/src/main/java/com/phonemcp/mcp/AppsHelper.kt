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

    /** Accepts an exact package name or a (case-insensitive) label substring. */
    fun launch(context: Context, query: String): Boolean {
        val pm = context.packageManager
        val q = query.trim()

        // Exact package first.
        pm.getLaunchIntentForPackage(q)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
            return true
        }

        val apps = listLaunchable(context)
        val match = apps.firstOrNull { it.label.equals(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.label.contains(q, ignoreCase = true) }
            ?: apps.firstOrNull { it.pkg.contains(q, ignoreCase = true) }
            ?: return false

        val launch = pm.getLaunchIntentForPackage(match.pkg) ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
        return true
    }
}
