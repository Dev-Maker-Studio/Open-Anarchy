package world.gregs.voidps.cache.compress

import kotlin.math.max
import kotlin.math.min

object BZip2Encoder {

    fun compress(data: ByteArray, blockSize: Int = 1): ByteArray {
        val stream = compressWithMagic(data, blockSize)
        return stream.copyOfRange(MAGIC_LENGTH, stream.size)
    }

    fun compressWithMagic(data: ByteArray, blockSize: Int = 1): ByteArray {
        require(blockSize in 1..9) { "Block size must be 1..9 (100kB units) but was $blockSize." }
        return BZip2Stream(blockSize, data.size).compress(data)
    }

    private const val MAGIC_LENGTH = 4
}

private class BZip2Stream(private val blockSize100k: Int, dataSize: Int) {

    private val blockMax = 100000 * blockSize100k - 19

    private val capacity = min(100000 * blockSize100k, dataSize + dataSize / 4 + 16)

    private val block = ByteArray(capacity + BZ_N_OVERSHOOT)

    private val quadrant = IntArray(capacity + BZ_N_OVERSHOOT)

    private val ptr = IntArray(max(capacity, 1))

    private val ftab = IntArray(65537)

    private var eclass: IntArray? = null

    private val mtfv = IntArray(max(capacity, 1) + 1)

    private val mtfFreq = IntArray(BZ_MAX_ALPHA_SIZE)
    private val selector = IntArray(2 + capacity / BZ_G_SIZE)
    private val selectorMtf = IntArray(2 + capacity / BZ_G_SIZE)
    private val len = Array(BZ_N_GROUPS) { IntArray(BZ_MAX_ALPHA_SIZE) }
    private val code = Array(BZ_N_GROUPS) { IntArray(BZ_MAX_ALPHA_SIZE) }
    private val rfreq = Array(BZ_N_GROUPS) { IntArray(BZ_MAX_ALPHA_SIZE) }
    private val lenPack = Array(BZ_MAX_ALPHA_SIZE) { IntArray(4) }
    private val inUse = BooleanArray(256)
    private val unseqToSeq = IntArray(256)

    private var nblock = 0
    private var nInUse = 0
    private var nMTF = 0
    private var origPtr = 0
    private var blockNo = 0

    private var stateInCh = 256
    private var stateInLen = 0

    private var blockCRC = 0
    private var combinedCRC = 0
    private var budget = 0

    private var out = ByteArray(max(64, dataSize + dataSize / 50 + 1024))
    private var numZ = 0
    private var bsBuff = 0
    private var bsLive = 0

    fun compress(data: ByteArray): ByteArray {
        initRL()
        prepareNewBlock()
        var position = 0
        while (true) {
            while (position < data.size && nblock < blockMax) {
                addCharToBlock(data[position].toInt() and 0xff)
                position++
            }
            if (position >= data.size) {
                flushRL()
                compressBlock(true)
                break
            }
            compressBlock(false)
            prepareNewBlock()
        }
        return out.copyOf(numZ)
    }

    private fun prepareNewBlock() {
        nblock = 0
        blockCRC = -1
        for (i in 0 until 256) {
            inUse[i] = false
        }
        blockNo++
    }

    private fun initRL() {
        stateInCh = 256
        stateInLen = 0
    }

    private fun flushRL() {
        if (stateInCh < 256) {
            addPairToBlock()
        }
        initRL()
    }

    private fun addCharToBlock(ch: Int) {
        if (ch != stateInCh && stateInLen == 1) {
            val previous = stateInCh
            blockCRC = (blockCRC shl 8) xor CRC_TABLE[(blockCRC ushr 24) xor previous]
            inUse[previous] = true
            block[nblock++] = previous.toByte()
            stateInCh = ch
        } else if (ch != stateInCh || stateInLen == 255) {
            if (stateInCh < 256) {
                addPairToBlock()
            }
            stateInCh = ch
            stateInLen = 1
        } else {
            stateInLen++
        }
    }

    private fun addPairToBlock() {
        val ch = stateInCh and 0xff
        for (i in 0 until stateInLen) {
            blockCRC = (blockCRC shl 8) xor CRC_TABLE[(blockCRC ushr 24) xor ch]
        }
        inUse[stateInCh] = true
        val byte = ch.toByte()
        when (stateInLen) {
            1 -> {
                block[nblock++] = byte
            }
            2 -> {
                block[nblock++] = byte
                block[nblock++] = byte
            }
            3 -> {
                block[nblock++] = byte
                block[nblock++] = byte
                block[nblock++] = byte
            }
            else -> {
                inUse[stateInLen - 4] = true
                block[nblock++] = byte
                block[nblock++] = byte
                block[nblock++] = byte
                block[nblock++] = byte
                block[nblock++] = (stateInLen - 4).toByte()
            }
        }
    }


    private fun grow(bytes: Int) {
        if (numZ + bytes > out.size) {
            out = out.copyOf(max(out.size * 2, numZ + bytes))
        }
    }

    private fun bsW(n: Int, v: Int) {
        grow(4)
        while (bsLive >= 8) {
            out[numZ++] = (bsBuff ushr 24).toByte()
            bsBuff = bsBuff shl 8
            bsLive -= 8
        }
        bsBuff = bsBuff or (v shl (32 - bsLive - n))
        bsLive += n
    }

