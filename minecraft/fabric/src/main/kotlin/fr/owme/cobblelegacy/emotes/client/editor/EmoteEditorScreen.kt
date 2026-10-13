package fr.owme.cobblelegacy.emotes.client.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aperso.composite.core.ComposeScreen
import fr.owme.cobblelegacy.emotes.EmoteIconFiles
import fr.owme.cobblelegacy.emotes.client.ui.*
import kotlinx.coroutines.isActive
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

internal val Amber = EmotesTheme.Admin
internal val DATE = SimpleDateFormat("dd/MM HH:mm")

// ═══════════════════════════════════════════════════════════════════════════════
// ÉCRAN
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Éditeur du catalogue d'émotes (administrateurs) : grille des émotes à gauche, panneau d'édition à
 * droite, actions groupées sur une sélection, catégories et promotion globale. Tout est revérifié par
 * le serveur, et partagé par tous les serveurs.
 */
class EmoteEditorScreen(internal val vm: EmoteEditorViewModel, private val back: (() -> Unit)?) :
    ComposeScreen(content = { EmoteScreenRoot { EditorContent(vm) } }) {

    override fun isPauseScreen(): Boolean = false

    /** Fichiers glissés sur la fenêtre : émotes à ajouter, ou nouvelle icône. */
    override fun onFilesDrop(paths: List<Path>) {
        vm.importFiles(paths)
    }

    override fun removed() {
        super.removed()
        vm.dispose()
    }

    override fun onClose() {
        // Échap ferme d'abord un menu ouvert.
        if (vm.menu != null) {
            vm.closeMenu()
            return
        }
        val previous = back
        if (previous != null) previous() else super.onClose()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RACINE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EditorContent(vm: EmoteEditorViewModel) {
    LaunchedEffect(vm) {
        while (isActive) withFrameNanos { vm.sync() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 1280.dp).fillMaxWidth(0.97f)
                .heightIn(max = 760.dp).fillMaxHeight(0.95f)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xF70A0A0A), Color(0xF20A0A0A), Color(0xF70A0A0A))))
                .border(1.dp, Amber.copy(alpha = 0.12f), shape)
        ) {
            EditorTopBar(vm)
            when (vm.tab) {
                EmoteEditorViewModel.Tab.EMOTES -> EmotesTab(vm)
                EmoteEditorViewModel.Tab.SHOP -> ShopTab(vm)
            }
        }
        EditorMenuLayer(vm)
        // L'onglet Émotes affiche ses messages sur la grille.
        if (vm.tab != EmoteEditorViewModel.Tab.EMOTES) vm.toastMessage?.let { Toast(it, vm.toastSuccess, Amber) { vm.dismissToast() } }
    }
}

@Composable
private fun EditorTopBar(vm: EmoteEditorViewModel) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                    .clickable { net.minecraft.client.Minecraft.getInstance().screen?.onClose() }
                    .padding(6.dp, 5.dp, 10.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(EmotesI18n.t("editor.back"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSoft)
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Amber.copy(alpha = 0.1f)).border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Edit, null, tint = Amber, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(EmotesI18n.t("editor.title"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle)
                Text(EmotesI18n.t("editor.subtitle"), fontSize = 9.sp, color = EmotesTheme.TextDim)
            }
            Spacer(Modifier.weight(1f))
            // Onglets en contrôle segmenté
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x0AFFFFFF))
                    .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SegmentTab(EmotesI18n.t("editor.tab.emotes"), vm.listings.size.toString(), vm.tab == EmoteEditorViewModel.Tab.EMOTES) { vm.tab = EmoteEditorViewModel.Tab.EMOTES }
                SegmentTab(EmotesI18n.t("editor.tab.shop"), null, vm.tab == EmoteEditorViewModel.Tab.SHOP) {
                    vm.loadShopFields()
                    vm.tab = EmoteEditorViewModel.Tab.SHOP
                }
            }
            if (vm.tab == EmoteEditorViewModel.Tab.EMOTES) {
                Spacer(Modifier.width(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (vm.importing) Amber.copy(alpha = 0.6f) else Amber)
                        .clickable(enabled = !vm.importing) { vm.pickFiles() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(if (vm.importing) Icons.Outlined.HourglassTop else Icons.Outlined.FileUpload, null, tint = OnAmber, modifier = Modifier.size(13.dp))
                    Text(EmotesI18n.t(if (vm.importing) "editor.importing" else "editor.import"), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnAmber)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Amber.copy(alpha = 0.08f)))
    }
}

/** Texte sur un fond ambre plein. */
internal val OnAmber = Color(0xFF1A1203)

@Composable
private fun SegmentTab(label: String, count: String?, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) Amber else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (active) OnAmber else EmotesTheme.TextSoft)
        count?.let {
            Text(
                it, fontSize = 8.sp, fontWeight = FontWeight.Bold, color = if (active) OnAmber else EmotesTheme.TextDim,
                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (active) Color(0x2E000000) else Color(0x0FFFFFFF)).padding(horizontal = 5.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
internal fun DraftIcon(draft: EmoteEditorViewModel.Draft, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val icon = draft.icon
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(0.86f), filterQuality = FilterQuality.Low)
        else Icon(Icons.Outlined.EmojiPeople, null, tint = DraftViolet.copy(alpha = 0.8f), modifier = Modifier.fillMaxSize(0.55f))
    }
}

/** Fichiers importés pas encore ajoutés au catalogue. */
internal val DraftViolet = Color(0xFFA78BFA)

