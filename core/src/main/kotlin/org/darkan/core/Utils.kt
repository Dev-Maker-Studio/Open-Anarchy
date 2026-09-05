package org.darkan.core

import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.type.Tile
import world.gregs.voidps.type.secureRandom
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

object Utils {

    @JvmField
    val ROTATION_DIR_X = intArrayOf(-1, 0, 1, 0)
    @JvmField
    val ROTATION_DIR_Y = intArrayOf(0, 1, 0, -1)
    @JvmField
    val FORMAT = DecimalFormat("###,###.##")

    private val ANGLE_DIRECTION_DELTA = arrayOf(
        byteArrayOf(0, -1), byteArrayOf(-1, -1), byteArrayOf(-1, 0),
        byteArrayOf(-1, 1), byteArrayOf(0, 1), byteArrayOf(1, 1),
        byteArrayOf(1, 0), byteArrayOf(1, -1)
    )

    // --- Random ---

    @JvmStatic
    fun random(maxValue: Int): Int = if (maxValue <= 0) 0 else secureRandom.nextInt(maxValue)

    @JvmStatic
    fun random(min: Int, max: Int): Int {
        val diff = abs(max - min)
        return min + if (diff == 0) 0 else random(diff)
    }

    @JvmStatic
    fun randomInclusive(min: Int, max: Int): Int = random(min, max + 1)

    @JvmStatic
    fun random(maxValue: Double): Double = random(0.0, maxValue)

    @JvmStatic
    fun random(min: Double, max: Double): Double = min + (max - min) * secureRandom.nextDouble()

    @JvmStatic
    fun randomD(): Double = secureRandom.nextDouble()

    @JvmStatic
    fun getRandomInclusive(maxValue: Int): Int = (Math.random() * (maxValue + 1)).toInt()

    @JvmStatic
    fun next(max: Int, min: Int): Int = min + (Math.random() * ((max - min) + 1)).toInt()

    @JvmStatic
    fun randomLong(n: Long): Long {
        var bits: Long
        var value: Long
        do {
            bits = (secureRandom.nextLong() shl 1) ushr 1
            value = bits % n
        } while (bits - value + (n - 1) < 0L)
        return value
    }

    @JvmStatic
    fun range(min: Int, max: Int): IntArray = IntArray(max - min + 1) { min + it }

    @JvmStatic
    fun range(min: Int, max: Int, step: Int): IntArray {
        val size = (max - min) / step + 1
        return IntArray(size) { min + it * step }
    }

    // --- Math ---

    @JvmStatic
    fun clampD(value: Double, min: Double, max: Double): Double = max(min, min(max, value))

    @JvmStatic
    fun clampI(value: Int, min: Int, max: Int): Int = max(min, min(max, value))

    @JvmStatic
    fun diff(a: Int, b: Int): Int = abs(a - b)

    @JvmStatic
    fun round(value: Double, places: Int): Double =
        BigDecimal(value).setScale(places, RoundingMode.HALF_UP).toDouble()

    @JvmStatic
    fun isWithin(i: Int, min: Int, max: Int): Boolean = i > min && i < max

    @JvmStatic
    fun isWithinInclusive(i: Int, min: Int, max: Int): Boolean = i in min..max

    @JvmStatic
    fun skillSuccess(level: Int, rate1: Int, rate99: Int): Boolean {
        val d = Math.random() * 256.0
        return d <= interpolate(level, rate1, rate99, 1, 99)
    }

    @JvmStatic
    fun skillSuccess(level: Int, percModifier: Double, rate1: Int, rate99: Int): Boolean {
        val d = Math.random() * 256.0
        return d <= interpolate(level, rate1, rate99, 1, 99) * percModifier
    }

    @JvmStatic
    fun skillSuccess(level: Int, percModifier: Double, rate1: Int, rate99: Int, maxSuccess: Int): Boolean {
        val d = Math.random() * maxSuccess.toDouble()
        return d <= interpolate(level, rate1, rate99, 1, 99) * percModifier
    }

    @JvmStatic
    fun interpolate(level: Int, lowValue: Int, highValue: Int, lowLevel: Int, highLevel: Int): Double {
        return lowValue + (highValue - lowValue) * ((level - lowLevel).toDouble() / (highLevel - lowLevel).toDouble())
    }

