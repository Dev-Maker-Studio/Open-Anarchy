package org.darkan.cs2

import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.ReadOnlyCache

class Cs2HotReload(
    private val cache: Cache,
    private val analyzer: Cs2Analyzer,
    val project: Cs2SourceProject = Cs2SourceProject(cache, analyzer),
) : Cs2Context, AutoCloseable {

    @Volatile
    var autoReload: Boolean = true

    @Volatile
    var watcherError: String? = null
        private set

    @Volatile
    var onReload: ((Event) -> Unit)? = null

    private val queue = ConcurrentLinkedQueue<Event>()
    private val entries = ArrayDeque<Event>()
    private val logLock = Any()

    @Volatile
    private var watcher: WatchService? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var watching: Path? = null


    class Event(
        val timestamp: Long,
        val scriptId: Int,
        val file: File?,
        val ok: Boolean,
        val message: String,
        val diagnostic: Cs2SourceProject.Diagnostic? = null,
        val signatureChanged: Boolean = false,
        val staleCallers: List<Int> = emptyList(),
    ) {
        val time: String
            get() = CLOCK.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

        override fun toString(): String = "$time ${if (ok) "ok" else "FAILED"} $scriptId: $message"

        private companion object {
            val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        }
    }

    val log: List<Event> get() = synchronized(logLock) { entries.toList() }

    val errors: List<Event> get() = synchronized(logLock) { entries.filter { !it.ok } }

    val overridden: List<Int> get() = analyzer.overriddenIds

    fun clearLog() = synchronized(logLock) { entries.clear() }

    fun poll(): Event? = queue.poll()

    fun drainInto(sink: MutableList<Event>): Int {
        var count = 0
        while (true) {
            val event = queue.poll() ?: break
            sink.add(event)
            onReload?.invoke(event)
            count++
        }
        return count
    }

    fun drain(): List<Event> {
        val events = ArrayList<Event>()
        drainInto(events)
        return events
    }


    fun reload(scriptId: Int): Event = install(scriptId, project.fileFor(scriptId), project.compile(scriptId))

    fun reloadFile(file: File): Event =
        install(project.scriptIdOf(file) ?: -1, file, project.compileFile(file))

    fun reloadAll(): List<Event> = overridden.map { reload(it) }

    fun revert(scriptId: Int): Boolean {
        val dropped = analyzer.uninstall(scriptId)
        if (dropped) record(Event(now(), scriptId, project.fileFor(scriptId), true, "reverted to the cached bytecode"))
        return dropped
    }

    fun revertAll(): Int {
        val count = analyzer.uninstallAll()
        if (count > 0) record(Event(now(), -1, null, true, "reverted $count scripts to the cached bytecode"))
        return count
    }

    class Sweep(
        val compiled: Int,
        val failed: Int,
        val identical: Int,
        val differing: Int,
        val installed: Int,
        val failures: List<Cs2SourceProject.Diagnostic>,
    ) {
        val exactRate: Double get() = if (compiled == 0) 0.0 else identical.toDouble() / compiled
    }

    fun sweepFolder(
        limit: Int? = null,
        installEdits: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Sweep {
        val ids = project.sourceIds().let { if (limit == null) it else it.take(limit) }
        var compiled = 0
        var identical = 0
        var differing = 0
        var installedCount = 0
        val failures = ArrayList<Cs2SourceProject.Diagnostic>()

        ids.forEachIndexed { index, id ->
            when (val result = project.compile(id)) {
                is Cs2SourceProject.CompileResult.Failed -> failures.add(result.error)
                is Cs2SourceProject.CompileResult.Ok -> {
                    compiled++
                    val original = project.cachedBytes(result.result.scriptId)
                    if (original != null && result.result.bytes.contentEquals(original)) {
                        identical++
                    } else {
                        differing++
                        if (installEdits) {
                            analyzer.install(result.result.scriptId, result.result.script)
                            installedCount++
                        }
                    }
                }
            }
            progress(index + 1, ids.size)
        }
        return Sweep(compiled, failures.size, identical, differing, installedCount, failures)
    }

    private fun install(fallbackId: Int, file: File?, result: Cs2SourceProject.CompileResult): Event {
        val compiled = result.compiled
        if (compiled == null) {
            val diagnostic = result.diagnostic
            val event = Event(
                timestamp = now(),
                scriptId = diagnostic?.scriptId?.takeIf { it >= 0 } ?: fallbackId,
                file = file,
                ok = false,
                message = diagnostic?.message ?: "compile failed",
                diagnostic = diagnostic,
            )
            record(event)
            return event
        }

        val installed = analyzer.install(compiled.scriptId, compiled.script)
        val original = project.cachedBytes(compiled.scriptId)
        val matchesCache = original != null && compiled.bytes.contentEquals(original)
        val callers = if (installed.signatureChanged) analyzer.callersOf(compiled.scriptId) else emptyList()

        val notes = ArrayList<String>()
        notes.add("${compiled.script.size} instructions, ${compiled.bytes.size} bytes")
        notes.add(if (matchesCache) "unchanged from the cache" else "differs from the cache")
        if (installed.headerChanged) notes.add("argument counts changed")
        if (installed.signatureChanged) {
            notes.add(
                "returns ${installed.previousReturnSignature} -> ${installed.returnSignature}; " +
                    "${callers.size} callers need recompiling",
            )
        }
        installed.failure?.let { notes.add("does not balance: $it") }

        val event = Event(
            timestamp = now(),
            scriptId = compiled.scriptId,
            file = file,
            ok = installed.failure == null,
            message = notes.joinToString("; "),
            signatureChanged = installed.signatureChanged,
            staleCallers = callers,
        )
        record(event)
        return event
    }

    fun reanalyse(log: (String) -> Unit = {}): Cs2Analyzer.Report {
        val report = analyzer.analyse(log)
        record(Event(now(), -1, null, report.ok, "re-analysed ${report.analysed} scripts, ${report.problems.size} problems"))
        return report
    }

    fun callersOf(scriptId: Int): List<Int> = analyzer.callersOf(scriptId)


    val cacheWritable: Boolean get() = cache !is ReadOnlyCache

    fun writeToCache(scriptId: Int): Event {
        if (!cacheWritable) {
            val event = Event(
                now(), scriptId, project.fileFor(scriptId), false,
                "cache is read-only (${cache::class.simpleName}); cannot commit script $scriptId",
            )
            record(event)
            return event
        }
        val result = project.compile(scriptId)
        val compiled = result.compiled ?: return install(scriptId, project.fileFor(scriptId), result)
        return try {
            cache.write(Index.CLIENT_SCRIPTS, compiled.scriptId, 0, compiled.bytes)
            val updated = cache.update()
            val event = Event(
                now(), compiled.scriptId, compiled.file, updated,
                if (updated) "committed ${compiled.bytes.size} bytes to cache index 12"
                else "cache.update() refused the write",
            )
            record(event)
            event
        } catch (e: Exception) {
            val event = Event(
                now(), compiled.scriptId, compiled.file, false,
                "cache write failed: ${e.message ?: e::class.simpleName}",
            )
            record(event)
            event
        }
    }


    val isWatching: Boolean get() = thread?.isAlive == true

    val watchedFolder: File? get() = watching?.toFile()

    fun start(): Boolean {
        stop()
        val directory = project.folder
        if (!directory.isDirectory) {
            watcherError = "no such folder: ${directory.path}"
            return false
        }
        return try {
            val service = FileSystems.getDefault().newWatchService()
            val path = directory.toPath()
            register(service, path)
            for (child in directory.listFiles().orEmpty()) {
                if (child.isDirectory) register(service, child.toPath())
            }
            watcher = service
            watching = path
            watcherError = null
            val worker = Thread({ watch(service, path) }, "cs2-hot-reload")
            worker.isDaemon = true
            thread = worker
            worker.start()
            record(Event(now(), -1, directory, true, "watching ${directory.path}"))
            true
        } catch (e: Exception) {
            watcherError = "cannot watch ${directory.path}: ${e.message}"
            false
        }
    }

    fun stop() {
        val service = watcher
        watcher = null
        watching = null
        val worker = thread
        thread = null
        try {
            service?.close()
        } catch (e: Exception) {
        }
        worker?.join(500)
    }

    fun useFolder(directory: File): Boolean {
        val wasWatching = isWatching
        stop()
        val resolved = project.useFolder(directory)
        if (resolved != directory) {
            watcherError = "no such folder: ${directory.path}"
            return false
        }
        return if (wasWatching) start() else true
    }

    private fun register(service: WatchService, path: Path) {
        path.register(
            service,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )
    }

    private fun watch(service: WatchService, root: Path) {
        val pending = HashMap<File, Long>()
        while (thread === Thread.currentThread()) {
            try {
                val key = service.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (key != null) {
                    val watched = key.watchable() as? Path ?: root
                    for (event in key.pollEvents()) {
                        val relative = event.context() as? Path ?: continue
                        val file = watched.resolve(relative).toFile()
                        if (file.isDirectory) {
                            try {
                                register(service, file.toPath())
                            } catch (e: Exception) {
                            }
                            continue
                        }
                        if (!interesting(file)) continue
                        pending[file] = System.currentTimeMillis()
                    }
                    if (!key.reset()) return
                }
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }

            if (pending.isEmpty()) continue
            val now = System.currentTimeMillis()
            val due = pending.filterValues { now - it >= DEBOUNCE_MS }.keys.toList()
            due.forEach { pending.remove(it) }
            for (file in due) {
                try {
                    handle(file)
                } catch (e: Exception) {
                    record(
                        Event(now(), -1, file, false, "reload crashed: ${e.message ?: e::class.simpleName}"),
                    )
                }
            }
        }
    }

    private fun interesting(file: File): Boolean {
        if (file.name.startsWith(".")) return false
        return project.isScriptFile(file) || project.isDeclarationFile(file)
    }

    private fun handle(file: File) {
        if (project.isDeclarationFile(file)) {
            project.reloadSymbols()
            record(Event(now(), -1, file, true, "declarations changed; symbol table re-read"))
            if (autoReload) reloadAll()
            return
        }
        if (!file.isFile) {
            val id = Cs2Names.scriptIdOfPath(
                Cs2Layout.relativePath(project.folder, file) ?: return,
            ) ?: return
            if (analyzer.uninstall(id)) {
                record(Event(now(), id, file, true, "source deleted; reverted to the cached bytecode"))
            }
            return
        }
        if (!autoReload) {
            record(Event(now(), project.scriptIdOf(file) ?: -1, file, true, "changed (auto-reload off)"))
            return
        }
        reloadFile(file)
    }


    private fun now() = System.currentTimeMillis()

    private fun record(event: Event) {
        synchronized(logLock) {
            entries.addLast(event)
            while (entries.size > LOG_LIMIT) entries.removeFirst()
        }
        queue.add(event)
    }

    override fun script(id: Int): Cs2Script? = analyzer.script(id)

    override fun paramIsString(paramId: Int): Boolean = analyzer.paramIsString(paramId)

    override fun close() = stop()

    companion object {
        const val DEBOUNCE_MS = 150L
        private const val POLL_MS = 100L
        private const val LOG_LIMIT = 200

    }
}
