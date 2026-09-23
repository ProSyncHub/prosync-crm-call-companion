package com.prosync.crmcompanion

object PhoneUtil {
    fun digitsOnly(value: String?): String = value.orEmpty().filter(Char::isDigit)

    fun normalizeIndia(value: String?): String {
        val digits = digitsOnly(value)
        return when {
            digits.length == 10 -> "91$digits"
            digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
            digits.length == 12 && digits.startsWith("91") -> digits
            else -> digits
        }
    }

    fun sameNumber(a: String?, b: String?): Boolean {
        val na = normalizeIndia(a)
        val nb = normalizeIndia(b)
        return na.isNotBlank() && na == nb
    }
}
