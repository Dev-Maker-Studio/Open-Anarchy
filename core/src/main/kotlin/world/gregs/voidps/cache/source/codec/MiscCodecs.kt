package world.gregs.voidps.cache.source.codec

import world.gregs.voidps.buffer.read.BufferReader
import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.DefinitionDecoder
import world.gregs.voidps.cache.DefinitionEncoder
import world.gregs.voidps.cache.Index
import world.gregs.voidps.cache.definition.data.AnimationBaseDefinition
import world.gregs.voidps.cache.definition.data.BodyDefinition
import world.gregs.voidps.cache.definition.data.CutsceneDefinition
import world.gregs.voidps.cache.definition.data.DefaultsDefinition
import world.gregs.voidps.cache.definition.data.GameTipDefinition
import world.gregs.voidps.cache.definition.data.HuffmanTableDefinition
import world.gregs.voidps.cache.definition.data.QuickChatOptionDefinition
import world.gregs.voidps.cache.definition.data.QuickChatPhraseDefinition
import world.gregs.voidps.cache.definition.decoder.AnimationBaseDecoder
import world.gregs.voidps.cache.definition.decoder.BodyDecoder
import world.gregs.voidps.cache.definition.decoder.CutsceneDecoder
import world.gregs.voidps.cache.definition.decoder.DefaultsDecoder
import world.gregs.voidps.cache.definition.decoder.GameTipDecoder
import world.gregs.voidps.cache.definition.decoder.HuffmanTableDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatOptionDecoder
import world.gregs.voidps.cache.definition.decoder.QuickChatPhraseDecoder
import world.gregs.voidps.cache.definition.encoder.AnimationBaseEncoder
import world.gregs.voidps.cache.definition.encoder.BodyEncoder
import world.gregs.voidps.cache.definition.encoder.CutsceneEncoder
import world.gregs.voidps.cache.definition.encoder.DefaultsEncoder
import world.gregs.voidps.cache.definition.encoder.GameTipEncoder
import world.gregs.voidps.cache.definition.encoder.HuffmanTableEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatOptionEncoder
import world.gregs.voidps.cache.definition.encoder.QuickChatPhraseEncoder

object MiscCodecs {

    fun register() {
        SourceCodecs.builtin(Index.ANIMATION_FRAMES, AnimationFrameCodec)
        SourceCodecs.builtin(Index.ANIMATION_SKELETONS, bases)
        SourceCodecs.builtin(Index.HUFFMAN, huffman)
        SourceCodecs.builtin(Index.WORLD_MAP, WorldMapCodec)
        SourceCodecs.builtin(Index.QUICK_CHAT_MESSAGES, quickChat)
        SourceCodecs.builtin(Index.QUICK_CHAT_MENUS, quickChat)
        SourceCodecs.builtin(Index.DEFAULTS, defaults)
        SourceCodecs.builtin(Index.GAME_TIPS, gameTips)
        SourceCodecs.builtin(Index.CUTSCENES, cutscenes)
    }

    private val bases = single("json-base", AnimationBaseDecoder(), AnimationBaseEncoder()) {
        AnimationBaseDefinition(it)
    }

    private val gameTips = single("json-gametip", GameTipDecoder(), GameTipEncoder()) { GameTipDefinition(it) }

    private val cutscenes = single("json-cutscene", CutsceneDecoder(), CutsceneEncoder()) { CutsceneDefinition(it) }

    private val huffman = ArchiveDirectoryCodec(
        id = "json-huffman",
        name = { HUFFMAN_NAME },
        archive = { name -> if (name == HUFFMAN_NAME) HUFFMAN_ARCHIVE else -1 },
        codec = { huffmanTable },
        nested = false
    )

    private val huffmanTable =
        named(HUFFMAN_NAME, "json-huffman", HuffmanTableDecoder(), HuffmanTableEncoder()) { HuffmanTableDefinition(it) }

