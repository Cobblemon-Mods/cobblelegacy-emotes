package fr.owme.cobblelegacy.emotes.client.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.EmoteIconFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.EmoteClientEvents
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_PITCH
import fr.owme.cobblelegacy.emotes.client.preview.PREVIEW_DEFAULT_YAW
import fr.owme.cobblelegacy.emotes.client.preview.applyPreviewZoom
import fr.owme.cobblelegacy.emotes.client.preview.clampPreviewPitch
import fr.owme.cobblelegacy.emotes.client.ui.EmoteIcons
import fr.owme.cobblelegacy.emotes.client.ui.EmotesI18n
import fr.owme.cobblelegacy.emotes.client.ui.normalizeSearch
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorBulkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import io.github.kosmx.emotes.main.EmoteHolder
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.Util
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** État de l'éditeur du catalogue d'émotes (administrateurs). */
class EmoteEditorViewModel(initialSelection: UUID?) {

    enum class Tab { EMOTES, SHOP }

    /** Onglets du panneau de droite. */
    enum class PanelTab { INFOS, SALE, OPTIONS }

    enum class StatusFilter { ALL, DRAFTS, PUBLISHED, NEW, PROMO }

    enum class Sort { CATALOG, RECENT, NAME, PRICE }

    /** Menus déroulants et fenêtres des actions groupées, dessinés au-dessus de l'éditeur. */
    enum class Menu { STATUS, SORT, BULK_PRICE, BULK_DISCOUNT, BULK_NEW }

    /** Fichier importé, pas encore enregistré au catalogue. */
    class Draft(
        val id: UUID,
        val fileName: String,
        val data: ByteArray,
        val sha256: String,
        val parsed: EmoteFiles.Parsed,
        val icon: ImageBitmap?
    )

    /** Champs du formulaire, en texte pour les nombres (saisie libre, vérifiée à l'enregistrement). */
    data class Form(
        val id: UUID,
        val creating: Boolean,
        val name: String,
        val description: String,
        val author: String,
        val categoryId: String?,
        val rarity: EmoteRarity,
        val access: EmoteAccess,
        val price: String,
        val discountPercent: String,
        /** Vide : garder la fin actuelle de la réduction. */
        val discountDays: String,
        val newEnabled: Boolean,
        /** Vide : garder la fin actuelle du badge (ou la durée par défaut pour une nouvelle émote). */
        val newDays: String,
        val published: Boolean,
        /** Se joue en se déplaçant : marcher ou voler ne l'arrête pas. */
        val playableWhileMoving: Boolean,
        /** Nouveau fichier à envoyer (icône changée) : octets et empreinte. */
        val pendingFile: ByteArray? = null,
        val pendingSha: String? = null,
        val pendingIcon: ImageBitmap? = null
    )

    var tab by mutableStateOf(Tab.EMOTES)
    var query by mutableStateOf("")
    var selectedId by mutableStateOf(initialSelection)
        private set
    var form by mutableStateOf<Form?>(null)
        private set
    val drafts = mutableStateListOf<Draft>()

    var saving by mutableStateOf(false)
        private set
    var confirmDelete by mutableStateOf(false)
        private set

    var previewYaw by mutableFloatStateOf(PREVIEW_DEFAULT_YAW)
        private set
    var previewPitch by mutableFloatStateOf(PREVIEW_DEFAULT_PITCH)
        private set
    var previewZoom by mutableFloatStateOf(1f)
        private set

    var toastMessage by mutableStateOf<String?>(null)
        private set
    var toastSuccess by mutableStateOf(true)
        private set

