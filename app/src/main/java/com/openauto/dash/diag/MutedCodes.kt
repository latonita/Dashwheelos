package com.openauto.dash.diag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fault codes the driver muted (long tap on a row): they stay listed, dimmed, but never warn. Keyed by the code
 * ("U0184"), so one mute covers every module that reports it. A code stays muted whatever its status until it is
 * long-tapped again. Saved in the "diag" prefs file (key "muted"), which the setup backup carries.
 */
object MutedCodes {
    private const val PREFS = "diag"
    private const val KEY = "muted"

    private val _codes = MutableStateFlow<Set<String>>(emptySet())
    val codes: StateFlow<Set<String>> = _codes.asStateFlow()

    fun isMuted(code: String): Boolean = norm(code) in _codes.value

    fun load(context: Context) {
        _codes.value = parse(prefs(context).getString(KEY, "").orEmpty())
    }

    /** Mutes [code], or unmutes it when it is muted; true when it is muted now. */
    fun toggle(context: Context, code: String): Boolean {
        val c = norm(code)
        val next = toggled(_codes.value, c)
        _codes.value = next
        prefs(context).edit().putString(KEY, format(next)).apply()
        return c in next
    }

    internal fun toggled(current: Set<String>, code: String): Set<String> =
        norm(code).let { c -> if (c in current) current - c else current + c }

    internal fun format(codes: Set<String>): String = codes.sorted().joinToString(",")

    internal fun parse(text: String): Set<String> = text.split(',').map { norm(it) }.filter { it.isNotEmpty() }.toSet()

    /** For tests: the in-memory set, without prefs. */
    internal fun setForTest(codes: Set<String>) { _codes.value = codes }

    private fun norm(code: String) = code.trim().uppercase()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