    private val quickChat = ArchiveDirectoryCodec(
        id = "quickchat",
        name = { archive -> QUICK_CHAT_NAMES[archive] ?: archive.toString() },
        archive = { name -> QUICK_CHAT_ARCHIVES[name] ?: name.toIntOrNull() ?: -1 },
        codec = { archive ->
            when (archive) {
                QUICK_CHAT_OPTIONS -> quickChatOptions
                QUICK_CHAT_PHRASES -> quickChatPhrases
                else -> RawFilesCodec.FLAT
            }
        }
    )

    private val quickChatOptions = flat("json-quickchatoption", QuickChatOptionDecoder(), QuickChatOptionEncoder()) {
        QuickChatOptionDefinition(it)
    }

    private val quickChatPhrases = flat("json-quickchatphrase", QuickChatPhraseDecoder(), QuickChatPhraseEncoder()) {
        QuickChatPhraseDefinition(it)
    }

    private val defaults = ArchiveDirectoryCodec(
        id = "defaults",
        name = { archive -> DEFAULTS_NAMES[archive] ?: archive.toString() },
        archive = { name -> DEFAULTS_ARCHIVES[name] ?: name.toIntOrNull() ?: -1 },
        codec = { archive -> defaultsCodecs[archive] ?: RawArchiveCodec },
        nested = false
    )

    private val defaultsCodecs: Map<Int, SourceCodec> by lazy {
        val decoder = DefaultsDecoder()
        val encoder = DefaultsEncoder()
        DEFAULTS_NAMES.entries.associate { (archive, name) ->
            archive to if (archive == DefaultsDefinition.EQUIPMENT) {
                named(name, "json-equipment", BodyDecoder(), BodyEncoder()) { BodyDefinition(it) }
            } else {
                named(name, "json-defaults", decoder, encoder) { DefaultsDefinition(it) }
            }
        }
    }

    private fun <T : Definition> single(
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, DefinitionLayout.Packed(0), decoder, encoder, create)

    private fun <T : Definition> flat(
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, DefinitionLayout.Flat, decoder, encoder, create)

    private fun <T : Definition> named(
        name: String,
        id: String,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = codec(id, NamedLayout(name), decoder, encoder, create)

    private fun <T : Definition> codec(
        id: String,
        layout: DefinitionLayout,
        decoder: DefinitionDecoder<T>,
        encoder: DefinitionEncoder<T>,
        create: (Int) -> T
    ): DefinitionJsonCodec<T> = DefinitionJsonCodec(
        id = id,
        layout = layout,
        create = create,
        decode = { definition, bytes -> decoder.readLoop(definition, BufferReader(bytes)) },
        encode = { definition, writer -> with(encoder) { writer.encode(definition) } }
    )

    private const val HUFFMAN_ARCHIVE = 1
    private const val HUFFMAN_NAME = "huffman"

    private const val QUICK_CHAT_OPTIONS = 0
    private const val QUICK_CHAT_PHRASES = 1

    private val QUICK_CHAT_NAMES = mapOf(
        QUICK_CHAT_OPTIONS to "quickchatoption",
        QUICK_CHAT_PHRASES to "quickchatphrase"
    )

    private val QUICK_CHAT_ARCHIVES = QUICK_CHAT_NAMES.entries.associate { (archive, name) -> name to archive }

    private val DEFAULTS_NAMES = mapOf(
        DefaultsDefinition.SCENE_CUBE_TEXTURES to "scene_cube_textures",
        DefaultsDefinition.ENTITY to "entity",
        DefaultsDefinition.TITLE_SCREEN_MUSIC to "titlescreen_music_info",
        DefaultsDefinition.EQUIPMENT to "equipment",
        DefaultsDefinition.INTERACTIONS to "interactions",
        DefaultsDefinition.CUTSCENE to "cutscene"
    )

    private val DEFAULTS_ARCHIVES = DEFAULTS_NAMES.entries.associate { (archive, name) -> name to archive }
}
