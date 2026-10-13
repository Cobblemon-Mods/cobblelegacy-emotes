package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.EmoteIconFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.network.EmoteCategoryEditPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorBulkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Modifications du catalogue, communes à l'éditeur et aux commandes `/emoteshop`.
 *
 * Tout est revérifié ici, côté serveur. La base est écrite d'abord ; en cas de succès le catalogue est
 * relu puis renvoyé à tous les joueurs. [done] reçoit `null` si tout va bien, sinon le message
 * d'erreur ; il est appelé sur le thread du serveur.
 */
object EmoteAdmin {
    private val logger = CobbleLegacyEmotes.logger

    private const val DAY = EmoteShopSettings.DAY_MS

    /** En dessous, le fichier ne peut pas cacher une icône lourde : pas la peine de le relire. */
    private const val LIGHT_FILE_BYTES = 48 * 1024
    private const val KEEP = EmoteEditorSavePayload.KEEP

    private fun unavailable(): String? = when {
        ServerEmoteCatalog.storage == null || !ServerEmoteCatalog.ready -> "Le catalogue d'émotes est indisponible sur ce serveur."
        else -> null
    }

    // ─── Émotes ──────────────────────────────────────────────────────────────────

    fun save(server: MinecraftServer, author: UUID?, payload: EmoteEditorSavePayload, done: (String?) -> Unit) =
        queued(server, done) { reply -> doSave(server, author, payload, reply) }

    private fun doSave(server: MinecraftServer, author: UUID?, payload: EmoteEditorSavePayload, done: (String?) -> Unit) {
        unavailable()?.let { return done(it) }
        val now = System.currentTimeMillis()
        val existing = ServerEmoteCatalog.listings[payload.id]
        if (payload.creating && existing != null) return done("Cette émote est déjà dans le catalogue (« ${existing.name} »).")
        if (!payload.creating && existing == null) return done("Cette émote n'existe plus.")

        val name = EmoteFiles.plain(payload.name).trim()
        if (name.isEmpty() || name.length > EmoteListing.MAX_NAME_LENGTH) return done("Le nom doit faire entre 1 et ${EmoteListing.MAX_NAME_LENGTH} caractères.")
        if (name.any { it.isISOControl() }) return done("Le nom contient des caractères invalides.")
        val description = EmoteFiles.plain(payload.description).trim().take(EmoteListing.MAX_DESCRIPTION_LENGTH)
        val authorName = EmoteFiles.plain(payload.author).trim().take(EmoteListing.MAX_AUTHOR_LENGTH)
        val categoryId = payload.categoryId.ifBlank { null }
        if (categoryId != null && ServerEmoteCatalog.categories.none { it.id == categoryId }) return done("Cette catégorie n'existe plus.")
        if (payload.price !in 0..EmoteShopSettings.MAX_PRICE) return done("Prix invalide.")
        if (payload.access == EmoteAccess.SHOP && payload.price <= 0) return done("Une émote en vente doit avoir un prix.")
        if (payload.discountPercent !in 0..EmoteShopSettings.MAX_PROMO_PERCENT) return done("La réduction doit être comprise entre 0 et ${EmoteShopSettings.MAX_PROMO_PERCENT} %.")

        val discountEnd = when {
            payload.discountPercent == 0 || payload.discountDurationMs == 0L -> 0L
            payload.discountDurationMs == KEEP -> existing?.discountEndsAtMs?.takeIf { it > now }
                ?: return done("Indique la durée de la réduction.")
            payload.discountDurationMs in 1..EmoteShopSettings.MAX_DAYS * DAY -> now + payload.discountDurationMs
            else -> return done("La réduction dure de 1 à ${EmoteShopSettings.MAX_DAYS} jours.")
        }
        val newUntil = when {
            payload.newDurationMs == 0L -> 0L
            payload.newDurationMs == KEEP -> existing?.newUntilMs
                ?: ServerEmoteCatalog.settings.newDays.takeIf { it > 0 }?.let { now + it * DAY } ?: 0L
            payload.newDurationMs in 1..EmoteShopSettings.MAX_DAYS * DAY -> now + payload.newDurationMs
            else -> return done("Le badge NEW dure de 1 à ${EmoteShopSettings.MAX_DAYS} jours.")
        }

        var file: ByteArray? = null
        var parsed: EmoteFiles.Parsed? = null
        if (payload.fileSha256.isNotEmpty()) {
            val bytes = EmoteFileServer.takeUpload(author ?: UUID(0, 0), payload.fileSha256)
                ?: return done("Fichier de l'émote non reçu, réessaie.")
            parsed = EmoteFiles.parseBinary(bytes) ?: return done("Ce fichier n'est pas une émote Emotecraft valide.")
            if (parsed.animation.uuid != payload.id) return done("Ce fichier contient une autre émote : ajoute-la comme nouvelle émote.")
            file = bytes
        } else if (payload.creating) {
            return done("Choisis le fichier de l'émote.")
        }

        val base = existing
        val listing = EmoteListing(
            id = payload.id,
            name = name,
            description = description,
            author = authorName,
            categoryId = categoryId,
            rarity = payload.rarity,
            access = payload.access,
            price = if (payload.access == EmoteAccess.SHOP) payload.price else 0L,
            discountPercent = if (discountEnd > 0) payload.discountPercent else 0,
            discountEndsAtMs = discountEnd,
            published = payload.published,
            newUntilMs = newUntil,
            sortOrder = base?.sortOrder ?: nextSortOrder(),
            fileSha256 = if (file != null) payload.fileSha256 else base!!.fileSha256,
            fileSize = file?.size ?: base!!.fileSize,
            durationTicks = parsed?.durationTicks ?: base!!.durationTicks,
            loops = parsed?.loops ?: base!!.loops,
            hasIcon = parsed?.hasIcon ?: base!!.hasIcon,
            addedAtMs = base?.addedAtMs ?: now,
            playableWhileMoving = payload.playableWhileMoving
        )
        commit(server, ServerEmoteCatalog.storage!!.saveListing(listing, file, author), "Enregistrement impossible.", done)
    }

