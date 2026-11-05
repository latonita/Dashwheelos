package com.openauto.dash

import android.app.ActivityOptions
import android.util.Log

/**
 * Asking for a window's launch mode on every Android the units run.
 *
 * API 28 introduced `ActivityOptions.setLaunchWindowingMode(int)`. Before it,
 * the same wish was expressed as a *stack*: the hidden
 * `setLaunchStackId(int)` with one of `ActivityManager.StackId`. Android 8.1
 * units (AC8227L and the like) have only the older call, and the newer one
 * fails there with `NoSuchMethodException` — silently, inside a runCatching,
 * which left apps opening full screen instead of in their tile.
 *
 * Neither call is public API, so both go through reflection and both may be
 * absent; [apply] reports whether either landed.
 */
internal object LaunchModeCompat {

    const val WINDOWING_MODE_FULLSCREEN = 1
    const val WINDOWING_MODE_FREEFORM = 5

    /** `ActivityManager.StackId` — what a windowing mode was called before API 28. */
    private const val STACK_FULLSCREEN = 1
    private const val STACK_FREEFORM = 2

    private const val TAG = "LaunchModeCompat"

    /** Asks [options] for [mode]; true when this Android understood one of the two calls. */
    fun apply(options: ActivityOptions, mode: Int): Boolean {
        val byMode = runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, mode)
        }.isSuccess
        if (byMode) return true

        val stackId = when (mode) {
            WINDOWING_MODE_FREEFORM -> STACK_FREEFORM
            WINDOWING_MODE_FULLSCREEN -> STACK_FULLSCREEN
            else -> return false
        }
        return runCatching {
            ActivityOptions::class.java
                .getMethod("setLaunchStackId", Int::class.javaPrimitiveType)
                .invoke(options, stackId)
        }.onFailure { Log.d(TAG, "no launch mode call for $mode", it) }.isSuccess
    }
}
