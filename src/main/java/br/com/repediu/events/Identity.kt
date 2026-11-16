package br.com.repediu.events

private const val DEFAULT_COUNTRY_CODE = "55"
private const val PHONE_ID_PREFIX = "rpd:tel:"
private const val EMAIL_ID_PREFIX = "rpd:mail:"

internal fun normalizePhone(phone: String?): String? {
    val digits = phone?.filter { it in '0'..'9' } ?: return null
    return when (digits.length) {
        10, 11 -> "$DEFAULT_COUNTRY_CODE$digits"
        12, 13 -> digits
        else -> null
    }
}

internal fun hashId(value: String): String {
    var h1 = 0x811c9dc5.toInt()
    var h2 = 0x01000193
    for (char in value) {
        h1 = (h1 xor char.code) * 0x01000193
        h2 = (h2 xor char.code) * 0x85ebca6b.toInt()
    }
    return hex32(h1) + hex32(h2)
}

internal fun deriveUserId(userId: String? = null, phone: String? = null, email: String? = null): String? {
    val trimmedId = userId?.trim().orEmpty()
    if (trimmedId.isNotEmpty()) return trimmedId

    normalizePhone(phone)?.let { return PHONE_ID_PREFIX + hashId(it) }

    val trimmedEmail = email?.trim().orEmpty()
    if (trimmedEmail.isNotEmpty()) return EMAIL_ID_PREFIX + hashId(trimmedEmail.lowercase())

    return null
}

private fun hex32(value: Int) = Integer.toHexString(value).padStart(8, '0')
