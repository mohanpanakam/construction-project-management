package com.panakam.construction.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A date-picker-backed text field, storing/returning the date as "yyyy-MM-dd".
 *
 * The field is read-only — tapping it (or the calendar icon) opens a Material3
 * [DatePickerDialog]. This lets a date be either:
 *  - auto-filled from OCR/receipt extraction (any parseable string is displayed as-is,
 *    and the picker falls back to today if it can't be parsed when opened), or
 *  - picked manually via the calendar widget.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true
) {
    var showPicker by remember { mutableStateOf(false) }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    if (showPicker) {
        val initialMillis = parseFlexibleDateMillis(value) ?: System.currentTimeMillis()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onValueChange(sdf.format(Date(millis)))
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = pickerState)
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = { if (enabled) showPicker = true }) {
                Icon(Icons.Filled.CalendarToday, contentDescription = "Pick date")
            }
        },
        supportingText = supportingText?.let { { Text(it, fontSize = 10.sp) } },
        singleLine = true,
        modifier = modifier
    )
}

/** Today's date formatted as "yyyy-MM-dd" — used as a sensible default value. */
fun todayAsIsoDate(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

/** Best-effort parse of common date formats (including ones OCR may extract) into epoch millis. */
private fun parseFlexibleDateMillis(text: String): Long? {
    if (text.isBlank()) return null
    val datePart = text.trim().substringBefore(" ") // strip any trailing time component
    val patterns = listOf(
        "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy",
        "dd MMM yyyy", "dd-MMM-yyyy", "dd/MMM/yyyy"
    )
    for (p in patterns) {
        runCatching {
            val sdf = SimpleDateFormat(p, Locale.getDefault())
            sdf.isLenient = false
            return sdf.parse(datePart)?.time
        }
    }
    return null
}

