package fr.owme.cobblelegacy.emotes.client

import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.network.EmoteFileChunkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteFileRequestPayload
import io.github.kosmx.emotes.arch.network.client.ClientNetwork
import io.github.kosmx.emotes.main.EmoteHolder
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.Util
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Fichiers des émotes du catalogue côté client.
 *
 * Chaque fichier est gardé dans `cache/cobblelegacy-emotes/<sha256>.emotecraft` : il n'est téléchargé
 * qu'une fois, puis ajouté aux émotes d'Emotecraft (marquées « venues du serveur », retirées à la
 * déconnexion). Une émote ajoutée au catalogue arrive donc sans mise à jour du launcher.
 */
@Environment(EnvType.CLIENT)
object ClientEmoteFiles {
    private val logger = CobbleLegacyEmotes.logger
    private const val MAX_IN_FLIGHT = 4

    private val cacheDirectory: Path
        get() = Minecraft.getInstance().gameDirectory.toPath().resolve("cache").resolve("cobblelegacy-emotes")

    /** Émotes du catalogue ajoutées à Emotecraft : id → fichier. */
    private val registered = HashMap<UUID, String>()

    /** Ce qu'on attend : fichier → émotes qui l'utilisent. */
    private val wanted = HashMap<String, MutableSet<UUID>>()
    private val toRequest = ArrayDeque<String>()
    private val inFlight = HashSet<String>()
    private val loading = HashSet<String>()
    private val chunks = HashMap<String, Array<ByteArray?>>()

    /** Fichiers reçus pendant cette session (aperçu de l'éditeur sans repasser par le disque). */
    private val recent = LinkedHashMap<String, KeyframeAnimation>()

    fun isReady(id: UUID): Boolean = registered.containsKey(id)

    fun animationOf(id: UUID): KeyframeAnimation? = EmoteHolder.list[id]?.emote

    /** Sur le thread du client, à chaque réception du catalogue. */
    fun sync(listings: List<EmoteListing>) {
        val visible = listings.associateBy { it.id }
        // Émotes retirées du catalogue (ou dont le fichier a changé) : on les enlève d'Emotecraft.
        val iterator = registered.entries.iterator()
        while (iterator.hasNext()) {
            val (id, sha) = iterator.next()
            val listing = visible[id]
            if (listing == null || listing.fileSha256 != sha) {
                EmoteHolder.list[id]?.takeIf { it.fromInstance != null }?.let { EmoteHolder.list.remove(id) }
                iterator.remove()
            }
        }
        wanted.clear()
        listings.forEach { listing ->
            if (registered[listing.id] == listing.fileSha256) return@forEach
            wanted.getOrPut(listing.fileSha256) { HashSet() }.add(listing.id)
        }
        wanted.keys.forEach { sha -> if (sha !in inFlight && sha !in loading && sha !in toRequest) loadOrRequest(sha) }
        ClientEmoteCatalog.markChanged()
    }

    private fun loadOrRequest(sha: String) {
        val file = cacheDirectory.resolve("$sha.emotecraft")
        loading += sha
        CompletableFuture.supplyAsync({
            if (Files.isRegularFile(file)) {
                val bytes = Files.readAllBytes(file)
                if (EmoteFiles.sha256(bytes) == sha) EmoteFiles.parseBinary(bytes) else null
            } else null
        }, Util.backgroundExecutor()).whenComplete { parsed, error ->
            Minecraft.getInstance().execute {
                loading -= sha
                if (parsed != null && error == null) {
                    install(sha, parsed.animation)
                } else if (sha in wanted) {
                    toRequest += sha
                }
            }
        }
    }

    /** Envoie les demandes en attente, quelques-unes à la fois. */
    fun tick() {
        if (toRequest.isEmpty() || inFlight.size >= MAX_IN_FLIGHT) return
        if (!ClientPlayNetworking.canSend(EmoteFileRequestPayload.TYPE)) return
        while (inFlight.size < MAX_IN_FLIGHT && toRequest.isNotEmpty()) {
            val sha = toRequest.removeFirst()
            if (sha !in wanted) continue
            inFlight += sha
            ClientPlayNetworking.send(EmoteFileRequestPayload(sha))
        }
    }

    fun onChunk(payload: EmoteFileChunkPayload) {
        val sha = payload.sha256
        if (sha !in inFlight) return
        val parts = chunks.getOrPut(sha) { arrayOfNulls(payload.count) }
        if (parts.size != payload.count || payload.index !in parts.indices) {
            chunks.remove(sha)
            inFlight -= sha
            return
        }
        parts[payload.index] = payload.data
        if (parts.any { it == null }) return

        chunks.remove(sha)
        val data = ByteArray(parts.sumOf { it!!.size })
        var offset = 0
        parts.forEach { part ->
            part!!.copyInto(data, offset)
            offset += part.size
        }
        CompletableFuture.supplyAsync({
            if (EmoteFiles.sha256(data) != sha) return@supplyAsync null
            runCatching {
                Files.createDirectories(cacheDirectory)
                Files.write(cacheDirectory.resolve("$sha.emotecraft"), data)
            }.onFailure { logger.warn("[Émotes] Cache d'émote non écrit : {}", it.message) }
            EmoteFiles.parseBinary(data)
        }, Util.backgroundExecutor()).whenComplete { parsed, _ ->
            Minecraft.getInstance().execute {
                inFlight -= sha
                if (parsed == null) {
                    logger.warn("[Émotes] Fichier d'émote reçu illisible ou abîmé : {}", sha)
                    return@execute
                }
                install(sha, parsed.animation)
            }
        }
    }

    private fun install(sha: String, animation: KeyframeAnimation) {
        remember(sha, animation)
        val ids = wanted.remove(sha) ?: return
        ids.forEach { id ->
            val listing = ClientEmoteCatalog.byId[id] ?: return@forEach
            if (listing.fileSha256 != sha || animation.uuid != id) return@forEach
            val previous = EmoteHolder.list[id]
            if (previous != null) {
                // Le joueur l'a déjà dans son dossier d'émotes, identique : on garde la sienne.
                if (previous.fromInstance == null && previous.emote == animation) {
                    registered[id] = sha
                    return@forEach
                }
                EmoteHolder.list.remove(id)
            }
            val holder = EmoteHolder(animation)
            holder.fromInstance = ClientNetwork.INSTANCE
            EmoteHolder.list.add(holder)
            registered[id] = sha
        }
        ClientEmoteCatalog.markChanged()
    }

    private fun remember(sha: String, animation: KeyframeAnimation) {
        recent.remove(sha)
        recent[sha] = animation
        while (recent.size > 32) recent.remove(recent.keys.first())
    }

    fun recentAnimation(sha: String): KeyframeAnimation? = recent[sha]

    /** Nombre d'émotes du catalogue encore en téléchargement. */
    val pendingCount: Int get() = wanted.values.sumOf { it.size }

    fun clear() {
        registered.clear()
        wanted.clear()
        toRequest.clear()
        inFlight.clear()
        loading.clear()
        chunks.clear()
        recent.clear()
    }
}
