package fr.owme.cobblelegacy.emotes.client.ui

import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorScreen
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel
import fr.owme.cobblelegacy.emotes.client.shop.EmoteShopScreen
import fr.owme.cobblelegacy.emotes.client.ui.collection.CollectionViewModel
import fr.owme.cobblelegacy.emotes.client.ui.collection.EmoteCollectionScreen
import fr.owme.cobblelegacy.emotes.client.ui.wheel.EmoteWheelScreen
import fr.owme.cobblelegacy.emotes.client.ui.wheel.WheelTarget
import fr.owme.cobblelegacy.emotes.client.ui.wheel.WheelViewModel
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Ouverture des écrans Compose (à n'appeler que si Composite est installé, cf. EmoteScreens).
 *
 * Un écran Compose fermé ne se rouvre pas (Composite libère sa scène) : le « retour » reconstruit
 * l'écran d'avant.
 */
object ComposeEmoteScreens {

    fun openWheel() {
        Minecraft.getInstance().setScreen(EmoteWheelScreen(WheelViewModel()))
    }

    /** [target] : on vient de la roue pour choisir l'émote de cette case. */
    fun openCollection(selected: UUID?, target: WheelTarget?) {
        Minecraft.getInstance().setScreen(EmoteCollectionScreen(CollectionViewModel(selected, target)))
    }

    fun openShop(selected: UUID?) {
        Minecraft.getInstance().setScreen(EmoteShopScreen(selected, backTo(selected)))
    }

    fun openEditor(selected: UUID?) {
        if (!ClientEmoteCatalog.editorEnabled) return
        Minecraft.getInstance().setScreen(EmoteEditorScreen(EmoteEditorViewModel(selected), backTo(selected)))
    }

    /** Retour vers l'écran d'émotes ouvert, ou rien (Pokématos, jeu). */
    private fun backTo(selected: UUID?): (() -> Unit)? = when (Minecraft.getInstance().screen) {
        is EmoteCollectionScreen -> { { openCollection(selected, null) } }
        is EmoteShopScreen -> { { openShop(selected) } }
        else -> null
    }
}
