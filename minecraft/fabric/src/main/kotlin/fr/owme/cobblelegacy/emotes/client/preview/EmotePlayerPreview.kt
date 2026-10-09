package fr.owme.cobblelegacy.emotes.client.preview

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import dev.aperso.composite.skia.LocalSkiaSurface
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.entity.LivingEntity
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.pow

/** De face, légèrement de trois quarts : on voit le visage et les bras. */
const val PREVIEW_DEFAULT_YAW = -25f
const val PREVIEW_DEFAULT_PITCH = 0f
const val PREVIEW_DEFAULT_ZOOM = 1f

// Négatifs : le modèle suit le geste.
private const val YAW_PER_PIXEL = -0.6f
private const val PITCH_PER_PIXEL = -0.3f
private const val PITCH_LIMIT = 40f
private const val ZOOM_MIN = 0.55f
private const val ZOOM_MAX = 2f
private const val ZOOM_STEP_FACTOR = 1.12f

/** Fraction de la hauteur du cadre occupée par le joueur. */
private const val ENTITY_FILL = 0.38f

/**
 * Marge de profondeur, en demi-épaisseurs de modèle : le fond de l'écran a écrit le depth buffer
 * à z=0 et tout ce qui passe derrière serait rejeté (même réglage que les aperçus de capes).
 */
private const val DEPTH_HEADROOM = 3.5f

fun clampPreviewPitch(pitch: Float): Float = pitch.coerceIn(-PITCH_LIMIT, PITCH_LIMIT)

fun applyPreviewZoom(current: Float, scrollDelta: Float): Float =
    (current * ZOOM_STEP_FACTOR.pow(-scrollDelta)).coerceIn(ZOOM_MIN, ZOOM_MAX)