    // --- GCD / LCM ---

    @JvmStatic
    fun gcm(a: Long, b: Long): Long = if (b == 0L) a else gcm(b, a % b)

    @JvmStatic
    fun greatestCommonDenominator(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDenominator(b, a % b)

    @JvmStatic
    fun leastCommonMultiple(a: Int, b: Int): Int = a / greatestCommonDenominator(a, b) * b

    // --- Distance / Angle ---

    @JvmStatic
    fun getDistance(t1: Tile, t2: Tile): Double = getDistance(t1.x, t1.y, t2.x, t2.y)

    @JvmStatic
    fun getDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        return sqrt(dx * dx + dy * dy)
    }

    @JvmStatic
    fun getDistanceI(t1: Tile, t2: Tile): Int = getDistanceI(t1.x, t1.y, t2.x, t2.y)

    @JvmStatic
    fun getDistanceI(x1: Int, y1: Int, x2: Int, y2: Int): Int = getDistance(x1, y1, x2, y2).toInt()

    @JvmStatic
    fun inCircle(location: Tile, center: Tile, radius: Int): Boolean {
        return getDistance(center, location) < radius
    }

    @JvmStatic
    fun getAngleTo(from: Tile, to: Tile): Int = getAngleTo(to.x - from.x, to.y - from.y)

    @JvmStatic
    fun getAngleTo(xOffset: Int, yOffset: Int): Int {
        return (atan2(-xOffset.toDouble(), -yOffset.toDouble()) * 2607.5945876176133).toInt() and 0x3fff
    }

    @JvmStatic
    fun getDirection(angle: Int): ByteArray = ANGLE_DIRECTION_DELTA[angle shr 11]

    @JvmStatic
    fun getPlayerWalkingDirection(dx: Int, dy: Int): Int = when {
        dx == -1 && dy == -1 -> 0
        dx == 0 && dy == -1 -> 1
        dx == 1 && dy == -1 -> 2
        dx == -1 && dy == 0 -> 3
        dx == 1 && dy == 0 -> 4
        dx == -1 && dy == 1 -> 5
        dx == 0 && dy == 1 -> 6
        dx == 1 && dy == 1 -> 7
        else -> -1
    }

    @JvmStatic
    fun getPlayerRunningDirection(dx: Int, dy: Int): Int = when {
        dx == -2 && dy == -2 -> 0
        dx == -1 && dy == -2 -> 1
        dx == 0 && dy == -2 -> 2
        dx == 1 && dy == -2 -> 3
        dx == 2 && dy == -2 -> 4
        dx == -2 && dy == -1 -> 5
        dx == 2 && dy == -1 -> 6
        dx == -2 && dy == 0 -> 7
        dx == 2 && dy == 0 -> 8
        dx == -2 && dy == 1 -> 9
        dx == 2 && dy == 1 -> 10
        dx == -2 && dy == 2 -> 11
        dx == -1 && dy == 2 -> 12
        dx == 0 && dy == 2 -> 13
        dx == 1 && dy == 2 -> 14
        dx == 2 && dy == 2 -> 15
        else -> -1
    }

    @JvmStatic
    fun getBackFace(dir: Int): IntArray {
        val ang = dir / 2607.5945876176133
        return intArrayOf(sin(ang).roundToInt(), cos(ang).roundToInt())
    }

    @JvmStatic
    fun getFrontFace(dir: Int): IntArray {
        val front = getBackFace(dir)
        return intArrayOf(front[0] * -1, front[1] * -1)
    }

    // --- String / Name formatting ---

    @JvmStatic
    fun formatPlayerNameForDisplay(name: String): String = name.formatPlayerNameForDisplay()

    @JvmStatic
    fun formatPlayerNameForProtocol(name: String): String = name.formatPlayerNameForProtocol()

    @JvmStatic
    fun fixChatMessage(message: String): String = message.fixChatMessage()

    @JvmStatic
    fun addArticle(name: String): String {
        val lower = name.lowercase()
        return if (lower.startsWith("a") || lower.startsWith("e") || lower.startsWith("i") || lower.startsWith("o") || lower.startsWith("u"))
            "an $name" else "a $name"
    }

