package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Config
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.OpcodeEncoder
import world.gregs.voidps.cache.config.decoder.ClientVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.HitSplatDecoder
import world.gregs.voidps.cache.config.decoder.IdentityKitDecoder
import world.gregs.voidps.cache.config.decoder.InventoryDecoder
import world.gregs.voidps.cache.config.decoder.MapSceneDecoder
import world.gregs.voidps.cache.config.decoder.OverlayDecoder
import world.gregs.voidps.cache.config.decoder.PlayerVariableParameterDecoder
import world.gregs.voidps.cache.config.decoder.QuestDecoder
import world.gregs.voidps.cache.config.decoder.UnderlayDecoder
import world.gregs.voidps.cache.config.decoder.WorldMapInfoDecoder
import world.gregs.voidps.cache.config.encoder.ClientVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.HitSplatEncoder
import world.gregs.voidps.cache.config.encoder.IdentityKitEncoder
import world.gregs.voidps.cache.config.encoder.InventoryEncoder
import world.gregs.voidps.cache.config.encoder.MapSceneEncoder
import world.gregs.voidps.cache.config.encoder.OverlayEncoder
import world.gregs.voidps.cache.config.encoder.PlayerVariableParameterEncoder
import world.gregs.voidps.cache.config.encoder.QuestEncoder
import world.gregs.voidps.cache.config.encoder.UnderlayEncoder
import world.gregs.voidps.cache.config.encoder.WorldMapInfoEncoder
import world.gregs.voidps.cache.config.data.ClientVariableParameterDefinition
import world.gregs.voidps.cache.config.data.HitSplatDefinition
import world.gregs.voidps.cache.config.data.IdentityKitDefinition
import world.gregs.voidps.cache.config.data.InventoryDefinition
import world.gregs.voidps.cache.config.data.MapSceneDefinition
import world.gregs.voidps.cache.config.data.OverlayDefinition
import world.gregs.voidps.cache.config.data.PlayerVariableParameterDefinition
import world.gregs.voidps.cache.config.data.QuestDefinition
import world.gregs.voidps.cache.config.data.UnderlayDefinition
import world.gregs.voidps.cache.config.data.WorldMapInfoDefinition
import world.gregs.voidps.cache.definition.OpcodeOrdered
import world.gregs.voidps.cache.definition.data.AnimationDefinition
import world.gregs.voidps.cache.definition.data.BASDefinition
import world.gregs.voidps.cache.definition.data.ClanVarDefinition
import world.gregs.voidps.cache.definition.data.EnumDefinition
import world.gregs.voidps.cache.definition.data.SpotAnimDefinition
import world.gregs.voidps.cache.definition.data.InterfaceComponentDefinition
import world.gregs.voidps.cache.definition.data.ItemDefinition
import world.gregs.voidps.cache.definition.data.NPCDefinition
import world.gregs.voidps.cache.definition.data.ObjectDefinition
import world.gregs.voidps.cache.definition.data.ParamDefinition
import world.gregs.voidps.cache.definition.data.StructDefinition
import world.gregs.voidps.cache.definition.data.VarBitDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationDecoder
import world.gregs.voidps.cache.definition.decoder.BASDecoder
import world.gregs.voidps.cache.definition.decoder.ClanVarDecoder
import world.gregs.voidps.cache.definition.decoder.EnumDecoder
import world.gregs.voidps.cache.definition.decoder.SpotAnimDecoder
import world.gregs.voidps.cache.definition.decoder.InterfaceDecoder
import world.gregs.voidps.cache.definition.decoder.ItemDecoder
import world.gregs.voidps.cache.definition.decoder.NPCDecoder
import world.gregs.voidps.cache.definition.decoder.ObjectDecoder
import world.gregs.voidps.cache.definition.decoder.ParamDecoder
import world.gregs.voidps.cache.definition.decoder.StructDecoder
import world.gregs.voidps.cache.definition.decoder.VarBitDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationEncoder
import world.gregs.voidps.cache.definition.encoder.BASEncoder
import world.gregs.voidps.cache.definition.encoder.ClanVarEncoder
import world.gregs.voidps.cache.definition.encoder.EnumEncoder
import world.gregs.voidps.cache.definition.encoder.SpotAnimEncoder
import world.gregs.voidps.cache.definition.encoder.InterfaceEncoder
import world.gregs.voidps.cache.definition.encoder.ItemEncoder
import world.gregs.voidps.cache.definition.encoder.NPCEncoder
import world.gregs.voidps.cache.definition.encoder.ObjectEncoder
import world.gregs.voidps.cache.definition.encoder.ParamEncoder
import world.gregs.voidps.cache.definition.encoder.StructEncoder
import world.gregs.voidps.cache.definition.encoder.VarBitEncoder
import world.gregs.voidps.cache.source.IndexNames

object DefinitionCodecs {

    class Type(
        val index: Int,
        val archive: Int,
        val name: String,
        val directory: String,
        val codec: DefinitionJsonCodec<*>
    )