    // Grille : filtres, tri, sélection pour les actions groupées
    var panelTab by mutableStateOf(PanelTab.INFOS)
    var statusFilter by mutableStateOf(StatusFilter.ALL)
    var sort by mutableStateOf(Sort.CATALOG)
    val selection = mutableStateListOf<UUID>()
    var confirmBulkDelete by mutableStateOf(false)
        private set
    var menu by mutableStateOf<Menu?>(null)
        private set
    /** Où ouvrir [menu] : le bouton qui l'a ouvert, en pixels de la fenêtre. */
    var menuAnchor by mutableStateOf(Rect.Zero)
        private set
    /** Où le menu ouvert est dessiné (lu au rendu de l'aperçu 3D, qui passe par-dessus l'interface). */
    var menuBounds = Rect.Zero
    var bulkPrice by mutableStateOf("")
    var bulkPercent by mutableIntStateOf(20)
    var bulkDays by mutableStateOf("3")
    var bulkNewDays by mutableStateOf("14")

    // Aperçu
    var previewPaused by mutableStateOf(false)
    var previewRestart by mutableIntStateOf(0)
        private set

    /** Le formulaire tel qu'enregistré : sert à compter les modifications en cours. */
    private var baseline: Form? = null

    // Réglages de la boutique
    var promoPercent by mutableStateOf("")
    var promoDays by mutableStateOf("")
    var newDaysSetting by mutableStateOf("")
    /** Allègement des icônes en cours sur le serveur. */
    var shrinking by mutableStateOf(false)
        private set

    private var revision by mutableIntStateOf(-1)
    private var batchRemaining = 0
    private val listener: (EmoteEditorResultPayload) -> Unit = { onResult(it) }

    init {
        EmoteClientEvents.editorListener = listener
        loadShopFields()
        initialSelection?.let { select(it) }
    }

    fun dispose() {
        if (EmoteClientEvents.editorListener === listener) EmoteClientEvents.editorListener = null
    }

    fun sync() {
        if (revision != ClientEmoteCatalog.revision) revision = ClientEmoteCatalog.revision
        // Après un enregistrement, le formulaire est relu depuis le catalogue (qui peut arriver avant ou après la réponse).
        val id = selectedId
        if (id != null && form == null && (listing(id) != null || draft(id) != null)) select(id)
        // Supprimée (d'ici ou d'un autre serveur) : le panneau se ferme, la sélection se nettoie.
        val current = form
        if (id != null && current != null && !current.creating && listing(id) == null && draft(id) == null) closePanel()
        if (selection.isNotEmpty()) selection.removeAll { listing(it) == null }
    }

    private fun observe() {
        revision
    }

    // ─── Données ────────────────────────────────────────────────────────────────

    /** Tout le catalogue (compteurs, statistiques) ; la grille affiche [visibleListings]. */
    val listings: List<EmoteListing>
        get() {
            observe()
            return ClientEmoteCatalog.listings
        }

    /** La grille : recherche (nom, auteur), filtres et tri appliqués. */
    val visibleListings: List<EmoteListing>
        get() {
            observe()
            val now = System.currentTimeMillis()
            val needle = normalizeSearch(query)
            val filtered = ClientEmoteCatalog.listings.filter { listing ->
                val matches = needle.isEmpty() || normalizeSearch(listing.name).contains(needle) || normalizeSearch(listing.author).contains(needle)
                matches && matchesStatus(listing, statusFilter, now)
            }
            return when (sort) {
                Sort.CATALOG -> filtered.sortedWith(compareBy({ it.sortOrder }, { it.addedAtMs }))
                Sort.RECENT -> filtered.sortedByDescending { it.addedAtMs }
                Sort.NAME -> filtered.sortedBy { it.name.lowercase() }
                Sort.PRICE -> filtered.sortedByDescending { if (it.access == EmoteAccess.SHOP) it.price else -1L }
            }
        }

    fun matchesStatus(listing: EmoteListing, filter: StatusFilter, now: Long): Boolean = when (filter) {
        StatusFilter.ALL -> true
        StatusFilter.DRAFTS -> !listing.published
        StatusFilter.PUBLISHED -> listing.published
        StatusFilter.NEW -> listing.isNew(now)
        StatusFilter.PROMO -> discountOf(listing, now) > 0
    }