    private fun bsPutUChar(c: Int) = bsW(8, c)

    private fun bsPutUInt32(u: Int) {
        bsW(8, (u ushr 24) and 0xff)
        bsW(8, (u ushr 16) and 0xff)
        bsW(8, (u ushr 8) and 0xff)
        bsW(8, u and 0xff)
    }

    private fun bsInitWrite() {
        bsLive = 0
        bsBuff = 0
    }

    private fun bsFinishWrite() {
        grow(4)
        while (bsLive > 0) {
            out[numZ++] = (bsBuff ushr 24).toByte()
            bsBuff = bsBuff shl 8
            bsLive -= 8
        }
    }


    private fun compressBlock(isLastBlock: Boolean) {
        if (nblock > 0) {
            blockCRC = blockCRC.inv()
            combinedCRC = (combinedCRC shl 1) or (combinedCRC ushr 31)
            combinedCRC = combinedCRC xor blockCRC
            blockSort()
        }

        if (blockNo == 1) {
            bsInitWrite()
            bsPutUChar(0x42)
            bsPutUChar(0x5a)
            bsPutUChar(0x68)
            bsPutUChar(0x30 + blockSize100k)
        }

        if (nblock > 0) {
            bsPutUChar(0x31)
            bsPutUChar(0x41)
            bsPutUChar(0x59)
            bsPutUChar(0x26)
            bsPutUChar(0x53)
            bsPutUChar(0x59)
            bsPutUInt32(blockCRC)
            bsW(1, 0)
            bsW(24, origPtr)
            generateMTFValues()
            sendMTFValues()
        }

        if (isLastBlock) {
            bsPutUChar(0x17)
            bsPutUChar(0x72)
            bsPutUChar(0x45)
            bsPutUChar(0x38)
            bsPutUChar(0x50)
            bsPutUChar(0x90)
            bsPutUInt32(combinedCRC)
            bsFinishWrite()
        }
    }


    private fun makeMaps() {
        nInUse = 0
        for (i in 0 until 256) {
            if (inUse[i]) {
                unseqToSeq[i] = nInUse
                nInUse++
            }
        }
    }

    private fun generateMTFValues() {
        val yy = IntArray(256)
        makeMaps()
        val eob = nInUse + 1
        for (i in 0..eob) {
            mtfFreq[i] = 0
        }

        var wr = 0
        var zPend = 0
        for (i in 0 until nInUse) {
            yy[i] = i
        }

        for (i in 0 until nblock) {
            var j = ptr[i] - 1
            if (j < 0) {
                j += nblock
            }
            val llI = unseqToSeq[block[j].toInt() and 0xff]

            if (yy[0] == llI) {
                zPend++
            } else {
                if (zPend > 0) {
                    zPend--
                    while (true) {
                        if (zPend and 1 != 0) {
                            mtfv[wr] = BZ_RUNB
                            wr++
                            mtfFreq[BZ_RUNB]++
                        } else {
                            mtfv[wr] = BZ_RUNA
                            wr++
                            mtfFreq[BZ_RUNA]++
                        }
                        if (zPend < 2) {
                            break
                        }
                        zPend = (zPend - 2) / 2
                    }
                    zPend = 0
                }
                var rtmp = yy[1]
                yy[1] = yy[0]
                var ryyj = 1
                while (llI != rtmp) {
                    ryyj++
                    val rtmp2 = rtmp
                    rtmp = yy[ryyj]
                    yy[ryyj] = rtmp2
                }
                yy[0] = rtmp
                mtfv[wr] = ryyj + 1
                wr++
                mtfFreq[ryyj + 1]++
            }
        }

        if (zPend > 0) {
            zPend--
            while (true) {
                if (zPend and 1 != 0) {
                    mtfv[wr] = BZ_RUNB
                    wr++
                    mtfFreq[BZ_RUNB]++
                } else {
                    mtfv[wr] = BZ_RUNA
                    wr++
                    mtfFreq[BZ_RUNA]++
                }
                if (zPend < 2) {
                    break
                }
                zPend = (zPend - 2) / 2
            }
        }

        mtfv[wr] = eob
        wr++
        mtfFreq[eob]++
        nMTF = wr
    }

