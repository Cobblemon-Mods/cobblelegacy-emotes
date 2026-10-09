package fr.owme.cobblelegacy.emotes.client.shop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.EmoteClientEvents
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_PITCH
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_YAW
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_ZOOM
import fr.owme.cobblelegacy.emotes.client.preview.applyPreviewZoom
import fr.owme.cobblelegacy.emotes.client.preview.clampPreviewPitch
import fr.owme.cobblelegacy.emotes.client.ui.EmotesI18n
import fr.owme.cobblelegacy.emotes.client.ui.normalizeSearch
import fr.owme.cobblelegacy.emotes.network.EmoteBuyPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import java.util.UUID

/** État de la boutique d'émotes (onglet du Pokématos ou écran à part). */
class EmoteShopViewModel(private val host: () -> EmoteShopHost, initialEmote: UUID?) {

    enum class Mode { CATEGORIES, ALL }

    /** Une catégorie de la boutique, avec ses émotes publiées (`id` vide : « Autres »). */
    data class ShopCategory(val id: String, val name: String, val entries: List<EmoteEntry>)

    data class Feedback(val message: String, val success: Boolean, val target: UUID?)

    var mode by mutableStateOf(Mode.CATEGORIES)
        private set
    var query by mutableStateOf("")
        private set
    var openCategoryId by mutableStateOf<String?>(null)
        private set
    var selectedId by mutableStateOf(initialEmote)
        private set

    var previewYaw by mutableFloatStateOf(PREVIEW_DEFAULT_YAW)
        private set
    var previewPitch by mutableFloatStateOf(PREVIEW_DEFAULT_PITCH)
        private set
    var previewZoom by mutableFloatStateOf(PREVIEW_DEFAULT_ZOOM)
        private set

    var confirmingBuy by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var feedback by mutableStateOf<Feedback?>(null)
        private set

    var nowMs by mutableLongStateOf(System.currentTimeMillis())
        private set
    private var busySince = 0L
    private var revision by mutableIntStateOf(-1)

    private val listener: (EmoteShopResultPayload) -> Unit = { onResult(it) }

    init {
        EmoteClientEvents.shopListener = listener
    }

    fun dispose() {
        if (EmoteClientEvents.shopListener === listener) EmoteClientEvents.shopListener = null
    }

    /** Appelé à chaque frame : catalogue, horloge (comptes à rebours), délai d'un achat sans réponse. */
    fun sync() {
        if (revision != ClientEmoteCatalog.revision) revision = ClientEmoteCatalog.revision
        val now = System.currentTimeMillis()
        if (now - nowMs >= 1000) nowMs = now
        if (busy && now - busySince > 15_000) {
            busy = false
            feedback = Feedback(EmotesI18n.t("shop.timeout"), false, selectedId)
        }
    }

    private fun observe() {
        revision
    }

    // ─── Données ────────────────────────────────────────────────────────────────

    val serverSupported: Boolean get() = observe().let { ClientEmoteCatalog.received && ClientPlayNetworking.canSend(EmoteBuyPayload.TYPE) }
    val shopOpen: Boolean get() = observe().let { ClientEmoteCatalog.shopOpen }
    val isAdmin: Boolean get() = observe().let { ClientEmoteCatalog.editorEnabled }
    val settings get() = observe().let { ClientEmoteCatalog.settings }

    /** Les émotes du catalogue montrées en boutique : publiées, dans l'ordre choisi par l'équipe. */
    val entries: List<EmoteEntry>
        get() {
            observe()
            return EmoteLibrary.entries().filter { it.listing?.published == true }
        }

    val newEntries: List<EmoteEntry> get() = entries.filter { it.listing!!.isNew(nowMs) }

