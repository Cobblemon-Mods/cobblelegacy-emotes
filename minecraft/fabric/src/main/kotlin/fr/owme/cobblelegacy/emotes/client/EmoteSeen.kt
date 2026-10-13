package fr.owme.cobblelegacy.emotes.client

import androidx.compose.runtime.mutableStateMapOf
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Le badge NEW de la roue et de la collection : une émote obtenue que le joueur n'a encore ni survolée
 * ni jouée. Retenu par compte Minecraft dans `config/cobblelegacy-emotes/vues.json`. La boutique, elle,
 * montre les nouveautés du catalogue (badge NEW réglé par l'équipe).
 */
@Environment(EnvType.CLIENT)
object EmoteSeen {
    private val logger = CobbleLegacyEmotes.logger

    private val file: Path
        get() = FabricLoader.getInstance().configDir.resolve("cobblelegacy-emotes").resolve("vues.json")

    /** Émotes déjà vues par le joueur courant ; état Compose : un badge disparaît aussitôt. */
    private val seen = mutableStateMapOf<UUID, Unit>()
    private var owner: UUID? = null

    /** NEW dans la roue et la collection : émote du catalogue obtenue, jamais survolée ni jouée. */
    fun isNew(entry: EmoteEntry): Boolean =
        owner != null && entry.unlocked && entry.source == EmoteSource.CATALOG && entry.id !in seen

    fun markSeen(id: UUID) {
        if (owner == null || id in seen) return
        if (EmoteLibrary.entry(id)?.source != EmoteSource.CATALOG) return
        seen[id] = Unit
        save()
    }

    /**
     * Charge les vues du joueur dès que le catalogue et ses possessions sont arrivés (thread du client).
     * La toute première fois, ce qu'il a déjà compte comme vu, sauf les nouveautés du catalogue : rien
     * ne s'allume d'un coup.
     */
    fun load() {
        val player = Minecraft.getInstance().user.profileId ?: return
        if (owner == player) return
        if (!ClientEmoteCatalog.received || !ClientEmoteCatalog.ownedReceived) return
        owner = player
        seen.clear()
        val stored = read()?.getAsJsonArray(player.toString())
        if (stored != null) {
            stored.forEach { element -> runCatching { UUID.fromString(element.asString) }.getOrNull()?.let { seen[it] = Unit } }
        } else {
            EmoteLibrary.entries()
                .filter { it.unlocked && it.source == EmoteSource.CATALOG && !it.isNew }
                .forEach { seen[it.id] = Unit }
            save()
        }
    }

    private fun read(): JsonObject? = try {
        if (Files.isRegularFile(file)) JsonParser.parseString(Files.readString(file)).asJsonObject else null
    } catch (e: Exception) {
        logger.warn("[Émotes] vues.json illisible, il sera recréé : {}", e.message)
        null
    }

    private fun save() {
        val player = owner ?: return
        try {
            val root = read() ?: JsonObject()
            root.add(player.toString(), JsonArray().apply { seen.keys.forEach { add(it.toString()) } })
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("vues.json.tmp")
            Files.writeString(temp, root.toString())
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.warn("[Émotes] Impossible d'enregistrer vues.json : {}", e.message)
        }
    }

    /** Déconnexion : relu à la prochaine connexion (autre compte possible). */
    fun clear() {
        owner = null
        seen.clear()
    }
}
