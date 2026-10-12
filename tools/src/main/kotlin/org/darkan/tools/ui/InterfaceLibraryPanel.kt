package org.darkan.tools.ui

import imgui.ImGui
import imgui.ImGuiListClipper
import imgui.callback.ImListClipperCallback
import imgui.flag.ImGuiSelectableFlags
import imgui.flag.ImGuiTableColumnFlags
import imgui.flag.ImGuiTableFlags
import imgui.type.ImBoolean
import imgui.type.ImString
import world.gregs.voidps.cache.definition.data.InterfaceDefinition
import java.io.File

class InterfaceLibraryPanel(
    private val count: Int,
    private val definitionOf: (Int) -> InterfaceDefinition?,
) {
    var visible: Boolean = true

    var onOpen: ((Int) -> Unit)? = null

    var currentId: Int = -1

    private val search = ImString(96)
    private var searchText = true
    private var searchNames = true
    private var searchScripts = false

    private class Entry(
        val id: Int,
        val components: Int,
        val rootWidth: Int,
        val rootHeight: Int,
        val label: String,
        val haystack: String,
        val scripts: Set<Int>,
    )

    private val entries = HashMap<Int, Entry>()
    private var scanned = 0

    private val recents = ArrayList<Int>()
    private val favourites = LinkedHashSet<Int>()
    private var favouritesLoaded = false

    private var matches = ArrayList<Entry>()
    private var lastQuery = ""
    private var lastScanned = -1
    private var lastFilters = 0

    fun render(): Boolean {
        if (!visible) {
            return false
        }
        loadFavourites()
        var opened = false

        if (!ImGui.begin("Interfaces")) {
            ImGui.end()
            return false
        }

        scanSome()

        ImGui.setNextItemWidth(-1f)
        ImGui.inputTextWithHint("##search", "id, text, component name...", search)

        val text = ImBoolean(searchText)
        if (ImGui.checkbox("text", text)) {
            searchText = text.get()
        }
        ImGui.sameLine()
        val names = ImBoolean(searchNames)
        if (ImGui.checkbox("names", names)) {
            searchNames = names.get()
        }
        ImGui.sameLine()
        val scripts = ImBoolean(searchScripts)
        if (ImGui.checkbox("scripts", scripts)) {
            searchScripts = scripts.get()
        }

        if (scanned < count) {
            ImGui.progressBar(scanned.toFloat() / count, -1f, 0f, "indexing $scanned / $count")
        }
        ImGui.separator()

        if (favourites.isNotEmpty() && ImGui.collapsingHeader("Pinned (${favourites.size})")) {
            for (id in favourites.toList()) {
                if (shortcut(id, "pin")) {
                    opened = true
                }
            }
        }
        if (recents.isNotEmpty() && ImGui.collapsingHeader("Recent")) {
            for (id in recents) {
                if (shortcut(id, "recent")) {
                    opened = true
                }
            }
        }

        refreshMatches()
        ImGui.text("${matches.size} interfaces")
        ImGui.separator()

        if (ImGui.beginChild("results", 0f, 0f)) {
            if (drawTable()) {
                opened = true
            }
        }
        ImGui.endChild()
        ImGui.end()
        return opened
    }

    fun noteOpened(id: Int) {
        currentId = id
        recents.remove(id)
        recents.add(0, id)
        while (recents.size > RECENT_LIMIT) {
            recents.removeAt(recents.size - 1)
        }
    }

    fun togglePinned(id: Int) {
        if (!favourites.remove(id)) {
            favourites.add(id)
        }
        saveFavourites()
    }


    private fun scanSome() {
        var budget = SCAN_PER_FRAME
        while (scanned < count && budget > 0) {
            val id = scanned
            scanned++
            budget--
            val definition = try {
                definitionOf(id)
            } catch (e: Exception) {
                null
            }
            val components = definition?.components ?: continue
            if (components.isEmpty()) {
                continue
            }

            val haystack = StringBuilder()
            val scripts = HashSet<Int>()
            for (component in components) {
                if (component.text.isNotEmpty()) {
                    haystack.append(component.text).append('\n')
                }
                if (component.name.isNotEmpty()) {
                    haystack.append(component.name).append('\n')
                }
                for (hook in component.hooks().values) {
                    scripts.add(hook.script)
                }
            }
            val root = components.firstOrNull()
            entries[id] = Entry(
                id = id,
                components = components.size,
                rootWidth = root?.baseWidth ?: 0,
                rootHeight = root?.baseHeight ?: 0,
                label = label(components.firstOrNull()?.name.orEmpty(), haystack),
                haystack = haystack.toString().lowercase(),
                scripts = scripts,
            )
        }
    }

    private fun label(rootName: String, haystack: StringBuilder): String {
        if (rootName.isNotEmpty()) {
            return rootName
        }
        val first = haystack.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        return first.replace(TAG, "").trim().take(48)
    }


    private fun refreshMatches() {
        val query = search.get().trim()
        val filters = (if (searchText) 1 else 0) or (if (searchNames) 2 else 0) or (if (searchScripts) 4 else 0)
        if (query == lastQuery && scanned == lastScanned && filters == lastFilters) {
            return
        }
        lastQuery = query
        lastScanned = scanned
        lastFilters = filters

        val result = ArrayList<Entry>()
        val lower = query.lowercase()
        val asId = query.toIntOrNull()
        for (id in 0 until count) {
            val entry = entries[id] ?: continue
            if (query.isEmpty() || matchesQuery(entry, lower, asId)) {
                result.add(entry)
            }
        }
        result.sortWith(compareBy({ if (it.id == asId) 0 else 1 }, { it.id }))
        matches = result
    }

    private fun matchesQuery(entry: Entry, lower: String, asId: Int?): Boolean {
        if (asId != null) {
            if (entry.id == asId) {
                return true
            }
            if (searchScripts && asId in entry.scripts) {
                return true
            }
        }
        if ((searchText || searchNames) && entry.haystack.contains(lower)) {
            return true
        }
        return false
    }


    private fun drawTable(): Boolean {
        val flags = ImGuiTableFlags.RowBg or ImGuiTableFlags.ScrollY or
            ImGuiTableFlags.SizingStretchProp or ImGuiTableFlags.BordersInnerV
        if (!ImGui.beginTable("interfaces", 4, flags)) {
            return false
        }
        ImGui.tableSetupColumn("id", ImGuiTableColumnFlags.WidthFixed, 52f)
        ImGui.tableSetupColumn("name", ImGuiTableColumnFlags.WidthStretch)
        ImGui.tableSetupColumn("size", ImGuiTableColumnFlags.WidthFixed, 78f)
        ImGui.tableSetupColumn("parts", ImGuiTableColumnFlags.WidthFixed, 44f)
        ImGui.tableSetupScrollFreeze(0, 1)
        ImGui.tableHeadersRow()

        var opened = false
        val rows = matches
        ImGuiListClipper.forEach(rows.size, object : ImListClipperCallback() {
            override fun accept(index: Int) {
                val entry = rows.getOrNull(index) ?: return
                if (drawRow(entry)) {
                    opened = true
                }
            }
        })
        ImGui.endTable()
        return opened
    }

    private fun drawRow(entry: Entry): Boolean {
        ImGui.tableNextRow()
        ImGui.tableSetColumnIndex(0)
        val selected = entry.id == currentId
        val clicked = ImGui.selectable(
            "${entry.id}##row${entry.id}",
            selected,
            ImGuiSelectableFlags.SpanAllColumns or ImGuiSelectableFlags.AllowDoubleClick,
        )
        if (ImGui.beginPopupContextItem("ctx${entry.id}")) {
            val pinned = entry.id in favourites
            if (ImGui.menuItem(if (pinned) "Unpin" else "Pin")) {
                togglePinned(entry.id)
            }
            if (ImGui.menuItem("Copy id")) {
                ImGui.setClipboardText(entry.id.toString())
            }
            ImGui.endPopup()
        }

        ImGui.tableSetColumnIndex(1)
        if (entry.label.isEmpty()) {
            ImGuiText.disabled("-")
        } else {
            ImGui.text(entry.label)
        }
        ImGui.tableSetColumnIndex(2)
        ImGuiText.disabled("${entry.rootWidth}x${entry.rootHeight}")
        ImGui.tableSetColumnIndex(3)
        ImGuiText.disabled(entry.components.toString())

        if (clicked) {
            onOpen?.invoke(entry.id)
            noteOpened(entry.id)
            return true
        }
        return false
    }

    private fun shortcut(id: Int, scope: String): Boolean {
        val entry = entries[id]
        val label = if (entry == null || entry.label.isEmpty()) "$id" else "$id  ${entry.label}"
        if (ImGui.selectable("$label##$scope$id", id == currentId)) {
            onOpen?.invoke(id)
            noteOpened(id)
            return true
        }
        return false
    }


    private fun loadFavourites() {
        if (favouritesLoaded) {
            return
        }
        favouritesLoaded = true
        val file = File(FAVOURITES_FILE)
        if (!file.isFile) {
            return
        }
        try {
            file.readLines().mapNotNull { it.trim().toIntOrNull() }.forEach { favourites.add(it) }
        } catch (e: Exception) {
            System.err.println("Could not read $FAVOURITES_FILE: ${e.message}")
        }
    }

    private fun saveFavourites() {
        try {
            File(FAVOURITES_FILE).writeText(favourites.joinToString("\n"))
        } catch (e: Exception) {
            System.err.println("Could not write $FAVOURITES_FILE: ${e.message}")
        }
    }

    companion object {
        private const val SCAN_PER_FRAME = 24
        private const val RECENT_LIMIT = 12
        private const val FAVOURITES_FILE = "darkan-tools-pinned-interfaces.txt"

        private val TAG = Regex("<[^>]*>")
    }
}
