package fr.owme.cobblelegacy.emotes.client.ui.wheel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import fr.owme.cobblelegacy.emotes.client.ui.EmoteThumbnail
import fr.owme.cobblelegacy.emotes.client.ui.EmotesTheme
import fr.owme.cobblelegacy.emotes.client.ui.NewBadge
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Ce qu'affiche une case de la roue. */
data class WheelSlot(
    /** L'émote placée (connue de ce client), sinon `null`. */
    val entry: EmoteEntry?,
    /** Une émote est placée ici, même inconnue (retirée du catalogue, émote d'un autre serveur…). */
    val assigned: Boolean
) {
    val locked: Boolean get() = entry != null && !entry.unlocked
    val missing: Boolean get() = assigned && entry == null
}

private const val SLOT_COUNT = 8
private const val SLOT_DEGREES = 360f / SLOT_COUNT

/** Proportions de l'anneau, en fraction du rayon extérieur. */
private const val INNER_RATIO = 0.43f
private const val GAP_DEGREES = 2.4f

/** Case sous [position] (0 en haut, sens horaire), ou `null` hors de l'anneau. */
fun wheelSlotAt(position: Offset, center: Offset, innerRadius: Float, outerRadius: Float): Int? {
    val dx = position.x - center.x
    val dy = position.y - center.y
    val distance = sqrt(dx * dx + dy * dy)
    if (distance < innerRadius || distance > outerRadius) return null
    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f + SLOT_DEGREES / 2f
    val normalized = ((degrees % 360f) + 360f) % 360f
    return (normalized / SLOT_DEGREES).toInt().coerceIn(0, SLOT_COUNT - 1)
}

/**
 * La roue ronde : huit cases en anneau autour d'un disque central ([center]).
 *
 * Survol, clics (gauche ou droit) et molette passent par une boucle pointeur brute : sous Composite,
 * les détecteurs de gestes standards perturbent les clics suivants.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun EmoteWheel(
    slots: List<WheelSlot>,
    hovered: Int?,
    onHover: (Int?) -> Unit,
    onClick: (slot: Int, secondary: Boolean) -> Unit,
    onScroll: (Int) -> Unit,
    size: Dp,
    modifier: Modifier = Modifier,
    accent: Color = EmotesTheme.Accent,
    /** Case mise en avant (placement d'une émote depuis la collection). */
    highlighted: Int? = null,
    compact: Boolean = false,
    center: @Composable BoxScope.(innerDiameter: Dp) -> Unit
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }
    val outer = sizePx / 2f * 0.94f
    val inner = outer * INNER_RATIO
    val hoverRef = rememberUpdatedState(onHover)
    val clickRef = rememberUpdatedState(onClick)
    val scrollRef = rememberUpdatedState(onScroll)

    // Chaque case « sort » un peu de l'anneau quand on la survole.
    val lifts = (0 until SLOT_COUNT).map { index ->
        val target = when (index) {
            hovered -> 1f
            highlighted -> 0.6f
            else -> 0f
        }
        animateFloatAsState(target, tween(120), label = "lift$index").value
    }

    Box(
        modifier = modifier
            .size(size)
            .pointerInput(sizePx) {
                awaitPointerEventScope {
                    val middle = Offset(sizePx / 2f, sizePx / 2f)
                    var pressed: Int? = null
                    var pressedSecondary = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val slot = wheelSlotAt(change.position, middle, inner, outer + 8f)
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> hoverRef.value(slot)
                            PointerEventType.Exit -> hoverRef.value(null)
                            PointerEventType.Press -> {
                                pressed = slot
                                pressedSecondary = event.button == PointerButton.Secondary
                            }
                            PointerEventType.Release -> {
                                val target = pressed
                                pressed = null
                                if (target != null && target == slot) clickRef.value(target, pressedSecondary)
                            }
                            PointerEventType.Scroll -> {
                                val delta = change.scrollDelta.y
                                if (delta != 0f) {
                                    scrollRef.value(if (delta > 0) 1 else -1)
                                    change.consume()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val middle = Offset(this.size.width / 2f, this.size.height / 2f)
            // Halo derrière l'anneau.
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.10f), Color.Transparent), middle, outer * 1.08f),
                radius = outer * 1.08f, center = middle
            )
            slots.forEachIndexed { index, slot ->
                val lift = lifts[index]
                val pop = lift * 7.dp.toPx()
                val start = -90f - SLOT_DEGREES / 2f + index * SLOT_DEGREES + GAP_DEGREES / 2f
                val sweep = SLOT_DEGREES - GAP_DEGREES
                val angle = Math.toRadians((-90f + index * SLOT_DEGREES).toDouble())
                val shift = Offset((cos(angle) * pop).toFloat(), (sin(angle) * pop).toFloat())
                val c = middle + shift
                val path = segment(c, inner, outer, start, sweep)
                val rarity = slot.entry?.let { EmotesTheme.rarityOf(it.rarity).color }
                val base = when {
                    slot.entry == null && !slot.assigned -> Color(0xB30C0D10)
                    slot.locked || slot.missing -> Color(0xC70D0E12)
                    else -> Color(0xD9101116)
                }
                drawPath(path, base)
                if (lift > 0f) {
                    drawPath(path, Brush.radialGradient(listOf(accent.copy(alpha = 0.28f * lift), accent.copy(alpha = 0.10f * lift)), c, outer))
                }
                drawPath(path, Color.White.copy(alpha = 0.08f + 0.14f * lift), style = Stroke(width = 1.dp.toPx()))
                // Liseré de rareté sur le bord extérieur.
                if (rarity != null) {
                    val inset = 3.dp.toPx()
                    drawArc(
                        color = rarity.copy(alpha = if (slot.locked) 0.25f else 0.75f),
                        startAngle = start + 1.2f,
                        sweepAngle = sweep - 2.4f,
                        useCenter = false,
                        topLeft = Offset(c.x - outer + inset, c.y - outer + inset),
                        size = androidx.compose.ui.geometry.Size((outer - inset) * 2f, (outer - inset) * 2f),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            // Disque central.
            drawCircle(Color(0xE60A0A0D), radius = inner - 5.dp.toPx(), center = middle)
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.14f), Color.Transparent), middle, inner),
                radius = inner - 5.dp.toPx(), center = middle
            )
            drawCircle(accent.copy(alpha = 0.22f), radius = inner - 5.dp.toPx(), center = middle, style = Stroke(1.dp.toPx()))
        }

        // Contenu des cases, au milieu de l'anneau.
        val ringMiddle = (inner + outer) / 2f
        val thickness = outer - inner
        val iconSize = with(density) { (thickness * if (compact) 0.52f else 0.46f).toDp() }
        val labelWidth = with(density) { (ringMiddle * 0.74f).toDp() }
        slots.forEachIndexed { index, slot ->
            val lift = lifts[index]
            val angle = Math.toRadians((-90f + index * SLOT_DEGREES).toDouble())
            val radius = ringMiddle + lift * with(density) { 7.dp.toPx() }
            val x = (cos(angle) * radius).toFloat()
            val y = (sin(angle) * radius).toFloat()
            Box(
                Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
                contentAlignment = Alignment.Center
            ) {
                SlotContent(slot, iconSize, labelWidth, hovered = index == hovered, compact = compact)
            }
        }

        center(with(density) { ((inner - 5.dp.toPx()) * 2f).toDp() })
    }
}