    private fun nextSortOrder(): Int = (ServerEmoteCatalog.listings.values.maxOfOrNull { it.sortOrder } ?: -1) + 1

    fun action(server: MinecraftServer, payload: EmoteEditorActionPayload, done: (String?) -> Unit) =
        queued(server, done) { reply -> doAction(server, payload, reply) }

    private fun doAction(server: MinecraftServer, payload: EmoteEditorActionPayload, done: (String?) -> Unit) {
        unavailable()?.let { return done(it) }
        val storage = ServerEmoteCatalog.storage!!
        val listing = ServerEmoteCatalog.listings[payload.id] ?: return done("Cette émote n'existe plus.")
        when (payload.action) {
            EmoteEditorActionPayload.Action.DELETE ->
                commit(server, storage.deleteListing(listing.id), "Suppression impossible.", done)
            EmoteEditorActionPayload.Action.PUBLISH, EmoteEditorActionPayload.Action.UNPUBLISH -> {
                val publish = payload.action == EmoteEditorActionPayload.Action.PUBLISH
                if (publish && listing.access == EmoteAccess.SHOP && listing.price <= 0) return done("Donne-lui un prix avant de la publier.")
                commit(server, storage.setPublished(listOf(listing.id), publish), "Modification impossible.", done)
            }
            EmoteEditorActionPayload.Action.MOVE_UP, EmoteEditorActionPayload.Action.MOVE_DOWN -> {
                val ordered = ServerEmoteCatalog.listings.values.sortedWith(compareBy({ it.sortOrder }, { it.addedAtMs })).toMutableList()
                val index = ordered.indexOfFirst { it.id == listing.id }
                val target = index + if (payload.action == EmoteEditorActionPayload.Action.MOVE_UP) -1 else 1
                if (target !in ordered.indices) return done(null)
                ordered.add(target, ordered.removeAt(index))
                val orders = ordered.withIndex().associate { (position, entry) -> entry.id to position }
                commit(server, storage.saveSortOrders(orders), "Déplacement impossible.", done)
            }
        }
    }

