package fr.owme.cobblelegacy.emotes.client.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Form
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.PanelTab
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.StatusFilter
import fr.owme.cobblelegacy.emotes.client.preview.EmotePlayerPreview
import fr.owme.cobblelegacy.emotes.client.ui.*
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import java.util.Date
import java.util.UUID

/** Le joueur occupe un peu plus de place que dans la collection : le cadre est plus étroit. */
private const val PREVIEW_FILL = 0.42f

// ═══════════════════════════════════════════════════════════════════════════════
// PANNEAU D'ÉDITION (à droite de la grille)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * L'émote choisie : en-tête, aperçu à gauche et formulaire en onglets à droite (côte à côte, pour
 * garder un grand aperçu même en grande échelle d'interface), puis la barre d'enregistrement.
 */
@Composable
internal fun EditPanel(vm: EmoteEditorViewModel, modifier: Modifier) {
    Column(modifier.background(Color(0x1F000000))) {
        val id = vm.selectedId
        if (id == null) {
            PanelEmptyState(vm)
            return@Column
        }
        // Juste après un enregistrement, le formulaire est relu au catalogue (une image sans) :
        // l'aperçu reste en place pour ne pas relancer l'émote.
        val form = vm.form
        PanelHeader(vm, id, form)
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PreviewColumn(vm, form, Modifier.weight(0.42f).fillMaxHeight())
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                if (form != null) FormColumn(vm, form)
            }
        }
        if (form != null) PanelFooter(vm, form)
    }
}

// ─── Aucune émote choisie ───────────────────────────────────────────────────────

@Composable
private fun PanelEmptyState(vm: EmoteEditorViewModel) {
    val now = System.currentTimeMillis()
    val listings = vm.listings
    Column(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(Amber.copy(alpha = 0.08f)).border(1.dp, Amber.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.TouchApp, null, tint = Amber, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(EmotesI18n.t("editor.panel.empty.title"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextTitle)
        Spacer(Modifier.height(4.dp))
        Text(EmotesI18n.t("editor.panel.empty.hint"), fontSize = 9.sp, color = EmotesTheme.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 300.dp))
        Spacer(Modifier.height(16.dp))
        // Les chiffres du catalogue ; un clic filtre la grille.
        Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile(listings.size, EmotesI18n.t("editor.stats.total"), EmotesTheme.TextTitle, Modifier.weight(1f)) { vm.clearFilters() }
                StatTile(vm.countWith(StatusFilter.PUBLISHED), EmotesI18n.t("editor.status_filter.published"), EmotesTheme.StatusActive, Modifier.weight(1f)) { vm.statusFilter = StatusFilter.PUBLISHED }
                StatTile(vm.countWith(StatusFilter.DRAFTS), EmotesI18n.t("editor.status_filter.drafts"), EmotesTheme.Warning, Modifier.weight(1f)) { vm.statusFilter = StatusFilter.DRAFTS }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile(listings.count { it.forSale }, EmotesI18n.t("editor.stats.for_sale"), EmotesTheme.ShopAccent, Modifier.weight(1f)) { vm.clearFilters() }
                StatTile(listings.count { it.isNew(now) }, EmotesI18n.t("editor.status_filter.new"), EmotesTheme.New, Modifier.weight(1f)) { vm.statusFilter = StatusFilter.NEW }
                StatTile(vm.countWith(StatusFilter.PROMO), EmotesI18n.t("editor.status_filter.promo"), EmotesTheme.Promo, Modifier.weight(1f)) { vm.statusFilter = StatusFilter.PROMO }
            }
        }
    }
}

