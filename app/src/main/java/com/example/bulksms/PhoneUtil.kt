package com.example.bulksms

object PhoneUtil {
    fun asciiDigits(t: String): String {
        val sb = StringBuilder()
        for (ch in t) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun normalize(p: String): String {
        val sb = StringBuilder()
        for (ch in asciiDigits(p)) {
            if (ch in '0'..'9' || ch == '+') sb.append(ch)
        }
        return sb.toString()
    }

    fun toIranMobile(raw: String): String? {
        var d = normalize(raw).filter { it in '0'..'9' }
        if (d.startsWith("0098")) d = d.substring(4)
        else if (d.startsWith("98") && d.length == 12) d = d.substring(2)
        if (d.length == 10 && d.startsWith("9")) d = "0$d"
        return if (d.length == 11 && d.startsWith("09")) d else null
    }

    fun variants(mobile: String): List<String> {
        val t = mobile.substring(1)
        return listOf(mobile, t, "+98$t", "98$t", "0098$t")
    }
}
