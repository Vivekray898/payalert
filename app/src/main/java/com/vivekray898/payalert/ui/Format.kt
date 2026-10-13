package com.vivekray898.payalert.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatAmount(value: Double): String = String.format(Locale.US, "%,.2f", value)

fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
