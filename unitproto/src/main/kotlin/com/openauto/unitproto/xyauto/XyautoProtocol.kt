package com.openauto.unitproto.xyauto

/*
 * The XYAuto ("xygala" / KYHero) firmware's CAN stack, as it appears to an
 * ordinary app. Unlike FYT there is no binder toolkit here: the stock plugin
 * (com.kyhero.car.myplugin.canbus) talks to the canbox over a serial line and
 * re-sends what it sees as plain broadcasts. Two of them carry the raw frames
 * verbatim, which is what makes this family worth supporting - a Dashwheel can
 * read signals the stock plugin never decodes.
 *
 * Raw mirroring is OFF until someone asks for it: the host app only forwards
 * frames once it has received [XyautoProtocol.ACTION_PRINTDATA] with "isStart"
 * true, and it stops again on false. Subscribing to the frame broadcasts
 * without sending that first is the classic way to watch a silent bus forever.
 *
 * This file is the transport and nothing else: the names of the packages and
 * broadcasts. What the bytes inside a frame mean depends on the canbox behind
 * the unit, not on the unit, and lives in [com.openauto.unitproto.canbox].
 * The Android side (receivers, intents) lives in the app.
 */
object XyautoProtocol {
    /** The firmware's host app and its CAN plugin, for package visibility and the report. */
    val PACKAGES = setOf("com.kyhero.car.myhost", "com.kyhero.car.myplugin.canbus")

    /**
     * Turns raw frame mirroring on (extra [EXTRA_IS_START] true) or off (false).
     * Must be sent before [ACTION_RX] / [ACTION_TX] carry anything; the host
     * forgets it when it restarts, so Dashwheel re-sends it on every resume.
     */
    const val ACTION_PRINTDATA = "com.xygala.canbus.printdata"
    const val EXTRA_IS_START = "isStart"

    /** Every frame received from the canbox, verbatim, in byte[] extra [EXTRA_RX]. */
    const val ACTION_RX = "xy.auto.canbus.rxpduses"
    const val EXTRA_RX = "rxpduses"

    /** Every frame the host sends to the canbox, verbatim, in byte[] extra [EXTRA_TX]. */
    const val ACTION_TX = "xy.auto.canbus.txpduses"
    const val EXTRA_TX = "txpduses"

    /** Whether the canbox is answering at all: boolean extra [EXTRA_CONNECTION]. */
    const val ACTION_CONNECTION = "xy.auto.canbus.connectionsta"
    const val EXTRA_CONNECTION = "connectionsta"

    /**
     * The two signals the stock plugin decodes for everyone, already in
     * engineering units in int extra [EXTRA_CANBUS_DATA]. Handy as a sanity
     * check against our own decoding of the raw frames, and as a fallback on
     * a unit whose box firmware the decoders do not know.
     */
    const val ACTION_RPM = "send_canbus_data_engine"
    const val ACTION_SPEED = "send_canbus_data_current"
    const val EXTRA_CANBUS_DATA = "canbus_send_data"

    /** A steering wheel / panel key as a raw frame of its own: byte[] extra [EXTRA_KEY]. */
    const val ACTION_KEY = "canbus.intent.action.key"
    const val EXTRA_KEY = "canbus_key"

    /** Everything worth listening to, for the receiver and the Signal Finder. */
    val BROADCASTS = setOf(
        ACTION_PRINTDATA, ACTION_RX, ACTION_TX, ACTION_CONNECTION, ACTION_RPM, ACTION_SPEED, ACTION_KEY
    )
}