    // ─── Actions groupées ────────────────────────────────────────────────────────

    /** Même modification sur plusieurs émotes. [done] reçoit l'erreur, ou `null` et le compte rendu. */
    fun bulk(server: MinecraftServer, payload: EmoteEditorBulkPayload, done: (error: String?, summary: String) -> Unit) =
        enqueue(server, { done(INTERNAL_ERROR, "") }) { finish ->
            doBulk(server, payload) { error, summary -> done(error, summary); finish() }
        }

    private fun doBulk(server: MinecraftServer, payload: EmoteEditorBulkPayload, done: (String?, String) -> Unit) {
        unavailable()?.let { return done(it, "") }
        val storage = ServerEmoteCatalog.storage!!
        val targets = payload.ids.distinct().mapNotNull { ServerEmoteCatalog.listings[it] }
        if (targets.isEmpty()) return done("Ces émotes ne sont plus dans le catalogue.", "")
        val now = System.currentTimeMillis()
        val forSale = targets.filter { it.access == EmoteAccess.SHOP }
        val maxDuration = EmoteShopSettings.MAX_DAYS * DAY
        val days = payload.durationMs / DAY
        when (payload.action) {
            EmoteEditorBulkPayload.Action.PUBLISH -> {
                val ready = targets.filter { it.access != EmoteAccess.SHOP || it.price > 0 }
                if (ready.isEmpty()) return done("Donne-leur un prix avant de les publier.", "")
                val skipped = targets.size - ready.size
                val summary = emotes(ready.size, "publiée") + if (skipped > 0) " ($skipped sans prix restent en brouillon)." else "."
                commitBulk(server, storage.setPublished(ready.map { it.id }, true), summary, done)
            }
            EmoteEditorBulkPayload.Action.UNPUBLISH ->
                commitBulk(server, storage.setPublished(targets.map { it.id }, false), emotes(targets.size, "repassée") + " en brouillon.", done)
            EmoteEditorBulkPayload.Action.CATEGORY -> {
                val categoryId = payload.categoryId.ifBlank { null }
                val category = categoryId?.let { id ->
                    ServerEmoteCatalog.categories.firstOrNull { it.id == id } ?: return done("Cette catégorie n'existe plus.", "")
                }
                val summary = if (category != null) emotes(targets.size, "rangée") + " dans « ${category.name} »." else emotes(targets.size, "retirée") + " de leur catégorie."
                commitBulk(server, storage.saveListings(targets.map { it.copy(categoryId = categoryId) }), summary, done)
            }
            EmoteEditorBulkPayload.Action.PRICE -> {
                if (payload.price !in 1..EmoteShopSettings.MAX_PRICE) return done("Prix invalide.", "")
                if (forSale.isEmpty()) return done("Aucune de ces émotes n'est en vente.", "")
                commitBulk(
                    server, storage.saveListings(forSale.map { it.copy(price = payload.price) }),
                    "Prix de ${count(forSale.size)} : ${EmoteShopServer.crystals(payload.price)} cristaux.", done
                )
            }
            EmoteEditorBulkPayload.Action.DISCOUNT -> {
                if (forSale.isEmpty()) return done("Aucune de ces émotes n'est en vente.", "")
                val percent = payload.percent
                if (percent != 0 && percent !in 1..EmoteShopSettings.MAX_PROMO_PERCENT) {
                    return done("La réduction doit être comprise entre 1 et ${EmoteShopSettings.MAX_PROMO_PERCENT} %.", "")
                }
                if (percent != 0 && payload.durationMs !in 1..maxDuration) return done("La réduction dure de 1 à ${EmoteShopSettings.MAX_DAYS} jours.", "")
                val updated = forSale.map {
                    if (percent == 0) it.copy(discountPercent = 0, discountEndsAtMs = 0L)
                    else it.copy(discountPercent = percent, discountEndsAtMs = now + payload.durationMs)
                }
                val summary = if (percent == 0) "Réduction retirée de ${count(forSale.size)}." else "-$percent % sur ${count(forSale.size)} pendant $days jour(s)."
                commitBulk(server, storage.saveListings(updated), summary, done)
            }
            EmoteEditorBulkPayload.Action.NEW_BADGE -> {
                val until = when (payload.durationMs) {
                    0L -> 0L
                    in 1..maxDuration -> now + payload.durationMs
                    else -> return done("Le badge NEW dure de 1 à ${EmoteShopSettings.MAX_DAYS} jours.", "")
                }
                val summary = if (until == 0L) "Badge NEW retiré de ${count(targets.size)}." else "Badge NEW sur ${count(targets.size)} pendant $days jour(s)."
                commitBulk(server, storage.saveListings(targets.map { it.copy(newUntilMs = until) }), summary, done)
            }
            EmoteEditorBulkPayload.Action.DELETE ->
                commitBulk(server, storage.deleteListings(targets.map { it.id }), emotes(targets.size, "supprimée") + " du catalogue.", done)
            EmoteEditorBulkPayload.Action.SHRINK_ICONS -> shrinkIcons(server, targets, done)
        }
    }

