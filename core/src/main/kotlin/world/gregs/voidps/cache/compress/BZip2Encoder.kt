package world.gregs.voidps.cache.compress

/**
 * Compresses data the way the bzip2 containers in the 727 cache were compressed.
 *
 * Every bzip2 container in the cache reproduces only with libbzip2's own compressor at block
 * size 1; nothing on the JVM matches it, so this is a faithful port of it. The returned stream
 * has the four byte `BZh1` magic stripped, which is how a container stores it - the client's
 * decompressor starts reading at the first block header.
 */
object BZip2Encoder {

    /**
     * @param data the bytes to compress
     * @param blockSize the block size in hundreds of kilobytes, 1-9; the cache only uses 1
     * @return the bzip2 stream without its four byte `BZh<blockSize>` magic
     */
    fun compress(data: ByteArray, blockSize: Int = 1): ByteArray {
        throw UnsupportedOperationException("BZip2Encoder is being ported")
    }
}