@Composable
private fun StatTile(value: Int, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(EmotesTheme.SurfaceTiny)
            .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(value.toString(), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 8.sp, color = EmotesTheme.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ─── En-tête ────────────────────────────────────────────────────────────────────

@Composable
private fun PanelHeader(vm: EmoteEditorViewModel, id: UUID, form: Form?) {
    val listing = vm.listing(id)
    val draft = vm.draft(id)
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PanelThumb(vm, id, form, 38.dp)
        Column(Modifier.weight(1f)) {
            Text(
                form?.name?.ifBlank { null } ?: listing?.name ?: draft?.fileName ?: "",
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val (status, color) = when {
                draft != null -> EmotesI18n.t("editor.panel.status.file") to DraftViolet
                listing?.published == true -> EmotesI18n.t("editor.panel.status.published") to EmotesTheme.StatusActive
                else -> EmotesI18n.t("editor.panel.status.draft") to EmotesTheme.Warning
            }
            Text(status, fontSize = 9.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, EmotesTheme.BorderMedium, RoundedCornerShape(8.dp))
                .clickable { vm.closePanel() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Close, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun PanelThumb(vm: EmoteEditorViewModel, id: UUID, form: Form?, size: Dp) {
    val rarity = EmotesTheme.rarityOf(form?.rarity ?: vm.listing(id)?.rarity ?: EmoteRarity.COMMUN).color
    val pending = form?.pendingIcon
    val draft = vm.draft(id)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(rarity.copy(alpha = 0.1f)).border(1.dp, rarity.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        when {
            pending != null -> Image(pending, null, Modifier.fillMaxSize(0.84f), filterQuality = FilterQuality.Low)
            draft != null -> DraftIcon(draft, size)
            else -> EmoteThumbnail(EmoteLibrary.entry(id), Modifier.fillMaxSize(), glow = false)
        }
    }
}

// ─── Aperçu ─────────────────────────────────────────────────────────────────────

@Composable
private fun PreviewColumn(vm: EmoteEditorViewModel, form: Form?, modifier: Modifier) {
    val rarity = EmotesTheme.rarityOf(form?.rarity ?: EmoteRarity.COMMUN).color
    val progress = remember { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val animation = vm.selectedAnimation
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF09090A))
                .background(Brush.radialGradient(listOf(rarity.copy(alpha = 0.13f), rarity.copy(alpha = 0.03f), Color.Transparent)))
                .border(1.dp, rarity.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                .onGloballyPositioned { bounds = it.boundsInRoot() }
        ) {
            EmotePlayerPreview(
                animation = animation,
                yaw = vm.previewYaw,
                pitch = vm.previewPitch,
                zoom = vm.previewZoom,
                onRotate = vm::rotatePreview,
                onZoom = vm::zoomPreview,
                fill = PREVIEW_FILL,
                // Le mannequin est dessiné après l'interface : caché quand un menu passe dessus.
                visible = { vm.menu == null || !vm.menuBounds.overlaps(bounds) },
                paused = vm.previewPaused,
                restartKey = vm.previewRestart,
                onProgress = { progress.floatValue = it },
                modifier = Modifier.fillMaxSize()
            )
            if (animation == null) {
                Text(
                    EmotesI18n.t("editor.preview.loading"), fontSize = 9.sp, color = EmotesTheme.TextDim,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)
                )
            }
            // Dans les coins seulement : le mannequin, dessiné par-dessus, occupe le centre.
            if (form?.playableWhileMoving == true) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Amber.copy(alpha = 0.12f))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.DirectionsRun, null, tint = Amber, modifier = Modifier.size(10.dp))
                    Text(EmotesI18n.t("editor.field.moving"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Amber)
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0x0AFFFFFF))
                    .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(7.dp))
                    .clickable { vm.resetPreviewView() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.CenterFocusStrong, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(12.dp))
            }
        }
        PlaybackBar(vm, progress)
    }
}