    /**
     * Icônes trop lourdes ramenées à 256 px : chaque fichier est relu, réécrit avec son icône allégée
     * (animation intacte) et remplacé s'il n'a pas changé entre-temps. Hors du thread du serveur.
     */
    private fun shrinkIcons(server: MinecraftServer, targets: List<EmoteListing>, done: (String?, String) -> Unit) {
        val storage = ServerEmoteCatalog.storage!!
        val candidates = targets.filter { it.hasIcon && it.fileSize > LIGHT_FILE_BYTES }
        if (candidates.isEmpty()) return done(null, "Les icônes sont déjà légères.")
        CompletableFuture.supplyAsync({
            var shrunk = 0
            var before = 0L
            var after = 0L
            for (listing in candidates) {
                val bytes = storage.readFile(listing.fileSha256).join() ?: continue
                val animation = EmoteFiles.parseBinary(bytes)?.animation ?: continue
                val lighter = EmoteIconFiles.lighten(animation)
                if (lighter === animation) continue
                val file = EmoteFiles.toBinary(lighter)
                if (file.size >= bytes.size) continue
                if (storage.replaceFile(listing.id, listing.fileSha256, EmoteFiles.sha256(file), file).join()) {
                    shrunk++
                    before += bytes.size
                    after += file.size
                }
            }
            Triple(shrunk, before, after)
        }, { task -> Thread(task, "CobbleLegacyEmotes-Icons").apply { isDaemon = true }.start() }).whenComplete { result, error ->
            server.execute {
                when {
                    error != null || result == null -> {
                        logger.error("[Émotes] Allègement des icônes en échec", error)
                        done("Allègement des icônes impossible.", "")
                    }
                    result.first == 0 -> done(null, "Les icônes sont déjà légères.")
                    else -> ServerEmoteCatalog.reload {
                        done(null, "${if (result.first == 1) "1 icône allégée" else "${result.first} icônes allégées"} : ${megabytes(result.second)} → ${megabytes(result.third)}.")
                    }
                }
            }
        }
    }

    private fun megabytes(bytes: Long): String =
        if (bytes < 1024 * 1024) "${(bytes + 1023) / 1024} Ko" else "%.1f Mo".format(Locale.FRANCE, bytes / 1048576.0)

    /** « 1 émote publiée », « 3 émotes publiées ». */
    private fun emotes(count: Int, participle: String) = if (count == 1) "1 émote $participle" else "$count émotes ${participle}s"

    private fun count(count: Int) = if (count == 1) "1 émote" else "$count émotes"

    private fun commitBulk(server: MinecraftServer, write: CompletableFuture<Boolean>, summary: String, done: (String?, String) -> Unit) {
        write.thenAccept { ok ->
            server.execute {
                if (ok) ServerEmoteCatalog.reload { done(null, summary) } else done("Enregistrement impossible.", "")
            }
        }
    }

    // ─── Catégories ──────────────────────────────────────────────────────────────

