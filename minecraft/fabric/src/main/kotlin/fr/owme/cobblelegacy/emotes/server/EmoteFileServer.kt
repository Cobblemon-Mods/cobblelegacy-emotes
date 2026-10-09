package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.network.EmoteFileChunkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteUploadChunkPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.ArrayDeque
import java.util.UUID

/**
 * Transferts de fichiers `.emotecraft` : téléchargements des joueurs (morceaux de 256 Ko, au plus
 * 1 Mo par joueur et par tick) et envois de l'éditeur (morceaux de 30 Ko, vérifiés par SHA-256).
 */
object EmoteFileServer {
    private val logger = CobbleLegacyEmotes.logger

    private const val CACHE_BYTES = 64L * 1024 * 1024
    private const val CHUNKS_PER_TICK = 4
    private const val MAX_UPLOADS_PER_PLAYER = 4
    private const val UPLOAD_TTL_MS = 10 * 60_000L

    /** Derniers fichiers lus, par SHA-256 (LRU, 64 Mo). */
    private val cache = object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
        var bytes = 0L
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            if (bytes <= CACHE_BYTES) return false
            bytes -= eldest.value.size
            return true
        }
    }

    private class Outgoing(val sha256: String, val data: ByteArray) {
        val count = (data.size + EmoteFileChunkPayload.CHUNK_SIZE - 1) / EmoteFileChunkPayload.CHUNK_SIZE
        var next = 0
    }

    private val queues = HashMap<UUID, ArrayDeque<Outgoing>>()
    private val pending = HashMap<UUID, MutableSet<String>>()

    private class Upload(val count: Int) {
        val chunks = arrayOfNulls<ByteArray>(count)
        var received = 0
        var size = 0
        val startedAt = System.currentTimeMillis()
    }

    private val uploads = HashMap<UUID, HashMap<String, Upload>>()

    /** Fichiers reçus et vérifiés, en attente de l'enregistrement qui les référence. */
    private class Received(val data: ByteArray, val at: Long)

    private val received = HashMap<UUID, HashMap<String, Received>>()

    // ─── Téléchargements ─────────────────────────────────────────────────────────

    fun onRequest(player: ServerPlayer, sha256: String) {
        if (!ServerEmoteCatalog.canDownload(player, sha256)) return
        val uuid = player.uuid
        val requested = pending.getOrPut(uuid) { HashSet() }
        if (!requested.add(sha256)) return
        val cached = synchronized(cache) { cache[sha256] }
        if (cached != null) {
            enqueue(uuid, sha256, cached)
            return
        }
        val server = player.server
        val storage = ServerEmoteCatalog.storage ?: return
        storage.readFile(sha256).thenAccept { data ->
            server.execute {
                if (data == null || EmoteFiles.sha256(data) != sha256) {
                    logger.warn("[Émotes] Fichier {} introuvable ou abîmé dans le stockage", sha256)
                    pending[uuid]?.remove(sha256)
                    return@execute
                }
                synchronized(cache) {
                    if (cache.put(sha256, data) == null) cache.bytes += data.size
                }
                enqueue(uuid, sha256, data)
            }
        }
    }

    private fun enqueue(player: UUID, sha256: String, data: ByteArray) {
        queues.getOrPut(player) { ArrayDeque() }.add(Outgoing(sha256, data))
    }

    fun tick(server: MinecraftServer) {
        if (queues.isNotEmpty()) {
            val iterator = queues.entries.iterator()
            while (iterator.hasNext()) {
                val (uuid, queue) = iterator.next()
                val player = server.playerList.getPlayer(uuid)
                if (player == null) {
                    iterator.remove()
                    continue
                }
                var budget = CHUNKS_PER_TICK
                while (budget > 0 && queue.isNotEmpty()) {
                    val out = queue.first()
                    val from = out.next * EmoteFileChunkPayload.CHUNK_SIZE
                    val to = minOf(out.data.size, from + EmoteFileChunkPayload.CHUNK_SIZE)
                    if (ServerPlayNetworking.canSend(player, EmoteFileChunkPayload.TYPE)) {
                        ServerPlayNetworking.send(player, EmoteFileChunkPayload(out.sha256, out.next, out.count, out.data.copyOfRange(from, to)))
                    }
                    out.next++
                    budget--
                    if (out.next >= out.count) {
                        queue.removeFirst()
                        pending[uuid]?.remove(out.sha256)
                    }
                }
                if (queue.isEmpty()) iterator.remove()
            }
        }
        if (server.tickCount % 200 == 0) expireUploads()
    }

    // ─── Envois de l'éditeur ─────────────────────────────────────────────────────

    /** Seuls les administrateurs envoient des fichiers (vérifié par l'appelant). */
    fun onUploadChunk(player: ServerPlayer, payload: EmoteUploadChunkPayload) {
        val uuid = player.uuid
        if (!SHA_PATTERN.matches(payload.sha256)) return
        val maxChunks = (EmoteFiles.MAX_FILE_SIZE + EmoteUploadChunkPayload.CHUNK_SIZE - 1) / EmoteUploadChunkPayload.CHUNK_SIZE
        if (payload.count !in 1..maxChunks || payload.index !in 0 until payload.count) return
        val mine = uploads.getOrPut(uuid) { HashMap() }
        val upload = mine[payload.sha256] ?: run {
            if (mine.size >= MAX_UPLOADS_PER_PLAYER) return
            Upload(payload.count).also { mine[payload.sha256] = it }
        }
        if (upload.count != payload.count || upload.chunks[payload.index] != null) return
        upload.size += payload.data.size
        if (upload.size > EmoteFiles.MAX_FILE_SIZE) {
            mine.remove(payload.sha256)
            return
        }
        upload.chunks[payload.index] = payload.data
        upload.received++
        if (upload.received < upload.count) return

        mine.remove(payload.sha256)
        val data = ByteArray(upload.size)
        var offset = 0
        upload.chunks.forEach { chunk ->
            chunk!!.copyInto(data, offset)
            offset += chunk.size
        }
        if (EmoteFiles.sha256(data) != payload.sha256) {
            logger.warn("[Émotes] Fichier envoyé par {} abîmé (empreinte différente)", player.gameProfile.name)
            return
        }
        received.getOrPut(uuid) { HashMap() }[payload.sha256] = Received(data, System.currentTimeMillis())
    }

    /** Fichier envoyé par ce joueur (retiré de l'attente), ou `null` s'il n'est pas arrivé. */
    fun takeUpload(player: UUID, sha256: String): ByteArray? = received[player]?.remove(sha256)?.data

    private fun expireUploads() {
        val now = System.currentTimeMillis()
        uploads.values.forEach { mine -> mine.values.removeIf { now - it.startedAt > UPLOAD_TTL_MS } }
        received.values.forEach { mine -> mine.values.removeIf { now - it.at > UPLOAD_TTL_MS } }
        uploads.values.removeIf { it.isEmpty() }
        received.values.removeIf { it.isEmpty() }
    }

    fun forget(player: UUID) {
        queues.remove(player)
        pending.remove(player)
        uploads.remove(player)
        received.remove(player)
    }

    fun clear() {
        queues.clear()
        pending.clear()
        uploads.clear()
        received.clear()
        synchronized(cache) {
            cache.clear()
            cache.bytes = 0
        }
    }

    private val SHA_PATTERN = Regex("[0-9a-f]{64}")
}