@Composable
private fun SlotContent(slot: WheelSlot, iconSize: Dp, labelWidth: Dp, hovered: Boolean, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val entry = slot.entry
        when {
            entry != null -> Box(contentAlignment = Alignment.TopEnd) {
                EmoteThumbnail(entry, Modifier.size(iconSize), locked = slot.locked, glow = false)
                if (slot.locked) {
                    Icon(Icons.Outlined.Lock, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(iconSize * 0.3f))
                } else if (entry.isNew && !compact) {
                    NewBadge(Modifier.offset(x = 6.dp, y = (-4).dp), fontSize = 6.sp)
                }
            }
            slot.missing -> Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = EmotesTheme.TextMuted, modifier = Modifier.size(iconSize * 0.55f))
            else -> Icon(Icons.Outlined.Add, null, tint = Color(0x2EFFFFFF), modifier = Modifier.size(iconSize * 0.45f))
        }
        if (!compact && entry != null) {
            Text(
                entry.name,
                fontSize = 9.sp,
                fontWeight = if (hovered) FontWeight.Bold else FontWeight.SemiBold,
                color = when {
                    slot.locked -> EmotesTheme.StatusLocked
                    hovered -> Color.White
                    else -> EmotesTheme.TextPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(labelWidth)
            )
        }
    }
}

/** Secteur d'anneau entre [innerRadius] et [outerRadius], de [startDegrees] sur [sweepDegrees]. */
private fun segment(center: Offset, innerRadius: Float, outerRadius: Float, startDegrees: Float, sweepDegrees: Float): Path {
    val outerRect = Rect(center.x - outerRadius, center.y - outerRadius, center.x + outerRadius, center.y + outerRadius)
    val innerRect = Rect(center.x - innerRadius, center.y - innerRadius, center.x + innerRadius, center.y + innerRadius)
    return Path().apply {
        arcTo(outerRect, startDegrees, sweepDegrees, forceMoveTo = true)
        arcTo(innerRect, startDegrees + sweepDegrees, -sweepDegrees, forceMoveTo = false)
        close()
    }
}

/** Taille de roue qui tient dans l'espace disponible. */
fun wheelSizeFor(maxWidth: Dp, maxHeight: Dp, cap: Dp = 460.dp): Dp =
    minOf(cap, maxHeight * 0.66f, maxWidth * 0.62f)
