package fr.owme.cobblelegacy.emotes.client.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import fr.owme.cobblelegacy.emotes.EmoteFiles
import io.github.kosmx.emotes.main.EmoteHolder
import net.minecraft.Util
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Image
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Icônes des émotes (PNG rangé dans le fichier `.emotecraft`), décodées en images Compose.
 *
 * Le décodage se fait hors du thread de rendu ; l'image arrive dans un état Compose, les menus se
 * redessinent tout seuls. Une émote sans icône garde son pictogramme.
 */
object EmoteIcons {
    private val images = mutableStateMapOf<UUID, ImageBitmap>()
    private val attempted = HashSet<UUID>()

    fun get(id: UUID): ImageBitmap? {
        images[id]?.let { return it }
        if (id in attempted) return null
        val holder = EmoteHolder.list[id] ?: return null // pas encore téléchargée : on réessaiera
        attempted += id
        val bytes = EmoteFiles.iconBytes(holder.emote) ?: return null
        CompletableFuture.supplyAsync({
            runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }, Util.backgroundExecutor()).thenAccept { bitmap ->
            if (bitmap != null) Minecraft.getInstance().execute { images[id] = bitmap }
        }
        return null
    }

    /** Pour l'éditeur : icône d'un fichier pas encore au catalogue. */
    fun decode(bytes: ByteArray): ImageBitmap? = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    fun invalidate(id: UUID) {
        images.remove(id)
        attempted.remove(id)
    }

    fun clear() {
        images.clear()
        attempted.clear()
    }
}