    /** La réduction qu'aura l'émote en vente, même pas encore publiée (la boutique ne compte que les publiées). */
    fun discountOf(listing: EmoteListing, now: Long): Int {
        if (listing.access != EmoteAccess.SHOP || listing.price <= 0) return 0
        val global = if (settings.promoActive(now)) settings.promoPercent else 0
        return maxOf(global, listing.itemDiscount(now))
    }

    fun countWith(filter: StatusFilter): Int {
        val now = System.currentTimeMillis()
        return listings.count { matchesStatus(it, filter, now) }
    }

    val hasFilters: Boolean get() = query.isNotBlank() || statusFilter != StatusFilter.ALL

    fun clearFilters() {
        query = ""
        statusFilter = StatusFilter.ALL
    }
    val settings: EmoteShopSettings get() = observe().let { ClientEmoteCatalog.settings }

    fun listing(id: UUID): EmoteListing? = ClientEmoteCatalog.byId[id]
    fun draft(id: UUID): Draft? = drafts.firstOrNull { it.id == id }

    /** L'animation de l'émote choisie, pour l'aperçu. */
    val selectedAnimation: KeyframeAnimation?
        get() {
            observe()
            val id = selectedId ?: return null
            return draft(id)?.parsed?.animation ?: EmoteHolder.list[id]?.emote
        }

    val selectedInfo: EmoteFiles.Parsed?
        get() {
            val id = selectedId ?: return null
            draft(id)?.let { return it.parsed }
            return EmoteHolder.list[id]?.emote?.let { EmoteFiles.describe(it) }
        }

    // ─── Sélection et formulaire ──────────────────────────────────────────────────

    fun select(id: UUID) {
        if (selectedId != id) {
            previewPaused = false
            previewRestart++
        }
        selectedId = id
        confirmDelete = false
        val draft = draft(id)
        form = if (draft != null) {
            Form(
                id = id, creating = true,
                name = draft.parsed.name.ifBlank { draft.fileName.substringBeforeLast('.') }.take(EmoteListing.MAX_NAME_LENGTH),
                description = draft.parsed.description.take(EmoteListing.MAX_DESCRIPTION_LENGTH),
                author = draft.parsed.author.take(EmoteListing.MAX_AUTHOR_LENGTH),
                categoryId = null, rarity = EmoteRarity.COMMUN, access = EmoteAccess.SHOP,
                price = "500", discountPercent = "0", discountDays = "",
                newEnabled = settings.newDays > 0, newDays = "", published = false, playableWhileMoving = false
            )
        } else {
            val listing = listing(id) ?: run {
                form = null
                return
            }
            val now = System.currentTimeMillis()
            val discount = listing.itemDiscount(now)
            Form(
                id = id, creating = false,
                name = listing.name, description = listing.description, author = listing.author,
                categoryId = listing.categoryId, rarity = listing.rarity, access = listing.access,
                price = if (listing.price > 0) listing.price.toString() else "",
                discountPercent = discount.toString(), discountDays = "",
                newEnabled = listing.isNew(now), newDays = "", published = listing.published,
                playableWhileMoving = listing.playableWhileMoving
            )
        }
        baseline = form
    }

    fun closePanel() {
        selectedId = null
        form = null
        baseline = null
        confirmDelete = false
    }

    /** Modifications pas encore enregistrées dans le formulaire. */
    val changeCount: Int
        get() {
            val current = form ?: return 0
            val base = baseline ?: return 0
            return listOf(
                current.name != base.name, current.description != base.description, current.author != base.author,
                current.categoryId != base.categoryId, current.rarity != base.rarity, current.access != base.access,
                current.price != base.price, current.discountPercent != base.discountPercent, current.discountDays.isNotBlank(),
                current.newEnabled != base.newEnabled, current.newDays.isNotBlank(),
                current.playableWhileMoving != base.playableWhileMoving, current.pendingFile != null
            ).count { it }
        }

