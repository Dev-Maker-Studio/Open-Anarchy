package world.gregs.voidps.cache.source.codec

/**
 * How a module outside `core` claims an index in a source tree.
 *
 * A codec has to be registered before any tree is read or written, and by everything that reads
 * or writes one - the servers, the tools CLI, the tests - so leaving registration to each entry
 * point means one of them eventually forgets and silently builds a tree with the raw codec
 * instead. Providers are found by [java.util.ServiceLoader] instead: a module declares one in
 * `META-INF/services/world.gregs.voidps.cache.source.codec.SourceCodecProvider` and claiming an
 * index becomes a property of being on the classpath.
 *
 * [SourceCodecs] loads every provider after its own built-ins, so a provider wins over the raw
 * default for the indices it names and nothing has to be ordered. Two providers naming the same
 * index is a packaging mistake and the last one loaded wins; keep one provider per module.
 *
 * ```kotlin
 * class Cs2SourceCodecProvider : SourceCodecProvider {
 *     override fun codecs(): Map<Int, SourceCodec> = mapOf(Index.CLIENT_SCRIPTS to Cs2SourceCodec)
 * }
 * ```
 *
 * Implementations must have a no-argument constructor and must not touch a cache, a file or
 * anything else expensive while being constructed: they are instantiated whenever [SourceCodecs]
 * initialises, including in processes that never open a tree.
 */
interface SourceCodecProvider {

    /** The indices this module claims, and the codec for each. */
    fun codecs(): Map<Int, SourceCodec>
}