    private fun sendMTFValues() {
        val cost = IntArray(BZ_N_GROUPS)
        val fave = IntArray(BZ_N_GROUPS)

        val alphaSize = nInUse + 2
        for (t in 0 until BZ_N_GROUPS) {
            for (v in 0 until alphaSize) {
                len[t][v] = BZ_GREATER_ICOST
            }
        }

        check(nMTF > 0) { "No MTF values to code." }
        val nGroups = when {
            nMTF < 200 -> 2
            nMTF < 600 -> 3
            nMTF < 1200 -> 4
            nMTF < 2400 -> 5
            else -> 6
        }

        var gs: Int
        var ge: Int
        run {
            var nPart = nGroups
            var remF = nMTF
            gs = 0
            while (nPart > 0) {
                val tFreq = remF / nPart
                ge = gs - 1
                var aFreq = 0
                while (aFreq < tFreq && ge < alphaSize - 1) {
                    ge++
                    aFreq += mtfFreq[ge]
                }
                if (ge > gs && nPart != nGroups && nPart != 1 && (nGroups - nPart) % 2 == 1) {
                    aFreq -= mtfFreq[ge]
                    ge--
                }
                for (v in 0 until alphaSize) {
                    len[nPart - 1][v] = if (v >= gs && v <= ge) BZ_LESSER_ICOST else BZ_GREATER_ICOST
                }
                nPart--
                gs = ge + 1
                remF -= aFreq
            }
        }

        var nSelectors = 0
        for (iter in 0 until BZ_N_ITERS) {
            for (t in 0 until nGroups) {
                fave[t] = 0
            }
            for (t in 0 until nGroups) {
                for (v in 0 until alphaSize) {
                    rfreq[t][v] = 0
                }
            }

            if (nGroups == 6) {
                for (v in 0 until alphaSize) {
                    lenPack[v][0] = (len[1][v] shl 16) or len[0][v]
                    lenPack[v][1] = (len[3][v] shl 16) or len[2][v]
                    lenPack[v][2] = (len[5][v] shl 16) or len[4][v]
                }
            }

            nSelectors = 0
            gs = 0
            while (true) {
                if (gs >= nMTF) {
                    break
                }
                ge = gs + BZ_G_SIZE - 1
                if (ge >= nMTF) {
                    ge = nMTF - 1
                }

                for (t in 0 until nGroups) {
                    cost[t] = 0
                }
                if (nGroups == 6 && ge - gs + 1 == 50) {
                    var cost01 = 0
                    var cost23 = 0
                    var cost45 = 0
                    for (nn in 0 until 50) {
                        val icv = mtfv[gs + nn]
                        cost01 += lenPack[icv][0]
                        cost23 += lenPack[icv][1]
                        cost45 += lenPack[icv][2]
                    }
                    cost[0] = cost01 and 0xffff
                    cost[1] = cost01 ushr 16
                    cost[2] = cost23 and 0xffff
                    cost[3] = cost23 ushr 16
                    cost[4] = cost45 and 0xffff
                    cost[5] = cost45 ushr 16
                } else {
                    for (i in gs..ge) {
                        val icv = mtfv[i]
                        for (t in 0 until nGroups) {
                            cost[t] += len[t][icv]
                        }
                    }
                }

                var bc = 999999999
                var bt = -1
                for (t in 0 until nGroups) {
                    if (cost[t] < bc) {
                        bc = cost[t]
                        bt = t
                    }
                }
                fave[bt]++
                selector[nSelectors] = bt
                nSelectors++

                val freqs = rfreq[bt]
                for (i in gs..ge) {
                    freqs[mtfv[i]]++
                }

                gs = ge + 1
            }

            for (t in 0 until nGroups) {
                makeCodeLengths(len[t], rfreq[t], alphaSize, BZ_MAX_COMPRESS_CODE_LEN)
            }
        }

        check(nGroups < 8) { "Too many coding tables: $nGroups." }
        check(nSelectors < 32768 && nSelectors <= selector.size) { "Too many selectors: $nSelectors." }

        run {
            val pos = IntArray(BZ_N_GROUPS)
            for (i in 0 until nGroups) {
                pos[i] = i
            }
            for (i in 0 until nSelectors) {
                val llI = selector[i]
                var j = 0
                var tmp = pos[j]
                while (llI != tmp) {
                    j++
                    val tmp2 = tmp
                    tmp = pos[j]
                    pos[j] = tmp2
                }
                pos[0] = tmp
                selectorMtf[i] = j
            }
        }

        for (t in 0 until nGroups) {
            var minLen = 32
            var maxLen = 0
            for (i in 0 until alphaSize) {
                if (len[t][i] > maxLen) {
                    maxLen = len[t][i]
                }
                if (len[t][i] < minLen) {
                    minLen = len[t][i]
                }
            }
            check(maxLen <= BZ_MAX_COMPRESS_CODE_LEN) { "Code length $maxLen exceeds the limit." }
            check(minLen >= 1) { "Code length $minLen below the limit." }
            assignCodes(code[t], len[t], minLen, maxLen, alphaSize)
        }

        run {
            val inUse16 = BooleanArray(16)
            for (i in 0 until 16) {
                inUse16[i] = false
                for (j in 0 until 16) {
                    if (inUse[i * 16 + j]) {
                        inUse16[i] = true
                    }
                }
            }
            for (i in 0 until 16) {
                bsW(1, if (inUse16[i]) 1 else 0)
            }
            for (i in 0 until 16) {
                if (inUse16[i]) {
                    for (j in 0 until 16) {
                        bsW(1, if (inUse[i * 16 + j]) 1 else 0)
                    }
                }
            }
        }

        bsW(3, nGroups)
        bsW(15, nSelectors)
        for (i in 0 until nSelectors) {
            for (j in 0 until selectorMtf[i]) {
                bsW(1, 1)
            }
            bsW(1, 0)
        }

        for (t in 0 until nGroups) {
            var curr = len[t][0]
            bsW(5, curr)
            for (i in 0 until alphaSize) {
                while (curr < len[t][i]) {
                    bsW(2, 2)
                    curr++
                }
                while (curr > len[t][i]) {
                    bsW(2, 3)
                    curr--
                }
                bsW(1, 0)
            }
        }

        var selCtr = 0
        gs = 0
        while (true) {
            if (gs >= nMTF) {
                break
            }
            ge = gs + BZ_G_SIZE - 1
            if (ge >= nMTF) {
                ge = nMTF - 1
            }
            check(selector[selCtr] < nGroups) { "Selector out of range." }
            val lengths = len[selector[selCtr]]
            val codes = code[selector[selCtr]]
            for (i in gs..ge) {
                val symbol = mtfv[i]
                bsW(lengths[symbol], codes[symbol])
            }
            gs = ge + 1
            selCtr++
        }
        check(selCtr == nSelectors) { "Wrote $selCtr groups for $nSelectors selectors." }
    }