    /** Des modifications non enregistrées dans cet onglet du panneau (point ambre sur l'onglet). */
    fun changedIn(tab: PanelTab): Boolean {
        val current = form ?: return false
        val base = baseline ?: return false
        return when (tab) {
            PanelTab.INFOS -> current.name != base.name || current.description != base.description || current.author != base.author ||
                current.categoryId != base.categoryId || current.rarity != base.rarity || current.pendingFile != null
            PanelTab.SALE -> current.access != base.access || current.price != base.price ||
                current.discountPercent != base.discountPercent || current.discountDays.isNotBlank()
            PanelTab.OPTIONS -> current.newEnabled != base.newEnabled || current.newDays.isNotBlank() ||
                current.playableWhileMoving != base.playableWhileMoving
        }
    }

    fun update(transform: (Form) -> Form) {
        form = form?.let(transform)
        confirmDelete = false
    }

    // ─── Import de fichiers ────────────────────────────────────────────────────────

    /** Lecture et allègement des fichiers importés en cours (hors du thread du jeu). */
    var importing by mutableStateOf(false)
        private set

    /**
     * Fichiers choisis ou glissés sur la fenêtre : `.emotecraft`/`.json` deviennent des brouillons, une
     * image (PNG, JPG, WebP…) devient l'icône de l'émote choisie. Les icônes sont allégées au passage.
     */
    fun importFiles(paths: List<Path>) {
        val emotes = paths.filter { EmoteFiles.extensionOf(it.fileName.toString()) in EmoteFiles.IMPORT_EXTENSIONS }
        val images = paths.filter { EmoteFiles.extensionOf(it.fileName.toString()) in EmoteIconFiles.EXTENSIONS }
        if (emotes.isEmpty() && images.size == 1) {
            if (selectedId != null) changeIcon(images.first()) else toast(EmotesI18n.t("editor.icon.pick_emote"), false)
            return
        }
        if (emotes.isEmpty() || importing) return
        importing = true
        // Lecture, conversion et réduction des icônes : plusieurs centaines de ms par grosse image.
        CompletableFuture.supplyAsync({ emotes.map { readDraft(it) } }, Util.backgroundExecutor()).whenComplete { read, _ ->
            Minecraft.getInstance().execute {
                importing = false
                addDrafts(emotes.size, read ?: emptyList())
            }
        }
    }

    private fun addDrafts(total: Int, read: List<Draft?>) {
        var firstNew: UUID? = null
        var skipped = 0
        read.forEach { draft ->
            when {
                draft == null -> skipped++
                listing(draft.id) != null || drafts.any { it.id == draft.id } -> skipped++
                else -> {
                    drafts += draft
                    if (firstNew == null) firstNew = draft.id
                }
            }
        }
        val added = total - skipped
        firstNew?.let { select(it) }
        when {
            added > 0 && skipped > 0 -> toast(EmotesI18n.t("editor.import.partial", added, skipped), true)
            added > 0 -> toast(EmotesI18n.t("editor.import.done", added), true)
            else -> toast(EmotesI18n.t("editor.import.none"), false)
        }
    }

    private fun readDraft(path: Path): Draft? = try {
        val fileName = path.fileName.toString()
        if (Files.size(path) > EmoteFiles.MAX_IMPORT_SIZE) return null
        val parsed = EmoteFiles.parse(Files.readAllBytes(path), fileName, EmoteFiles.MAX_IMPORT_SIZE) ?: return null
        var animation = parsed.animation
        if (parsed.hasIcon) {
            animation = EmoteIconFiles.lighten(animation, EmoteIcons::light)
        } else {
            // Icône à côté du fichier (même nom), comme dans le dossier d'émotes d'Emotecraft.
            siblingIcon(path)?.let { icon -> EmoteIcons.light(icon)?.let { animation = EmoteFiles.withIcon(animation, it) } }
        }
        val binary = EmoteFiles.toBinary(animation)
        if (binary.size > EmoteFiles.MAX_FILE_SIZE) null
        else {
            val info = EmoteFiles.describe(animation)
            Draft(animation.uuid, fileName, binary, EmoteFiles.sha256(binary), info, EmoteFiles.iconBytes(animation)?.let { EmoteIcons.decode(it) })
        }
    } catch (e: Exception) {
        null
    }

