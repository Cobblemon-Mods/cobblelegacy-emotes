package fr.owme.cobblelegacy.emotes.client.editor

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.network.EmoteUploadChunkPayload
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explorateur de fichiers natif (tinyfd, fourni avec Minecraft), comme l'éditeur des capes. La boîte
 * de dialogue bloque le fil qui l'ouvre : elle tourne à part, le jeu continue de s'afficher.
 */
@Environment(EnvType.CLIENT)
object EmoteFilePicker {
    private val open = AtomicBoolean(false)

    /** Un ou plusieurs fichiers d'émotes (`.emotecraft`, `.json`). */
    fun pickEmotes(onPicked: (List<Path>) -> Unit) =
        pick("Ajouter des émotes", listOf("*.emotecraft", "*.json"), "Émotes Emotecraft (.emotecraft, .json)", multiple = true, onPicked)

    fun pickIcon(onPicked: (Path) -> Unit) =
        pick("Choisir l'icône de l'émote", listOf("*.png"), "Images PNG", multiple = false) { onPicked(it.first()) }

    private fun pick(title: String, patterns: List<String>, description: String, multiple: Boolean, onPicked: (List<Path>) -> Unit) {
        if (!open.compareAndSet(false, true)) return
        Thread({
            try {
                val chosen = MemoryStack.stackPush().use { stack ->
                    val filters = stack.mallocPointer(patterns.size)
                    patterns.forEach { filters.put(stack.UTF8(it)) }
                    filters.flip()
                    TinyFileDialogs.tinyfd_openFileDialog(title, defaultFolder(), filters, description, multiple)
                }
                if (chosen != null) {
                    val paths = chosen.split('|').filter { it.isNotBlank() }.map { Path.of(it) }
                    if (paths.isNotEmpty()) Minecraft.getInstance().execute { onPicked(paths) }
                }
            } catch (t: Throwable) {
                CobbleLegacyEmotes.logger.error("[Émotes] Explorateur de fichiers indisponible", t)
            } finally {
                open.set(false)
            }
        }, "CobbleLegacyEmotes-FilePicker").apply { isDaemon = true }.start()
    }

    private fun defaultFolder(): String {
        val downloads = Path.of(System.getProperty("user.home"), "Downloads")
        return if (Files.isDirectory(downloads)) downloads.toString() + java.io.File.separator else ""
    }
}

/**
 * Envoie les fichiers de l'éditeur au serveur, en morceaux étalés sur plusieurs ticks (la connexion
 * reste fluide). Plusieurs fichiers à la suite pour un import en lot. Thread client uniquement.
 */
@Environment(EnvType.CLIENT)
object EmoteUploader {
    /** ~600 Ko par tick. */
    private const val CHUNKS_PER_TICK = 20

    private class Upload(val sha256: String, val data: ByteArray, val onSent: () -> Unit) {
        val count = (data.size + EmoteUploadChunkPayload.CHUNK_SIZE - 1) / EmoteUploadChunkPayload.CHUNK_SIZE
        var next = 0
    }

    private val queue = ArrayDeque<Upload>()

    val busy: Boolean get() = queue.isNotEmpty()

    fun send(data: ByteArray, sha256: String, onSent: () -> Unit) {
        queue.add(Upload(sha256, data, onSent))
    }

    fun tick() {
        val upload = queue.peekFirst() ?: return
        if (!ClientPlayNetworking.canSend(EmoteUploadChunkPayload.TYPE)) {
            queue.clear()
            return
        }
        repeat(CHUNKS_PER_TICK) {
            if (upload.next >= upload.count) return@repeat
            val start = upload.next * EmoteUploadChunkPayload.CHUNK_SIZE
            val end = minOf(upload.data.size, start + EmoteUploadChunkPayload.CHUNK_SIZE)
            ClientPlayNetworking.send(EmoteUploadChunkPayload(upload.sha256, upload.next, upload.count, upload.data.copyOfRange(start, end)))
            upload.next++
        }
        if (upload.next >= upload.count) {
            queue.removeFirst()
            upload.onSent()
        }
    }

    fun clear() {
        queue.clear()
    }
}