    val categories: List<ShopCategory>
        get() {
            val all = entries
            val result = ClientEmoteCatalog.categories.mapNotNull { category: EmoteCategory ->
                val inside = all.filter { it.categoryId == category.id }
                if (inside.isEmpty()) null else ShopCategory(category.id, category.name, inside)
            }.toMutableList()
            val others = all.filter { entry -> entry.categoryId == null || ClientEmoteCatalog.categories.none { it.id == entry.categoryId } }
            if (others.isNotEmpty()) result += ShopCategory("", EmotesI18n.t("shop.category.others"), others)
            return result
        }

    val openCategory: ShopCategory? get() = openCategoryId?.let { id -> categories.firstOrNull { it.id == id } }

    val searching: Boolean get() = query.isNotBlank()

    val visibleEntries: List<EmoteEntry>
        get() {
            val base = when {
                searching -> {
                    val needle = normalizeSearch(query)
                    entries.filter { normalizeSearch(it.name).contains(needle) || normalizeSearch(it.author).contains(needle) }
                }
                mode == Mode.ALL -> entries
                else -> openCategory?.entries ?: emptyList()
            }
            // À vendre d'abord (nouveautés en tête), puis les gratuites, exclusives et possédées.
            return base.sortedWith(compareBy({ isOwned(it) }, { priceOf(it) == null }, { !it.isNew }))
        }

    val selected: EmoteEntry? get() = selectedId?.let { id -> entries.firstOrNull { it.id == id } }

    fun isOwned(entry: EmoteEntry): Boolean = entry.unlocked

    fun priceOf(entry: EmoteEntry): Long? = entry.listing?.let { settings.priceOf(it, nowMs) }

    fun discountOf(entry: EmoteEntry): Int = entry.listing?.let { settings.discountOf(it, nowMs) } ?: 0

    fun discountRemainingMs(entry: EmoteEntry): Long {
        val listing: EmoteListing = entry.listing ?: return 0
        return (settings.discountEndOf(listing, nowMs) - nowMs).coerceAtLeast(0)
    }

    val promoActive: Boolean get() = settings.promoActive(nowMs)
    val promoRemainingMs: Long get() = (settings.promoEndsAtMs - nowMs).coerceAtLeast(0)

    fun minPriceOf(category: ShopCategory): Long? = category.entries.filter { !isOwned(it) }.mapNotNull { priceOf(it) }.minOrNull()

    fun categoryName(entry: EmoteEntry): String? = ClientEmoteCatalog.category(entry.categoryId)?.name

    // ─── Navigation ──────────────────────────────────────────────────────────────

    fun selectMode(value: Mode) {
        mode = value
        openCategoryId = null
    }

    fun updateQuery(value: String) {
        query = value.take(40)
    }

    fun clearQuery() {
        query = ""
    }

    fun openCategory(id: String) {
        openCategoryId = id
    }

    fun closeCategory() {
        openCategoryId = null
    }

    fun open(entry: EmoteEntry) {
        selectedId = entry.id
        confirmingBuy = false
        feedback = null
        resetPreview()
    }

    fun closeDetails() {
        selectedId = null
        confirmingBuy = false
        feedback = null
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

    // ─── Achat ───────────────────────────────────────────────────────────────────

    /** Premier clic : confirmation ; second : achat. */
    fun buy(entry: EmoteEntry) {
        val price = priceOf(entry) ?: return
        if (busy) return
        if (!confirmingBuy) {
            confirmingBuy = true
            feedback = null
            return
        }
        confirmingBuy = false
        if (!ClientPlayNetworking.canSend(EmoteBuyPayload.TYPE)) {
            feedback = Feedback(EmotesI18n.t("shop.unavailable"), false, entry.id)
            return
        }
        busy = true
        busySince = System.currentTimeMillis()
        ClientPlayNetworking.send(EmoteBuyPayload(entry.id, price))
    }

    fun cancelBuy() {
        confirmingBuy = false
    }

    private fun onResult(result: EmoteShopResultPayload) {
        busy = false
        if (result.balance >= 0) {
            host().onCrystalsChanged(result.balance)
            ClientEmoteCatalog.applyBalance(result.balance)
        }
        feedback = Feedback(result.message, result.success, result.emoteId)
    }
}
