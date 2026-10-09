package fr.owme.cobblelegacy.emotes.client.preview

import dev.kosmx.playerAnim.api.IPlayer
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import java.util.Collections
import java.util.WeakHashMap

/**
 * Un mannequin qui joue une émote en boucle (une émote qui ne boucle pas reprend après une courte
 * pause). Les aperçus vivants sont avancés à chaque tick du client par [EmotePreviews].
 */
@Environment(EnvType.CLIENT)
class EmotePreview {
    private var player: RemotePlayer? = null
    private var animation: KeyframeAnimation? = null
    private var pause = -1

    /** Ticks avant de relancer une émote terminée. */
    var pauseBetweenLoops = 12

    fun show(next: KeyframeAnimation?) {
        if (next === animation) return
        animation = next
        pause = -1
        restart()
    }

    fun restart() {
        val mannequin = entity() ?: return
        val current = animation
        if (current == null) mannequin.`emotecraft$voidEmote`() else mannequin.`emotecraft$playEmote`(current, 0, false)
    }

    /** Le mannequin, recréé si le monde a changé. `null` hors d'une partie. */
    fun entity(): RemotePlayer? {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val local = mc.player ?: return null
        val existing = player
        if (existing != null && existing.level() === level) return existing
        val created = EmotePreviewMannequin.create(level, local.gameProfile)
        player = created
        animation?.let { created.`emotecraft$playEmote`(it, 0, false) }
        return created
    }

    fun tick() {
        val mannequin = player ?: return
        mannequin.tickCount++
        // L'interface de PlayerAnimator est ajoutée par mixin, invisible pour le compilateur.
        ((mannequin as Any) as IPlayer).animationStack.tick()
        if (animation == null) return
        if (mannequin.`emotecraft$getEmote`()?.isActive == true) return
        if (pause < 0) {
            pause = pauseBetweenLoops
        } else if (--pause <= 0) {
            pause = -1
            restart()
        }
    }

    fun dispose() {
        player = null
        animation = null
    }
}

@Environment(EnvType.CLIENT)
object EmotePreviews {
    private val active: MutableSet<EmotePreview> = Collections.newSetFromMap(WeakHashMap())

    fun attach(preview: EmotePreview) {
        active += preview
    }

    fun detach(preview: EmotePreview) {
        active -= preview
    }

    fun tick() {
        if (active.isEmpty()) return
        active.toList().forEach { it.tick() }
    }

    fun clear() {
        active.toList().forEach { it.dispose() }
        active.clear()
    }
}