    private val heap = IntArray(BZ_MAX_ALPHA_SIZE + 2)
    private val weight = IntArray(BZ_MAX_ALPHA_SIZE * 2)
    private val parent = IntArray(BZ_MAX_ALPHA_SIZE * 2)

    private fun upHeap(z: Int) {
        var zz = z
        val tmp = heap[zz]
        while (weight[tmp] < weight[heap[zz shr 1]]) {
            heap[zz] = heap[zz shr 1]
            zz = zz shr 1
        }
        heap[zz] = tmp
    }

    private fun downHeap(z: Int, nHeap: Int) {
        var zz = z
        val tmp = heap[zz]
        while (true) {
            var yy = zz shl 1
            if (yy > nHeap) {
                break
            }
            if (yy < nHeap && weight[heap[yy + 1]] < weight[heap[yy]]) {
                yy++
            }
            if (weight[tmp] < weight[heap[yy]]) {
                break
            }
            heap[zz] = heap[yy]
            zz = yy
        }
        heap[zz] = tmp
    }

    private fun makeCodeLengths(lengths: IntArray, freq: IntArray, alphaSize: Int, maxLen: Int) {
        for (i in 0 until alphaSize) {
            weight[i + 1] = (if (freq[i] == 0) 1 else freq[i]) shl 8
        }

        while (true) {
            var nNodes = alphaSize
            var nHeap = 0

            heap[0] = 0
            weight[0] = 0
            parent[0] = -2

            for (i in 1..alphaSize) {
                parent[i] = -1
                nHeap++
                heap[nHeap] = i
                upHeap(nHeap)
            }

            while (nHeap > 1) {
                val n1 = heap[1]
                heap[1] = heap[nHeap]
                nHeap--
                downHeap(1, nHeap)
                val n2 = heap[1]
                heap[1] = heap[nHeap]
                nHeap--
                downHeap(1, nHeap)
                nNodes++
                parent[n1] = nNodes
                parent[n2] = nNodes
                weight[nNodes] = addWeights(weight[n1], weight[n2])
                parent[nNodes] = -1
                nHeap++
                heap[nHeap] = nNodes
                upHeap(nHeap)
            }

            var tooLong = false
            for (i in 1..alphaSize) {
                var j = 0
                var k = i
                while (parent[k] >= 0) {
                    k = parent[k]
                    j++
                }
                lengths[i - 1] = j
                if (j > maxLen) {
                    tooLong = true
                }
            }

            if (!tooLong) {
                break
            }

            for (i in 1..alphaSize) {
                var j = weight[i] shr 8
                j = 1 + (j / 2)
                weight[i] = j shl 8
            }
        }
    }

    private fun addWeights(w1: Int, w2: Int): Int {
        val weights = (w1 and 0xffffff00.toInt()) + (w2 and 0xffffff00.toInt())
        val depth = 1 + max(w1 and 0xff, w2 and 0xff)
        return weights or depth
    }

    private fun assignCodes(codes: IntArray, lengths: IntArray, minLen: Int, maxLen: Int, alphaSize: Int) {
        var vec = 0
        for (n in minLen..maxLen) {
            for (i in 0 until alphaSize) {
                if (lengths[i] == n) {
                    codes[i] = vec
                    vec++
                }
            }
            vec = vec shl 1
        }
    }


    private fun blockSort() {
        if (nblock < 10000) {
            fallbackSort(nblock)
        } else {
            var wfact = WORK_FACTOR
            if (wfact < 1) {
                wfact = 1
            }
            if (wfact > 100) {
                wfact = 100
            }
            budget = nblock * ((wfact - 1) / 3)
            mainSort(nblock)
            if (budget < 0) {
                fallbackSort(nblock)
            }
        }

        origPtr = -1
        for (i in 0 until nblock) {
            if (ptr[i] == 0) {
                origPtr = i
                break
            }
        }
        check(origPtr != -1) { "Block sort lost the original pointer." }
    }


