package fr.owme.cobblelegacy.emotes.client

import dev.kosmx.playerAnim.core.impl.event.EventResult
import fr.owme.cobblelegacy.emotes.client.editor.EmoteUploader
import fr.owme.cobblelegacy.emotes.client.preview.EmotePreviews
import fr.owme.cobblelegacy.emotes.client.ui.EmoteIcons
import fr.owme.cobblelegacy.emotes.network.EmoteBalancePayload
import fr.owme.cobblelegacy.emotes.network.EmoteCatalogPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteFileChunkPayload
import fr.owme.cobblelegacy.emotes.network.EmoteOwnedPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import fr.owme.cobblelegacy.emotes.EmoteFiles
import io.github.kosmx.emotes.api.events.client.ClientEmoteEvents
import io.github.kosmx.emotes.main.EmoteHolder
import io.github.kosmx.emotes.main.network.ClientEmotePlay
import java.util.function.Predicate
import java.util.function.UnaryOperator
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Player

@Environment(EnvType.CLIENT)
object CobbleLegacyEmotesClient : ClientModInitializer {

    override fun onInitializeClient() {
        // Autotest en jeu (`runSelftest`) : source set à part, absent du jar du mod.
        if (FabricLoader.getInstance().isDevelopmentEnvironment && System.getProperty("cobblelegacy.emotes.selftest") != null) {
            Class.forName("fr.owme.cobblelegacy.emotes.selftest.SelfTest").getMethod("start").invoke(null)
        }

        ClientPlayNetworking.registerGlobalReceiver(EmoteCatalogPayload.TYPE) { payload, context ->
            context.client().execute { ClientEmoteCatalog.apply(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EmoteOwnedPayload.TYPE) { payload, context ->
            context.client().execute { ClientEmoteCatalog.applyOwned(payload.owned) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EmoteFileChunkPayload.TYPE) { payload, context ->
            context.client().execute { ClientEmoteFiles.onChunk(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EmoteShopResultPayload.TYPE) { payload, context ->
            context.client().execute { EmoteClientEvents.dispatchShop(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EmoteEditorResultPayload.TYPE) { payload, context ->
            context.client().execute { EmoteClientEvents.dispatchEditor(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EmoteBalancePayload.TYPE) { payload, context ->
            context.client().execute { ClientEmoteCatalog.applyBalance(payload.balance) }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                ClientEmoteCatalog.clear()
                ClientEmoteFiles.clear()
                EmotePreviews.clear()
                EmoteUploader.clear()
                if (EmoteScreens.composeAvailable) EmoteIcons.clear()
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            ClientEmoteFiles.tick()
            EmotePreviews.tick()
            EmoteUploader.tick()
            stopEmoteInWater(client)
        }

        // Émotes réglées « en mouvement » dans l'éditeur (une course…) : marcher, courir ou voler ne
        // les arrête pas. S'accroupir les arrête toujours (Emotecraft, changement de posture).
        ClientEmotePlay.playableWhileMoving = Predicate { emote -> ClientEmoteCatalog.byId[emote.uuid]?.playableWhileMoving == true }

        // Émotes du catalogue : le réseau ne transporte qu'une version allégée, chacun joue sa copie
        // téléchargée (les grosses émotes dépassaient la taille d'un paquet d'Emotecraft).
        ClientEmotePlay.outgoingEmote = UnaryOperator { emote ->
            if (ClientEmoteCatalog.managed && ClientEmoteFiles.isReady(emote.uuid)) EmoteFiles.stubOf(emote) else emote
        }
        ClientEmotePlay.incomingEmote = UnaryOperator { emote ->
            if (ClientEmoteFiles.isReady(emote.uuid)) EmoteHolder.list[emote.uuid]?.emote ?: emote else emote
        }

        // Émote verrouillée (raccourci clavier, commande, ancienne roue…) : rien ne part au serveur.
        ClientEmoteEvents.LOCAL_EMOTE_REQUEST.register { emote, _ ->
            val mc = Minecraft.getInstance()
            val refusal = when {
                !EmoteLibrary.canPlay(emote.uuid) -> "cobblelegacy-emotes.locked.actionbar"
                mc.player?.let(::isSwimming) == true -> "cobblelegacy-emotes.water.actionbar"
                else -> null
            }
            if (refusal == null) {
                EventResult.PASS
            } else {
                mc.gui.setOverlayMessage(Component.translatable(refusal).withStyle(ChatFormatting.GOLD), false)
                EventResult.FAIL
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("emote")
                    .executes {
                        // La commande s'exécute depuis le chat, qui se ferme juste après : on attend un tick.
                        Minecraft.getInstance().tell { EmoteScreens.openCollection() }
                        1
                    }
                    .then(ClientCommandManager.literal("roue").executes {
                        Minecraft.getInstance().tell { EmoteScreens.openWheel() }
                        1
                    })
            )
        }
    }

    /** Dans l'eau sans toucher le fond (ou en nage rapide) : pas d'émote. */
    fun isSwimming(player: Player): Boolean = player.isSwimming || (player.isInWater && !player.onGround())

    /**
     * Pas d'émote à la nage. Emotecraft n'arrête que la nage rapide : nager doucement garde la posture
     * debout, et une émote jouable en mouvement continuerait.
     */
    private fun stopEmoteInWater(client: Minecraft) {
        val player = client.player ?: return
        if (player.isPlayingEmote && !player.`emotecraft$isForcedEmote`() && isSwimming(player)) {
            ClientEmotePlay.clientStopLocalEmote()
        }
    }
}
