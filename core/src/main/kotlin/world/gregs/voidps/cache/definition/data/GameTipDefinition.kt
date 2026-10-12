package world.gregs.voidps.cache.definition.data

import world.gregs.voidps.cache.Definition
import world.gregs.voidps.cache.definition.DefinitionRecord

data class GameTipDefinition(
    override var id: Int = -1,
    var version: Int? = null,
    var renderTypes: IntArray? = null,
    var definedStageCount: Int? = null,
    var totalStageCount: Int? = null,
    var tipsFileId: Int? = null,
    var displayDuration: Int? = null,
    var timeBetweenUpdates: Int? = null,
    var stages: List<GameTipStageRecord>? = null,
    var elements: List<GameTipElementRecord>? = null
) : Definition

data class GameTipStageRecord(
    var stage: Int,
    var hasUpdates: Int,
    var updates: List<GameTipUpdateRecord>
) : DefinitionRecord

data class GameTipUpdateRecord(
    var tipFileId: Int,
    var displayDuration: Int,
    var timeBetweenUpdates: Int
) : DefinitionRecord

data class GameTipElementRecord(
    var type: Int,
    var colour: Int? = null,
    var newsItemId: Int? = null,
    var graphicId: Int? = null,
    var tipText: String? = null,
    var anchorX: Int? = null,
    var anchorY: Int? = null,
    var offsetX: Int? = null,
    var offsetY: Int? = null,
    var width: Int? = null,
    var height: Int? = null,
    var textOffsetY: Int? = null,
    var textAlign: Int? = null,
    var textVAlign: Int? = null,
    var lineSpacing: Int? = null,
    var fileId: Int? = null,
    var textColour: Int? = null,
    var shadeColour: Int? = null,
    var textShadowColour: Int? = null,
    var fillColour: Int? = null,
    var outlineColour: Int? = null,
    var backgroundColour: Int? = null,
    var drawOutline: Int? = null,
    var graphicFileId: Int? = null,
    var fillGraphic: Int? = null,
    var emptyGraphic: Int? = null,
    var leftCapGraphic: Int? = null,
    var rightCapGraphic: Int? = null,
    var topBorderGraphic: Int? = null,
    var bottomBorderGraphic: Int? = null,
    var angle: Int? = null,
    var scrollSpeed: Int? = null,
    var wideFileIds: Int? = null
) : DefinitionRecord