/**
 * Le joueur local (son skin) qui joue [animation] en boucle, dessiné en 3D.
 *
 * Comme les aperçus de capes, le dessin passe par la surface de Composite et s'exécute APRÈS l'UI
 * Compose : rien ne doit être superposé à cette zone. [visible] est relu à chaque frame (`false`
 * quand une fenêtre Compose recouvre l'aperçu).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EmotePlayerPreview(
    animation: KeyframeAnimation?,
    modifier: Modifier = Modifier,
    yaw: Float = PREVIEW_DEFAULT_YAW,
    pitch: Float = PREVIEW_DEFAULT_PITCH,
    zoom: Float = PREVIEW_DEFAULT_ZOOM,
    onRotate: ((deltaYaw: Float, deltaPitch: Float) -> Unit)? = null,
    onZoom: ((scrollDelta: Float) -> Unit)? = null,
    fill: Float = ENTITY_FILL,
    visible: () -> Boolean = { true }
) {
    val surface = LocalSkiaSurface.current
    val preview = remember { EmotePreview() }
    DisposableEffect(preview) {
        EmotePreviews.attach(preview)
        onDispose {
            EmotePreviews.detach(preview)
            preview.dispose()
        }
    }
    SideEffect { preview.show(animation) }

    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val mc = Minecraft.getInstance()
    val guiScale = mc.window.guiScale.toFloat()
    val density = 1f / guiScale

    // La boucle de rendu n'est jamais relancée : elle lit les valeurs courantes.
    val yawRef = rememberUpdatedState(yaw)
    val pitchRef = rememberUpdatedState(pitch)
    val zoomRef = rememberUpdatedState(zoom)
    val fillRef = rememberUpdatedState(fill)
    val visibleRef = rememberUpdatedState(visible)

    LaunchedEffect(coordinates, guiScale) {
        val coords = coordinates ?: return@LaunchedEffect
        while (isActive) {
            withFrameNanos {
                surface.record {
                    if (!coords.isAttached || !visibleRef.value()) return@record
                    val bounds = coords.boundsInWindow()
                    if (bounds.width <= 0f || bounds.height <= 0f) return@record
                    val entity = preview.entity() ?: return@record

                    val position = coords.positionInWindow()
                    val x1 = position.x * density
                    val y1 = position.y * density
                    val boxWidth = coords.size.width * density
                    val boxHeight = coords.size.height * density
                    // Zone visible (rognée par les parents défilants), en unités d'interface.
                    val clipX1 = bounds.left * density
                    val clipY1 = bounds.top * density
                    val clipX2 = bounds.right * density
                    val clipY2 = bounds.bottom * density

                    val size = (boxHeight * fillRef.value * zoomRef.value) / entity.scale
                    enableScissor(clipX1.toInt(), clipY1.toInt(), clipX2.toInt(), clipY2.toInt())
                    try {
                        renderMannequin(
                            this, entity, x1 + boxWidth / 2f, y1 + boxHeight / 2f, size,
                            yawRef.value, pitchRef.value, mc.timer.getGameTimeDeltaPartialTick(true)
                        )
                    } catch (_: Exception) {
                    } finally {
                        disableScissor()
                    }
                }
            }
        }
    }

    val rotate = rememberUpdatedState(onRotate)
    val zoomer = rememberUpdatedState(onZoom)
    Spacer(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates = it }
            // Boucle pointeur brute plutôt que detectDragGestures : sous Composite, les détecteurs
            // standards perturbent le dispatch des clics suivants.
            .pointerInput(guiScale) {
                awaitPointerEventScope {
                    var held = false
                    var last: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Press -> if (event.button == PointerButton.Primary && rotate.value != null) {
                                held = true
                                last = change.position
                            }
                            PointerEventType.Move -> {
                                val previous = last
                                if (held && previous != null) {
                                    val dx = change.position.x - previous.x
                                    val dy = change.position.y - previous.y
                                    if (dx != 0f || dy != 0f) rotate.value?.invoke(dx * YAW_PER_PIXEL, dy * PITCH_PER_PIXEL)
                                    last = change.position
                                }
                            }
                            PointerEventType.Release -> {
                                held = false
                                last = null
                            }
                            PointerEventType.Scroll -> {
                                // Composite envoie le delta en pixels fenêtre : on repasse en crans.
                                val scrollY = change.scrollDelta.y / guiScale
                                val zoomHandler = zoomer.value
                                if (scrollY != 0f && zoomHandler != null) {
                                    zoomHandler(scrollY)
                                    change.consume()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
    )
}

/** Même chemin que le joueur de l'inventaire vanilla, avec la vraie fraction de tick : l'animation reste fluide. */
private fun renderMannequin(
    graphics: GuiGraphics,
    entity: LivingEntity,
    x: Float,
    y: Float,
    size: Float,
    yaw: Float,
    pitch: Float,
    partialTick: Float
) {
    val rotation = Quaternionf().rotateZ(Math.PI.toFloat())
    val cameraTilt = Quaternionf().rotateX(pitch * (Math.PI.toFloat() / 180f))
    rotation.mul(cameraTilt)

    val bodyYaw = 180f + yaw
    entity.yBodyRot = bodyYaw
    entity.yBodyRotO = bodyYaw
    entity.yRot = bodyYaw
    entity.yRotO = bodyYaw
    entity.xRot = -pitch
    entity.xRotO = -pitch
    entity.yHeadRot = bodyYaw
    entity.yHeadRotO = bodyYaw

    val scale = entity.scale
    val translate = Vector3f(0f, entity.bbHeight / 2f + 0.0625f * scale, 0f)
    val pose = graphics.pose()
    pose.pushPose()
    pose.translate(0.0, 0.0, (size * DEPTH_HEADROOM).toDouble())
    pose.translate(x.toDouble(), y.toDouble(), 50.0)
    pose.scale(size, size, -size)
    pose.translate(translate.x, translate.y, translate.z)
    pose.mulPose(rotation)
    Lighting.setupForEntityInInventory()
    val dispatcher = Minecraft.getInstance().entityRenderDispatcher
    dispatcher.overrideCameraOrientation(Quaternionf(cameraTilt).conjugate().rotateY(Math.PI.toFloat()))
    dispatcher.setRenderShadow(false)
    // Pas d'étiquette de nom au-dessus du mannequin : Minecraft ne les dessine pas en interface masquée.
    val options = Minecraft.getInstance().options
    val hideGui = options.hideGui
    options.hideGui = true
    try {
        RenderSystem.runAsFancy {
            dispatcher.render(entity, 0.0, 0.0, 0.0, 0f, partialTick, pose, graphics.bufferSource(), 15728880)
        }
    } finally {
        options.hideGui = hideGui
    }
    graphics.flush()
    dispatcher.setRenderShadow(true)
    pose.popPose()
    Lighting.setupFor3DItems()
}
