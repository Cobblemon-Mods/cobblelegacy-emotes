package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Les émotes possédées par les joueurs connectés, chargées à la connexion.
 *
 * Lu depuis le thread réseau (autorisation des émotes) comme depuis celui du serveur : les ensembles
 * sont remplacés, jamais modifiés en place.
 */
object EmoteOwnership {
    private val logger = CobbleLegacyEmotes.logger

    private val owned = ConcurrentHashMap<UUID, Set<UUID>>()

    /** Augmenté à chaque modification locale : une lecture de la base commencée avant est ignorée. */
    private val versions = ConcurrentHashMap<UUID, Int>()

    /** Actions arrivées pendant le chargement d'un joueur (commande `give` à la connexion, achat…). */
    private val waiting = ConcurrentHashMap<UUID, MutableList<() -> Unit>>()

    fun isLoaded(player: UUID): Boolean = owned.containsKey(player)

    fun ownedBy(player: UUID): Set<UUID> = owned[player] ?: emptySet()

    fun canUse(player: UUID, listing: EmoteListing): Boolean =
        listing.access == EmoteAccess.FREE || ownedBy(player).contains(listing.id)

    fun load(player: ServerPlayer) {
        val server = player.server
        val storage = ServerEmoteCatalog.storage ?: return
        if (!ServerEmoteCatalog.ready) return
        val uuid = player.uuid
        val version = versions[uuid] ?: 0
        storage.loadOwned(uuid).thenAccept { set ->
            server.execute {
                if (server.playerList.getPlayer(uuid) == null) return@execute
                if (set == null) {
                    logger.warn("[Émotes] Émotes possédées de {} illisibles : seules les gratuites seront jouables", player.gameProfile.name)
                    owned[uuid] = emptySet()
                } else if ((versions[uuid] ?: 0) == version) {
                    owned[uuid] = set
                } else {
                    owned[uuid] = set + ownedBy(uuid)
                }
                EmoteNetworking.sendOwned(player)
                waiting.remove(uuid)?.forEach { it() }
            }
        }
    }

    fun loadOnline(server: MinecraftServer) {
        server.playerList.players.forEach { load(it) }
    }

    /** Relit les émotes des joueurs connectés : achats ou dons faits depuis un autre serveur. */
    fun syncOnline(server: MinecraftServer) {
        val storage = ServerEmoteCatalog.storage ?: return
        val players = server.playerList.players.map { it.uuid }
        if (players.isEmpty()) return
        val started = players.associateWith { versions[it] ?: 0 }
        storage.loadOwned(players).thenAccept { result ->
            server.execute {
                result?.forEach { (uuid, set) ->
                    if ((versions[uuid] ?: 0) != started[uuid]) return@forEach
                    val before = owned[uuid] ?: return@forEach
                    if (set != before) {
                        owned[uuid] = set
                        server.playerList.getPlayer(uuid)?.let { EmoteNetworking.sendOwned(it) }
                    }
                }
            }
        }
    }

    /** Exécute [action] une fois les émotes du joueur chargées (tout de suite si c'est fait). */
    fun whenLoaded(player: UUID, action: () -> Unit) {
        if (isLoaded(player)) action() else waiting.getOrPut(player) { mutableListOf() }.add(action)
    }

    /** Ajoute en mémoire et prévient le joueur (déjà enregistré en base par l'appelant). */
    fun addLocal(server: MinecraftServer, player: UUID, emote: UUID) {
        versions.merge(player, 1, Int::plus)
        owned.compute(player) { _, current -> (current ?: emptySet()) + emote }
        server.playerList.getPlayer(player)?.let { EmoteNetworking.sendOwned(it) }
    }

    fun removeLocal(server: MinecraftServer, player: UUID, emote: UUID) {
        versions.merge(player, 1, Int::plus)
        owned.computeIfPresent(player) { _, current -> current - emote }
        server.playerList.getPlayer(player)?.let { EmoteNetworking.sendOwned(it) }
    }

    fun forget(player: UUID) {
        owned.remove(player)
        versions.remove(player)
        waiting.remove(player)
    }

    fun clear() {
        owned.clear()
        versions.clear()
        waiting.clear()
    }
}
