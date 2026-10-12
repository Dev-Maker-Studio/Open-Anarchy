package com.rs.utils.bench

import world.gregs.voidps.cache.Cache
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main() {
    for (i in 0..50)
        runCoroutineTest()
    println("JVM heated up. Starting...")

    var results: MutableList<Double> = mutableListOf()
    for (i in 0..50) {
        var start = System.nanoTime()
        runCoroutineTest()
        results.add((System.nanoTime() - start) / 1000000.0)
    }

    println("Kotlin coroutines: "+results.average()+" ms")
}

fun runCoroutineTest() {
    Cache.get()
    runBlocking {
        (0 until 1000).map { _ ->
            launch {
            }
        }.forEach { it.join() }
    }
}