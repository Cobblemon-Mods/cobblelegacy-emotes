package fr.owme.cobblelegacy.emotes.client

import fr.owme.cobblelegacy.emotes.client.ui.ComposeEmoteScreens
import io.github.kosmx.emotes.arch.screen.ingame.FastMenuScreen
import io.github.kosmx.emotes.arch.screen.ingame.FullMenuScreen
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Ouverture des menus d'émotes. Les écrans Compose ont besoin de Composite (cobblelegacy-libs) ; sans
 * lui, on retombe sur les écrans d'origine d'Emotecraft. Les classes Compose ne sont touchées que si
 * Composite est installé.
 */
@Environment(EnvType.CLIENT)
object EmoteScreens {
    /** `cobblelegacy-libs` en jeu ; `composite` pour la version JitPack de la même bibliothèque. */
    val composeAvailable: Boolean by lazy {
        FabricLoader.getInstance().isModLoaded("cobblelegacy-libs") || FabricLoader.getInstance().isModLoaded("composite")
    }

    /** Touche de la roue (B par défaut). */
    @JvmStatic
    fun openWheel() {
        val mc = Minecraft.getInstance()
        if (composeAvailable) ComposeEmoteScreens.openWheel() else mc.setScreen(FastMenuScreen(null))
    }

    @JvmStatic
    @JvmOverloads
    fun openCollection(selected: UUID? = null) {
        val mc = Minecraft.getInstance()
        if (composeAvailable) ComposeEmoteScreens.openCollection(selected, null) else mc.setScreen(FullMenuScreen(null))
    }

    /** Éditeur du catalogue (administrateurs). */
    fun openEditor(selected: UUID? = null) {
        if (composeAvailable && ClientEmoteCatalog.editorEnabled) ComposeEmoteScreens.openEditor(selected)
    }
}
