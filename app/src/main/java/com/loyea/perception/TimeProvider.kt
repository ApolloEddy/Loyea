package com.loyea.perception

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TimeProvider {
    fun getCurrentTimeFormatted(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    fun getDayOfWeek(): String {
        val sdf = SimpleDateFormat("EEEE", Locale.getDefault())
        return sdf.format(Date())
    }
}