    fun category(server: MinecraftServer, payload: EmoteCategoryEditPayload, done: (String?) -> Unit) =
        queued(server, done) { reply -> doCategory(server, payload, reply) }

    private fun doCategory(server: MinecraftServer, payload: EmoteCategoryEditPayload, done: (String?) -> Unit) {
        unavailable()?.let { return done(it) }
        val storage = ServerEmoteCatalog.storage!!
        val categories = ServerEmoteCatalog.categories.toMutableList()
        val name = EmoteFiles.plain(payload.name).trim()
        fun checkName(): String? = when {
            name.isEmpty() || name.length > EmoteCategory.MAX_NAME_LENGTH -> "Le nom fait de 1 à ${EmoteCategory.MAX_NAME_LENGTH} caractères."
            name.any { it.isISOControl() } -> "Le nom contient des caractères invalides."
            categories.any { it.name.equals(name, ignoreCase = true) && it.id != payload.id } -> "Cette catégorie existe déjà."
            else -> null
        }
        when (payload.action) {
            EmoteCategoryEditPayload.Action.CREATE -> {
                checkName()?.let { return done(it) }
                val id = uniqueSlug(name, categories.map { it.id }.toSet())
                categories += EmoteCategory(id, name, categories.size)
                commit(server, storage.saveCategories(renumber(categories), null), "Création impossible.", done)
            }
            EmoteCategoryEditPayload.Action.RENAME -> {
                val index = categories.indexOfFirst { it.id == payload.id }
                if (index < 0) return done("Cette catégorie n'existe plus.")
                checkName()?.let { return done(it) }
                categories[index] = categories[index].copy(name = name)
                commit(server, storage.saveCategories(renumber(categories), null), "Modification impossible.", done)
            }
            EmoteCategoryEditPayload.Action.DELETE -> {
                if (categories.none { it.id == payload.id }) return done("Cette catégorie n'existe plus.")
                categories.removeAll { it.id == payload.id }
                commit(server, storage.saveCategories(renumber(categories), payload.id), "Suppression impossible.", done)
            }
            EmoteCategoryEditPayload.Action.MOVE_UP, EmoteCategoryEditPayload.Action.MOVE_DOWN -> {
                val index = categories.indexOfFirst { it.id == payload.id }
                if (index < 0) return done("Cette catégorie n'existe plus.")
                val target = index + if (payload.action == EmoteCategoryEditPayload.Action.MOVE_UP) -1 else 1
                if (target !in categories.indices) return done(null)
                categories.add(target, categories.removeAt(index))
                commit(server, storage.saveCategories(renumber(categories), null), "Déplacement impossible.", done)
            }
        }
    }

    private fun renumber(categories: List<EmoteCategory>) = categories.mapIndexed { index, category -> category.copy(sortOrder = index) }

    /** « Danses & fêtes » → `danses_fetes`, unique parmi [taken]. */
    private fun uniqueSlug(name: String, taken: Set<String>): String {
        val base = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(48)
            .ifEmpty { "categorie" }
        if (base !in taken) return base
        var index = 2
        while ("${base}_$index" in taken) index++
        return "${base}_$index"
    }

    // ─── Réglages de la boutique ─────────────────────────────────────────────────

    fun settings(server: MinecraftServer, payload: EmoteShopSettingsPayload, done: (String?) -> Unit) =
        queued(server, done) { reply -> doSettings(server, payload, reply) }

