package fr.owme.cobblelegacy.emotes.server

import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.server.storage.CatalogSnapshot
import fr.owme.cobblelegacy.emotes.server.storage.EmoteStorage
import fr.owme.cobblelegacy.emotes.server.storage.FileEmoteStorage
import fr.owme.cobblelegacy.emotes.server.storage.SqlEmoteStorage
import io.github.kosmx.emotes.server.serializer.UniversalEmoteSerializer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Le catalogue d'émotes du serveur, en mémoire.
 *
 * Chargé au démarrage, puis relu quand le compteur de révision de la base change (une modification
 * faite depuis un autre serveur arrive en 30 s au plus). Il décide aussi qui peut jouer quoi : le
 * relais d'Emotecraft lui demande l'autorisation pour chaque émote reçue (cf. [authorize]).
 */
object ServerEmoteCatalog {
    private val logger = CobbleLegacyEmotes.logger

    /** Relecture du compteur de révision d'une base partagée : toutes les 30 s. */
    private const val POLL_TICKS = 600

    var server: MinecraftServer? = null
        private set

    var storage: EmoteStorage? = null
        private set

    /** Le stockage a répondu : boutique et éditeur utilisables. */
    @Volatile
    var ready = false
        private set

    @Volatile
    var listings: Map<UUID, EmoteListing> = emptyMap()
        private set

    @Volatile
    var categories: List<EmoteCategory> = emptyList()
        private set

    @Volatile
    var settings = EmoteShopSettings()
        private set

    /** Animations du catalogue, lues depuis leur fichier (sans icône). */
    private val animations = ConcurrentHashMap<UUID, KeyframeAnimation>()
    private val animationFiles = ConcurrentHashMap<UUID, String>()

    /** Ce qui part sur le réseau pour chaque émote du catalogue (cf. [EmoteFiles.stubOf]). */
    private val stubs = ConcurrentHashMap<UUID, KeyframeAnimation>()

    /** Émotes livrées avec Emotecraft, gratuites tant que le catalogue ne les reprend pas. */
    @Volatile
    var builtins: Map<UUID, KeyframeAnimation> = emptyMap()
        private set

    private var revision = Long.MIN_VALUE
    private var polling = false
    private var ticks = 0

    /** Un catalogue publié existe : seules ses émotes (possédées ou gratuites) se jouent. */
    val managed: Boolean get() = ready && listings.values.any { it.published }

    // ─── Cycle de vie ────────────────────────────────────────────────────────────

    fun start(server: MinecraftServer) {
        this.server = server
        EmoteServerConfig.load()
        refreshBuiltins()
        val database = EmoteServerConfig.database
        val chosen = if (database.configured) SqlEmoteStorage(database) else FileEmoteStorage(EmoteServerConfig.localStoreDirectory)
        storage = chosen
        logger.info("[Émotes] Catalogue rangé dans : {}", chosen.label)
        chosen.start().thenCompose { ok ->
            if (ok) chosen.loadCatalog() else CompletableFuture.completedFuture(null)
        }.thenAccept { snapshot ->
            server.execute {
                if (snapshot == null) {
                    logger.error("[Émotes] Catalogue indisponible : boutique et éditeur fermés sur ce serveur")
                    return@execute
                }
                ready = true
                apply(snapshot)
                EmoteOwnership.loadOnline(server)
            }
        }
    }

    fun stop() {
        storage?.stop()
        storage = null
        server = null
        ready = false
        listings = emptyMap()
        categories = emptyList()
        settings = EmoteShopSettings()
        animations.clear()
        animationFiles.clear()
        stubs.clear()
        revision = Long.MIN_VALUE
        polling = false
    }

    fun tick(server: MinecraftServer) {
        val current = storage ?: return
        if (!ready || !current.shared || polling || ++ticks < POLL_TICKS) return
        ticks = 0
        polling = true
        current.revision().thenAccept { remote ->
            server.execute {
                if (remote != null && remote != revision) {
                    reload { polling = false }
                    EmoteOwnership.syncOnline(server)
                } else {
                    polling = false
                }
            }
        }
    }

    /**
     * Base partagée : relit le catalogue s'il a changé depuis un autre serveur, puis appelle [done] sur
     * le thread du serveur (tout de suite si la base n'est pas partagée).
     */
    fun refresh(done: () -> Unit) {
        val server = server ?: return
        val current = storage ?: return
        if (!ready || !current.shared) return done()
        current.revision().thenAccept { remote ->
            server.execute { if (remote != null && remote != revision) reload(done) else done() }
        }
    }

    /** Relit tout le catalogue (après une modification, ou quand un autre serveur en a fait une). */
    fun reload(done: (() -> Unit)? = null) {
        val server = server ?: return
        val current = storage ?: return
        current.loadCatalog().thenAccept { snapshot ->
            server.execute {
                if (snapshot != null) apply(snapshot)
                done?.invoke()
            }
        }
    }