/** Lecture, pause, reprise du début et avancement de l'émote dans l'aperçu. */
@Composable
private fun PlaybackBar(vm: EmoteEditorViewModel, progress: State<Float>) {
    val info = vm.selectedInfo
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).background(Amber).clickable { vm.previewPaused = !vm.previewPaused },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (vm.previewPaused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null, tint = OnAmber, modifier = Modifier.size(13.dp))
        }
        Box(
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { vm.restartPreview() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Replay, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(13.dp))
        }
        // Lu au dessin seulement : la barre avance sans recomposer le panneau.
        Box(
            Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0x1AFFFFFF))
                .drawBehind {
                    drawRoundRect(Amber, size = Size(size.width * progress.value, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
        )
        Text(
            info?.let { if (it.loops) EmotesI18n.t("collection.detail.loop") else EmotesI18n.animationLength(it.durationTicks) } ?: "—",
            fontSize = 8.sp, color = EmotesTheme.TextDim, maxLines = 1, modifier = Modifier.padding(end = 6.dp)
        )
    }
}

// ─── Formulaire en onglets ──────────────────────────────────────────────────────

@Composable
private fun FormColumn(vm: EmoteEditorViewModel, form: Form) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            PanelTab.entries.forEach { tab ->
                FormTab(EmotesI18n.t("editor.panel.tab." + tab.name.lowercase()), vm.panelTab == tab, vm.changedIn(tab)) { vm.panelTab = tab }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x12FFFFFF)))
        key(vm.panelTab, form.id) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 6.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                when (vm.panelTab) {
                    PanelTab.INFOS -> InfosTab(vm, form)
                    PanelTab.SALE -> SaleTab(vm, form)
                    PanelTab.OPTIONS -> OptionsTab(vm, form)
                }
            }
        }
    }
}

@Composable
private fun FormTab(label: String, selected: Boolean, changed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clickable { onClick() }
            .drawBehind {
                if (selected) {
                    val line = 2.dp.toPx()
                    drawRect(Amber, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
                }
            }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) EmotesTheme.TextTitle else EmotesTheme.TextDim)
        // Point ambre : des modifications non enregistrées dans cet onglet.
        if (changed) Box(Modifier.size(5.dp).clip(CircleShape).background(Amber))
    }
}

