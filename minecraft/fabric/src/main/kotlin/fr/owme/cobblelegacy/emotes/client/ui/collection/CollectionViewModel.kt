package fr.owme.cobblelegacy.emotes.client.ui.collection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.platform.InputConstants
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.ClientEmoteFiles
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.EmoteSource
import fr.owme.cobblelegacy.emotes.client.WheelConfig
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_PITCH
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_YAW
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_ZOOM
import fr.owme.cobblelegacy.emotes.client.preview.applyPreviewZoom
import fr.owme.cobblelegacy.emotes.client.preview.clampPreviewPitch
import fr.owme.cobblelegacy.emotes.client.ui.EmotesI18n
import fr.owme.cobblelegacy.emotes.client.ui.normalizeSearch
import fr.owme.cobblelegacy.emotes.client.ui.wheel.WheelSlot
import fr.owme.cobblelegacy.emotes.client.ui.wheel.WheelTarget
import io.github.kosmx.emotes.executor.EmoteInstance
import io.github.kosmx.emotes.inline.TmpGetters
import io.github.kosmx.emotes.main.EmoteHolder
import io.github.kosmx.emotes.main.config.ClientConfig
import io.github.kosmx.emotes.server.config.Serializer
import java.util.UUID

/** État de la collection d'émotes (« Toutes les émotes »). */
class CollectionViewModel(initialSelection: UUID?, val target: WheelTarget?) {

    enum class Filter { ALL, UNLOCKED, LOCKED, NEW }

    var query by mutableStateOf("")
    var filter by mutableStateOf(Filter.ALL)
        private set

    /** Id de catégorie, ou [BASE] / [LOCAL] / [NONE]. */
    var category by mutableStateOf<String?>(null)
        private set
    var rarity by mutableStateOf<EmoteRarity?>(null)
        private set
    var selectedId by mutableStateOf(initialSelection)
        private set

    var previewYaw by mutableFloatStateOf(PREVIEW_DEFAULT_YAW)
        private set
    var previewPitch by mutableFloatStateOf(PREVIEW_DEFAULT_PITCH)
        private set
    var previewZoom by mutableFloatStateOf(PREVIEW_DEFAULT_ZOOM)
        private set

    /** Petite roue affichée pour placer l'émote choisie. */
    var placing by mutableStateOf(false)
        private set
    var placingPage by mutableIntStateOf(target?.page ?: WheelConfig.page)
        private set
    var placingHovered by mutableStateOf<Int?>(null)

    /** En attente d'une touche pour le raccourci de l'émote choisie. */
    var capturingKey by mutableStateOf(false)
        private set

    var toastMessage by mutableStateOf<String?>(null)
        private set
    var toastSuccess by mutableStateOf(true)
        private set

    private var revision by mutableIntStateOf(-1)
    private var wheelRevision by mutableIntStateOf(0)

    fun sync() {
        if (revision != ClientEmoteCatalog.revision) revision = ClientEmoteCatalog.revision
    }

    private fun observe() {
        revision
        wheelRevision
    }

    // ─── Données ────────────────────────────────────────────────────────────────

    val entries: List<EmoteEntry>
        get() {
            observe()
            return EmoteLibrary.entries()
        }

    val managed: Boolean get() = observe().let { ClientEmoteCatalog.managed }
    val editorAvailable: Boolean get() = observe().let { ClientEmoteCatalog.editorEnabled }
    val serverHasShop: Boolean get() = observe().let { ClientEmoteCatalog.received && ClientEmoteCatalog.shopOpen }
    val downloading: Int get() = observe().let { ClientEmoteFiles.pendingCount }

    val unlockedCount: Int get() = entries.count { it.unlocked }

    val filtered: List<EmoteEntry>
        get() {
            val needle = normalizeSearch(query)
            val now = System.currentTimeMillis()
            return entries.filter { entry ->
                (needle.isEmpty() || normalizeSearch(entry.name).contains(needle) || normalizeSearch(entry.author).contains(needle)) &&
                    when (filter) {
                        Filter.ALL -> true
                        Filter.UNLOCKED -> entry.unlocked
                        Filter.LOCKED -> !entry.unlocked
                        Filter.NEW -> entry.listing?.isNew(now) == true
                    } &&
                    when (category) {
                        null -> true
                        BASE -> entry.source == EmoteSource.BUILTIN
                        LOCAL -> entry.source == EmoteSource.LOCAL
                        NONE -> entry.source == EmoteSource.CATALOG && entry.categoryId == null
                        else -> entry.categoryId == category
                    } &&
                    (rarity == null || entry.rarity == rarity)
            }.sortedWith(compareBy({ !it.unlocked }, { it.source.ordinal }))
        }

    val selected: EmoteEntry? get() = selectedId?.let { id -> entries.firstOrNull { it.id == id } }

