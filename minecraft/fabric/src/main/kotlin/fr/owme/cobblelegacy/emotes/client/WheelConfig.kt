package fr.owme.cobblelegacy.emotes.client

import io.github.kosmx.emotes.arch.screen.widget.ModernChooseWheel
import io.github.kosmx.emotes.executor.EmoteInstance
import io.github.kosmx.emotes.main.config.ClientConfig
import io.github.kosmx.emotes.server.config.Serializer
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import java.util.UUID

/**
 * La roue d'Emotecraft : 10 pages de 8 cases, rangées dans `config/emotecraft.json` (on garde son
 * format, la roue d'origine lit les mêmes cases). Case 0 en haut, puis dans le sens des aiguilles.
 */
@Environment(EnvType.CLIENT)
object WheelConfig {
    const val PAGES = 10
    const val SLOTS = 8

    private val config: ClientConfig get() = EmoteInstance.config as ClientConfig

    /** Dernière page affichée (partagée avec la roue d'origine). */
    var page: Int
        get() = ModernChooseWheel.fastMenuPage.coerceIn(0, PAGES - 1)
        set(value) {
            ModernChooseWheel.fastMenuPage = Math.floorMod(value, PAGES)
        }

    fun get(page: Int, slot: Int): UUID? = config.fastMenuEmotes.getOrNull(page)?.getOrNull(slot)

    fun set(page: Int, slot: Int, emote: UUID?) {
        config.fastMenuEmotes[page][slot] = emote
        Serializer.saveConfig()
    }

    /** Où cette émote est placée (page, case), s'il y en a. */
    fun positionsOf(emote: UUID): List<Pair<Int, Int>> = buildList {
        for (p in 0 until PAGES) for (s in 0 until SLOTS) if (get(p, s) == emote) add(p to s)
    }

    fun pageIsEmpty(page: Int): Boolean = (0 until SLOTS).all { get(page, it) == null }

    /** Première case libre en partant de [fromPage], ou `null` si la roue est pleine. */
    fun firstFree(fromPage: Int): Pair<Int, Int>? {
        for (offset in 0 until PAGES) {
            val p = (fromPage + offset) % PAGES
            for (s in 0 until SLOTS) if (get(p, s) == null) return p to s
        }
        return null
    }
}