    /** Sur le thread du serveur. */
    private fun apply(snapshot: CatalogSnapshot) {
        revision = snapshot.revision
        listings = snapshot.listings.associateBy { it.id }
        categories = snapshot.categories.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
        settings = snapshot.settings

        // Animations : on ne relit que les fichiers qui ont changé.
        animations.keys.retainAll(listings.keys)
        animationFiles.keys.retainAll(listings.keys)
        stubs.keys.retainAll(listings.keys)
        val toLoad = listings.values.filter { animationFiles[it.id] != it.fileSha256 }
        if (toLoad.isNotEmpty()) loadAnimations(toLoad)

        syncEmotecraftEmotes()
        EmoteNetworking.broadcastCatalog()
    }

    private fun loadAnimations(toLoad: List<EmoteListing>) {
        val server = server ?: return
        val current = storage ?: return
        toLoad.forEach { listing ->
            current.readFile(listing.fileSha256).thenAccept { bytes ->
                val parsed = bytes?.let { EmoteFiles.parseBinary(it) }
                server.execute {
                    if (parsed == null) {
                        logger.warn("[Émotes] Fichier de « {} » introuvable ou illisible ({})", listing.name, listing.fileSha256)
                        return@execute
                    }
                    if (listings[listing.id]?.fileSha256 != listing.fileSha256) return@execute
                    animations[listing.id] = EmoteFiles.withoutIcon(parsed.animation)
                    stubs[listing.id] = EmoteFiles.stubOf(parsed.animation)
                    animationFiles[listing.id] = listing.fileSha256
                    syncEmotecraftEmotes()
                }
            }
        }
    }

    /**
     * Les émotes du catalogue rejoignent les émotes « cachées » d'Emotecraft (chargées mais non
     * envoyées aux joueurs) : `/emotes play` les trouve par leur nom. Les clients reçoivent leurs
     * fichiers par la boutique, avec un cache.
     */
    fun syncEmotecraftEmotes() {
        val hidden = UniversalEmoteSerializer.hiddenServerEmotes
        hidden.removeIf { it.extraData[CATALOG_MARKER] == true }
        stubs.forEach { (id, stub) ->
            val listing = listings[id] ?: return@forEach
            val copy = stub.mutableCopy()
            copy.extraData[CATALOG_MARKER] = true
            copy.extraData["name"] = "\"" + listing.name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            hidden.add(copy.build())
        }
    }

    /** Les émotes intégrées à Emotecraft, connues des deux côtés sans rien télécharger. */
    fun refreshBuiltins() {
        builtins = UniversalEmoteSerializer.hiddenServerEmotes.values
            .filter { it.extraData["isBuiltin"] == true && it.extraData[CATALOG_MARKER] != true }
            .associateBy { it.uuid }
    }

    // ─── Visibilité ──────────────────────────────────────────────────────────────

    fun isAdmin(player: ServerPlayer): Boolean = player.hasPermissions(EmoteServerConfig.gameplay.niveauAdmin)

    /** Le catalogue que voit ce joueur : les brouillons ne sont montrés qu'aux administrateurs. */
    fun visibleTo(player: ServerPlayer): List<EmoteListing> {
        val admin = isAdmin(player)
        return listings.values.filter { admin || it.published }.sortedWith(compareBy({ it.sortOrder }, { it.addedAtMs }))
    }

    /** Ce joueur peut-il télécharger ce fichier ? */
    fun canDownload(player: ServerPlayer, sha256: String): Boolean {
        val admin = isAdmin(player)
        return listings.values.any { it.fileSha256 == sha256 && (admin || it.published) }
    }

    fun animationOf(id: UUID): KeyframeAnimation? = animations[id]

    fun findByName(name: String): EmoteListing? {
        val wanted = name.trim()
        runCatching { UUID.fromString(wanted) }.getOrNull()?.let { listings[it]?.let { found -> return found } }
        return listings.values.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    // ─── Autorisation ────────────────────────────────────────────────────────────

    /**
     * Appelé pour chaque émote qu'un joueur lance. Renvoie ce qui sera diffusé aux autres joueurs, ou
     * `null` pour la refuser. Pour une émote du catalogue, c'est sa version allégée : chaque client
     * joue sa copie téléchargée, un client modifié ne peut donc pas maquiller une émote qu'il possède.
     */
    fun authorize(emote: KeyframeAnimation, playerId: UUID): KeyframeAnimation? {
        if (!managed) return emote
        val config = EmoteServerConfig.gameplay
        val player = server?.playerList?.getPlayer(playerId)
        val admin = player != null && config.adminsJouentTout && isAdmin(player)
        val listing = listings[emote.uuid]
        if (listing == null) {
            builtins[emote.uuid]?.let { return it }
            return if (admin || config.autoriserEmotesHorsCatalogue) emote else null
        }
        if (!admin) {
            if (!listing.published) return null
            if (!EmoteOwnership.canUse(playerId, listing)) return null
        }
        return stubs[listing.id] ?: EmoteFiles.stubOf(emote)
    }

    /** Peut-il jouer cette émote du catalogue ? (commande `/emotes play`, aperçu de l'éditeur…) */
    fun canPlay(player: ServerPlayer, listing: EmoteListing): Boolean {
        if (EmoteServerConfig.gameplay.adminsJouentTout && isAdmin(player)) return true
        return listing.published && (listing.access == EmoteAccess.FREE || EmoteOwnership.canUse(player.uuid, listing))
    }

    /** Marque nos copies dans les émotes cachées d'Emotecraft. */
    private const val CATALOG_MARKER = "cobblelegacyCatalog"
}