@Composable
private fun InfosTab(vm: EmoteEditorViewModel, form: Form) {
    EditorField(EmotesI18n.t("editor.field.name"), form.name, { v -> vm.update { it.copy(name = v.take(EmoteListing.MAX_NAME_LENGTH)) } })
    EditorField(EmotesI18n.t("editor.field.description"), form.description, { v -> vm.update { it.copy(description = v.take(EmoteListing.MAX_DESCRIPTION_LENGTH)) } })
    EditorField(EmotesI18n.t("editor.field.author"), form.author, { v -> vm.update { it.copy(author = v.take(EmoteListing.MAX_AUTHOR_LENGTH)) } })

    FieldLabel(EmotesI18n.t("editor.field.category"))
    ChipRow {
        Chip(EmotesI18n.t("editor.category.none"), form.categoryId == null, EmotesTheme.TextSoft) { vm.update { it.copy(categoryId = null) } }
        vm.categories.forEach { category ->
            Chip(category.name, form.categoryId == category.id, Amber) { vm.update { it.copy(categoryId = category.id) } }
        }
    }

    FieldLabel(EmotesI18n.t("editor.field.rarity"))
    ChipRow {
        EmoteRarity.entries.forEach { rarity ->
            Chip(EmotesI18n.rarityName(rarity), form.rarity == rarity, EmotesTheme.rarityOf(rarity).color) { vm.update { it.copy(rarity = rarity) } }
        }
    }

    FieldLabel(EmotesI18n.t("editor.field.icon"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelThumb(vm, form.id, form, 32.dp)
        QuickButton(EmotesI18n.t("editor.icon.change"), EmotesTheme.TextPrimary, filled = false) { vm.pickIcon() }
    }
    if (form.pendingIcon != null) Text(EmotesI18n.t("editor.icon.pending"), fontSize = 8.sp, color = Amber)
}

@Composable
private fun SaleTab(vm: EmoteEditorViewModel, form: Form) {
    val now = System.currentTimeMillis()
    val listing = vm.listing(form.id)
    val settings = vm.settings

    FieldLabel(EmotesI18n.t("editor.field.access"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(EmotesI18n.t("editor.access.shop"), form.access == EmoteAccess.SHOP, EmotesTheme.ShopAccent, Modifier.weight(1f)) { vm.update { it.copy(access = EmoteAccess.SHOP) } }
        Chip(EmotesI18n.t("editor.access.free"), form.access == EmoteAccess.FREE, EmotesTheme.StatusActive, Modifier.weight(1f)) { vm.update { it.copy(access = EmoteAccess.FREE) } }
        Chip(EmotesI18n.t("editor.access.exclusive"), form.access == EmoteAccess.EXCLUSIVE, EmotesTheme.Accent, Modifier.weight(1f)) { vm.update { it.copy(access = EmoteAccess.EXCLUSIVE) } }
    }
    Text(EmotesI18n.t("editor.access.hint." + form.access.name.lowercase()), fontSize = 8.sp, color = EmotesTheme.TextMuted)
    if (form.access != EmoteAccess.SHOP) return

    EditorField(
        EmotesI18n.t("editor.field.price"), form.price, { v -> vm.update { it.copy(price = v.filter(Char::isDigit).take(9)) } },
        placeholder = "500", leading = { CrystalGlyph(11.dp) }
    )

    FieldLabel(EmotesI18n.t("editor.field.discount"))
    val percent = form.discountPercent.trim().toIntOrNull() ?: 0
    ChipRow {
        Chip(EmotesI18n.t("editor.discount.off"), percent == 0, EmotesTheme.TextSoft) { vm.update { it.copy(discountPercent = "0", discountDays = "") } }
        listOf(10, 20, 30, 50).forEach { preset ->
            Chip("-$preset %", percent == preset, EmotesTheme.Promo) { vm.update { it.copy(discountPercent = preset.toString()) } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        EditorField(null, form.discountPercent, { v -> vm.update { it.copy(discountPercent = v.filter(Char::isDigit).take(2)) } }, Modifier.weight(1f), trailing = { UnitText("%") })
        UnitText(EmotesI18n.t("editor.bulk.during"))
        EditorField(null, form.discountDays, { v -> vm.update { it.copy(discountDays = v.filter(Char::isDigit).take(3)) } }, Modifier.weight(1f), placeholder = "—", trailing = { UnitText(EmotesI18n.t("editor.field.days_unit")) })
    }
    val active = listing?.itemDiscount(now) ?: 0
    val days = form.discountDays.trim().toIntOrNull()
    val (status, statusColor) = when {
        percent <= 0 -> EmotesI18n.t("editor.discount.hint") to EmotesTheme.TextMuted
        days != null && days > 0 -> EmotesI18n.t("editor.discount.planned", percent, days, DATE.format(Date(now + days * EmoteShopSettings.DAY_MS))) to EmotesTheme.Promo
        active > 0 -> EmotesI18n.t("editor.discount.active", active, DATE.format(Date(listing!!.discountEndsAtMs))) to EmotesTheme.Promo
        else -> EmotesI18n.t("editor.discount.need_days") to EmotesTheme.Warning
    }
    Text(status, fontSize = 8.sp, color = statusColor)
    if (settings.promoActive(now)) {
        Text(EmotesI18n.t("editor.discount.global", settings.promoPercent, DATE.format(Date(settings.promoEndsAtMs))), fontSize = 8.sp, color = EmotesTheme.TextDim)
    }

    // Ce que paient vraiment les joueurs : la plus forte des deux réductions.
    val price = form.price.trim().toLongOrNull() ?: 0L
    if (price > 0) {
        val global = if (settings.promoActive(now)) settings.promoPercent else 0
        val effective = maxOf(percent.coerceIn(0, EmoteShopSettings.MAX_PROMO_PERCENT), global)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(EmotesTheme.ShopAccent.copy(alpha = 0.07f))
                .border(1.dp, EmotesTheme.ShopAccent.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(EmotesI18n.t("editor.field.paid"), fontSize = 8.sp, color = EmotesTheme.TextDim)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PriceTag(EmoteShopSettings.discounted(price, effective), 12.sp, original = if (effective > 0) price else null)
                if (effective > 0) {
                    Text(
                        EmotesI18n.t(if (global >= effective && global > percent) "editor.paid.global" else "editor.paid.item"),
                        fontSize = 8.sp, color = EmotesTheme.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsTab(vm: EmoteEditorViewModel, form: Form) {
    val now = System.currentTimeMillis()
    val listing = vm.listing(form.id)

    OptionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            NewBadge(fontSize = 8.sp)
            Text(EmotesI18n.t("editor.field.new"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle, modifier = Modifier.weight(1f))
            Toggle(form.newEnabled) { enabled -> vm.update { it.copy(newEnabled = enabled) } }
        }
        if (form.newEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UnitText(EmotesI18n.t("editor.bulk.during"))
                EditorField(null, form.newDays, { v -> vm.update { it.copy(newDays = v.filter(Char::isDigit).take(3)) } }, Modifier.width(64.dp), placeholder = "—")
                UnitText(EmotesI18n.t("editor.field.days_unit"))
            }
        }
        Text(
            when {
                !form.newEnabled -> EmotesI18n.t("editor.new.off")
                listing != null && listing.isNew(now) && form.newDays.isBlank() -> EmotesI18n.t("editor.new.until", DATE.format(Date(listing.newUntilMs)))
                form.creating && form.newDays.isBlank() -> EmotesI18n.t("editor.new.default", vm.settings.newDays)
                else -> EmotesI18n.t("editor.new.hint")
            },
            fontSize = 8.sp, color = EmotesTheme.TextMuted
        )
    }

    OptionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.AutoMirrored.Outlined.DirectionsRun, null, tint = if (form.playableWhileMoving) Amber else EmotesTheme.TextDim, modifier = Modifier.size(14.dp))
            Text(EmotesI18n.t("editor.field.moving"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle, modifier = Modifier.weight(1f))
            Toggle(form.playableWhileMoving) { enabled -> vm.update { it.copy(playableWhileMoving = enabled) } }
        }
        Text(
            EmotesI18n.t(if (form.playableWhileMoving) "editor.moving.on" else "editor.moving.off"),
            fontSize = 9.sp, color = if (form.playableWhileMoving) EmotesTheme.TextPrimary else EmotesTheme.TextSoft
        )
        Text(EmotesI18n.t("editor.moving.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted)
    }

    if (listing != null) {
        OptionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.SwapVert, null, tint = EmotesTheme.TextDim, modifier = Modifier.size(14.dp))
                Text(EmotesI18n.t("editor.field.position"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle, modifier = Modifier.weight(1f))
                SmallIconButton(Icons.Outlined.ArrowUpward) { vm.action(EmoteEditorActionPayload.Action.MOVE_UP) }
                SmallIconButton(Icons.Outlined.ArrowDownward) { vm.action(EmoteEditorActionPayload.Action.MOVE_DOWN) }
            }
            Text(EmotesI18n.t("editor.position.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted)
        }
    }

    vm.selectedInfo?.let { info ->
        OptionCard {
            Text(EmotesI18n.t("editor.field.file"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle)
            FileRow(Icons.Outlined.Timer, EmotesI18n.t("editor.file.duration"), if (info.loops) EmotesI18n.t("collection.detail.loop") else EmotesI18n.animationLength(info.durationTicks))
            (vm.draft(form.id)?.data?.size ?: listing?.fileSize)?.let { size ->
                FileRow(Icons.Outlined.Description, EmotesI18n.t("editor.file.size"), EmotesI18n.t("editor.file.size_value", (size + 1023) / 1024))
            }
            val hasIcon = info.hasIcon || form.pendingIcon != null
            FileRow(if (hasIcon) Icons.Outlined.Image else Icons.Outlined.HideImage, EmotesI18n.t("editor.field.icon"), EmotesI18n.t(if (hasIcon) "editor.file.icon" else "editor.file.no_icon"))
            listing?.let { FileRow(Icons.Outlined.CalendarToday, EmotesI18n.t("editor.file.added"), DATE.format(Date(it.addedAtMs))) }
        }
    }
}

@Composable
private fun OptionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EmotesTheme.SurfaceTiny)
            .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

@Composable
private fun FileRow(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = EmotesTheme.TextSubtle, modifier = Modifier.size(11.dp))
        Text(label, fontSize = 9.sp, color = EmotesTheme.TextDim, modifier = Modifier.weight(1f))
        Text(value, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextPrimary, maxLines = 1)
    }
}

@Composable
private fun UnitText(text: String) {
    Text(text, fontSize = 9.sp, color = EmotesTheme.TextSoft, maxLines = 1)
}

// ─── Barre d'enregistrement ─────────────────────────────────────────────────────

@Composable
private fun PanelFooter(vm: EmoteEditorViewModel, form: Form) {
    val listing = vm.listing(form.id)
    val changes = vm.changeCount
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
        Row(
            modifier = Modifier.fillMaxWidth().height(50.dp).background(Amber.copy(alpha = 0.035f)).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (form.creating) {
                FooterButton(EmotesI18n.t("editor.footer.discard"), EmotesTheme.TextSoft, filled = false) { vm.discardDraft() }
            } else {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(EmotesTheme.StatusError.copy(alpha = if (vm.confirmDelete) 0.25f else 0.08f))
                        .border(1.dp, EmotesTheme.StatusError.copy(alpha = if (vm.confirmDelete) 0.7f else 0.3f), RoundedCornerShape(8.dp))
                        .clickable { vm.action(EmoteEditorActionPayload.Action.DELETE) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.DeleteOutline, null, tint = EmotesTheme.StatusError, modifier = Modifier.size(14.dp))
                }
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val (text, color) = when {
                    vm.saving -> EmotesI18n.t("editor.saving") to Amber
                    vm.confirmDelete -> EmotesI18n.t("editor.footer.delete_again") to EmotesTheme.StatusError
                    form.creating -> EmotesI18n.t("editor.footer.not_added") to DraftViolet
                    changes > 0 -> EmotesI18n.t("editor.footer.changes", changes) to EmotesTheme.TextPrimary
                    else -> EmotesI18n.t("editor.footer.saved") to EmotesTheme.TextDim
                }
                if (changes > 0 && !vm.saving && !vm.confirmDelete && !form.creating) Box(Modifier.size(6.dp).clip(CircleShape).background(Amber))
                Text(text, fontSize = 9.sp, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val enabled = !vm.saving
            when {
                form.creating -> {
                    FooterButton(EmotesI18n.t("editor.footer.add_draft"), EmotesTheme.TextPrimary, filled = false, enabled = enabled) { vm.save(publish = false) }
                    FooterButton(EmotesI18n.t("editor.footer.add_publish"), EmotesTheme.ShopAccent, filled = true, enabled = enabled) { vm.save(publish = true) }
                }
                listing?.published == true -> {
                    FooterButton(EmotesI18n.t("editor.footer.unpublish"), EmotesTheme.Warning, filled = false, enabled = enabled) { vm.save(publish = false) }
                    FooterButton(EmotesI18n.t("editor.save"), Amber, filled = true, enabled = enabled) { vm.save() }
                }
                else -> {
                    FooterButton(EmotesI18n.t("editor.save"), EmotesTheme.TextPrimary, filled = false, enabled = enabled) { vm.save() }
                    FooterButton(EmotesI18n.t("editor.footer.save_publish"), EmotesTheme.ShopAccent, filled = true, enabled = enabled) { vm.save(publish = true) }
                }
            }
        }
    }
}

@Composable
private fun FooterButton(label: String, color: Color, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (filled) Color(0xFF0B0B0D) else color,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (filled) color else color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.3f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}
