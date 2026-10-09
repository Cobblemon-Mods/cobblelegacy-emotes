package fr.owme.cobblelegacy.emotes.client.preview

import com.mojang.authlib.GameProfile
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.player.Player

/**
 * Mannequin des aperçus : un joueur jamais ajouté au monde, avec le profil du joueur local (même UUID,
 * donc même skin), qui n'est jamais « tické » par le jeu : seule son animation avance (cf. [EmotePreview]).
 *
 * Pas de sous-classe : Emotecraft ajoute son interface d'émotes à AbstractClientPlayer à la compilation
 * (méthodes fournies par mixin), une sous-classe Kotlin ne pourrait pas être instanciée.
 */
@Environment(EnvType.CLIENT)
object EmotePreviewMannequin {

    fun create(level: ClientLevel, profile: GameProfile): RemotePlayer {
        val mannequin = RemotePlayer(level, profile)
        val local = Minecraft.getInstance().player
        // Très bas sous la carte : les éventuelles notes de musique d'une émote ne s'entendent pas.
        mannequin.setPos(local?.x ?: 0.0, -4096.0, local?.z ?: 0.0)
        copySkinLayers(mannequin)
        return mannequin
    }

    /** Chapeau, veste, manches… comme le joueur les a réglés dans ses options de skin. */
    fun copySkinLayers(mannequin: RemotePlayer) {
        val local = Minecraft.getInstance().player ?: return
        mannequin.entityData.set(Player.DATA_PLAYER_MODE_CUSTOMISATION, local.entityData.get(Player.DATA_PLAYER_MODE_CUSTOMISATION))
    }
}
