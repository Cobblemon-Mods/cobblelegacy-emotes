package fr.owme.cobblelegacy.emotes

import fr.owme.cobblelegacy.emotes.server.EmoteNetworking
import fr.owme.cobblelegacy.emotes.server.EmoteServer
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.ResourceLocation
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * CobbleLegacy Émotes : catalogue d'émotes du serveur, boutique (onglet du Pokématos), éditeur
 * d'administration, roue et collection en Compose. Emotecraft reste le moteur : animation des joueurs
 * et relais des émotes entre joueurs.
 *
 * Aucun contenu de registre : le mod côté client peut manquer sans empêcher la connexion.
 */
object CobbleLegacyEmotes : ModInitializer {
    const val NAMESPACE = "cobblelegacy-emotes"

    val logger: Logger = LoggerFactory.getLogger("CobbleLegacyEmotes")

    fun id(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(NAMESPACE, path)

    override fun onInitialize() {
        EmoteNetworking.registerPayloads()
        EmoteNetworking.registerServerHandlers()
        EmoteServer.register()
    }
}