    private fun doSettings(server: MinecraftServer, payload: EmoteShopSettingsPayload, done: (String?) -> Unit) {
        unavailable()?.let { return done(it) }
        val now = System.currentTimeMillis()
        val current = ServerEmoteCatalog.settings
        if (payload.newDays !in 0..EmoteShopSettings.MAX_DAYS) return done("Le badge NEW dure de 0 à ${EmoteShopSettings.MAX_DAYS} jours.")
        val settings = when {
            payload.promoPercent == 0 -> current.copy(promoPercent = 0, promoEndsAtMs = 0L, newDays = payload.newDays)
            payload.promoPercent !in 1..EmoteShopSettings.MAX_PROMO_PERCENT ->
                return done("La promotion doit être comprise entre 1 et ${EmoteShopSettings.MAX_PROMO_PERCENT} %.")
            payload.promoDurationMs == KEEP && current.promoActive(now) ->
                current.copy(promoPercent = payload.promoPercent, newDays = payload.newDays)
            payload.promoDurationMs in 1..EmoteShopSettings.MAX_DAYS * DAY ->
                current.copy(promoPercent = payload.promoPercent, promoEndsAtMs = now + payload.promoDurationMs, newDays = payload.newDays)
            else -> return done("La promotion dure de 1 à ${EmoteShopSettings.MAX_DAYS} jours.")
        }
        commit(server, ServerEmoteCatalog.storage!!.saveSettings(settings), "Enregistrement des réglages impossible.", done)
    }

    // ─── Import d'un dossier (commande) ──────────────────────────────────────────

    class ImportReport(val added: List<String>, val skipped: List<String>)

    /**
     * Ajoute au catalogue, en brouillon, les fichiers de `config/cobblelegacy-emotes/import/`
     * (`.emotecraft` ou `.json`, avec une icône `.png` du même nom si elle existe). Les fichiers
     * importés sont rangés dans `import/importees/`.
     */
    fun importFolder(server: MinecraftServer, publish: Boolean, author: UUID?, done: (ImportReport?, String?) -> Unit) =
        enqueue(server, { done(null, INTERNAL_ERROR) }) { finish ->
            doImportFolder(server, publish, author) { report, error -> done(report, error); finish() }
        }

    private fun doImportFolder(server: MinecraftServer, publish: Boolean, author: UUID?, done: (ImportReport?, String?) -> Unit) {
        unavailable()?.let { return done(null, it) }
        val storage = ServerEmoteCatalog.storage!!
        val folder = EmoteServerConfig.importDirectory
        val files = Files.list(folder).use { paths ->
            paths.filter { Files.isRegularFile(it) && EmoteFiles.extensionOf(it.fileName.toString()) in EmoteFiles.IMPORT_EXTENSIONS }.sorted().toList()
        }
        if (files.isEmpty()) return done(ImportReport(emptyList(), emptyList()), null)

        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val now = System.currentTimeMillis()
        var order = nextSortOrder()
        val known = ServerEmoteCatalog.listings.keys.toMutableSet()
        val writes = mutableListOf<CompletableFuture<Boolean>>()
        val defaultPrice = EmoteServerConfig.gameplay.prixImportParDefaut.coerceIn(1, EmoteShopSettings.MAX_PRICE)

        for (path in files) {
            val fileName = path.fileName.toString()
            val parsed = runCatching {
                if (Files.size(path) <= EmoteFiles.MAX_IMPORT_SIZE) EmoteFiles.parse(Files.readAllBytes(path), fileName, EmoteFiles.MAX_IMPORT_SIZE) else null
            }.getOrNull()
            if (parsed == null) {
                skipped += "$fileName (illisible)"
                continue
            }
            // Icône allégée (256 px) : celle du fichier, sinon une image du même nom à côté.
            var animation = EmoteIconFiles.lighten(parsed.animation)
            val base = fileName.substringBeforeLast('.')
            val icon = EmoteIconFiles.EXTENSIONS.map { path.resolveSibling("$base.$it") }
                .firstOrNull { Files.isRegularFile(it) && Files.size(it) <= EmoteIconFiles.MAX_SOURCE_BYTES }
            if (icon != null) {
                runCatching { EmoteIconFiles.light(Files.readAllBytes(icon)) }.getOrNull()?.let { animation = EmoteFiles.withIcon(animation, it) }
            }
            if (!known.add(animation.uuid)) {
                skipped += "$fileName (déjà au catalogue)"
                continue
            }
            val bytes = EmoteFiles.toBinary(animation)
            if (bytes.size > EmoteFiles.MAX_FILE_SIZE) {
                skipped += "$fileName (trop lourd)"
                continue
            }
            val info = EmoteFiles.describe(animation)
            val name = info.name.ifBlank { fileName.substringBeforeLast('.') }.take(EmoteListing.MAX_NAME_LENGTH)
            val listing = EmoteListing(
                id = animation.uuid,
                name = name,
                description = info.description.take(EmoteListing.MAX_DESCRIPTION_LENGTH),
                author = info.author.take(EmoteListing.MAX_AUTHOR_LENGTH),
                categoryId = null,
                rarity = EmoteRarity.COMMUN,
                access = EmoteAccess.SHOP,
                price = defaultPrice,
                discountPercent = 0,
                discountEndsAtMs = 0L,
                published = publish,
                newUntilMs = ServerEmoteCatalog.settings.newDays.takeIf { it > 0 }?.let { now + it * DAY } ?: 0L,
                sortOrder = order++,
                fileSha256 = EmoteFiles.sha256(bytes),
                fileSize = bytes.size,
                durationTicks = info.durationTicks,
                loops = info.loops,
                hasIcon = info.hasIcon,
                addedAtMs = now
            )
            writes += storage.saveListing(listing, bytes, author)
            added += name
            moveToImported(path)
            if (icon != null && Files.isRegularFile(icon)) moveToImported(icon)
        }

        CompletableFuture.allOf(*writes.toTypedArray()).thenAccept {
            server.execute {
                val failed = writes.count { !it.join() }
                ServerEmoteCatalog.reload {
                    if (failed > 0) done(ImportReport(added, skipped), "$failed émote(s) n'ont pas pu être enregistrées.")
                    else done(ImportReport(added, skipped), null)
                }
            }
        }
    }