    @JvmStatic
    fun kmify(num: Int): String = when {
        num >= 10_000_000 -> "${num / 1_000_000}M"
        num >= 100_000 -> "${num / 1_000}K"
        else -> num.formatNumber()
    }

    @JvmStatic
    fun quote(str: String): String = "'$str'"

    @JvmStatic
    fun quote(c: Char): String = "'$c'"

    @JvmStatic
    fun concat(args: Array<String>): String = args.joinToString(" ")

    @JvmStatic
    fun concat(args: Array<String>, start: Int): String = args.drop(start).joinToString(" ")

    @JvmStatic
    fun formatNumber(number: Int): String = number.formatNumber()

    @JvmStatic
    fun getFormattedNumber(amount: Int): String = NumberFormat.getNumberInstance(Locale.US).format(amount.toLong())

    @JvmStatic
    fun getFormattedNumber(amount: Double, separator: Char): String {
        val format = DecimalFormat("#,###")
        val symbols = format.decimalFormatSymbols
        symbols.groupingSeparator = separator
        format.decimalFormatSymbols = symbols
        return format.format(amount)
    }

    @JvmStatic
    fun formatTypicalInteger(integer: Int): String = NumberFormat.getIntegerInstance().format(integer.toLong())

    @JvmStatic
    fun formatLong(value: Long): String = NumberFormat.getNumberInstance(Locale.US).format(value)

    @JvmStatic
    fun formatDouble(d: Double): String = FORMAT.format(d)

    @JvmStatic
    fun formatTime(time: Long): String {
        val sec = (time / 1000) % 60
        val min = (time / 60000) % 60
        val hr = time / 3600000
        return String.format("%02d:%02d:%02d", hr, min, sec)
    }

    @JvmStatic
    fun ticksToTime(ticks: Double): String = ticks.ticksToTimeString()

    @JvmStatic
    fun getFormatedDate(): String = SimpleDateFormat("[MM/d/yyyy]").format(Date())

    @JvmStatic
    fun getDateString(): String = SimpleDateFormat("yyyy-MM-dd").format(Date())

    // --- Validity ---

    @JvmStatic
    fun invalidAccountName(name: String): Boolean = !name.isValidAccountName()

    @JvmStatic
    fun containsInvalidCharacter(c: Char): Boolean = !c.isLetterOrDigit() && c != '_'

    @JvmStatic
    fun containsInvalidCharacter(name: String): Boolean = name.any { containsInvalidCharacter(it) }

    @JvmStatic
    fun containsBadCharacter(c: Char): Boolean = !c.isLetterOrDigit() && c != ' ' && c != '-' && c != '_'

    @JvmStatic
    fun containsBadCharacter(name: String): Boolean = name.any { containsBadCharacter(it) }

    @JvmStatic
    fun validEmail(emailStr: String): Boolean = emailStr.isValidEmail()

    // --- RS name↔long encoding ---

    @JvmStatic
    fun stringToLong(s: String): Long {
        var l = 0L
        for (i in 0 until minOf(s.length, 12)) {
            val c = s[i]
            l *= 37L
            l += when {
                c in 'A'..'Z' -> (c - 'A' + 1).toLong()
                c in 'a'..'z' -> (c - 'a' + 1).toLong()
                c in '0'..'9' -> (c - '0' + 27).toLong()
                else -> 0L
            }
        }
        while (l % 37L == 0L && l != 0L) l /= 37L
        return l
    }

    @JvmStatic
    fun longToString(l: Long): String {
        if (l <= 0L || l >= 0x5B5B57F8A98A5DD1L) return "invalid_name"
        var remaining = l
        var len = 0
        var temp = remaining
        while (temp != 0L) {
            temp /= 37L
            len++
        }
        val chars = CharArray(len)
        for (i in len - 1 downTo 0) {
            val digit = (remaining % 37L).toInt()
            remaining /= 37L
            chars[i] = when {
                digit == 0 -> '_'
                digit < 27 -> ('a' + digit - 1)
                else -> ('0' + digit - 27)
            }
        }
        return String(chars)
    }