    private fun mainGtU(start1: Int, start2: Int, nblock: Int): Boolean {
        var i1 = start1
        var i2 = start2
        var n = 12
        while (n > 0) {
            val c1 = block[i1].toInt() and 0xff
            val c2 = block[i2].toInt() and 0xff
            if (c1 != c2) {
                return c1 > c2
            }
            i1++
            i2++
            n--
        }

        var k = nblock + 8
        do {
            var m = 8
            while (m > 0) {
                val c1 = block[i1].toInt() and 0xff
                val c2 = block[i2].toInt() and 0xff
                if (c1 != c2) {
                    return c1 > c2
                }
                val s1 = quadrant[i1]
                val s2 = quadrant[i2]
                if (s1 != s2) {
                    return s1 > s2
                }
                i1++
                i2++
                m--
            }
            if (i1 >= nblock) {
                i1 -= nblock
            }
            if (i2 >= nblock) {
                i2 -= nblock
            }
            k -= 8
            budget--
        } while (k >= 0)

        return false
    }

    private fun mainInsert(i: Int, h: Int, d: Int, nblock: Int, lo: Int) {
        val v = ptr[i]
        var j = i
        while (mainGtU(ptr[j - h] + d, v + d, nblock)) {
            ptr[j] = ptr[j - h]
            j -= h
            if (j <= lo + h - 1) {
                break
            }
        }
        ptr[j] = v
    }

    private fun mainSimpleSort(nblock: Int, lo: Int, hi: Int, d: Int) {
        val bigN = hi - lo + 1
        if (bigN < 2) {
            return
        }

        var hp = 0
        while (INCS[hp] < bigN) {
            hp++
        }
        hp--

        while (hp >= 0) {
            val h = INCS[hp]
            var i = lo + h
            while (true) {
                if (i > hi) {
                    break
                }
                mainInsert(i, h, d, nblock, lo)
                i++

                if (i > hi) {
                    break
                }
                mainInsert(i, h, d, nblock, lo)
                i++

                if (i > hi) {
                    break
                }
                mainInsert(i, h, d, nblock, lo)
                i++

                if (budget < 0) {
                    return
                }
            }
            hp--
        }
    }

    private fun med3(a0: Int, b0: Int, c: Int): Int {
        var a = a0
        var b = b0
        if (a > b) {
            val t = a
            a = b
            b = t
        }
        if (b > c) {
            b = c
            if (a > b) {
                b = a
            }
        }
        return b
    }

    private fun swap(i: Int, j: Int) {
        val tmp = ptr[i]
        ptr[i] = ptr[j]
        ptr[j] = tmp
    }

    private fun vswap(p1: Int, p2: Int, count: Int) {
        var yyp1 = p1
        var yyp2 = p2
        var yyn = count
        while (yyn > 0) {
            swap(yyp1, yyp2)
            yyp1++
            yyp2++
            yyn--
        }
    }

    private fun mainQSort3(nblock: Int, loSt: Int, hiSt: Int, dSt: Int) {
        val stackLo = IntArray(MAIN_QSORT_STACK_SIZE)
        val stackHi = IntArray(MAIN_QSORT_STACK_SIZE)
        val stackD = IntArray(MAIN_QSORT_STACK_SIZE)
        val nextLo = IntArray(3)
        val nextHi = IntArray(3)
        val nextD = IntArray(3)

        var sp = 0
        stackLo[sp] = loSt
        stackHi[sp] = hiSt
        stackD[sp] = dSt
        sp++

        while (sp > 0) {
            check(sp < MAIN_QSORT_STACK_SIZE - 2) { "Main sort stack overflow." }

            sp--
            val lo = stackLo[sp]
            val hi = stackHi[sp]
            val d = stackD[sp]

            if (hi - lo < MAIN_QSORT_SMALL_THRESH || d > MAIN_QSORT_DEPTH_THRESH) {
                mainSimpleSort(nblock, lo, hi, d)
                if (budget < 0) {
                    return
                }
                continue
            }

            val med = med3(
                block[ptr[lo] + d].toInt() and 0xff,
                block[ptr[hi] + d].toInt() and 0xff,
                block[ptr[(lo + hi) shr 1] + d].toInt() and 0xff,
            )

            var unLo = lo
            var ltLo = lo
            var unHi = hi
            var gtHi = hi

            while (true) {
                while (true) {
                    if (unLo > unHi) {
                        break
                    }
                    val n = (block[ptr[unLo] + d].toInt() and 0xff) - med
                    if (n == 0) {
                        swap(unLo, ltLo)
                        ltLo++
                        unLo++
                        continue
                    }
                    if (n > 0) {
                        break
                    }
                    unLo++
                }
                while (true) {
                    if (unLo > unHi) {
                        break
                    }
                    val n = (block[ptr[unHi] + d].toInt() and 0xff) - med
                    if (n == 0) {
                        swap(unHi, gtHi)
                        gtHi--
                        unHi--
                        continue
                    }
                    if (n < 0) {
                        break
                    }
                    unHi--
                }
                if (unLo > unHi) {
                    break
                }
                swap(unLo, unHi)
                unLo++
                unHi--
            }

            if (gtHi < ltLo) {
                stackLo[sp] = lo
                stackHi[sp] = hi
                stackD[sp] = d + 1
                sp++
                continue
            }

            var n = min(ltLo - lo, unLo - ltLo)
            vswap(lo, unLo - n, n)
            val m = min(hi - gtHi, gtHi - unHi)
            vswap(unLo, hi - m + 1, m)

            n = lo + unLo - ltLo - 1
            val mm = hi - (gtHi - unHi) + 1

            nextLo[0] = lo
            nextHi[0] = n
            nextD[0] = d
            nextLo[1] = mm
            nextHi[1] = hi
            nextD[1] = d
            nextLo[2] = n + 1
            nextHi[2] = mm - 1
            nextD[2] = d + 1

            if (nextHi[0] - nextLo[0] < nextHi[1] - nextLo[1]) {
                nextSwap(nextLo, nextHi, nextD, 0, 1)
            }
            if (nextHi[1] - nextLo[1] < nextHi[2] - nextLo[2]) {
                nextSwap(nextLo, nextHi, nextD, 1, 2)
            }
            if (nextHi[0] - nextLo[0] < nextHi[1] - nextLo[1]) {
                nextSwap(nextLo, nextHi, nextD, 0, 1)
            }

            for (index in 0 until 3) {
                stackLo[sp] = nextLo[index]
                stackHi[sp] = nextHi[index]
                stackD[sp] = nextD[index]
                sp++
            }
        }
    }

