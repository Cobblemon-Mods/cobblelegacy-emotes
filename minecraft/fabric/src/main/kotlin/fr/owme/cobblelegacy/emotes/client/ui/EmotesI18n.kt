package fr.owme.cobblelegacy.emotes.client.ui

import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import net.minecraft.client.resources.language.I18n
import java.util.Locale

/** Textes des menus, dans la langue du jeu (`assets/cobblelegacy-emotes/lang/`). */
object EmotesI18n {

    /** Clé du mod, préfixe `cobblelegacy-emotes.` ajouté automatiquement. */
    fun t(key: String, vararg args: Any): String = I18n.get("cobblelegacy-emotes.$key", *args)

    fun rarityName(rarity: EmoteRarity): String = I18n.get(rarity.translationKey)

    /** « 12 500 », comme les prix de la boutique du Pokématos. */
    fun crystals(amount: Long): String = "%,d".format(Locale.ROOT, amount).replace(',', ' ')

    /** Durée d'une animation : « 3,5 s », « 1 min 12 s ». */
    fun animationLength(ticks: Int): String {
        val seconds = ticks / 20.0
        return if (seconds < 60) {
            String.format(Locale.FRANCE, "%.1f s", seconds).replace(",0 s", " s")
        } else {
            val total = seconds.toInt()
            t("time.minutes_seconds", total / 60, total % 60)
        }
    }

    /** Temps restant : « 13 j 4 h », « 5 h 12 min », « 8 min ». */
    fun remaining(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        val days = minutes / 1440
        val hours = minutes % 1440 / 60
        return when {
            days > 0 -> t("time.days_hours", days, hours)
            hours > 0 -> t("time.hours_minutes", hours, minutes % 60)
            else -> t("time.minutes", minutes)
        }
    }
}