    val categories get() = observe().let { ClientEmoteCatalog.categories }

    fun countIn(categoryId: String): Int = entries.count {
        when (categoryId) {
            BASE -> it.source == EmoteSource.BUILTIN
            LOCAL -> it.source == EmoteSource.LOCAL
            NONE -> it.source == EmoteSource.CATALOG && it.categoryId == null
            else -> it.categoryId == categoryId
        }
    }

    val hasActiveFilter: Boolean get() = filter != Filter.ALL || category != null || rarity != null || query.isNotBlank()

    // ─── Filtres et sélection ─────────────────────────────────────────────────────

    fun selectFilter(value: Filter) {
        filter = if (filter == value) Filter.ALL else value
    }

    fun toggleCategory(id: String) {
        category = if (category == id) null else id
    }

    fun toggleRarity(value: EmoteRarity) {
        rarity = if (rarity == value) null else value
    }

    fun resetFilters() {
        filter = Filter.ALL
        category = null
        rarity = null
        query = ""
    }

    fun select(id: UUID) {
        if (selectedId != id) {
            selectedId = id
            placing = false
            capturingKey = false
        }
    }

    fun rotatePreview(deltaYaw: Float, deltaPitch: Float) {
        previewYaw += deltaYaw
        previewPitch = clampPreviewPitch(previewPitch + deltaPitch)
    }

    fun zoomPreview(scroll: Float) {
        previewZoom = applyPreviewZoom(previewZoom, scroll)
    }

    fun resetPreview() {
        previewYaw = PREVIEW_DEFAULT_YAW
        previewPitch = PREVIEW_DEFAULT_PITCH
        previewZoom = PREVIEW_DEFAULT_ZOOM
    }

    // ─── Actions ─────────────────────────────────────────────────────────────────

    /** Lance l'émote ; `true` si elle part (le menu se ferme). */
    fun play(entry: EmoteEntry): Boolean {
        if (!entry.unlocked) return false
        val holder = EmoteHolder.list[entry.id]
        if (holder == null) {
            toast(EmotesI18n.t("wheel.loading"), false)
            return false
        }
        val player = TmpGetters.getClientMethods().mainPlayer ?: return false
        if (!holder.playEmote(player)) {
            toast(EmotesI18n.t("wheel.cannot_play"), false)
            return false
        }
        return true
    }

    // Roue
    fun startPlacing() {
        placing = true
        capturingKey = false
    }

    fun stopPlacing() {
        placing = false
    }

    fun changePlacingPage(delta: Int) {
        placingPage = Math.floorMod(placingPage + delta, WheelConfig.PAGES)
        placingHovered = null
    }

    fun placingSlots(): List<WheelSlot> {
        observe()
        return (0 until WheelConfig.SLOTS).map { slot ->
            val id = WheelConfig.get(placingPage, slot)
            WheelSlot(id?.let { EmoteLibrary.entry(it) }, id != null)
        }
    }

    fun assign(page: Int, slot: Int, id: UUID?) {
        WheelConfig.set(page, slot, id)
        wheelRevision++
        if (id != null) toast(EmotesI18n.t("collection.placed", slot + 1, page + 1), true)
    }

    fun wheelPositions(id: UUID): List<Pair<Int, Int>> {
        observe()
        return WheelConfig.positionsOf(id)
    }

    // Raccourci clavier
    private val config: ClientConfig get() = EmoteInstance.config as ClientConfig

    fun keyOf(id: UUID): InputConstants.Key? {
        observe()
        return config.emoteKeyMap.getR(id)
    }

    fun startKeyCapture() {
        capturingKey = true
        placing = false
    }

    fun cancelKeyCapture() {
        capturingKey = false
    }

    /** [key] `null` : retirer le raccourci. Une touche déjà prise est retirée de l'autre émote. */
    fun bindKey(id: UUID, key: InputConstants.Key?) {
        capturingKey = false
        val map = config.emoteKeyMap
        map.removeL(id)
        if (key != null && key != InputConstants.UNKNOWN) {
            map.removeR(key)
            map.put(id, key)
            toast(EmotesI18n.t("collection.key.bound", key.displayName.string), true)
        } else {
            toast(EmotesI18n.t("collection.key.removed"), true)
        }
        Serializer.saveConfig()
        wheelRevision++
    }

    fun priceOf(entry: EmoteEntry): Long? {
        val listing = entry.listing ?: return null
        return ClientEmoteCatalog.settings.priceOf(listing, System.currentTimeMillis())
    }

    fun accessOf(entry: EmoteEntry): EmoteAccess? = entry.listing?.access

    fun toast(message: String, success: Boolean) {
        toastMessage = message
        toastSuccess = success
    }

    fun dismissToast() {
        toastMessage = null
    }

    companion object {
        const val BASE = "@base"
        const val LOCAL = "@local"
        const val NONE = "@none"
    }
}
