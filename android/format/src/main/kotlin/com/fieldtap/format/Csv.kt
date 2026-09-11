package com.fieldtap.format

/** Cell rules shared by every CSV in a session. Blank means unknown, never "null". */
object Csv {
    fun cell(value: Int?): String = value?.toString() ?: ""
}