    @JvmStatic
    fun getNameHash(name: String): Int {
        var hash = 0
        for (c in name.formatPlayerNameForProtocol()) {
            hash = (hash shl 5) - hash + c.code
        }
        return hash
    }

    // --- Interface helpers ---

    @JvmStatic
    fun interfaceIdFromHash(hash: Int): Int = hash shr 16

    @JvmStatic
    fun componentIdFromHash(hash: Int): Int = hash and 0xFFFF

    @JvmStatic
    fun toInterfaceHash(interfaceId: Int, componentId: Int): Int = (interfaceId shl 16) or componentId

    // --- Projectile helpers ---

    @JvmStatic
    fun calcProjectileInAirTime(start: Tile, end: Tile, presetInAirClientCycles: Int): Int {
        val dist = getDistance(start, end)
        return presetInAirClientCycles + (dist * 5).toInt()
    }

    @JvmStatic
    fun projInAirToTicks(inAirClientCycles: Int): Int = (inAirClientCycles / 30) + 1

    @JvmStatic
    fun projInAirToTicks(startDelayClientCycles: Int, inAirClientCycles: Int): Int =
        ((startDelayClientCycles + inAirClientCycles) / 30) + 1

    // --- Crypto ---

    @JvmStatic
    fun cryptRSA(data: ByteArray, exponent: BigInteger, modulus: BigInteger): ByteArray =
        BigInteger(data).modPow(exponent, modulus).toByteArray()

