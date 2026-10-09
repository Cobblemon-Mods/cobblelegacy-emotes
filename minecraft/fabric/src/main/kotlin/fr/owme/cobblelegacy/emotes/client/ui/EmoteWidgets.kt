package fr.owme.cobblelegacy.emotes.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════════
// VIGNETTE D'UNE ÉMOTE
// ═══════════════════════════════════════════════════════════════════════════════

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * L'icône de l'émote (celle de son fichier) sur un halo de sa rareté, ou un pictogramme si elle n'en a
 * pas. Verrouillée : grisée et assombrie.
 */
@Composable
fun EmoteThumbnail(entry: EmoteEntry?, modifier: Modifier = Modifier, locked: Boolean = false, glow: Boolean = true) {
    val rarity = EmotesTheme.rarityOf(entry?.rarity ?: EmoteRarity.COMMUN)
    val icon: ImageBitmap? = entry?.let { EmoteIcons.get(it.id) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (glow) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(listOf(rarity.color.copy(alpha = if (locked) 0.06f else 0.16f), Color.Transparent))
                )
            )
        }
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = entry.name,
                filterQuality = FilterQuality.Low,
                colorFilter = if (locked) grayscale else null,
                modifier = Modifier.fillMaxSize(0.86f).alpha(if (locked) 0.45f else 1f)
            )
        } else {
            Icon(
                Icons.Outlined.EmojiPeople, null,
                tint = rarity.color.copy(alpha = if (locked) 0.25f else 0.75f),
                modifier = Modifier.fillMaxSize(0.5f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BADGES
// ═══════════════════════════════════════════════════════════════════════════════

/** Le petit tag « NEW » des émotes ajoutées récemment. */
@Composable
fun NewBadge(modifier: Modifier = Modifier, fontSize: TextUnit = 7.sp) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Brush.horizontalGradient(listOf(EmotesTheme.New, Color(0xFFFFB347))))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(EmotesI18n.t("badge.new"), fontSize = fontSize, fontWeight = FontWeight.ExtraBold, color = Color(0xFF1A0F00), letterSpacing = 0.5.sp, maxLines = 1)
    }
}

/** « -20 % » sur une émote en promotion. */
@Composable
fun PromoBadge(percent: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(EmotesTheme.Promo)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text("-$percent %", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
    }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.5.sp, maxLines = 1)
    }
}

@Composable
fun RarityChip(rarity: EmoteRarity) {
    val style = EmotesTheme.rarityOf(rarity)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(style.bg)
            .border(1.dp, style.border, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(EmotesI18n.rarityName(rarity).uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = style.color, letterSpacing = 0.5.sp)
    }
}

@Composable
fun CountBadge(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.03f))
            .border(1.dp, color.copy(alpha = 0.125f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
fun LockIcon(size: Dp = 11.dp) {
    Icon(Icons.Outlined.Lock, null, tint = EmotesTheme.StatusLocked, modifier = Modifier.size(size))
}

// ═══════════════════════════════════════════════════════════════════════════════
// CRISTAUX
// ═══════════════════════════════════════════════════════════════════════════════

private var crystalImage: ImageBitmap? = null
private var crystalLoaded = false

/** L'icône de cristal du Pokématos (`cristal.png`) si elle est chargée, sinon un losange violet. */
@Composable
fun CrystalGlyph(size: Dp) {
    if (!crystalLoaded) {
        crystalLoaded = true
        crystalImage = runCatching {
            Thread.currentThread().contextClassLoader.getResourceAsStream("cristal.png")?.use { EmoteIcons.decode(it.readAllBytes()) }
        }.getOrNull()
    }
    val image = crystalImage
    if (image != null) {
        Image(image, null, modifier = Modifier.size(size), filterQuality = FilterQuality.Low)
    } else {
        Canvas(Modifier.size(size)) {
            val path = Path().apply {
                moveTo(this@Canvas.size.width / 2f, 0f)
                lineTo(this@Canvas.size.width, this@Canvas.size.height / 2f)
                lineTo(this@Canvas.size.width / 2f, this@Canvas.size.height)
                lineTo(0f, this@Canvas.size.height / 2f)
                close()
            }
            drawPath(path, Brush.linearGradient(listOf(Color(0xFFD8B4FE), Color(0xFF9333EA)), Offset.Zero, Offset(this.size.width, this.size.height)))
        }
    }
}

/** Prix avec son cristal ; [original] barré devant quand une promotion le réduit. */
@Composable
fun PriceTag(
    amount: Long,
    size: TextUnit,
    color: Color = EmotesTheme.ShopAccent,
    original: Long? = null,
    crystal: @Composable (Float) -> Unit = { CrystalGlyph(it.dp) }
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (original != null && original > amount) {
            Text(EmotesI18n.crystals(original), fontSize = size * 0.75f, color = EmotesTheme.TextMuted, textDecoration = TextDecoration.LineThrough)
            Spacer(Modifier.width(5.dp))
        }
        Text(EmotesI18n.crystals(amount), fontSize = size, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(4.dp))
        crystal(size.value * 0.9f)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BOUTONS, LIGNES, MESSAGES
// ═══════════════════════════════════════════════════════════════════════════════

val ButtonShape = RoundedCornerShape(10.dp)

@Composable
fun ButtonContent(icon: ImageVector?, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(13.dp)) }
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PrimaryButton(icon: ImageVector?, label: String, accent: Color = EmotesTheme.Accent, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.09f))))
            .border(1.dp, accent.copy(alpha = 0.3f), ButtonShape)
            .clickable { onClick() }
            .padding(11.dp),
        contentAlignment = Alignment.Center
    ) {
        ButtonContent(icon, label, accent)
    }
}

