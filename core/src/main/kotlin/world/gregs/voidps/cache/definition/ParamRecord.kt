package world.gregs.voidps.cache.definition

/**
 * One `key / value` record of a param opcode (249), in the order the file carried it.
 *
 * A [Parameterized] definition decodes its params into a map, which loses a record whenever a file
 * carries the same key twice. The 37 shipped structs that do keep their records whole so the file
 * can be written back; see [world.gregs.voidps.cache.definition.data.StructDefinition.paramRecords].
 */
data class ParamRecord(val key: Int, val value: Any)
