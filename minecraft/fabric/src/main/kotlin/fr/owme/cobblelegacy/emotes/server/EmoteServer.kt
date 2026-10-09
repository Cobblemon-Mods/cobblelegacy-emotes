package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.network.EmoteCatalogPayload
import io.github.kosmx.emotes.api.events.server.ServerEmoteEvents
import io.github.kosmx.emotes.server.serializer.UniversalEmoteSerializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents

/** Branche le catalogue d'émotes sur le cycle de vie du serveur (dédié ou intégré). */
object EmoteServer {

    fun register() {
        ServerLifecycleEvents.SERVER_STARTED.register { server -> ServerEmoteCatalog.start(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            ServerEmoteCatalog.stop()
            EmoteAdmin.clear()
            EmoteOwnership.clear()
            EmoteFileServer.clear()
            EmoteShopServer.clear()
        }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            ServerEmoteCatalog.tick(server)
            EmoteFileServer.tick(server)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            EmoteNetworking.sendCatalog(player)
            EmoteOwnership.load(player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val uuid = handler.player.uuid
            EmoteOwnership.forget(uuid)
            EmoteFileServer.forget(uuid)
            EmoteShopServer.forget(uuid)
        }
        // Filet de sécurité : un client qui annonce ses canaux en retard reçoit quand même tout.
        S2CPlayChannelEvents.REGISTER.register { handler, _, _, channels ->
            if (EmoteCatalogPayload.TYPE.id() in channels) {
                EmoteNetworking.sendCatalog(handler.player)
                if (EmoteOwnership.isLoaded(handler.player.uuid)) EmoteNetworking.sendOwned(handler.player)
            }
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> EmoteCommands.register(dispatcher) }

        // Chaque émote lancée par un joueur passe par le catalogue avant d'être diffusée.
        ServerEmoteEvents.EMOTE_AUTHORIZE.register { emote, player -> ServerEmoteCatalog.authorize(emote, player) }

        // `/emotes reload` vide les émotes cachées d'Emotecraft : on y remet celles du catalogue.
        UniversalEmoteSerializer.RELOAD_LISTENERS.add(Runnable {
            ServerEmoteCatalog.refreshBuiltins()
            ServerEmoteCatalog.syncEmotecraftEmotes()
        })
    }
}