@Composable
fun SecondaryButton(icon: ImageVector?, label: String, modifier: Modifier = Modifier, color: Color = Color(0x99FFFFFF), onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x14FFFFFF), ButtonShape)
            .clickable { onClick() }
            .padding(11.dp),
        contentAlignment = Alignment.Center
    ) {
        ButtonContent(icon, label, color)
    }
}

@Composable
fun DisabledButton(icon: ImageVector?, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(Color(0x08FFFFFF))
            .border(1.dp, Color(0x10FFFFFF), ButtonShape)
            .padding(11.dp),
        contentAlignment = Alignment.Center
    ) {
        ButtonContent(icon, label, EmotesTheme.StatusLocked)
    }
}

/** Bouton plein de la boutique (achat, confirmation). */
@Composable
fun ShopButton(icon: ImageVector?, label: String, color: Color, filled: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val background = when {
        !enabled -> Color(0x08FFFFFF)
        filled -> color
        else -> color.copy(alpha = 0.08f)
    }
    val content = if (filled && enabled) Color.White else color
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ButtonShape)
            .background(background)
            .border(1.dp, if (enabled) color.copy(alpha = if (filled) 1f else 0.3f) else Color(0x10FFFFFF), ButtonShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon?.let {
            Icon(it, null, tint = if (enabled) content else EmotesTheme.StatusLocked, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (enabled) content else EmotesTheme.StatusLocked, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DetailRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EmotesTheme.SurfaceTiny)
            .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 10.sp, color = EmotesTheme.TextSubtle)
        Spacer(Modifier.width(8.dp))
        content()
    }
}

@Composable
fun Notice(text: String, color: Color, icon: ImageVector = Icons.Outlined.Info) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, fontSize = 8.sp, color = color)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextMuted, letterSpacing = 1.sp)
}

/** Message refermé au bout de 3 s, en bas au centre par défaut (sans cacher les onglets du haut). */
@Composable
fun Toast(message: String, success: Boolean, accent: Color = EmotesTheme.Accent, alignment: Alignment = Alignment.BottomCenter, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        delay(3000)
        onDismiss()
    }
    Box(Modifier.fillMaxSize()) {
        val color = if (success) accent else EmotesTheme.StatusError
        Box(
            modifier = Modifier
                .align(alignment)
                .padding(16.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xF00A0A0A))
                .background(color.copy(alpha = 0.1f))
                .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(10.dp, 8.dp, 18.dp, 8.dp)
        ) {
            Text((if (success) "✓ " else "✗ ") + message, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILTRES ET RECHERCHE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun FilterButton(label: String, icon: ImageVector, isActive: Boolean, activeColor: Color, count: Int? = null, onClick: () -> Unit) {
    val textColor = if (isActive) activeColor else EmotesTheme.TextSubtle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) activeColor.copy(alpha = 0.07f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (isActive) activeColor.copy(alpha = 0.21f) else EmotesTheme.BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = textColor, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        count?.let { Text(it.toString(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = textColor.copy(alpha = 0.7f)) }
    }
}

@Composable
fun RarityFilterButton(rarity: EmoteRarity, isActive: Boolean, onClick: () -> Unit) {
    val style = EmotesTheme.rarityOf(rarity)
    val textColor = if (isActive) style.color else EmotesTheme.TextSubtle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) style.bg else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (isActive) style.border else EmotesTheme.BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).background(style.color, CircleShape))
        Text(EmotesI18n.rarityName(rarity), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = textColor)
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, accent: Color, modifier: Modifier = Modifier) {
    // BasicTextField ne reprend pas LocalTextStyle : on lui donne la police explicitement.
    val style = LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = EmotesFont.family()))
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(40)) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(accent),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, if (value.isNotEmpty()) accent.copy(alpha = 0.35f) else EmotesTheme.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Search, null, tint = if (value.isNotEmpty()) accent else EmotesTheme.TextMuted, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(7.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 11.sp, color = EmotesTheme.TextMuted, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(Modifier.size(16.dp).clip(CircleShape).clickable { onValueChange("") }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    )
}

/** Grille adaptative : autant de colonnes que la largeur le permet. */
@Composable
fun <T> ResponsiveGrid(items: List<T>, minWidth: Dp, gap: Dp = 8.dp, maxColumns: Int = 8, content: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceIn(1, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { item -> content(item, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Accents de texte (« é » → « e ») retirés, pour une recherche tolérante. */
fun normalizeSearch(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()