// ═══════════════════════════════════════════════════════════════════════════════
// ONGLET BOUTIQUE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ShopTab(vm: EmoteEditorViewModel) {
    val now = System.currentTimeMillis()
    val settings = vm.settings
    // Deux colonnes : les réglages à gauche, le poids des fichiers et les chiffres à droite.
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
      Row(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocalOffer, null, tint = EmotesTheme.Promo, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(EmotesI18n.t("editor.promo.title"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(EmotesI18n.t("editor.promo.hint"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorField(EmotesI18n.t("editor.field.discount_percent"), vm.promoPercent, { vm.promoPercent = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f))
                    EditorField(EmotesI18n.t("editor.field.days"), vm.promoDays, { vm.promoDays = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), placeholder = "—")
                }
                Text(
                    if (settings.promoActive(now)) EmotesI18n.t("editor.promo.active", settings.promoPercent, DATE.format(Date(settings.promoEndsAtMs)))
                    else EmotesI18n.t("editor.promo.none"),
                    fontSize = 9.sp, color = if (settings.promoActive(now)) EmotesTheme.Promo else EmotesTheme.TextMuted
                )
                if (settings.promoActive(now)) {
                    SecondaryButton(Icons.Outlined.StopCircle, EmotesI18n.t("editor.promo.stop"), color = EmotesTheme.StatusError) {
                        vm.promoPercent = "0"
                        vm.saveShopSettings()
                    }
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NewBadge(fontSize = 9.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(EmotesI18n.t("editor.newdays.title"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Text(EmotesI18n.t("editor.newdays.hint"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
                EditorField(EmotesI18n.t("editor.field.days"), vm.newDaysSetting, { vm.newDaysSetting = it.filter(Char::isDigit).take(3) }, Modifier.width(140.dp))
            }
            PrimaryButton(Icons.Outlined.Save, EmotesI18n.t("editor.shop.save"), Amber) { vm.saveShopSettings() }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val listings = vm.listings
            FilesCard(vm)
            Card {
                Text(EmotesI18n.t("editor.stats.title"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                DetailRow(EmotesI18n.t("editor.stats.total")) { Text("${listings.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                DetailRow(EmotesI18n.t("editor.stats.published")) { Text("${listings.count { it.published }}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.StatusActive) }
                DetailRow(EmotesI18n.t("editor.stats.for_sale")) { Text("${listings.count { it.forSale }}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.ShopAccent) }
                DetailRow(EmotesI18n.t("editor.stats.new")) { Text("${listings.count { it.isNew(now) }}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.New) }
            }
        }
      }
    }
}

/**
 * Poids des fichiers que téléchargent les joueurs (une fois, puis gardés en cache) : les icônes en font
 * presque tout. Le bouton les fait réduire par le serveur.
 */
@Composable
private fun FilesCard(vm: EmoteEditorViewModel) {
    val listings = vm.listings
    val published = listings.filter { it.published }.sumOf { it.fileSize.toLong() }
    val total = listings.sumOf { it.fileSize.toLong() }
    val heavy = listings.count { vm.iconBytes(it) > EmoteIconFiles.MAX_BYTES }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Image, null, tint = Amber, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
            Text(EmotesI18n.t("editor.files.title"), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text(EmotesI18n.t("editor.files.hint"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
        DetailRow(EmotesI18n.t("editor.files.published")) { Text(megabytes(published), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        DetailRow(EmotesI18n.t("editor.files.total", listings.size)) { Text(megabytes(total), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextSoft) }
        Text(
            if (heavy > 0) EmotesI18n.t("editor.files.heavy", heavy) else EmotesI18n.t("editor.files.light"),
            fontSize = 9.sp, color = if (heavy > 0) EmotesTheme.Warning else EmotesTheme.StatusActive
        )
        ShopButton(
            if (vm.shrinking) Icons.Outlined.HourglassTop else Icons.Outlined.Compress,
            EmotesI18n.t(if (vm.shrinking) "editor.files.shrinking" else "editor.files.shrink"),
            Amber, filled = heavy > 0, enabled = !vm.shrinking && listings.isNotEmpty()
        ) { vm.shrinkIcons() }
    }
}

private fun megabytes(bytes: Long): String =
    if (bytes < 1024 * 1024) EmotesI18n.t("editor.file.size_value", (bytes + 1023) / 1024)
    else EmotesI18n.t("editor.files.mb", "%.1f".format(bytes / 1048576.0))

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(EmotesTheme.SurfaceTiny)
            .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHAMPS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
internal fun FieldLabel(text: String) {
    Text(text.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextDim, letterSpacing = 1.sp)
}

@Composable
internal fun EditorField(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        label?.let { FieldLabel(it) }
        val style = LocalTextStyle.current.merge(TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = EmotesFont.family()))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Amber),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x08FFFFFF))
                        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    leading?.invoke()
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 11.sp, color = EmotesTheme.TextMuted, maxLines = 1)
                        inner()
                    }
                    trailing?.invoke()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { content() }
}

@Composable
internal fun Chip(label: String, selected: Boolean, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) color.copy(alpha = 0.14f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (selected) color.copy(alpha = 0.45f) else EmotesTheme.BorderSubtle, RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = if (selected) color else EmotesTheme.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 17.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (checked) Amber else Color(0x1FFFFFFF))
            .clickable { onChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
internal fun SmallIconButton(icon: ImageVector, tint: Color = EmotesTheme.TextSoft, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(EmotesTheme.SurfaceLight).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
    }
}