    private fun siblingIcon(path: Path): ByteArray? {
        val base = path.fileName.toString().substringBeforeLast('.')
        return EmoteIconFiles.EXTENSIONS.asSequence()
            .map { path.resolveSibling("$base.$it") }
            .firstOrNull { Files.isRegularFile(it) && Files.size(it) <= EmoteIconFiles.MAX_SOURCE_BYTES }
            ?.let { runCatching { Files.readAllBytes(it) }.getOrNull() }
    }

    fun pickFiles() = EmoteFilePicker.pickEmotes { importFiles(it) }

    fun pickIcon() = EmoteFilePicker.pickIcon { changeIcon(it) }

    /**
     * Nouvelle icône pour l'émote choisie, réduite à 256 px quelle que soit l'image (PNG, JPG, WebP…) :
     * le fichier est réécrit et sera renvoyé à l'enregistrement.
     */
    private fun changeIcon(path: Path) {
        val id = selectedId ?: return
        val animation = selectedAnimation ?: run {
            toast(EmotesI18n.t("editor.icon.loading"), false)
            return
        }
        CompletableFuture.supplyAsync({
            val source = runCatching { if (Files.size(path) <= EmoteIconFiles.MAX_SOURCE_BYTES) Files.readAllBytes(path) else null }.getOrNull()
            source?.let { EmoteIcons.light(it) }
        }, Util.backgroundExecutor()).whenComplete { png, _ ->
            Minecraft.getInstance().execute { applyIcon(id, animation, png) }
        }
    }

    private fun applyIcon(id: UUID, animation: KeyframeAnimation, png: ByteArray?) {
        val current = form?.takeIf { it.id == id } ?: return
        if (png == null) return toast(EmotesI18n.t("editor.icon.invalid"), false)
        val binary = EmoteFiles.toBinary(EmoteFiles.withIcon(animation, png))
        form = current.copy(pendingFile = binary, pendingSha = EmoteFiles.sha256(binary), pendingIcon = EmoteIcons.decode(png))
        val side = EmoteIconFiles.sizeOf(png)?.first ?: EmoteIconFiles.SIDE
        toast(EmotesI18n.t("editor.icon.changed", side, (png.size + 1023) / 1024), true)
    }

    // ─── Enregistrement ───────────────────────────────────────────────────────────

