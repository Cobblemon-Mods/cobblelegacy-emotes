package fr.owme.cobblelegacy.emotes

import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Icônes des émotes, des deux côtés : toute image est ramenée à un PNG carré d'au plus [SIDE] px.
 *
 * Chaque joueur télécharge le fichier de chaque émote publiée, icône comprise : une image de 2 Mo
 * collée telle quelle pèse plus lourd que toutes les animations réunies. 256 px suffisent aux plus
 * grands affichages (collection, éditeur), même en grande échelle d'interface.
 */
object EmoteIconFiles {
    const val SIDE = 256

    /** Au-delà, même un PNG à la bonne taille est réencodé (ou réduit davantage). */
    const val MAX_BYTES = 160 * 1024

    /** Image d'origine acceptée par l'éditeur, avant réduction. */
    const val MAX_SOURCE_BYTES = 40 * 1024 * 1024

    /** Côté le plus grand lu dans une image : au-delà, elle est refusée. */
    private const val MAX_SOURCE_SIDE = 20_000

    val EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    /**
     * [bytes] en icône légère : rendu tel quel si c'est déjà un PNG d'au plus [SIDE] px et [MAX_BYTES],
     * sinon réduit (jamais agrandi), centré sur un carré transparent et réencodé en PNG. `null` si
     * l'image est illisible (format inconnu, fichier abîmé, image démesurée).
     */
    fun light(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val decoded = read(bytes) ?: return null
        if (isPng(bytes) && decoded.width <= SIDE && decoded.height <= SIDE && bytes.size <= MAX_BYTES) return bytes
        var side = SIDE
        while (true) {
            val png = encode(fit(decoded.image, side))
            if (png.size <= MAX_BYTES || side <= 64) return png
            side = side * 3 / 4
        }
    }

    /** Côtés de l'image, sans la décoder ; `null` si elle est illisible. */
    fun sizeOf(bytes: ByteArray): Pair<Int, Int>? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * [animation] avec son icône allégée par [lighten] ; la même instance si l'icône l'est déjà, est
     * illisible ou absente.
     */
    fun lighten(animation: KeyframeAnimation, lighten: (ByteArray) -> ByteArray? = ::light): KeyframeAnimation {
        val icon = EmoteFiles.iconBytes(animation) ?: return animation
        val light = lighten(icon) ?: return animation
        return if (light === icon || light.contentEquals(icon)) animation else EmoteFiles.withIcon(animation, light)
    }

    // ─── Lecture, réduction, écriture ────────────────────────────────────────────

    private class Decoded(val image: BufferedImage, val width: Int, val height: Int)

    private fun read(bytes: ByteArray): Decoded? = try {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE) return null
                val param = reader.defaultReadParam
                // Très grande image : lue sous-échantillonnée, jamais plus de 2× la taille finale en mémoire.
                val step = max(width, height) / (SIDE * 2)
                if (step > 1) param.setSourceSubsampling(step, step, 0, 0)
                Decoded(reader.read(0, param), width, height)
            } finally {
                reader.dispose()
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Réduite pour tenir dans [side] px (jamais agrandie), centrée sur un carré transparent. */
    private fun fit(source: BufferedImage, side: Int): BufferedImage {
        val longest = max(source.width, source.height)
        val square = minOf(side, longest)
        val scale = square.toDouble() / longest
        val width = max(1, (source.width * scale).roundToInt())
        val height = max(1, (source.height * scale).roundToInt())
        val scaled = downscale(argb(source), width, height)
        if (width == square && height == square) return scaled
        val canvas = BufferedImage(square, square, BufferedImage.TYPE_INT_ARGB)
        canvas.createGraphics().apply {
            drawImage(scaled, (square - width) / 2, (square - height) / 2, null)
            dispose()
        }
        return canvas
    }

    /** Réduction par moitiés successives : nette, sans le crénelage d'une réduction d'un seul coup. */
    private fun downscale(source: BufferedImage, width: Int, height: Int): BufferedImage {
        var current = source
        var w = source.width
        var h = source.height
        while (w != width || h != height) {
            w = max(width, w / 2)
            h = max(height, h / 2)
            val next = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            next.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY)
                drawImage(current, 0, 0, w, h, null)
                dispose()
            }
            current = next
        }
        return current
    }

    private fun argb(image: BufferedImage): BufferedImage {
        if (image.type == BufferedImage.TYPE_INT_ARGB) return image
        val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        copy.createGraphics().apply {
            drawImage(image, 0, 0, null)
            dispose()
        }
        return copy
    }

    private fun encode(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