    val types: List<Type> = buildList {
        add(index(Index.OBJECTS, "loc", 8, ObjectDecoder(), ObjectEncoder()) { ObjectDefinition(it) })
        add(index(Index.ENUMS, "enum", 8, EnumDecoder(), EnumEncoder()) { EnumDefinition(it) })
        add(index(Index.NPCS, "npc", 7, NPCDecoder(), NPCEncoder()) { NPCDefinition(it) })
        add(index(Index.ITEMS, "obj", 8, ItemDecoder(), ItemEncoder()) { ItemDefinition(it) })
        add(index(Index.ANIMATIONS, "seq", 7, AnimationDecoder(), AnimationEncoder()) { AnimationDefinition(it) })
        add(index(Index.SPOTANIMS, "spotanim", 8, SpotAnimDecoder(), SpotAnimEncoder()) { SpotAnimDefinition(it) })
        add(index(Index.VAR_BIT, "varbit", 10, VarBitDecoder(), VarBitEncoder()) { VarBitDefinition(it) })
        add(interfaces())
        add(config(Config.FLOOR_UNDERLAY, "underlay", UnderlayDecoder(), UnderlayEncoder()) { UnderlayDefinition(it) })
        add(config(Config.IDENTITY_KIT, "idk", IdentityKitDecoder(), IdentityKitEncoder()) { IdentityKitDefinition(it) })
        add(config(Config.FLOOR_OVERLAY, "overlay", OverlayDecoder(), OverlayEncoder()) { OverlayDefinition(it) })
        add(config(Config.INVENTORIES, "inv", InventoryDecoder(), InventoryEncoder()) { InventoryDefinition(it) })
        add(config(Config.PARAMS, "param", ParamDecoder(), ParamEncoder()) { ParamDefinition(it) })
        add(
            config(Config.VARP, "varp", PlayerVariableParameterDecoder(), PlayerVariableParameterEncoder()) {
                PlayerVariableParameterDefinition(it)
            }
        )
        add(
            config(Config.VARC, "varc", ClientVariableParameterDecoder(), ClientVariableParameterEncoder()) {
                ClientVariableParameterDefinition(it)
            }
        )
        add(config(Config.STRUCTS, "struct", StructDecoder(), StructEncoder()) { StructDefinition(it) })
        add(config(Config.RENDER_ANIMATIONS, "bas", BASDecoder(), BASEncoder()) { BASDefinition(it) })
        add(config(Config.MAP_SCENES, "mapscene", MapSceneDecoder(), MapSceneEncoder()) { MapSceneDefinition(it) })
        add(config(Config.QUESTS, "quest", QuestDecoder(), QuestEncoder()) { QuestDefinition(it) })
        add(
            config(Config.WORLD_MAP_INFO, "mapelement", WorldMapInfoDecoder(), WorldMapInfoEncoder()) {
                WorldMapInfoDefinition(it)
            }
        )
        add(config(Config.HIT_SPLATS, "hitmark", HitSplatDecoder(), HitSplatEncoder()) { HitSplatDefinition(it) })
        add(config(Config.VAR_CLAN, "varclan", ClanVarDecoder(), ClanVarEncoder()) { ClanVarDefinition(it) })
    }

    fun register() {
        for (type in types) {
            if (type.archive < 0) {
                SourceCodecs.builtin(type.index, type.codec)
            } else {
                ConfigCodec.register(type.archive, type.codec)
            }
        }
    }

    private fun <T> index(
        index: Int,
        name: String,
        bits: Int,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): Type where T : Definition, T : OpcodeOrdered = Type(
        index = index,
        archive = -1,
        name = name,
        directory = IndexNames.name(index),
        codec = ordered("json-$name", DefinitionLayout.Packed(bits), decoder, encoder, create)
    )

    private fun <T> config(
        archive: Int,
        name: String,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): Type where T : Definition, T : OpcodeOrdered = Type(
        index = Index.CONFIGS,
        archive = archive,
        name = name,
        directory = "${IndexNames.name(Index.CONFIGS)}/${ConfigCodec.name(archive)}",
        codec = ordered("json-$name", DefinitionLayout.Flat, decoder, encoder, create)
    )

    private fun interfaces(): Type {
        val decoder = InterfaceDecoder()
        val encoder = InterfaceEncoder()
        return Type(
            index = Index.INTERFACES,
            archive = -1,
            name = "interface",
            directory = IndexNames.name(Index.INTERFACES),
            codec = DefinitionJsonCodec(
                id = "json-interface",
                layout = DefinitionLayout.Nested(COMPONENT_BITS),
                create = { InterfaceComponentDefinition(id = it) },
                decode = { definition, bytes -> with(decoder) { definition.read(BufferReader(bytes)) } },
                encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
            )
        )
    }

    private fun <T> ordered(
        id: String,
        layout: DefinitionLayout,
        decoder: DefinitionDecoder<T>,
        encoder: OpcodeEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> where T : Definition, T : OpcodeOrdered = DefinitionJsonCodec(
        id = id,
        layout = layout,
        create = create,
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    private const val COMPONENT_BITS = 16
}
