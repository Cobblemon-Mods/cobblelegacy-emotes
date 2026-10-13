package fr.owme.cobblelegacy.emotes.client.shop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    data class Feedback(val message: String, val success: Boolean, val target: UUID?)

    var query by mutableStateOf("")
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

    val newCount: Int get() = entries.count { it.isNew }

    val searching: Boolean get() = query.isNotBlank()

    /**
     * Toutes les émotes en une liste : les nouveautés en tête (la plus récente d'abord) tant qu'elles ont
     * le badge NEW, puis celles à vendre, les gratuites et exclusives, les possédées à la fin ; à égalité,
     * l'ordre du catalogue.
     */
    val visibleEntries: List<EmoteEntry>
        get() {
            val base = if (searching) {
                val needle = normalizeSearch(query)
                entries.filter { normalizeSearch(it.name).contains(needle) || normalizeSearch(it.author).contains(needle) }
            } else {
                entries
            }
            return base.sortedWith(
                compareBy<EmoteEntry>({ !it.isNew }, { if (it.isNew) -(it.listing?.addedAtMs ?: 0L) else 0L }, { isOwned(it) }, { priceOf(it) == null })
            )
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

    // ─── Navigation ──────────────────────────────────────────────────────────────

    fun updateQuery(value: String) {
        query = value.take(40)
    }

    fun clearQuery() {
        query = ""
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
