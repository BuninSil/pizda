package com.phonemcp.mcp

/**
 * Hard-coded detection of sensitive actions, split into two tiers:
 *
 *  - SEND: sending/replying to messages. Confirmation for these is OPTIONAL
 *    (off by default) because the owner now confirms by voice through Claude.
 *  - ALWAYS: purchases, transfers, banking, calls, deletion, resets — these
 *    ALWAYS require on-device confirmation regardless of the setting.
 *
 * Matches labels on the element being tapped (RU + EN).
 */
object Sensitivity {

    // Message-send actions — gated by the "confirm sends on device" setting.
    private val SEND_KEYWORDS = listOf(
        "отправить", "отправка", "послать", "send", "reply", "ответить"
    )

    // Always-confirm actions: money, banking, calls, destructive ops.
    private val ALWAYS_KEYWORDS = listOf(
        // calls
        "позвонить", "вызов", "call", "dial", "набрать",
        // purchase / pay
        "купить", "оплатить", "оплата", "платёж", "платеж", "подтвердить заказ",
        "buy", "pay", "purchase", "checkout", "order", "subscribe", "подписаться",
        // transfers / banking
        "перевод", "перевести", "перечислить", "transfer", "remittance",
        "банк", "bank", "реквизиты", "card number", "iban",
        // delete / remove
        "удалить", "удаление", "стереть", "delete", "remove", "erase", "wipe",
        // destructive
        "сбросить", "reset", "форматировать", "format"
    )

    /** @return reason if the label is an ALWAYS-confirm action, else null. */
    fun alwaysReason(label: String?): String? = match(label, ALWAYS_KEYWORDS)

    /** @return reason if the label is a message-send action, else null. */
    fun sendReason(label: String?): String? = match(label, SEND_KEYWORDS)

    private fun match(label: String?, keywords: List<String>): String? {
        if (label.isNullOrBlank()) return null
        val l = label.lowercase()
        val hit = keywords.firstOrNull { l.contains(it) } ?: return null
        return "элемент содержит «$hit»"
    }

    /** System settings changes are always sensitive. */
    fun isSettingsPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        return pkg == "com.android.settings" || pkg.endsWith(".settings")
    }

    // Messengers where pressing Enter sends a message — treat enter as a send.
    private val MESSENGERS = setOf(
        "org.telegram.messenger", "org.thunderdog.challegram",
        "com.whatsapp", "com.whatsapp.w4b",
        "com.viber.voip", "org.thoughtcrime.securesms", // Signal
        "com.facebook.orca", "com.instagram.android",
        "com.vkontakte.android", "com.vk.im",
        "ru.ok.android", "com.google.android.apps.messaging",
        "com.discord", "com.skype.raider", "com.tencent.mm",
        "com.yandex.messenger", "im.threema.app"
    )

    fun isMessenger(pkg: String?): Boolean = pkg != null && MESSENGERS.contains(pkg)
}
