package org.darkan.core.net.prot.update

import org.darkan.core.update.HitSplat
import org.darkan.core.update.HitsUpdate
import world.gregs.voidps.buffer.write.BufferWriter

/**
 * Shared hit splat + bar encoding, parameterized on the count-prefix byte transforms.
 *
 * Player hits uses `writeByteSubtract` for splat count and `writeByte` for bar count.
 * NPC hits uses `writeByteAdd` for both splat and bar count.
 * The splat and bar encoding bodies are identical between player and NPC.
 */
inline fun encodeHits(
    out: BufferWriter,
    hits: HitsUpdate,
    writeSplatCount: (BufferWriter, Int) -> Unit,
    writeBarCount: (BufferWriter, Int) -> Unit,
) {
    writeSplatCount(out, hits.hitSplats.size)

    for (splat in hits.hitSplats) {
        when (splat) {
            is HitSplat.Miss -> {
                out.writeSmart(32766)
                out.writeByteInverse(splat.missValue)
                out.writeSmart(splat.delay)
            }
            is HitSplat.Soak -> {
                val big = splat.value > Short.MAX_VALUE / 2 || splat.soakValue > Short.MAX_VALUE / 2
                if (big) {
                    out.writeSmart(32764)
                    out.writeSmart(splat.id)
                    out.writeString(splat.value.toString())
                    out.writeSmart(splat.soakId)
                    out.writeString(splat.soakValue.toString())
                } else {
                    out.writeSmart(32767)
                    out.writeSmart(splat.id)
                    out.writeSmart(splat.value)
                    out.writeSmart(splat.soakId)
                    out.writeSmart(splat.soakValue)
                }
                out.writeSmart(splat.delay)
            }
            is HitSplat.Standard -> {
                val big = splat.value > Short.MAX_VALUE / 2
                if (big) {
                    out.writeSmart(32765)
                    out.writeSmart(splat.id)
                    out.writeString(splat.value.toString())
                } else {
                    out.writeSmart(splat.id)
                    out.writeSmart(splat.value)
                }
                out.writeSmart(splat.delay)
            }
            is HitSplat.CustomText -> {
                if (splat.soakId != null && splat.soakText != null) {
                    out.writeSmart(32764)
                    out.writeSmart(splat.id)
                    out.writeString(splat.text)
                    out.writeSmart(splat.soakId)
                    out.writeString(splat.soakText)
                } else {
                    out.writeSmart(32765)
                    out.writeSmart(splat.id)
                    out.writeString(splat.text)
                }
                out.writeSmart(splat.delay)
            }
        }
    }

    writeBarCount(out, hits.hitBars.size)
    for (bar in hits.hitBars) {
        out.writeSmart(bar.type)
        out.writeSmart(bar.timer)
        if (bar.timer != 32767) {
            out.writeSmart(bar.delay)
            out.writeByteSubtract(bar.fromPercentage)
            if (bar.toPercentage != bar.fromPercentage) {
                out.writeByteAdd(bar.toPercentage)
            }
        }
    }
}
