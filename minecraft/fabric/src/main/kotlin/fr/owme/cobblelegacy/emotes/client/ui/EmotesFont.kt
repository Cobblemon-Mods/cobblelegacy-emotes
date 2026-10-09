package fr.owme.cobblelegacy.emotes.client.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import net.minecraft.client.Minecraft
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/**
 * Space Grotesk avec ses vraies graisses (Regular, Medium, Bold), comme le Pokématos : sans elles,
 * Skia épaissit le Regular et les titres bavent. Les TTF sont dans `assets/cobblelegacy-emotes/fonts/`,
 * lus par le ResourceManager de Minecraft : leurs noms restent en minuscules, une majuscule rend
 * l'identifiant invalide et l'interface retombait sur la police du système.
 */
object EmotesFont {
    private val logger = CobbleLegacyEmotes.logger

    @Volatile
    private var cached: FontFamily? = null

    fun family(): FontFamily {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return build().also { cached = it }
        }
    }

    private fun build(): FontFamily {
        val regular = load("Regular") ?: return FontFamily.Default
        val medium = load("Medium")
        val bold = load("Bold")
        if (medium == null || bold == null) return single(regular)
        return try {
            FontFamily(
                font("SpaceGrotesk-Regular", regular, FontWeight.Normal),
                font("SpaceGrotesk-Medium", medium, FontWeight.Medium),
                font("SpaceGrotesk-SemiBold", medium, FontWeight.SemiBold),
                font("SpaceGrotesk-Bold", bold, FontWeight.Bold),
                font("SpaceGrotesk-ExtraBold", bold, FontWeight.ExtraBold)
            )
        } catch (e: Exception) {
            logger.warn("[Émotes] Police multi-graisses impossible ({}), Regular seul", e.message)
            single(regular)
        }
    }

    /** TTF statiques : des réglages de variation vides évitent un `makeClone()` absent du Skia de Composite. */
    private fun font(identity: String, data: ByteArray, weight: FontWeight) =
        Font(identity, data, weight, FontStyle.Normal, FontVariation.Settings())

    private fun single(regular: ByteArray): FontFamily = try {
        FontFamily(Typeface(FontMgr.default.makeFromData(Data.makeFromBytes(regular), 0)!!))
    } catch (e: Exception) {
        FontFamily.Default
    }

    private fun load(weight: String): ByteArray? = try {
        val id = CobbleLegacyEmotes.id("fonts/space_grotesk_${weight.lowercase()}.ttf")
        Minecraft.getInstance().resourceManager.getResource(id).orElse(null)?.open()?.use { it.readAllBytes() }
            .also { if (it == null) logger.warn("[Émotes] Police introuvable : {}", id) }
    } catch (e: Exception) {
        logger.warn("[Émotes] Lecture de la police {} impossible : {}", weight, e.message)
        null
    }
}

/** Space Grotesk sur tout le contenu (les `BasicTextField` la reçoivent à part). */
@Composable
fun WithEmotesFont(content: @Composable () -> Unit) {
    val family = remember { EmotesFont.family() }
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = family), content = content)
}

/**
 * Racine des écrans d'émotes. Composite compose l'écran dès sa création, avant de lui donner la
 * taille et la densité de la fenêtre : ce qui se calcule d'après la place disponible (taille de la
 * roue…) partait d'une densité de 1 et restait deux fois trop grand jusqu'au premier survol. On
 * attend donc la première image, où tout est en place.
 */
@Composable
fun EmoteScreenRoot(content: @Composable () -> Unit) {
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        ready = true
    }
    if (ready) WithEmotesFont(content)
}