    @JvmStatic
    fun encryptUsingMD5(buffer: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(buffer)

    // --- Color ---

    @JvmStatic
    fun getRSColor(red: Int, green: Int, blue: Int): Int {
        val r = red / 256.0
        val g = green / 256.0
        val b = blue / 256.0
        var mn = minOf(r, g, b)
        var mx = maxOf(r, g, b)
        var h = 0.0
        var s: Double
        val l = (mn + mx) / 2.0
        if (mn == mx) {
            h = 0.0
            s = 0.0
        } else {
            val delta = mx - mn
            s = if (l < 0.5) delta / (mx + mn) else delta / (2.0 - mx - mn)
            if (r == mx) h = (g - b) / delta
            else if (g == mx) h = (b - r) / delta + 2.0
            else if (b == mx) h = (r - g) / delta + 4.0
        }
        h /= 6.0
        val hi = (h * 256.0).toInt()
        val si = (s * 256.0).toInt()
        val li = (l * 256.0).toInt()
        if (si < 0) return li
        val hc = if (hi > 255) 255 else hi
        val sc = if (si > 255) 255 else si
        val lc = if (li > 255) 255 else li
        return (hc shl 10) + (sc shl 7) + lc
    }

    // --- Misc ---

    @JvmStatic
    fun djb2(str: String): Int {
        var hash = 0
        for (c in str) hash = c.code + ((hash shl 5) - hash)
        return hash
    }

    @JvmStatic
    fun getHashMapSize(size: Int): Int {
        var n = size - 1
        n = n or (n ushr 1)
        n = n or (n ushr 2)
        n = n or (n ushr 4)
        n = n or (n ushr 8)
        n = n or (n ushr 16)
        return n + 1
    }

    @JvmStatic
    fun getTodayDate(): Int {
        val cal = Calendar.getInstance()
        return ((cal.get(Calendar.YEAR) - 2000) shl 9) or (cal.get(Calendar.MONTH) shl 5) or cal.get(Calendar.DAY_OF_MONTH)
    }

    @JvmStatic
    fun findClosestIdx(arr: IntArray, target: Int): Int {
        var closest = 0
        for (i in arr.indices) {
            if (abs(arr[i] - target) < abs(arr[closest] - target)) closest = i
        }
        return closest
    }

    @JvmStatic
    fun shuffleIntArray(array: IntArray): IntArray {
        val copy = array.copyOf()
        for (i in copy.indices.reversed()) {
            val j = secureRandom.nextInt(i + 1)
            val temp = copy[i]
            copy[i] = copy[j]
            copy[j] = temp
        }
        return copy
    }

    @JvmStatic
    fun randSum(numVars: Int, total: Int): IntArray {
        val result = IntArray(numVars)
        for (i in 0 until total) result[secureRandom.nextInt(numVars)]++
        return result
    }

    @JvmStatic
    fun removeInvalidChars(s: String): String = s.filter { it.isLetterOrDigit() || it == '_' || it == ' ' }

    @JvmStatic
    fun strikeThroughIf(string: String, condition: () -> Boolean): String =
        if (condition()) "<str>$string</str>" else string

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun cloneMap(from: Map<String, Any>): Map<String, Any> =
        from.entries.associate { (k, v) ->
            k to if (v is Map<*, *>) cloneMap(v as Map<String, Any>) else v
        }

    // --- CP1252 encoding ---

    @JvmField
    val CP_1252_CHARACTERS = charArrayOf(
        '\u20ac', '\u0000', '\u201a', '\u0192', '\u201e', '\u2026', '\u2020', '\u2021',
        '\u02c6', '\u2030', '\u0160', '\u2039', '\u0152', '\u0000', '\u017d', '\u0000',
        '\u0000', '\u2018', '\u2019', '\u201c', '\u201d', '\u2022', '\u2013', '\u2014',
        '\u02dc', '\u2122', '\u0161', '\u203a', '\u0153', '\u0000', '\u017e', '\u0178'
    )

    @JvmStatic
    fun cp1252ToChar(i: Byte): Char {
        var cp = (i.toInt() and 0xff)
        if (cp == 0) throw IllegalArgumentException("Non cp1252 character 0x${Integer.toString(cp, 16)} provided")
        if (cp in 128..159) {
            var c = CP_1252_CHARACTERS[cp - 128]
            if (c.code == 0) c = '?'
            cp = c.code
        }
        return cp.toChar()
    }

    @JvmStatic
    fun charToCp1252(c: Char): Byte {
        if (c.code > 0 && c.code < 128 || c.code in 160..255) return c.code.toByte()
        for (i in CP_1252_CHARACTERS.indices) {
            if (CP_1252_CHARACTERS[i] == c) return (i + 128).toByte()
        }
        return '?'.code.toByte()
    }

    // --- Definition counts: the id space the loaded cache holds, one past the highest id ---

    @JvmStatic fun getItemDefinitionsSize(): Int = Cache.items.size

    @JvmStatic fun getNPCDefinitionsSize(): Int = Cache.npcs.size

    @JvmStatic fun getObjectDefinitionsSize(): Int = Cache.objects.size

    @JvmStatic fun getSpotAnimDefinitionsSize(): Int = Cache.spotAnims.size

    @JvmStatic fun getAnimationDefinitionsSize(): Int = Cache.animations.size

    @JvmStatic fun getBASAnimDefSize(): Int = Cache.bas.size

    /**
     * Legacy `com.rs.lib.util.Utils#getConfigDefinitionsSize` (core Utils.java:976-979):
     * `lastArchiveId(VARBITS) * 256 + validFilesCount(lastArchiveId)`. Varbit configs are
     * packed 256 per archive in index 22, so this is one past the highest packed config id.
     */
    @JvmStatic fun getConfigDefinitionsSize(): Int {
        val cache = Cache.get()
        val lastArchive = cache.lastArchiveId(Index.VAR_BIT)
        if (lastArchive < 0) return 0
        return lastArchive * 256 + cache.fileCount(Index.VAR_BIT, lastArchive)
    }

    /**
     * Legacy used `lastArchiveId(VARBITS) * 0x3ff` (core Utils.java:1003-1005), an estimate from
     * before the varbit table was decoded. The decoded table is exact and is what
     * [org.darkan.core.model.Vars.getVarBit] already bounds itself by, so use its length.
     */
    @JvmStatic fun getVarbitDefinitionsSize(): Int = Cache.varbits.size

    // One past the highest id, matching every "size" here and the `id >= size` / `size <= id`
    // guards that call them - lastArchiveId/lastFileId are the highest id, not the count, and a
    // component archive is sparse so its file count is not its last file id either.

    @JvmStatic fun getInterfaceDefinitionsSize(): Int =
        Cache.get().lastArchiveId(Index.INTERFACES) + 1

    @JvmStatic fun getInterfaceDefinitionsComponentsSize(interfaceId: Int): Int =
        Cache.get().lastFileId(Index.INTERFACES, interfaceId) + 1

    /**
     * Which item ids the cache actually holds a definition for, indexed by id.
     *
     * Index 19 is sparse - the decoded [Cache.items] array is dense over the whole id space, so a
     * hole in the cache is indistinguishable from a real definition there. The authority is the
     * reference table: an item exists when index 19 has file `id and 0xff` in archive `id ushr 8`.
     * Built once because [itemExists] is on the path of every single item added to a container.
     */
    private val itemIdExists: BooleanArray by lazy {
        val size = getItemDefinitionsSize()
        val exists = BooleanArray(size)
        val cache = Cache.get()
        var archive = 0
        while ((archive shl 8) < size) {
            for (file in cache.files(Index.ITEMS, archive)) {
                val id = (archive shl 8) or file
                if (id < size) exists[id] = true
            }
            archive++
        }
        exists
    }

    @JvmStatic fun itemExists(id: Int): Boolean = id >= 0 && id < itemIdExists.size && itemIdExists[id]

    // --- Legacy compatibility methods ---

    /**
     * Flattens varargs of objects and arrays into a single Object[].
     * Arrays are expanded element by element.
     */
    @JvmStatic
    fun streamObjects(vararg objects: Any): Array<Any> {
        val list = mutableListOf<Any>()
        for (obj in objects) {
            if (obj is IntArray) {
                for (i in obj) list.add(i)
            } else if (obj.javaClass.isArray) {
                val len = java.lang.reflect.Array.getLength(obj)
                for (i in 0 until len) list.add(java.lang.reflect.Array.get(obj, i))
            } else {
                list.add(obj)
            }
        }
        return list.toTypedArray()
    }

    /**
     * Gets classes annotated with the given annotation from the specified package.
     * Uses ClassGraph for classpath scanning.
     */
    @JvmStatic
    @Throws(ClassNotFoundException::class, java.io.IOException::class)
    fun getClassesWithAnnotation(packageName: String, annotation: Class<out Annotation>): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()
        io.github.classgraph.ClassGraph()
            .enableClassInfo()
            .enableAnnotationInfo()
            .acceptPackages(packageName)
            .scan().use { scanResult ->
                for (classInfo in scanResult.getClassesWithAnnotation(annotation.name)) {
                    classes.add(classInfo.loadClass())
                }
            }
        return classes
    }

