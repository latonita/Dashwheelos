package com.openauto.dash

import android.graphics.Typeface
import android.os.Build

/**
 * Roboto at a weight, on every unit Dashwheel runs on.
 *
 * `Typeface.create(Typeface, Int, Boolean)` — the call that asks for a weight
 * directly — landed in API 28, and the oldest units run 27 (the MediaTek
 * AC8227L boards, which report Android 10 and are Oreo underneath). Below 28
 * Android still ships one named family per weight, which is what the weighted
 * call resolves to internally anyway, so the look holds.
 */
internal fun robotoWeight(weight: Int, condensed: Boolean = false, italic: Boolean = false): Typeface {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val base = Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif", Typeface.NORMAL)
        return Typeface.create(base, weight, italic)
    }
    // API 27 and below: the named families. Checked against /system/etc/fonts.xml
    // on an AC8227L unit — all of these are present there.
    val name = if (condensed) when {
        weight <= 350 -> "sans-serif-condensed-light"
        weight <= 450 -> "sans-serif-condensed"
        weight <= 650 -> "sans-serif-condensed-medium"
        else -> "sans-serif-condensed"
    } else when {
        weight <= 150 -> "sans-serif-thin"
        weight <= 350 -> "sans-serif-light"
        weight <= 450 -> "sans-serif"
        weight <= 650 -> "sans-serif-medium"
        else -> "sans-serif-black"
    }
    // Only the 700 slots lean on the bold face; the rest carry their weight in the family.
    val bold = condensed && weight > 650
    val style = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    return Typeface.create(name, style)
}
