package org.darkan.migration

import kotlin.system.exitProcess

private const val USAGE = """
Legacy -> modern database migration.

  inspect --source <uri>
      Read-only survey of a legacy database: counts, and the documents that need a decision.

  migrate --source <uri> --target <uri> [--dry-run] [--replace] [--only accounts,clans,...]
      Transform every collection from source into target. The source is never written.
      --dry-run   run the full transform and print the report, write nothing
      --replace   drop target collections that already contain documents

  verify --target <uri>
      Decode every target document through the modern code paths. Run with MONGO_URI=<target uri>.

URIs must name a database, e.g. mongodb://user:pass@host:27017/darkan-dev?authSource=admin
"""

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val command = args[0]
    val flags = HashSet<String>()
    val options = HashMap<String, String>()
    var i = 1
    while (i < args.size) {
        val arg = args[i]
        if (!arg.startsWith("--")) usage("Unexpected argument: $arg")
        val name = arg.removePrefix("--")
        if (name in setOf("dry-run", "replace")) {
            flags += name
            i++
        } else {
            options[name] = args.getOrNull(i + 1) ?: usage("--$name needs a value")
            i += 2
        }
    }

    val ok = when (command) {
        "inspect" -> MongoTarget(option(options, "source")).use { source ->
            inspect(source)
            true
        }
        "migrate" -> MongoTarget(option(options, "source")).use { source ->
            MongoTarget(option(options, "target")).use { target ->
                val only = options["only"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                only.filter { it !in COLLECTIONS }.forEach { usage("Unknown collection: $it") }
                migrate(source, target, only, dryRun = "dry-run" in flags, replace = "replace" in flags)
            }
        }
        "verify" -> MongoTarget(option(options, "target")).use { verify(it) }
        else -> usage("Unknown command: $command")
    }
    println(if (ok) "Finished with no problems." else "Finished WITH PROBLEMS - see report above.")
    exitProcess(if (ok) 0 else 1)
}

private fun option(options: Map<String, String>, name: String) = options[name] ?: usage("--$name is required")

private fun usage(error: String? = null): Nothing {
    if (error != null) System.err.println("error: $error")
    println(USAGE.trimIndent())
    exitProcess(2)
}
