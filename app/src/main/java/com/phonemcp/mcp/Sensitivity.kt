package com.phonemcp.mcp

/**
 * Hard-coded detection of actions that must be confirmed on the device:
 * sending messages, calls, purchases, deletion, and system-settings changes.
 * Matches labels on the element being tapped (RU + EN).
 */
object Sensitivity {

    private val KEYWORDS = listOf(
        // send
        "отправить", "послать", "send", "reply", "ответить",
        // call
        "позвонить", "вызов", "call", "dial", "набрать",
        // purchase / pay
        "купить", "оплатить", "оплата", "платёж", "платеж", "подтвердить заказ",
        "buy", "pay", "purchase", "checkout", "order", "subscribe", "подписаться",
        // delete / remove
        "удалить", "удаление", "стереть", "delete", "remove", "erase", "wipe",
        // confirm/destructive
        "подтвердить", "confirm", "сбросить", "reset", "форматировать", "format"
    )

    /** @return a short reason string if the label looks sensitive, else null. */
    fun reasonFor(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val l = label.lowercase()
        val hit = KEYWORDS.firstOrNull { l.contains(it) } ?: return null
        return "элемент содержит «$hit»"
    }

    /** System settings changes are always sensitive. */
    fun isSettingsPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg == "com.android.settings" || pkg.endsWith(".settings")
    }
}