    private fun nextSwap(nextLo: IntArray, nextHi: IntArray, nextD: IntArray, a: Int, b: Int) {
        var tz = nextLo[a]
        nextLo[a] = nextLo[b]
        nextLo[b] = tz
        tz = nextHi[a]
        nextHi[a] = nextHi[b]
        nextHi[b] = tz
        tz = nextD[a]
        nextD[a] = nextD[b]
        nextD[b] = tz
    }

    private fun mainSort(nblock: Int) {
        for (i in 65536 downTo 0) {
            ftab[i] = 0
        }

        var j = (block[0].toInt() and 0xff) shl 8
        var i = nblock - 1
        while (i >= 0) {
            quadrant[i] = 0
            j = (j shr 8) or ((block[i].toInt() and 0xff) shl 8)
            ftab[j]++
            i--
        }

        for (k in 0 until BZ_N_OVERSHOOT) {
            block[nblock + k] = block[k]
            quadrant[nblock + k] = 0
        }

        for (k in 1..65536) {
            ftab[k] += ftab[k - 1]
        }

        var s = (block[0].toInt() and 0xff) shl 8
        i = nblock - 1
        while (i >= 0) {
            s = (s shr 8) or ((block[i].toInt() and 0xff) shl 8)
            val at = ftab[s] - 1
            ftab[s] = at
            ptr[at] = i
            i--
        }

        val runningOrder = IntArray(256)
        val bigDone = BooleanArray(256)
        val copyStart = IntArray(256)
        val copyEnd = IntArray(256)
        for (k in 0..255) {
            bigDone[k] = false
            runningOrder[k] = k
        }

        run {
            var h = 1
            do {
                h = 3 * h + 1
            } while (h <= 256)
            do {
                h /= 3
                for (index in h..255) {
                    val vv = runningOrder[index]
                    var jj = index
                    while (bigFreq(runningOrder[jj - h]) > bigFreq(vv)) {
                        runningOrder[jj] = runningOrder[jj - h]
                        jj -= h
                        if (jj <= h - 1) {
                            break
                        }
                    }
                    runningOrder[jj] = vv
                }
            } while (h != 1)
        }

        for (index in 0..255) {
            val ss = runningOrder[index]

            for (jj in 0..255) {
                if (jj != ss) {
                    val sb = (ss shl 8) + jj
                    if (ftab[sb] and SETMASK == 0) {
                        val lo = ftab[sb] and CLEARMASK
                        val hi = (ftab[sb + 1] and CLEARMASK) - 1
                        if (hi > lo) {
                            mainQSort3(nblock, lo, hi, BZ_N_RADIX)
                            if (budget < 0) {
                                return
                            }
                        }
                    }
                    ftab[sb] = ftab[sb] or SETMASK
                }
            }

            check(!bigDone[ss]) { "Big bucket $ss sorted twice." }

            run {
                for (jj in 0..255) {
                    copyStart[jj] = ftab[(jj shl 8) + ss] and CLEARMASK
                    copyEnd[jj] = (ftab[(jj shl 8) + ss + 1] and CLEARMASK) - 1
                }
                var jj = ftab[ss shl 8] and CLEARMASK
                while (jj < copyStart[ss]) {
                    var k = ptr[jj] - 1
                    if (k < 0) {
                        k += nblock
                    }
                    val c1 = block[k].toInt() and 0xff
                    if (!bigDone[c1]) {
                        ptr[copyStart[c1]++] = k
                    }
                    jj++
                }
                jj = (ftab[(ss + 1) shl 8] and CLEARMASK) - 1
                while (jj > copyEnd[ss]) {
                    var k = ptr[jj] - 1
                    if (k < 0) {
                        k += nblock
                    }
                    val c1 = block[k].toInt() and 0xff
                    if (!bigDone[c1]) {
                        ptr[copyEnd[c1]--] = k
                    }
                    jj--
                }
            }

            check(
                copyStart[ss] - 1 == copyEnd[ss] ||
                    (copyStart[ss] == 0 && copyEnd[ss] == nblock - 1),
            ) { "Bucket $ss copy bounds disagree." }

            for (jj in 0..255) {
                ftab[(jj shl 8) + ss] = ftab[(jj shl 8) + ss] or SETMASK
            }

            bigDone[ss] = true

            if (index < 255) {
                val bbStart = ftab[ss shl 8] and CLEARMASK
                val bbSize = (ftab[(ss + 1) shl 8] and CLEARMASK) - bbStart
                var shifts = 0

                while ((bbSize shr shifts) > 65534) {
                    shifts++
                }

                for (jj in bbSize - 1 downTo 0) {
                    val a2update = ptr[bbStart + jj]
                    val qVal = jj shr shifts
                    quadrant[a2update] = qVal
                    if (a2update < BZ_N_OVERSHOOT) {
                        quadrant[a2update + nblock] = qVal
                    }
                }
                check((bbSize - 1) shr shifts <= 65535) { "Quadrant value overflow." }
            }
        }
    }

