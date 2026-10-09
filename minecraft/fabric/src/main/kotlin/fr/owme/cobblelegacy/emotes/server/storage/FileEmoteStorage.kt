package fr.owme.cobblelegacy.emotes.server.storage

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Catalogue rangé dans `config/cobblelegacy-emotes/local/`, sans base de données : un serveur seul,
 * le solo, les tests. Rien n'est partagé avec d'autres serveurs.
 */
class FileEmoteStorage(private val directory: Path) : EmoteStorage {

    override val shared = false
    override val label = "fichiers locaux ($directory)"

    private val logger = CobbleLegacyEmotes.logger
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CobbleLegacyEmotes-Local").apply { isDaemon = true }
    }

    private val catalogFile get() = directory.resolve("catalogue.json")
    private val ownedFile get() = directory.resolve("possessions.json")
    private val filesDirectory get() = directory.resolve("fichiers")

    // Tout l'état vit en mémoire et n'est touché que par le thread de l'exécuteur.
    private var revision = 0L
    private val listings = LinkedHashMap<UUID, EmoteListing>()
    private var categories = listOf<EmoteCategory>()
    private var settings = EmoteShopSettings()
    private val owned = HashMap<UUID, MutableSet<UUID>>()

    private fun <T> submit(fallback: T, task: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync({
        try {
            task()
        } catch (e: Exception) {
            logger.error("[Émotes] Erreur du stockage local", e)
            fallback
        }
    }, executor)

    override fun start(): CompletableFuture<Boolean> = submit(false) {
        Files.createDirectories(filesDirectory)
        readCatalog()
        readOwned()
        true
    }

    override fun stop() {}

    override fun revision(): CompletableFuture<Long?> = submit(null) { revision }

    override fun loadCatalog(): CompletableFuture<CatalogSnapshot?> = submit(null) {
        CatalogSnapshot(listings.values.sortedWith(compareBy({ it.sortOrder }, { it.addedAtMs })), categories, settings, revision)
    }

    override fun readFile(sha256: String): CompletableFuture<ByteArray?> = submit(null) {
        val file = fileOf(sha256) ?: return@submit null
        if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    }

    override fun saveListing(listing: EmoteListing, file: ByteArray?, author: UUID?): CompletableFuture<Boolean> = submit(false) {
        if (file != null) {
            val target = fileOf(listing.fileSha256) ?: return@submit false
            if (!Files.exists(target)) writeAtomically(target, file)
        }
        listings[listing.id] = listing
        changed()
        true
    }

    override fun deleteListing(id: UUID): CompletableFuture<Boolean> = submit(false) {
        listings.remove(id)
        changed()
        true
    }

    override fun saveSortOrders(orders: Map<UUID, Int>): CompletableFuture<Boolean> = submit(false) {
        orders.forEach { (id, order) -> listings[id]?.let { listings[id] = it.copy(sortOrder = order) } }
        changed()
        true
    }

    override fun setPublished(ids: Collection<UUID>, published: Boolean): CompletableFuture<Boolean> = submit(false) {
        ids.forEach { id -> listings[id]?.let { listings[id] = it.copy(published = published) } }
        changed()
        true
    }

    override fun saveCategories(categories: List<EmoteCategory>, deletedId: String?): CompletableFuture<Boolean> = submit(false) {
        if (deletedId != null) {
            listings.replaceAll { _, listing -> if (listing.categoryId == deletedId) listing.copy(categoryId = null) else listing }
        }
        this.categories = categories.filter { it.id != deletedId }
        changed()
        true
    }

    override fun saveSettings(settings: EmoteShopSettings): CompletableFuture<Boolean> = submit(false) {
        this.settings = settings
        changed()
        true
    }

    override fun loadOwned(player: UUID): CompletableFuture<Set<UUID>?> = submit(null) {
        owned[player]?.toSet() ?: emptySet()
    }

    override fun loadOwned(players: Collection<UUID>): CompletableFuture<Map<UUID, Set<UUID>>?> = submit(null) {
        players.associateWith { owned[it]?.toSet() ?: emptySet() }
    }

    override fun grant(player: UUID, emote: UUID, source: String): CompletableFuture<Boolean> = submit(false) {
        if (owned.getOrPut(player) { HashSet() }.add(emote)) writeOwned()
        true
    }

    override fun revoke(player: UUID, emote: UUID): CompletableFuture<Boolean> = submit(false) {
        if (owned[player]?.remove(emote) == true) writeOwned()
        true
    }

    override fun recordPurchase(player: UUID, playerName: String, emote: UUID, price: Long): CompletableFuture<PurchaseOutcome> =
        submit(PurchaseOutcome.FAILED) {
            if (!owned.getOrPut(player) { HashSet() }.add(emote)) return@submit PurchaseOutcome.ALREADY_OWNED
            writeOwned()
            logger.info("[Émotes] Achat : {} ({}) a acheté {} pour {} cristaux", playerName, player, emote, price)
            PurchaseOutcome.RECORDED
        }

    // ─── Fichiers ────────────────────────────────────────────────────────────────

    private fun fileOf(sha256: String): Path? =
        if (SHA_PATTERN.matches(sha256)) filesDirectory.resolve("$sha256.emotecraft") else null

    private fun changed() {
        revision++
        // Les fichiers qui ne servent plus à aucune émote sont supprimés.
        val used = listings.values.mapTo(HashSet()) { it.fileSha256 }
        Files.list(filesDirectory).use { paths ->
            paths.filter { path -> path.fileName.toString().removeSuffix(".emotecraft") !in used }.forEach { Files.deleteIfExists(it) }
        }
        writeCatalog()
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(temp, bytes)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeCatalog() {
        val root = JsonObject()
        root.addProperty("revision", revision)
        root.add("emotes", JsonArray().apply { listings.values.forEach { add(toJson(it)) } })
        root.add("categories", JsonArray().apply {
            categories.forEach { category ->
                add(JsonObject().apply {
                    addProperty("id", category.id)
                    addProperty("nom", category.name)
                    addProperty("ordre", category.sortOrder)
                })
            }
        })
        root.add("reglages", JsonObject().apply {
            addProperty("promoPourcent", settings.promoPercent)
            addProperty("promoFin", settings.promoEndsAtMs)
            addProperty("joursNouveau", settings.newDays)
        })
        writeAtomically(catalogFile, gson.toJson(root).toByteArray())
    }

    private fun readCatalog() {
        if (!Files.exists(catalogFile)) return
        val root = JsonParser.parseString(Files.readString(catalogFile)).asJsonObject
        revision = root.get("revision")?.asLong ?: 0L
        listings.clear()
        root.getAsJsonArray("emotes")?.forEach { element ->
            runCatching { fromJson(element.asJsonObject) }
                .onSuccess { listings[it.id] = it }
                .onFailure { logger.warn("[Émotes] Émote illisible dans catalogue.json : {}", it.message) }
        }
        categories = root.getAsJsonArray("categories")?.mapNotNull { element ->
            runCatching {
                val json = element.asJsonObject
                EmoteCategory(json.get("id").asString, json.get("nom").asString, json.get("ordre")?.asInt ?: 0)
            }.getOrNull()
        } ?: emptyList()
        root.getAsJsonObject("reglages")?.let { json ->
            settings = EmoteShopSettings(
                promoPercent = json.get("promoPourcent")?.asInt ?: 0,
                promoEndsAtMs = json.get("promoFin")?.asLong ?: 0L,
                newDays = json.get("joursNouveau")?.asInt ?: EmoteShopSettings.DEFAULT_NEW_DAYS
            )
        }
    }

    private fun writeOwned() {
        val root = JsonObject()
        owned.forEach { (player, emotes) ->
            if (emotes.isNotEmpty()) root.add(player.toString(), JsonArray().apply { emotes.forEach { add(it.toString()) } })
        }
        writeAtomically(ownedFile, gson.toJson(root).toByteArray())
    }

    private fun readOwned() {
        owned.clear()
        if (!Files.exists(ownedFile)) return
        JsonParser.parseString(Files.readString(ownedFile)).asJsonObject.entrySet().forEach { (key, value) ->
            val player = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            owned[player] = value.asJsonArray.mapNotNullTo(HashSet()) { runCatching { UUID.fromString(it.asString) }.getOrNull() }
        }
    }

    private fun toJson(listing: EmoteListing) = JsonObject().apply {
        addProperty("id", listing.id.toString())
        addProperty("nom", listing.name)
        addProperty("description", listing.description)
        addProperty("auteur", listing.author)
        listing.categoryId?.let { addProperty("categorie", it) }
        addProperty("rarete", listing.rarity.key)
        addProperty("acces", listing.access.name)
        addProperty("prix", listing.price)
        addProperty("reduction", listing.discountPercent)
        addProperty("reductionFin", listing.discountEndsAtMs)
        addProperty("publiee", listing.published)
        addProperty("nouveauJusqua", listing.newUntilMs)
        addProperty("ordre", listing.sortOrder)
        addProperty("fichier", listing.fileSha256)
        addProperty("taille", listing.fileSize)
        addProperty("dureeTicks", listing.durationTicks)
        addProperty("boucle", listing.loops)
        addProperty("icone", listing.hasIcon)
        addProperty("ajouteeLe", listing.addedAtMs)
    }

    private fun fromJson(json: JsonObject) = EmoteListing(
        id = UUID.fromString(json.get("id").asString),
        name = json.get("nom").asString,
        description = json.get("description")?.asString ?: "",
        author = json.get("auteur")?.asString ?: "",
        categoryId = json.get("categorie")?.asString,
        rarity = EmoteRarity.parse(json.get("rarete")?.asString),
        access = EmoteAccess.parse(json.get("acces")?.asString),
        price = json.get("prix")?.asLong ?: 0L,
        discountPercent = json.get("reduction")?.asInt ?: 0,
        discountEndsAtMs = json.get("reductionFin")?.asLong ?: 0L,
        published = json.get("publiee")?.asBoolean ?: false,
        newUntilMs = json.get("nouveauJusqua")?.asLong ?: 0L,
        sortOrder = json.get("ordre")?.asInt ?: 0,
        fileSha256 = json.get("fichier").asString,
        fileSize = json.get("taille")?.asInt ?: 0,
        durationTicks = json.get("dureeTicks")?.asInt ?: 0,
        loops = json.get("boucle")?.asBoolean ?: false,
        hasIcon = json.get("icone")?.asBoolean ?: false,
        addedAtMs = json.get("ajouteeLe")?.asLong ?: 0L
    )

    companion object {
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
    }
}