    /**
     * Stub: splits text into lines based on font metrics.
     * TODO: wire to modern cache (FontMetrics)
     */
    @JvmStatic
    fun splitTextIntoLines(text: String, fontId: Int, width: Int): Array<String> {
        return arrayOf(text)
    }

    /**
     * Stub: gets the height of a string rendered at the given font and width.
     * TODO: wire to modern cache (FontMetrics)
     */
    @JvmStatic
    fun getIFStringHeightAbs(text: String, fontId: Int, width: Int): Int {
        return 0
    }

    /**
     * Adds all elements from an array to an ArrayList.
     */
    @JvmStatic
    fun <T> add(list: java.util.ArrayList<T>, items: Array<T>?) {
        if (items != null) {
            for (item in items) {
                if (item != null) list.add(item)
            }
        }
    }

    /**
     * Gets the value of a field via reflection.
     */
    @JvmStatic
    fun getFieldValue(obj: Any, field: java.lang.reflect.Field): Any? {
        field.isAccessible = true
        return field.get(obj)
    }

    /**
     * Gets subclasses of the given class from the given package via classpath scanning.
     */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T> getSubClasses(packageName: String, superClass: Class<T>): List<Class<T>> {
        val classes = mutableListOf<Class<T>>()
        io.github.classgraph.ClassGraph()
            .enableClassInfo()
            .acceptPackages(packageName)
            .scan().use { scanResult ->
                for (classInfo in scanResult.getSubclasses(superClass.name)) {
                    if (!java.lang.reflect.Modifier.isAbstract(classInfo.modifiers)) {
                        classes.add(classInfo.loadClass() as Class<T>)
                    }
                }
            }
        return classes
    }
}