    private fun bigFreq(b: Int) = ftab[(b + 1) shl 8] - ftab[b shl 8]


    private fun fallbackSimpleSort(fmap: IntArray, eclass: IntArray, lo: Int, hi: Int) {
        if (lo == hi) {
            return
        }

        if (hi - lo > 3) {
            var i = hi - 4
            while (i >= lo) {
                val tmp = fmap[i]
                val ecTmp = eclass[tmp]
                var j = i + 4
                while (j <= hi && ecTmp > eclass[fmap[j]]) {
                    fmap[j - 4] = fmap[j]
                    j += 4
                }
                fmap[j - 4] = tmp
                i--
            }
        }

        var i = hi - 1
        while (i >= lo) {
            val tmp = fmap[i]
            val ecTmp = eclass[tmp]
            var j = i + 1
            while (j <= hi && ecTmp > eclass[fmap[j]]) {
                fmap[j - 1] = fmap[j]
                j++
            }
            fmap[j - 1] = tmp
            i--
        }
    }

    private fun fallbackQSort3(fmap: IntArray, eclass: IntArray, loSt: Int, hiSt: Int) {
        val stackLo = IntArray(FALLBACK_QSORT_STACK_SIZE)
        val stackHi = IntArray(FALLBACK_QSORT_STACK_SIZE)

        var r = 0
        var sp = 0
        stackLo[sp] = loSt
        stackHi[sp] = hiSt
        sp++

        while (sp > 0) {
            check(sp < FALLBACK_QSORT_STACK_SIZE - 1) { "Fallback sort stack overflow." }

            sp--
            val lo = stackLo[sp]
            val hi = stackHi[sp]
            if (hi - lo < FALLBACK_QSORT_SMALL_THRESH) {
                fallbackSimpleSort(fmap, eclass, lo, hi)
                continue
            }

            r = ((r * 7621) + 1) % 32768
            val med = when (r % 3) {
                0 -> eclass[fmap[lo]]
                1 -> eclass[fmap[(lo + hi) shr 1]]
                else -> eclass[fmap[hi]]
            }

            var unLo = lo
            var ltLo = lo
            var unHi = hi
            var gtHi = hi

            while (true) {
                while (true) {
                    if (unLo > unHi) {
                        break
                    }
                    val n = eclass[fmap[unLo]] - med
                    if (n == 0) {
                        val tmp = fmap[unLo]
                        fmap[unLo] = fmap[ltLo]
                        fmap[ltLo] = tmp
                        ltLo++
                        unLo++
                        continue
                    }
                    if (n > 0) {
                        break
                    }
                    unLo++
                }
                while (true) {
                    if (unLo > unHi) {
                        break
                    }
                    val n = eclass[fmap[unHi]] - med
                    if (n == 0) {
                        val tmp = fmap[unHi]
                        fmap[unHi] = fmap[gtHi]
                        fmap[gtHi] = tmp
                        gtHi--
                        unHi--
                        continue
                    }
                    if (n < 0) {
                        break
                    }
                    unHi--
                }
                if (unLo > unHi) {
                    break
                }
                val tmp = fmap[unLo]
                fmap[unLo] = fmap[unHi]
                fmap[unHi] = tmp
                unLo++
                unHi--
            }

            if (gtHi < ltLo) {
                continue
            }

            var n = min(ltLo - lo, unLo - ltLo)
            fallbackVswap(fmap, lo, unLo - n, n)
            val m = min(hi - gtHi, gtHi - unHi)
            fallbackVswap(fmap, unLo, hi - m + 1, m)

            n = lo + unLo - ltLo - 1
            val mm = hi - (gtHi - unHi) + 1

            if (n - lo > hi - mm) {
                stackLo[sp] = lo
                stackHi[sp] = n
                sp++
                stackLo[sp] = mm
                stackHi[sp] = hi
                sp++
            } else {
                stackLo[sp] = mm
                stackHi[sp] = hi
                sp++
                stackLo[sp] = lo
                stackHi[sp] = n
                sp++
            }
        }
    }

    private fun fallbackVswap(fmap: IntArray, p1: Int, p2: Int, count: Int) {
        var yyp1 = p1
        var yyp2 = p2
        var yyn = count
        while (yyn > 0) {
            val tmp = fmap[yyp1]
            fmap[yyp1] = fmap[yyp2]
            fmap[yyp2] = tmp
            yyp1++
            yyp2++
            yyn--
        }
    }

