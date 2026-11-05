package com.openauto.unitproto.canbox

/**
 * The decoders there are, and how one is picked: by the version a box names
 * itself with, falling back on the stock one when nothing claims it.
 *
 * Order matters - the first decoder to claim a version wins - so a more
 * specific decoder is listed ahead of the one it extends.
 */
class CanboxRegistry(private val decoders: List<CanboxDecoder>, val fallback: CanboxDecoder) {

    /** The decoder for a box that names its firmware [version]; [fallback] when none claims it. */
    fun select(version: String): CanboxDecoder = decoders.firstOrNull { it.matches(version) } ?: fallback

    /** The decoder called [id], for a manual override; null when there is none. */
    fun byId(id: String): CanboxDecoder? = decoders.firstOrNull { it.id == id }

    /** Every decoder's id, for a picker. */
    val ids: List<String> get() = decoders.map { it.id }

    companion object {
        /** Adding a canbox: its decoder goes on one line here. */
        val DEFAULT: CanboxRegistry = HiworldJpf2Decoder().let { stock ->
            CanboxRegistry(listOf(Jpf20lDecoder(), stock), fallback = stock)
        }
    }
}

/** One frame through a [CanboxPipeline]: the frame, its decoder's name for it, and what it said. */
class Decoded(val frame: CanFrame, val name: String, val values: List<CanboxValue>)

/**
 * Raw bytes to values for one box: parses with the current decoder's framing,
 * decodes, and, the first time the box names its firmware version, switches to
 * the decoder the [registry] picks for it. Until then the stock decoder reads
 * the stream, which is also what a box that never names itself gets.
 *
 * [force] pins a decoder by id whatever the box says (the manual override);
 * null hands the choice back to the version. A box whose framing is not the
 * default's cannot announce itself through it, and needs the override.
 */
class CanboxPipeline(private val registry: CanboxRegistry = CanboxRegistry.DEFAULT, forced: String? = null) {

    private var forcedId: String? = forced
    private var current: CanboxDecoder = forced?.let(registry::byId) ?: registry.fallback

    /** The version the box last named, or null before it has. */
    var version: String? = null
        private set

    /** The decoder reading the stream now. */
    val decoder: CanboxDecoder get() = current

    /** Pins the decoder [id] (an unknown id is ignored), or with null follows the box's version again. */
    fun force(id: String?) {
        forcedId = id
        current = id?.let(registry::byId) ?: version?.let(registry::select) ?: registry.fallback
    }

    /** What [bytes] say, or null when they are not a frame of the current decoder's framing. */
    fun feed(bytes: ByteArray?): Decoded? {
        val frame = current.framing.parse(bytes) ?: return null
        val values = current.decode(frame)
        val said = values.filterIsInstance<CanboxValue.Version>().firstOrNull()
        if (said != null && said.text != version) {
            version = said.text
            if (forcedId == null) current = registry.select(said.text)
        }
        return Decoded(frame, current.commandName(frame.cmd), values)
    }
}
