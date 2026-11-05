package com.openauto.dash

import android.util.Log
import java.security.Security
import org.conscrypt.Conscrypt

/**
 * TLS 1.3 on units whose Android is too old to have it.
 *
 * Android got TLS 1.3 in Android 10. These head units are Android 8.1, and a
 * growing number of the free services the app depends on now refuse everything
 * older: measured against the defaults, `valhalla1.openstreetmap.de` (the
 * routing) and `overpass-api.de` (speed limits, cameras, station names) both
 * answer a TLS 1.2 hello with `handshake_failure`, and are simply unreachable
 * from the unit's own stack. The map tiles and Nominatim still take 1.2, which
 * is why the symptom was a map that drew fine and a route that never came —
 * "Routing failed (Handshake failed)".
 *
 * Conscrypt is Android's own TLS implementation, shipped as an ordinary library
 * with its own BoringSSL inside, so installing it as the first JSSE provider
 * gives the whole app — OkHttp included, which finds it on its own — the TLS of
 * a current Android regardless of what the unit's system stack can do. It wants
 * no Play services, which these units do not have.
 *
 * Registered first thing in [DashApp], before anything can open a socket.
 */
object ModernTls {

    private const val TAG = "ModernTls"

    /**
     * Installs Conscrypt ahead of the platform provider.
     *
     * Failure is not fatal and must not be: without it the app falls back to
     * the system TLS and behaves exactly as it did before — the map, the search
     * and anything else on TLS 1.2 keep working, and only the 1.3-only services
     * stay out of reach.
     */
    fun install() {
        runCatching {
            // Position 1 is "ask this one first". The platform provider stays
            // behind it, so anything Conscrypt does not handle still resolves.
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            Log.i(TAG, "Conscrypt installed: TLS 1.3 available (${Conscrypt.version()})")
        }.onFailure {
            Log.w(TAG, "Conscrypt unavailable; staying on the system TLS", it)
        }
    }
}
