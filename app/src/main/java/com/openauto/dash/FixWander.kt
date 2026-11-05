package com.openauto.dash

/**
 * A parked car's GPS fixes wander a few metres around where it stands. The map then keeps the puck
 * and camera where they are instead of gliding to each fix. "Parked" is the map's own notion
 * (the stationary detector in MapMotion.kt), passed in.
 */
internal object FixWander {
    /** A fix closer than this to the last one used, while stationary, is noise. */
    const val MAX_NOISE_M = 3f

    fun ignore(stationary: Boolean, metresFromLast: Float): Boolean = stationary && metresFromLast < MAX_NOISE_M
}