    private fun fallbackSort(nblock: Int) {
        val fmap = ptr
        val eclass = this.eclass ?: IntArray(max(capacity, 1)).also { this.eclass = it }
        val bhtab = ftab
        val localFtab = IntArray(257)
        val ftabCopy = IntArray(256)

        for (i in 0 until 257) {
            localFtab[i] = 0
        }
        for (i in 0 until nblock) {
            localFtab[block[i].toInt() and 0xff]++
        }
        for (i in 0 until 256) {
            ftabCopy[i] = localFtab[i]
        }
        for (i in 1 until 257) {
            localFtab[i] += localFtab[i - 1]
        }

        for (i in 0 until nblock) {
            val j = block[i].toInt() and 0xff
            val k = localFtab[j] - 1
            localFtab[j] = k
            fmap[k] = i
        }

        val nBhtab = 2 + (nblock / 32)
        for (i in 0 until nBhtab) {
            bhtab[i] = 0
        }
        for (i in 0 until 256) {
            setBh(bhtab, localFtab[i])
        }

        for (i in 0 until 32) {
            setBh(bhtab, nblock + 2 * i)
            clearBh(bhtab, nblock + 2 * i + 1)
        }

        var h = 1
        while (true) {
            var j = 0
            for (i in 0 until nblock) {
                if (isSetBh(bhtab, i)) {
                    j = i
                }
                var k = fmap[i] - h
                if (k < 0) {
                    k += nblock
                }
                eclass[k] = j
            }

            var nNotDone = 0
            var r = -1
            while (true) {
                var k = r + 1
                while (isSetBh(bhtab, k) && (k and 0x1f) != 0) {
                    k++
                }
                if (isSetBh(bhtab, k)) {
                    while (bhtab[k shr 5] == -1) {
                        k += 32
                    }
                    while (isSetBh(bhtab, k)) {
                        k++
                    }
                }
                val l = k - 1
                if (l >= nblock) {
                    break
                }
                while (!isSetBh(bhtab, k) && (k and 0x1f) != 0) {
                    k++
                }
                if (!isSetBh(bhtab, k)) {
                    while (bhtab[k shr 5] == 0) {
                        k += 32
                    }
                    while (!isSetBh(bhtab, k)) {
                        k++
                    }
                }
                r = k - 1
                if (r >= nblock) {
                    break
                }

                if (r > l) {
                    nNotDone += (r - l + 1)
                    fallbackQSort3(fmap, eclass, l, r)

                    var cc = -1
                    for (i in l..r) {
                        val cc1 = eclass[fmap[i]]
                        if (cc != cc1) {
                            setBh(bhtab, i)
                            cc = cc1
                        }
                    }
                }
            }

            h *= 2
            if (h > nblock || nNotDone == 0) {
                break
            }
        }

        var j = 0
        for (i in 0 until nblock) {
            while (ftabCopy[j] == 0) {
                j++
            }
            ftabCopy[j]--
            block[fmap[i]] = j.toByte()
        }
        check(j < 256) { "Fallback sort lost a byte value." }
    }

    private fun setBh(bhtab: IntArray, zz: Int) {
        bhtab[zz shr 5] = bhtab[zz shr 5] or (1 shl (zz and 31))
    }

    private fun clearBh(bhtab: IntArray, zz: Int) {
        bhtab[zz shr 5] = bhtab[zz shr 5] and (1 shl (zz and 31)).inv()
    }

    private fun isSetBh(bhtab: IntArray, zz: Int) = bhtab[zz shr 5] and (1 shl (zz and 31)) != 0
}

private const val BZ_N_RADIX = 2

private const val BZ_N_QSORT = 12

private const val BZ_N_SHELL = 18

private const val BZ_N_OVERSHOOT = BZ_N_RADIX + BZ_N_QSORT + BZ_N_SHELL + 2

private const val BZ_MAX_ALPHA_SIZE = 258
private const val BZ_N_GROUPS = 6
private const val BZ_G_SIZE = 50
private const val BZ_N_ITERS = 4
private const val BZ_RUNA = 0
private const val BZ_RUNB = 1
private const val BZ_LESSER_ICOST = 0
private const val BZ_GREATER_ICOST = 15

private const val BZ_MAX_COMPRESS_CODE_LEN = 17

private const val WORK_FACTOR = 30

private const val MAIN_QSORT_SMALL_THRESH = 20
private const val MAIN_QSORT_DEPTH_THRESH = BZ_N_RADIX + BZ_N_QSORT
private const val MAIN_QSORT_STACK_SIZE = 100
private const val FALLBACK_QSORT_SMALL_THRESH = 10
private const val FALLBACK_QSORT_STACK_SIZE = 100

private const val SETMASK = 1 shl 21
private const val CLEARMASK = SETMASK.inv()

private val INCS = intArrayOf(
    1, 4, 13, 40, 121, 364, 1093, 3280,
    9841, 29524, 88573, 265720,
    797161, 2391484,
)

private val CRC_TABLE = IntArray(256) { index ->
    var crc = index shl 24
    repeat(8) {
        crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
    }
    crc
}
