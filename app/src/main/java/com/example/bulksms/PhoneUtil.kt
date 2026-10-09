package com.example.bulksms

object PhoneUtil {
    fun normalize(p: String): String {
        val sb = StringBuilder()
        for (ch in p) {
            when (ch) {
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '0'..'9', '+' -> sb.append(ch)
                else -> {}
            }
        }
        return sb.toString()
    }
}
