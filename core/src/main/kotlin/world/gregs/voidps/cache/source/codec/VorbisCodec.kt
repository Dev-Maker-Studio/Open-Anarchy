package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.cache.audio.JagexVorbis
import world.gregs.voidps.cache.source.SourceJson
import java.nio.file.Files
import java.nio.file.Path

private val BYTES = BinaryArchiveCodec("jagvorbis", ".jagvorbis")

object VorbisCodec : SourceCodec by BYTES {

    const val MANIFEST = "samples.json"

    override val id: String
        get() = "vorbis"

    override fun unpackIndex(directory: Path, index: Int, archives: IntArray): List<SourceFile> {
        val out = StringBuilder(archives.size * 128)
        out.append("{\n")
        out.append("  \"note\": ").append(SourceJson.quote(NOTE)).append(",\n")
        val setup = header(directory, SETUP, 2)
        if (setup != null) {
            val decoded = JagexVorbis.setup(setup)
            out.append("  \"setup\": {\"file\": ").append(SourceJson.quote("$SETUP${BYTES.extension}"))
            out.append(", \"blockSize0\": ").append(decoded.blockSize0)
            out.append(", \"blockSize1\": ").append(decoded.blockSize1)
            out.append(", \"codebooks\": ").append(decoded.codebooks).append("},\n")
        }
        out.append("  \"samples\": {")
        var first = true
        for (archive in archives) {
            if (archive == SETUP) {
                continue
            }
            val data = header(directory, archive, JagexVorbis.HEADER) ?: continue
            val sample = JagexVorbis.sample(data)
            if (!first) {
                out.append(',')
            }
            first = false
            out.append("\n    ").append(SourceJson.quote(archive.toString())).append(": {")
            out.append("\"sampleRate\": ").append(sample.sampleRate)
            out.append(", \"samples\": ").append(sample.samples)
            out.append(", \"millis\": ").append(sample.millis)
            out.append(", \"loopStart\": ").append(sample.loopStart)
            out.append(", \"loopEnd\": ").append(sample.loopEnd)
            out.append(", \"loopConsistent\": ").append(sample.loopConsistent)
            out.append(", \"packets\": ").append(sample.packets)
            out.append('}')
        }
        if (!first) {
            out.append("\n  ")
        }
        out.append("}\n}\n")
        return listOf(SourceFile(MANIFEST, out.toString().toByteArray(Charsets.UTF_8)))
    }

    override fun owner(directory: Path, path: String): Owner {
        if (path == MANIFEST) {
            return Owner.Index
        }
        val archive = archiveOf(directory, path)
        return if (archive == null) Owner.None else Owner.Archive(archive)
    }

    private fun header(directory: Path, archive: Int, length: Int): ByteArray? {
        val file = directory.resolve("$archive${BYTES.extension}")
        if (!Files.isRegularFile(file)) {
            return null
        }
        val bytes = ByteArray(length)
        Files.newInputStream(file).use { stream ->
            var read = 0
            while (read < length) {
                val count = stream.read(bytes, read, length - read)
                if (count < 0) {
                    return null
                }
                read += count
            }
        }
        return bytes
    }

    private const val SETUP = 0

    private const val NOTE =
        "Derived from the .jagvorbis payloads, which are the source; this listing is their table of " +
            "contents and editing it changes nothing. Index 14 holds bare vorbis packets in Jagex's " +
            "own framing, not Ogg: five big endian ints (sample rate, samples, loop start, loop end " +
            "whose sign bit is the loop consistency flag, packet count) and then each packet, " +
            "preceded by its length as bytes that add up with 0xff meaning 255 and more. Archive 0 " +
            "is the vorbis setup header they all share, with its two block sizes in the first byte."
}