    /** [publish] : publier (`true`) ou repasser en brouillon (`false`) en même temps ; `null` garde l'état. */
    fun save(publish: Boolean? = null) {
        val current = (form ?: return).let { if (publish != null) it.copy(published = publish) else it }
        if (saving) return
        val name = current.name.trim()
        if (name.isEmpty()) return toast(EmotesI18n.t("editor.error.name"), false)
        val price = current.price.trim().toLongOrNull() ?: 0L
        if (current.access == EmoteAccess.SHOP && price <= 0) return toast(EmotesI18n.t("editor.error.price"), false)
        val percent = current.discountPercent.trim().ifEmpty { "0" }.toIntOrNull()
        if (percent == null || percent !in 0..EmoteShopSettings.MAX_PROMO_PERCENT) return toast(EmotesI18n.t("editor.error.discount"), false)
        val discountDays = current.discountDays.trim().toIntOrNull()
        val listing = listing(current.id)
        val discountActive = listing != null && listing.itemDiscount(System.currentTimeMillis()) > 0
        val discountDuration = when {
            percent == 0 -> 0L
            discountDays != null && discountDays in 1..EmoteShopSettings.MAX_DAYS -> discountDays * EmoteShopSettings.DAY_MS
            discountDays == null && discountActive -> EmoteEditorSavePayload.KEEP
            else -> return toast(EmotesI18n.t("editor.error.discount_days"), false)
        }
        val newDays = current.newDays.trim().toIntOrNull()
        val newDuration = when {
            !current.newEnabled -> 0L
            newDays != null && newDays in 1..EmoteShopSettings.MAX_DAYS -> newDays * EmoteShopSettings.DAY_MS
            newDays == null -> EmoteEditorSavePayload.KEEP
            else -> return toast(EmotesI18n.t("editor.error.new_days"), false)
        }
        if (current.newEnabled && newDuration == EmoteEditorSavePayload.KEEP && !current.creating && listing?.isNew(System.currentTimeMillis()) != true) {
            return toast(EmotesI18n.t("editor.error.new_days"), false)
        }

        val draft = draft(current.id)
        val file = current.pendingFile ?: draft?.data
        val sha = current.pendingSha ?: draft?.sha256
        val payload = EmoteEditorSavePayload(
            creating = current.creating,
            id = current.id,
            name = name.take(EmoteListing.MAX_NAME_LENGTH),
            description = current.description.trim().take(EmoteListing.MAX_DESCRIPTION_LENGTH),
            author = current.author.trim().take(EmoteListing.MAX_AUTHOR_LENGTH),
            categoryId = current.categoryId ?: "",
            rarity = current.rarity,
            access = current.access,
            price = if (current.access == EmoteAccess.SHOP) price.coerceIn(0, EmoteShopSettings.MAX_PRICE) else 0L,
            discountPercent = percent,
            discountDurationMs = discountDuration,
            published = current.published,
            newDurationMs = newDuration,
            playableWhileMoving = current.playableWhileMoving,
            fileSha256 = if (file != null) sha!! else ""
        )
        if (!ClientPlayNetworking.canSend(EmoteEditorSavePayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        saving = true
        if (file != null) {
            // Le fichier part d'abord, morceau par morceau : le serveur le reçoit avant l'enregistrement.
            EmoteUploader.send(file, sha!!) { ClientPlayNetworking.send(payload) }
        } else {
            ClientPlayNetworking.send(payload)
        }
    }

    /** Enregistre tous les brouillons d'un coup (import en lot), en brouillon dans le catalogue. */
    fun saveAllDrafts() {
        if (saving || drafts.isEmpty()) return
        if (!ClientPlayNetworking.canSend(EmoteEditorSavePayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        saving = true
        batchRemaining = drafts.size
        drafts.toList().forEach { draft ->
            val payload = EmoteEditorSavePayload(
                creating = true, id = draft.id,
                name = draft.parsed.name.ifBlank { draft.fileName.substringBeforeLast('.') }.take(EmoteListing.MAX_NAME_LENGTH),
                description = draft.parsed.description.take(EmoteListing.MAX_DESCRIPTION_LENGTH),
                author = draft.parsed.author.take(EmoteListing.MAX_AUTHOR_LENGTH),
                categoryId = "", rarity = EmoteRarity.COMMUN, access = EmoteAccess.SHOP, price = 500,
                discountPercent = 0, discountDurationMs = 0, published = false,
                newDurationMs = EmoteEditorSavePayload.KEEP, playableWhileMoving = false, fileSha256 = draft.sha256
            )
            EmoteUploader.send(draft.data, draft.sha256) { ClientPlayNetworking.send(payload) }
        }
    }

    fun discardDraft() {
        val id = selectedId ?: return
        drafts.removeAll { it.id == id }
        closePanel()
    }

    /** Publier ou repasser en brouillon depuis la carte, sans ouvrir le panneau. */
    fun quickPublish(id: UUID, publish: Boolean) {
        if (!ClientPlayNetworking.canSend(EmoteEditorActionPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        ClientPlayNetworking.send(EmoteEditorActionPayload(if (publish) EmoteEditorActionPayload.Action.PUBLISH else EmoteEditorActionPayload.Action.UNPUBLISH, id))
    }

    // ─── Sélection multiple et actions groupées ───────────────────────────────────

    fun isSelected(id: UUID): Boolean = id in selection

    fun toggleSelection(id: UUID) {
        if (!selection.remove(id)) selection += id
        confirmBulkDelete = false
    }

    fun selectAllVisible() {
        visibleListings.forEach { if (it.id !in selection) selection += it.id }
    }

    fun clearSelection() {
        selection.clear()
        confirmBulkDelete = false
        if (menu?.name?.startsWith("BULK") == true) menu = null
    }

    fun cancelBulkDelete() {
        confirmBulkDelete = false
    }

    fun openMenu(next: Menu, anchor: Rect) {
        if (menu == next) {
            menu = null
            return
        }
        menu = next
        menuAnchor = anchor
        menuBounds = Rect.Zero
        if (next == Menu.BULK_PRICE) bulkPrice = ""
    }

    fun closeMenu() {
        menu = null
    }

    fun bulk(action: EmoteEditorBulkPayload.Action, price: Long = 0, percent: Int = 0, durationMs: Long = 0) {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        if (action == EmoteEditorBulkPayload.Action.DELETE && !confirmBulkDelete) {
            confirmBulkDelete = true
            return
        }
        confirmBulkDelete = false
        if (!ClientPlayNetworking.canSend(EmoteEditorBulkPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        ClientPlayNetworking.send(EmoteEditorBulkPayload(action, ids, "", price, percent, durationMs))
        menu = null
        if (action == EmoteEditorBulkPayload.Action.DELETE) {
            selection.clear()
            if (selectedId in ids) closePanel()
        }
    }

    fun applyBulkPrice() {
        val price = bulkPrice.trim().toLongOrNull()
        if (price == null || price !in 1..EmoteShopSettings.MAX_PRICE) return toast(EmotesI18n.t("editor.error.price"), false)
        bulk(EmoteEditorBulkPayload.Action.PRICE, price = price)
    }

    fun applyBulkDiscount(remove: Boolean) {
        if (remove) return bulk(EmoteEditorBulkPayload.Action.DISCOUNT, percent = 0)
        val days = bulkDays.trim().toIntOrNull()
        if (days == null || days !in 1..EmoteShopSettings.MAX_DAYS) return toast(EmotesI18n.t("editor.error.discount_days"), false)
        bulk(EmoteEditorBulkPayload.Action.DISCOUNT, percent = bulkPercent, durationMs = days * EmoteShopSettings.DAY_MS)
    }

    fun applyBulkNew(remove: Boolean) {
        if (remove) return bulk(EmoteEditorBulkPayload.Action.NEW_BADGE, durationMs = 0)
        val days = bulkNewDays.trim().toIntOrNull()
        if (days == null || days !in 1..EmoteShopSettings.MAX_DAYS) return toast(EmotesI18n.t("editor.error.new_days"), false)
        bulk(EmoteEditorBulkPayload.Action.NEW_BADGE, durationMs = days * EmoteShopSettings.DAY_MS)
    }

    fun restartPreview() {
        previewPaused = false
        previewRestart++
    }

    fun action(action: EmoteEditorActionPayload.Action) {
        val id = selectedId ?: return
        if (action == EmoteEditorActionPayload.Action.DELETE && !confirmDelete) {
            confirmDelete = true
            return
        }
        confirmDelete = false
        if (!ClientPlayNetworking.canSend(EmoteEditorActionPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        ClientPlayNetworking.send(EmoteEditorActionPayload(action, id))
        if (action == EmoteEditorActionPayload.Action.DELETE) {
            selection.remove(id)
            closePanel()
        }
    }

    // ─── Boutique ────────────────────────────────────────────────────────────────

    fun loadShopFields() {
        val now = System.currentTimeMillis()
        promoPercent = if (settings.promoActive(now)) settings.promoPercent.toString() else "0"
        promoDays = ""
        newDaysSetting = settings.newDays.toString()
    }

    fun saveShopSettings() {
        val percent = promoPercent.trim().ifEmpty { "0" }.toIntOrNull()
        if (percent == null || percent !in 0..EmoteShopSettings.MAX_PROMO_PERCENT) return toast(EmotesI18n.t("editor.error.discount"), false)
        val days = promoDays.trim().toIntOrNull()
        val active = settings.promoActive(System.currentTimeMillis())
        val duration = when {
            percent == 0 -> 0L
            days != null && days in 1..EmoteShopSettings.MAX_DAYS -> days * EmoteShopSettings.DAY_MS
            days == null && active -> EmoteEditorSavePayload.KEEP
            else -> return toast(EmotesI18n.t("editor.error.promo_days"), false)
        }
        val newDays = newDaysSetting.trim().toIntOrNull()
        if (newDays == null || newDays !in 0..EmoteShopSettings.MAX_DAYS) return toast(EmotesI18n.t("editor.error.new_days"), false)
        if (!ClientPlayNetworking.canSend(EmoteShopSettingsPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        ClientPlayNetworking.send(EmoteShopSettingsPayload(percent, duration, newDays))
    }

    /** Le serveur ramène les icônes trop lourdes du catalogue à 256 px (fichiers plus légers à télécharger). */
    fun shrinkIcons() {
        if (shrinking) return
        val ids = listings.map { it.id }
        if (ids.isEmpty()) return
        if (!ClientPlayNetworking.canSend(EmoteEditorBulkPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        shrinking = true
        ClientPlayNetworking.send(EmoteEditorBulkPayload(EmoteEditorBulkPayload.Action.SHRINK_ICONS, ids))
    }

    /** Taille de l'icône rangée dans le fichier de l'émote (0 sans icône ou pas encore téléchargée). */
    fun iconBytes(listing: EmoteListing): Int = EmoteHolder.list[listing.id]?.emote?.let { EmoteFiles.iconBytes(it)?.size } ?: 0

    // ─── Réponses ────────────────────────────────────────────────────────────────

    private fun onResult(result: EmoteEditorResultPayload) {
        if (batchRemaining > 0) {
            batchRemaining--
            if (result.success) drafts.removeAll { it.id == result.emoteId }
            if (batchRemaining == 0) {
                saving = false
                toast(EmotesI18n.t("editor.import.saved"), true)
            } else if (!result.success) {
                toast(result.message, false)
            }
            return
        }
        saving = false
        if (result.emoteId == null) shrinking = false
        toast(result.message, result.success)
        if (!result.success) return
        val id = result.emoteId ?: run {
            loadShopFields()
            // Action groupée ou réglages : le formulaire ouvert est relu s'il n'a rien en cours.
            if (form?.creating == false && changeCount == 0) form = null
            return
        }
        if (drafts.removeAll { it.id == id } || form?.pendingFile != null) EmoteIcons.invalidate(id)
        // Le catalogue arrive avec la réponse : le formulaire sera relu à la prochaine frame.
        if (selectedId == id) form = null
    }

    // ─── Aperçu et messages ───────────────────────────────────────────────────────

    fun rotatePreview(deltaYaw: Float, deltaPitch: Float) {
        previewYaw += deltaYaw
        previewPitch = clampPreviewPitch(previewPitch + deltaPitch)
    }

    fun zoomPreview(scroll: Float) {
        previewZoom = applyPreviewZoom(previewZoom, scroll)
    }

    fun resetPreviewView() {
        previewYaw = PREVIEW_DEFAULT_YAW
        previewPitch = PREVIEW_DEFAULT_PITCH
        previewZoom = 1f
    }

    fun toast(message: String, success: Boolean) {
        toastMessage = message
        toastSuccess = success
    }

    fun dismissToast() {
        toastMessage = null
    }
}
