package com.openauto.dash

import android.os.Build
import java.lang.reflect.Method
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * The @hide calls the GitHub edition makes (windowing mode, the tile's input,
 * Wi-Fi and Bluetooth joins), through HiddenApiBypass. The Play edition has
 * its own HiddenApi that refuses them, so the library isn't in its bundle:
 * Google Play rejects API-bypass SDKs. Every caller already has a fallback.
 *
 * API 27 and below need no bypass at all: the hidden-API blocklist arrived in
 * API 28, and plain reflection reaches @hide directly on the units that run
 * Oreo. HiddenApiBypass itself is built for 28 and up, so it is only called there.
 */
object HiddenApi {
    fun invoke(cls: Class<*>, target: Any?, name: String, vararg args: Any?): Any? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return HiddenApiBypass.invoke(cls, target, name, *args)
        }
        return plainMethod(cls, name, args.size).invoke(target, *args)
    }

    fun getDeclaredMethod(cls: Class<*>, name: String, vararg types: Class<*>?): Method {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return HiddenApiBypass.getDeclaredMethod(cls, name, *types)
        }
        return cls.getDeclaredMethod(name, *types.filterNotNull().toTypedArray()).apply { isAccessible = true }
    }

    /** The one method of that name taking that many arguments, this class or a parent's. */
    private fun plainMethod(cls: Class<*>, name: String, count: Int): Method =
        generateSequence(cls) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == name && it.parameterTypes.size == count }
            ?.apply { isAccessible = true }
            ?: throw NoSuchMethodException("$name taking $count on ${cls.name}")
}
