package org.darkan.tools.rsinterface

import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentHook
import world.gregs.voidps.cache.definition.data.InterfaceComponentSetting
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object ComponentDefinitions {

    const val ROOT = -1

    const val MAX_COMPONENTS = 0xffff

    fun pack(interfaceId: Int, index: Int): Int = (interfaceId shl 16) or index

    fun interfaceOf(hash: Int): Int = hash shr 16

    fun indexOf(hash: Int): Int = hash and 0xffff

    fun create(interfaceId: Int, index: Int, type: Int = ComponentType.CONTAINER): InterfaceComponentDefinition =
        InterfaceComponentDefinition(id = pack(interfaceId, index), type = type)

    fun copyHook(hook: InterfaceComponentHook?): InterfaceComponentHook? =
        if (hook == null) null else InterfaceComponentHook(hook.script, hook.args.copyOf())

    @Suppress("LongMethod")
    fun deepCopy(source: InterfaceComponentDefinition): InterfaceComponentDefinition = source.copy(
        keyRepeats = source.keyRepeats?.copyOf(),
        keyCodes = source.keyCodes?.copyOf(),
        keyModifiers = source.keyModifiers?.copyOf(),
        options = source.options?.copyOf(),
        mouseIcon = source.mouseIcon?.copyOf(),
        setting = InterfaceComponentSetting(source.setting.setting, source.setting.anInt7413),
        clientParams = source.clientParams?.let { HashMap(it) },
        onLoadScript = copyHook(source.onLoadScript),
        onMouseOver = copyHook(source.onMouseOver),
        onMouseLeaveScript = copyHook(source.onMouseLeaveScript),
        hookParams = copyHook(source.hookParams),
        onTargetEnter = copyHook(source.onTargetEnter),
        onVarpTransmit = copyHook(source.onVarpTransmit),
        mouseLeaveScript = copyHook(source.mouseLeaveScript),
        onStatTransmit = copyHook(source.onStatTransmit),
        onTimer = copyHook(source.onTimer),
        params = copyHook(source.params),
        onTargetLeave = copyHook(source.onTargetLeave),
        popupScript = copyHook(source.popupScript),
        onClick = copyHook(source.onClick),
        onClickRepeat = copyHook(source.onClickRepeat),
        onRelease = copyHook(source.onRelease),
        onHold = copyHook(source.onHold),
        onDrag = copyHook(source.onDrag),
        onDragComplete = copyHook(source.onDragComplete),
        onMouseMove = copyHook(source.onMouseMove),
        onKey = copyHook(source.onKey),
        onScrollWheel = copyHook(source.onScrollWheel),
        varps = source.varps?.copyOf(),
        mouseLeaveArrayParams = source.mouseLeaveArrayParams?.copyOf(),
        statTransmitFilter = source.statTransmitFilter?.copyOf(),
        keyPressArray = source.keyPressArray?.copyOf(),
        mouseWheelArray = source.mouseWheelArray?.copyOf(),
        extras = source.extras?.toMap()
    )

    fun isComponentHash(value: Int, interfaceId: Int, componentCount: Int): Boolean =
        value >= 0 && interfaceOf(value) == interfaceId && indexOf(value) < componentCount

    fun remapHookArgs(definition: InterfaceComponentDefinition, remap: (Int) -> Int?): Int {
        var changed = 0
        for (hook in definition.hooks().values) {
            val args = hook.args
            for (i in args.indices) {
                val value = args[i] as? Int ?: continue
                val mapped = remap(value) ?: continue
                if (mapped != value) {
                    args[i] = mapped
                    changed++
                }
            }
        }
        return changed
    }

    fun componentHashArgs(definition: InterfaceComponentDefinition): List<Int> {
        val hashes = ArrayList<Int>()
        for (hook in definition.hooks().values) {
            for (arg in hook.args) {
                if (arg is Int && arg >= 0 && interfaceOf(arg) != 0) {
                    hashes.add(arg)
                }
            }
        }
        return hashes
    }
}

object ComponentFields {

    val fields: List<Field> = InterfaceComponentDefinition::class.java.declaredFields
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .onEach { it.isAccessible = true }

    private val byName: Map<String, Field> = fields.associateBy { it.name }

    val transientFields: Set<String> = setOf("clientParams", "stringId", "extras")

    val derivedFields: Set<String> = setOf("id", "hasScript", "clickable")

    fun field(name: String): Field? = byName[name]

    fun get(definition: InterfaceComponentDefinition, field: Field): Any? = field.get(definition)

    fun set(definition: InterfaceComponentDefinition, field: Field, value: Any?) {
        field.set(definition, value)
    }

    fun same(a: Any?, b: Any?): Boolean = when {
        a is IntArray && b is IntArray -> a.contentEquals(b)
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Array<*> && b is Array<*> -> a.contentDeepEquals(b)
        else -> a == b
    }

    fun describe(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { describe(it) }
        is InterfaceComponentHook -> "script=${value.script} args=${describe(value.args)}"
        else -> value.toString()
    }
}
