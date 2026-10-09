package fr.owme.cobblelegacy.emotes

import dev.kosmx.playerAnim.core.data.AnimationFormat
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import io.github.kosmx.emotes.PlatformTools
import io.github.kosmx.emotes.common.network.EmotePacket
import io.github.kosmx.emotes.server.serializer.UniversalEmoteSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import net.minecraft.ChatFormatting

/**
 * Lecture et écriture des fichiers d'émotes, des deux côtés.
 *
 * Le catalogue range toujours le format binaire `.emotecraft` (animation, nom, auteur, icône et son
 * éventuel) : l'éditeur convertit un `.json` avant l'envoi, et le serveur relit chaque fichier reçu
 * avant de l'accepter.
 */
object EmoteFiles {
    /** Fichier rangé dans le catalogue (icône comprise). */
    const val MAX_FILE_SIZE = 4 * 1024 * 1024

    /** Icône PNG ajoutée depuis l'éditeur. */
    const val MAX_ICON_SIZE = 1024 * 1024

    val IMPORT_EXTENSIONS = setOf("emotecraft", "json")

    class Parsed(
        val animation: KeyframeAnimation,
        val name: String,
        val description: String,
        val author: String,
        val durationTicks: Int,
        val loops: Boolean,
        val hasIcon: Boolean
    )

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Un fichier du catalogue (binaire). `null` s'il est illisible. */
    fun parseBinary(bytes: ByteArray): Parsed? = parse(bytes, "emote.emotecraft")

    /** Un fichier importé, d'après son extension (`.emotecraft` ou `.json`). */
    fun parse(bytes: ByteArray, fileName: String): Parsed? {
        if (bytes.isEmpty() || bytes.size > MAX_FILE_SIZE) return null
        val animation = try {
            UniversalEmoteSerializer.readData(ByteArrayInputStream(bytes), fileName).firstOrNull()
        } catch (e: Exception) {
            CobbleLegacyEmotes.logger.debug("[Émotes] Fichier d'émote illisible ({}) : {}", fileName, e.message)
            null
        } ?: return null
        return describe(animation)
    }

    fun describe(animation: KeyframeAnimation): Parsed {
        val loops = animation.isInfinite
        return Parsed(
            animation = animation,
            name = textOf(animation.extraData["name"]).trim(),
            description = textOf(animation.extraData["description"]).trim(),
            author = textOf(animation.extraData["author"]).trim(),
            durationTicks = (if (loops) animation.endTick else animation.stopTick).coerceAtLeast(0),
            loops = loops,
            hasIcon = animation.extraData["iconData"] is ByteBuffer
        )
    }

    private val repeatedSpaces = Regex(" {2,}")

    /** Sans les codes de couleur (« §4 ») que beaucoup d'auteurs mettent dans leurs noms. */
    fun plain(text: String): String = (ChatFormatting.stripFormatting(text) ?: "").replace(repeatedSpaces, " ")

    /** Texte brut d'un champ d'émote (souvent un composant JSON comme `{"text":"..."}`), sans couleurs. */
    fun textOf(value: Any?): String = try {
        if (value == null) "" else plain(PlatformTools.fromJson(value).string)
    } catch (e: Exception) {
        value?.let { plain(it.toString()) } ?: ""
    }

    /** L'animation au format binaire du catalogue. */
    fun toBinary(animation: KeyframeAnimation): ByteArray {
        val out = ByteArrayOutputStream()
        UniversalEmoteSerializer.writeKeyframeAnimation(out, animation, AnimationFormat.BINARY)
        return out.toByteArray()
    }

    /** Copie de [animation] avec une autre icône PNG. */
    fun withIcon(animation: KeyframeAnimation, png: ByteArray): KeyframeAnimation {
        val copy = animation.mutableCopy()
        copy.extraData["iconData"] = ByteBuffer.wrap(png)
        return copy.build()
    }

    /** Octets de l'icône PNG d'une émote, s'il y en a une. */
    fun iconBytes(animation: KeyframeAnimation): ByteArray? {
        val buffer = animation.extraData["iconData"] as? ByteBuffer ?: return null
        val copy = buffer.duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    /** L'animation sans son icône : c'est tout ce qu'il faut au serveur pour la diffuser. */
    fun withoutIcon(animation: KeyframeAnimation): KeyframeAnimation {
        if (!animation.extraData.containsKey("iconData")) return animation
        val copy = animation.mutableCopy()
        copy.extraData.remove("iconData")
        return copy.build()
    }

    /**
     * Version « allégée » d'une émote du catalogue pour le réseau : même UUID et même minutage, sans
     * images clés. Chaque client a déjà le fichier complet (téléchargé et vérifié) et joue sa copie :
     * une émote de plusieurs centaines de Ko passe ainsi dans un seul petit paquet (Emotecraft 2.4
     * refuse d'envoyer une émote de plus de 32 Ko).
     */
    fun stubOf(animation: KeyframeAnimation): KeyframeAnimation {
        val builder = KeyframeAnimation.AnimationBuilder(AnimationFormat.SERVER)
        builder.uuid = animation.uuid
        builder.beginTick = animation.beginTick
        builder.endTick = animation.endTick
        builder.stopTick = animation.stopTick
        builder.isLooped = animation.isInfinite
        builder.returnTick = animation.returnToTick
        builder.isEasingBefore = animation.isEasingBefore
        builder.nsfw = animation.nsfw
        builder.extraData[STUB_MARKER] = true
        return builder.build()
    }

    const val STUB_MARKER = "cobblelegacyStub"

    /** Taille du paquet de lecture d'une émote (sans icône), pour repérer celles qui dépassent un paquet. */
    fun streamSize(animation: KeyframeAnimation): Int = try {
        EmotePacket.Builder().configureToStreamEmote(animation).setSizeLimit(Int.MAX_VALUE).build().write().limit()
    } catch (e: Exception) {
        -1
    }

    fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
}
