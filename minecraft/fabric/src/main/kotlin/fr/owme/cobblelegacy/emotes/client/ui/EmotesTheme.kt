package fr.owme.cobblelegacy.emotes.client.ui

import androidx.compose.ui.graphics.Color
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity

/** Palette des menus d'émotes, alignée sur les capes et les montures ; accent lavande. */
object EmotesTheme {

    // ─── Accents ──────────────────────────────────────────────────────────────
    val Accent = Color(0xFFA78BFA)
    val AccentStrong = Color(0xFF8B5CF6)

    /** Boutique : même émeraude que la boutique du Pokématos. */
    val ShopAccent = Color(0xFF10B981)
    val Promo = Color(0xFFF97316)
    val New = Color(0xFFFF9100)
    val Admin = Color(0xFFF59E0B)

    // ─── Texte ────────────────────────────────────────────────────────────────
    val TextTitle = Color(0xFFF5F5F5)
    val TextPrimary = Color(0xFFE0E0E0)
    val TextMuted = Color(0x4DFFFFFF)   // 0.3
    val TextSubtle = Color(0x66FFFFFF)  // 0.4
    val TextDim = Color(0x80FFFFFF)     // 0.5
    val TextSoft = Color(0x99FFFFFF)    // 0.6

    // ─── Bordures et surfaces ─────────────────────────────────────────────────
    val BorderSubtle = Color(0x0AFFFFFF)
    val Border = Color(0x0FFFFFFF)
    val BorderMedium = Color(0x15FFFFFF)
    val SurfaceTiny = Color(0x05FFFFFF)
    val SurfaceLight = Color(0x0AFFFFFF)
    val SurfaceMedium = Color(0x15FFFFFF)
    val Panel = Color(0xF20A0A0A)

    // ─── États ────────────────────────────────────────────────────────────────
    val StatusActive = Color(0xFF4ADE80)
    val StatusError = Color(0xFFF87171)
    val StatusLocked = Color(0x59FFFFFF)
    val Warning = Color(0xFFFBBF24)
    val Danger = Color(0xFFEF4444)

    // ─── Raretés ──────────────────────────────────────────────────────────────
    data class RarityStyle(val color: Color, val bg: Color, val border: Color)

    private val rarities = mapOf(
        EmoteRarity.COMMUN to RarityStyle(Color(0xFF9CA3AF), Color(0x149CA3AF), Color(0x339CA3AF)),
        EmoteRarity.RARE to RarityStyle(Color(0xFF60A5FA), Color(0x1460A5FA), Color(0x3360A5FA)),
        EmoteRarity.EPIQUE to RarityStyle(Color(0xFFC084FC), Color(0x14C084FC), Color(0x33C084FC)),
        EmoteRarity.LEGENDAIRE to RarityStyle(Color(0xFFFBBF24), Color(0x14FBBF24), Color(0x33FBBF24)),
        EmoteRarity.LEGACY to RarityStyle(Color(0xFFF87171), Color(0x14F87171), Color(0x33F87171))
    )

    fun rarityOf(rarity: EmoteRarity): RarityStyle = rarities[rarity] ?: rarities.getValue(EmoteRarity.COMMUN)
}
