package fr.owme.cobblelegacy.emotes.client

import fr.owme.cobblelegacy.emotes.network.EmoteEditorResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Réponses du serveur aux achats et à l'éditeur. Le menu ouvert s'abonne ; sans menu pour l'afficher,
 * la réponse va dans le chat.
 */
@Environment(EnvType.CLIENT)
object EmoteClientEvents {
    var shopListener: ((EmoteShopResultPayload) -> Unit)? = null
    var editorListener: ((EmoteEditorResultPayload) -> Unit)? = null

    fun dispatchShop(result: EmoteShopResultPayload) {
        val listener = shopListener
        if (listener != null) listener(result) else chat(result.message, result.success)
    }

    fun dispatchEditor(result: EmoteEditorResultPayload) {
        val listener = editorListener
        if (listener != null) listener(result) else chat(result.message, result.success)
    }

    private fun chat(message: String, success: Boolean) {
        Minecraft.getInstance().gui.chat.addMessage(
            Component.literal(message).withStyle(if (success) ChatFormatting.GREEN else ChatFormatting.RED)
        )
    }
}