    private fun moveToImported(path: Path) {
        runCatching {
            val target = path.parent.resolve("importees")
            Files.createDirectories(target)
            Files.move(path, target.resolve(path.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { logger.warn("[Émotes] Impossible de ranger {} : {}", path.fileName, it.message) }
    }

    // ─── Commun ──────────────────────────────────────────────────────────────────

    private const val INTERNAL_ERROR = "Erreur interne : voir la console du serveur."

    private class Job(val crashed: () -> Unit, val run: (finish: () -> Unit) -> Unit)

    /**
     * Les modifications passent une par une, chacune sur le catalogue laissé par la précédente (et relu
     * s'il a changé depuis un autre serveur). Sans cela, deux catégories créées coup sur coup partent
     * de la même liste et la seconde efface la première.
     */
    private val jobs = ArrayDeque<Job>()
    private var busy = false
    private var generation = 0

    private fun queued(server: MinecraftServer, done: (String?) -> Unit, job: (reply: (String?) -> Unit) -> Unit) =
        enqueue(server, { done(INTERNAL_ERROR) }) { finish -> job { error -> done(error); finish() } }

    private fun enqueue(server: MinecraftServer, crashed: () -> Unit, run: (finish: () -> Unit) -> Unit) {
        jobs.addLast(Job(crashed, run))
        if (!busy) next(server)
    }

    private fun next(server: MinecraftServer) {
        val job = jobs.removeFirstOrNull()
        busy = job != null
        if (job == null) return
        val current = generation
        var finished = false
        val finish = {
            if (!finished && current == generation) {
                finished = true
                server.execute { if (current == generation) next(server) }
            }
        }
        ServerEmoteCatalog.refresh {
            try {
                job.run(finish)
            } catch (e: Exception) {
                logger.error("[Émotes] Modification du catalogue en échec", e)
                if (!finished) job.crashed()
                finish()
            }
        }
    }

    /** Arrêt du serveur : les modifications en attente sont abandonnées. */
    fun clear() {
        jobs.clear()
        busy = false
        generation++
    }

    private fun commit(server: MinecraftServer, write: CompletableFuture<Boolean>, failure: String, done: (String?) -> Unit) {
        write.thenAccept { ok ->
            server.execute {
                if (ok) {
                    ServerEmoteCatalog.reload { done(null) }
                } else {
                    done(failure)
                }
            }
        }
    }
}
