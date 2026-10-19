package com.phonemcp.core

import android.content.Context

/**
 * Editable blacklist of app packages. While a blacklisted app is in the
 * foreground, every tool refuses to read the screen or act. Seeded with
 * banks / payments / government / password managers.
 */
object Blacklist {
    private const val PREFS = "phone_mcp"
    private const val KEY = "blacklist"

    // A reasonable default set for RU + global. The user can edit this list in-app.
    private val DEFAULTS = linkedSetOf(
        // Password managers
        "com.google.android.gms.auth.otp", "com.authy.authy",
        "com.bitwarden.authenticator", "com.x8bit.bitwarden",
        "com.agilebits.onepassword", "com.lastpass.lpandroid",
        "org.kde.kdeconnect_tp.keepass", "com.keepassdroid",
        "com.kunzisoft.keepass.libre", "com.google.android.apps.authenticator2",
        // Government / ID (RU)
        "ru.gosuslugi.goverment", "ru.rtlabs.ebs.esia", "ru.fns.billing",
        "ru.nalog.app", "ru.gibdd.app",
        // Banks (RU, common ones)
        "ru.sberbankmobile", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "com.idamob.tinkoff.android",
        "ru.raiffeisennews", "ru.gazprombank.android.mobilebank.app",
        "ru.bank_hlynov.ibank", "ru.rshb.dbo", "ru.pochtabank",
        // Payments / wallets (global)
        "com.google.android.apps.walletnfcrel", "com.paypal.android.p2pmobile",
        "com.revolut.revolut", "com.wise.android", "com.coinbase.android",
        "com.binance.dev", "io.metamask"
    )

    fun get(context: Context): MutableSet<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) {
            prefs.edit().putStringSet(KEY, DEFAULTS).apply()
            return DEFAULTS.toMutableSet()
        }
        return prefs.getStringSet(KEY, DEFAULTS)?.toMutableSet() ?: DEFAULTS.toMutableSet()
    }

    fun set(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, packages).apply()
    }

    fun add(context: Context, pkg: String) {
        val set = get(context)
        set.add(pkg.trim())
        set(context, set)
    }

    fun remove(context: Context, pkg: String) {
        val set = get(context)
        set.remove(pkg.trim())
        set(context, set)
    }

    fun isBlocked(context: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return get(context).contains(pkg)
    }
}
