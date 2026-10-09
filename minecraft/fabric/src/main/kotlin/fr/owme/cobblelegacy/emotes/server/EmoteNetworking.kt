package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.network.EmoteBalancePayload
import fr.owme.cobblelegacy.emotes.network.EmoteBalanceRequestPayload
import fr.owme.cobblelegacy.emotes.network.EmoteBuyPayload
import fr.owme.cobblelegacy.emotes.network.EmoteCatalogPayload
import fr.owme.cobblelegacy.emotes.network.EmoteCategoryEditPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteFileChunkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteFileRequestPayload
import fr.owme.cobblelegacy.emotes.network.EmoteOwnedPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import fr.owme.cobblelegacy.emotes.network.EmoteUploadChunkPayload
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Paquets du catalogue d'émotes. Rien n'est envoyé à un client qui n'a pas le mod : chaque envoi
 * vérifie d'abord que le joueur écoute ce canal.
 */
object EmoteNetworking {

    fun registerPayloads() {
        val s2c = PayloadTypeRegistry.playS2C()
        s2c.register(EmoteCatalogPayload.TYPE, EmoteCatalogPayload.CODEC)
        s2c.register(EmoteOwnedPayload.TYPE, EmoteOwnedPayload.CODEC)
        s2c.register(EmoteFileChunkPayload.TYPE, EmoteFileChunkPayload.CODEC)
        s2c.register(EmoteShopResultPayload.TYPE, EmoteShopResultPayload.CODEC)
        s2c.register(EmoteEditorResultPayload.TYPE, EmoteEditorResultPayload.CODEC)
        s2c.register(EmoteBalancePayload.TYPE, EmoteBalancePayload.CODEC)

        val c2s = PayloadTypeRegistry.playC2S()
        c2s.register(EmoteBalanceRequestPayload.TYPE, EmoteBalanceRequestPayload.CODEC)
        c2s.register(EmoteFileRequestPayload.TYPE, EmoteFileRequestPayload.CODEC)
        c2s.register(EmoteBuyPayload.TYPE, EmoteBuyPayload.CODEC)
        c2s.register(EmoteUploadChunkPayload.TYPE, EmoteUploadChunkPayload.CODEC)
        c2s.register(EmoteEditorSavePayload.TYPE, EmoteEditorSavePayload.CODEC)
        c2s.register(EmoteEditorActionPayload.TYPE, EmoteEditorActionPayload.CODEC)
        c2s.register(EmoteCategoryEditPayload.TYPE, EmoteCategoryEditPayload.CODEC)
        c2s.register(EmoteShopSettingsPayload.TYPE, EmoteShopSettingsPayload.CODEC)
    }

    fun registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(EmoteBalanceRequestPayload.TYPE) { _, context ->
            val player = context.player()
            context.server().execute {
                if (ServerPlayNetworking.canSend(player, EmoteBalancePayload.TYPE)) {
                    ServerPlayNetworking.send(player, EmoteBalancePayload(EmoteEconomy.balance(player.uuid)))
                }
            }
        }
        ServerPlayNetworking.registerGlobalReceiver(EmoteFileRequestPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute { EmoteFileServer.onRequest(player, payload.sha256) }
        }
        ServerPlayNetworking.registerGlobalReceiver(EmoteBuyPayload.TYPE) { payload, context ->
            val player = context.player()
            context.server().execute { EmoteShopServer.onBuy(player, payload) }
        }
        admin(EmoteUploadChunkPayload.TYPE) { player, payload -> EmoteFileServer.onUploadChunk(player, payload) }
        admin(EmoteEditorSavePayload.TYPE) { player, payload ->
            EmoteAdmin.save(player.server, player.uuid, payload) { error ->
                replyEditor(player, error, if (payload.creating) "« ${payload.name.trim()} » ajoutée au catalogue." else "« ${payload.name.trim()} » enregistrée.", payload.id)
            }
        }
        admin(EmoteEditorActionPayload.TYPE) { player, payload ->
            val name = ServerEmoteCatalog.listings[payload.id]?.name ?: "Émote"
            EmoteAdmin.action(player.server, payload) { error ->
                val message = when (payload.action) {
                    EmoteEditorActionPayload.Action.DELETE -> "« $name » supprimée du catalogue."
                    EmoteEditorActionPayload.Action.PUBLISH -> "« $name » est publiée."
                    EmoteEditorActionPayload.Action.UNPUBLISH -> "« $name » repasse en brouillon."
                    else -> "Ordre enregistré."
                }
                replyEditor(player, error, message, payload.id)
            }
        }
        admin(EmoteCategoryEditPayload.TYPE) { player, payload ->
            EmoteAdmin.category(player.server, payload) { error -> replyEditor(player, error, "Catégories enregistrées.", null) }
        }
        admin(EmoteShopSettingsPayload.TYPE) { player, payload ->
            EmoteAdmin.settings(player.server, payload) { error -> replyEditor(player, error, "Réglages de la boutique enregistrés.", null) }
        }
    }

    /** Paquet réservé aux administrateurs : ignoré sans bruit pour les autres. */
    private fun <T : CustomPacketPayload> admin(type: CustomPacketPayload.Type<T>, handler: (ServerPlayer, T) -> Unit) {
        ServerPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            val player = context.player()
            context.server().execute {
                if (ServerEmoteCatalog.isAdmin(player)) handler(player, payload)
            }
        }
    }

    private fun replyEditor(player: ServerPlayer, error: String?, success: String, id: UUID?) {
        if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, EmoteEditorResultPayload.TYPE)) return
        ServerPlayNetworking.send(player, EmoteEditorResultPayload(error == null, error ?: success, id))
    }

    fun sendCatalog(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, EmoteCatalogPayload.TYPE)) return
        val ready = ServerEmoteCatalog.ready
        val admin = ServerEmoteCatalog.isAdmin(player)
        val config = EmoteServerConfig.gameplay
        ServerPlayNetworking.send(
            player,
            EmoteCatalogPayload(
                listings = if (ready) ServerEmoteCatalog.visibleTo(player) else emptyList(),
                categories = ServerEmoteCatalog.categories,
                settings = ServerEmoteCatalog.settings,
                managed = ServerEmoteCatalog.managed,
                editor = ready && admin,
                shopOpen = ready && EmoteEconomy.available,
                playsAll = admin && config.adminsJouentTout,
                allowsLocal = config.autoriserEmotesHorsCatalogue
            )
        )
    }

    fun sendOwned(player: ServerPlayer) {
        if (!ServerPlayNetworking.canSend(player, EmoteOwnedPayload.TYPE)) return
        ServerPlayNetworking.send(player, EmoteOwnedPayload(EmoteOwnership.ownedBy(player.uuid)))
    }

    fun broadcastCatalog() {
        val server = ServerEmoteCatalog.server ?: return
        server.playerList.players.forEach { sendCatalog(it) }
    }
}
