package fr.owme.cobblelegacy.emotes.client

import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import io.github.kosmx.emotes.main.EmoteHolder
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import java.util.UUID

/** D'où vient une émote affichée dans les menus. */
enum class EmoteSource {
    /** Catalogue du serveur (boutique, gratuites, exclusives). */
    CATALOG,

    /** Livrée avec Emotecraft, gratuite pour tous. */
    BUILTIN,

    /** Dossier `emotes` du joueur. */
    LOCAL
}

/** Une émote telle que la roue, la collection et la boutique la montrent. */
data class EmoteEntry(
    val id: UUID,
    val name: String,
    val description: String,
    val author: String,
    val rarity: EmoteRarity,
    val categoryId: String?,
    val source: EmoteSource,
    val listing: EmoteListing?,
    /** Jouable maintenant (possédée, gratuite, ou serveur sans catalogue). */
    val unlocked: Boolean,
    val isNew: Boolean,
    /** L'animation est arrivée (fichier téléchargé) : on peut la jouer et la prévisualiser. */
    val ready: Boolean,
    val durationTicks: Int,
    val loops: Boolean
) {
    val animation: KeyframeAnimation? get() = EmoteHolder.list[id]?.emote
}

/**
 * Réunit le catalogue du serveur et les émotes connues d'Emotecraft.
 *
 * Sur un serveur CobbleLegacy avec un catalogue publié, on ne montre que ses émotes (possédées ou
 * verrouillées) et celles de base ; sans catalogue (solo, autre serveur), toutes les émotes du joueur.
 */
@Environment(EnvType.CLIENT)
object EmoteLibrary {
    private var cachedRevision = -1
    private var cachedListSize = -1
    private var cachedEntries: List<EmoteEntry> = emptyList()
    private var cachedById: Map<UUID, EmoteEntry> = emptyMap()

    fun entries(): List<EmoteEntry> {
        refresh()
        return cachedEntries
    }

    fun entry(id: UUID): EmoteEntry? {
        refresh()
        return cachedById[id]
    }

    /** Ce joueur peut-il lancer cette émote sur ce serveur ? */
    fun canPlay(id: UUID): Boolean {
        val catalog = ClientEmoteCatalog
        if (!catalog.managed) return true
        val listing = catalog.byId[id]
        if (listing != null) return unlocked(listing)
        val holder = EmoteHolder.list[id] ?: return catalog.allowsLocal || catalog.playsAll
        if (isBuiltin(holder)) return true
        return catalog.allowsLocal || catalog.playsAll
    }

    private fun unlocked(listing: EmoteListing): Boolean {
        val catalog = ClientEmoteCatalog
        if (catalog.playsAll) return true
        if (!listing.published) return false
        return listing.access == EmoteAccess.FREE || listing.id in catalog.owned
    }

    private fun isBuiltin(holder: EmoteHolder): Boolean = holder.emote.extraData["isBuiltin"] == true

    private fun refresh() {
        val revision = ClientEmoteCatalog.revision
        val listSize = EmoteHolder.list.size
        if (revision == cachedRevision && listSize == cachedListSize) return
        cachedRevision = revision
        cachedListSize = listSize

        val catalog = ClientEmoteCatalog
        val now = System.currentTimeMillis()
        val result = ArrayList<EmoteEntry>()
        val seen = HashSet<UUID>()

        catalog.listings.forEach { listing ->
            seen += listing.id
            result += EmoteEntry(
                id = listing.id,
                name = EmoteFiles.plain(listing.name),
                description = EmoteFiles.plain(listing.description),
                author = EmoteFiles.plain(listing.author),
                rarity = listing.rarity,
                categoryId = listing.categoryId,
                source = EmoteSource.CATALOG,
                listing = listing,
                unlocked = if (catalog.managed) unlocked(listing) else true,
                isNew = listing.isNew(now),
                ready = ClientEmoteFiles.isReady(listing.id) || EmoteHolder.list.containsKey(listing.id),
                durationTicks = listing.durationTicks,
                loops = listing.loops
            )
        }

        val locals = ArrayList<EmoteEntry>()
        EmoteHolder.list.values.toList().forEach { holder ->
            val id = holder.uuid
            if (id in seen) return@forEach
            if (holder.fromInstance != null) return@forEach // émote d'un serveur hors catalogue
            val builtin = isBuiltin(holder)
            if (catalog.managed && !builtin && !(catalog.allowsLocal || catalog.playsAll)) return@forEach
            seen += id
            val animation = holder.emote
            locals += EmoteEntry(
                id = id,
                name = EmoteFiles.plain(holder.name.string).ifBlank { "Émote" },
                description = EmoteFiles.plain(holder.description.string),
                author = EmoteFiles.plain(holder.author.string),
                rarity = EmoteRarity.COMMUN,
                categoryId = null,
                source = if (builtin) EmoteSource.BUILTIN else EmoteSource.LOCAL,
                listing = null,
                unlocked = true,
                isNew = false,
                ready = true,
                durationTicks = if (animation.isInfinite) animation.endTick else animation.stopTick,
                loops = animation.isInfinite
            )
        }
        locals.sortBy { it.name.lowercase() }
        result += locals

        cachedEntries = result
        cachedById = result.associateBy { it.id }
    }
}
