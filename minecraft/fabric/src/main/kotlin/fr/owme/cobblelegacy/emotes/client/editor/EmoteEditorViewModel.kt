package fr.owme.cobblelegacy.emotes.client.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
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
import fr.owme.cobblelegacy.emotes.network.EmoteCategoryEditPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import io.github.kosmx.emotes.main.EmoteHolder
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** État de l'éditeur du catalogue d'émotes (administrateurs). */
class EmoteEditorViewModel(initialSelection: UUID?) {

    enum class Tab { EMOTES, CATEGORIES, SHOP }

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

    // Réglages de la boutique
    var promoPercent by mutableStateOf("")
    var promoDays by mutableStateOf("")
    var newDaysSetting by mutableStateOf("")
    var newCategoryName by mutableStateOf("")
    var renaming by mutableStateOf<String?>(null)
    var renameText by mutableStateOf("")
    var confirmDeleteCategory by mutableStateOf<String?>(null)

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
    }

    private fun observe() {
        revision
    }

    // ─── Données ────────────────────────────────────────────────────────────────

    val listings: List<EmoteListing>
        get() {
            observe()
            val needle = normalizeSearch(query)
            return ClientEmoteCatalog.listings.filter { needle.isEmpty() || normalizeSearch(it.name).contains(needle) }
        }

    val categories: List<EmoteCategory> get() = observe().let { ClientEmoteCatalog.categories }
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
    }

    fun update(transform: (Form) -> Form) {
        form = form?.let(transform)
        confirmDelete = false
    }

    // ─── Import de fichiers ────────────────────────────────────────────────────────

    /** Fichiers choisis ou glissés sur la fenêtre : `.emotecraft`/`.json` deviennent des brouillons, un `.png` l'icône de l'émote choisie. */
    fun importFiles(paths: List<Path>) {
        val emotes = paths.filter { EmoteFiles.extensionOf(it.fileName.toString()) in EmoteFiles.IMPORT_EXTENSIONS }
        val images = paths.filter { EmoteFiles.extensionOf(it.fileName.toString()) == "png" }
        if (emotes.isEmpty() && images.size == 1 && selectedId != null) {
            changeIcon(images.first())
            return
        }
        var firstNew: UUID? = null
        var skipped = 0
        emotes.forEach { path ->
            val draft = readDraft(path)
            when {
                draft == null -> skipped++
                listing(draft.id) != null || drafts.any { it.id == draft.id } -> skipped++
                else -> {
                    drafts += draft
                    if (firstNew == null) firstNew = draft.id
                }
            }
        }
        val added = emotes.size - skipped
        firstNew?.let { select(it) }
        when {
            added > 0 && skipped > 0 -> toast(EmotesI18n.t("editor.import.partial", added, skipped), true)
            added > 0 -> toast(EmotesI18n.t("editor.import.done", added), true)
            emotes.isNotEmpty() -> toast(EmotesI18n.t("editor.import.none"), false)
        }
    }

    private fun readDraft(path: Path): Draft? = try {
        val fileName = path.fileName.toString()
        val parsed = EmoteFiles.parse(Files.readAllBytes(path), fileName) ?: return null
        var animation = parsed.animation
        // Icône à côté du fichier (même nom, .png), comme dans le dossier d'émotes d'Emotecraft.
        val icon = path.resolveSibling(fileName.substringBeforeLast('.') + ".png")
        if (!parsed.hasIcon && Files.isRegularFile(icon) && Files.size(icon) <= EmoteFiles.MAX_ICON_SIZE) {
            animation = EmoteFiles.withIcon(animation, Files.readAllBytes(icon))
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

    fun pickFiles() = EmoteFilePicker.pickEmotes { importFiles(it) }

    fun pickIcon() = EmoteFilePicker.pickIcon { changeIcon(it) }

    /** Nouvelle icône pour l'émote choisie : le fichier est réécrit et sera renvoyé à l'enregistrement. */
    private fun changeIcon(path: Path) {
        val current = form ?: return
        val png = runCatching { Files.readAllBytes(path) }.getOrNull()
        if (png == null || png.size > EmoteFiles.MAX_ICON_SIZE || EmoteIcons.decode(png) == null) {
            toast(EmotesI18n.t("editor.icon.invalid"), false)
            return
        }
        val animation = selectedAnimation ?: run {
            toast(EmotesI18n.t("editor.icon.loading"), false)
            return
        }
        val binary = EmoteFiles.toBinary(EmoteFiles.withIcon(animation, png))
        form = current.copy(pendingFile = binary, pendingSha = EmoteFiles.sha256(binary), pendingIcon = EmoteIcons.decode(png))
        toast(EmotesI18n.t("editor.icon.changed"), true)
    }

    // ─── Enregistrement ───────────────────────────────────────────────────────────

    fun save() {
        val current = form ?: return
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
        selectedId = null
        form = null
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
            selectedId = null
            form = null
        }
    }

    // ─── Catégories ──────────────────────────────────────────────────────────────

    fun category(action: EmoteCategoryEditPayload.Action, id: String = "", name: String = "") {
        if (action == EmoteCategoryEditPayload.Action.DELETE && confirmDeleteCategory != id) {
            confirmDeleteCategory = id
            return
        }
        confirmDeleteCategory = null
        if (!ClientPlayNetworking.canSend(EmoteCategoryEditPayload.TYPE)) return toast(EmotesI18n.t("editor.error.server"), false)
        ClientPlayNetworking.send(EmoteCategoryEditPayload(action, id, name.trim()))
        if (action == EmoteCategoryEditPayload.Action.CREATE) newCategoryName = ""
        if (action == EmoteCategoryEditPayload.Action.RENAME) renaming = null
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
        toast(result.message, result.success)
        if (!result.success) return
        val id = result.emoteId ?: run {
            loadShopFields()
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

    fun toast(message: String, success: Boolean) {
        toastMessage = message
        toastSuccess = success
    }

    fun dismissToast() {
        toastMessage = null
    }
}
