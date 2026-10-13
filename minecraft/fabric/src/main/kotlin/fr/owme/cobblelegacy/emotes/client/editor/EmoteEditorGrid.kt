package fr.owme.cobblelegacy.emotes.client.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Menu
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Sort
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.StatusFilter
import fr.owme.cobblelegacy.emotes.client.ui.*
import fr.owme.cobblelegacy.emotes.network.EmoteEditorBulkPayload
import kotlin.math.roundToInt

private val CARD_HEIGHT = 150.dp
private val CardShape = RoundedCornerShape(12.dp)

// ═══════════════════════════════════════════════════════════════════════════════
// ONGLET ÉMOTES : grille à gauche, panneau d'édition à droite
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
internal fun EmotesTab(vm: EmoteEditorViewModel) {
    Column(Modifier.fillMaxSize()) {
        CatalogToolbar(vm)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(0.56f).fillMaxHeight()) {
                CatalogGrid(vm)
                if (vm.selection.isNotEmpty()) BulkBar(vm, Modifier.align(Alignment.BottomCenter))
                // Sur la grille seulement (au-dessus de la barre d'actions) : le panneau et ses boutons restent visibles.
                vm.toastMessage?.let { message ->
                    Box(Modifier.fillMaxSize().padding(bottom = if (vm.selection.isNotEmpty()) 56.dp else 0.dp)) {
                        Toast(message, vm.toastSuccess, Amber) { vm.dismissToast() }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Amber.copy(alpha = 0.07f)))
            EditPanel(vm, Modifier.weight(0.44f).fillMaxHeight())
        }
    }
}

// ─── Barre de filtres ───────────────────────────────────────────────────────────

@Composable
private fun CatalogToolbar(vm: EmoteEditorViewModel) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SearchField(vm.query, { vm.query = it }, EmotesI18n.t("editor.search.placeholder"), Amber, Modifier.width(180.dp))
            MenuButton(vm, Menu.STATUS, EmotesI18n.t("editor.filter.status"), statusLabel(vm.statusFilter), vm.statusFilter != StatusFilter.ALL)
            MenuButton(vm, Menu.CATEGORY, EmotesI18n.t("editor.filter.category"), categoryLabel(vm), vm.categoryFilter != null)
            MenuButton(vm, Menu.SORT, EmotesI18n.t("editor.filter.sort"), sortLabel(vm.sort), false)
            if (vm.hasFilters) {
                Text(
                    EmotesI18n.t("editor.filter.clear"), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSoft,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.clearFilters() }.padding(horizontal = 6.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Text(EmotesI18n.t("editor.count", vm.visibleListings.size), fontSize = 9.sp, color = EmotesTheme.TextDim)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0AFFFFFF)))
    }
}

/** Bouton qui ouvre un menu déroulant, dessiné par [EditorMenuLayer] sous le bouton. */
@Composable
private fun MenuButton(vm: EmoteEditorViewModel, menu: Menu, label: String, value: String, active: Boolean, color: Color = Amber) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val open = vm.menu == menu
    val highlighted = active || open
    Row(
        modifier = Modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) color.copy(alpha = 0.1f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (highlighted) color.copy(alpha = 0.35f) else EmotesTheme.BorderMedium, RoundedCornerShape(8.dp))
            .clickable { vm.openMenu(menu, bounds) }
            .padding(start = 9.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, fontSize = 9.sp, color = EmotesTheme.TextDim)
        Text(value, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = if (active) color else EmotesTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = EmotesTheme.TextDim, modifier = Modifier.size(12.dp))
    }
}

private fun statusLabel(filter: StatusFilter): String = EmotesI18n.t("editor.status_filter." + filter.name.lowercase())

private fun sortLabel(sort: Sort): String = EmotesI18n.t("editor.sort." + sort.name.lowercase())

private fun categoryLabel(vm: EmoteEditorViewModel): String = when (val id = vm.categoryFilter) {
    null -> EmotesI18n.t("editor.filter.all_categories")
    "" -> EmotesI18n.t("editor.filter.no_category")
    else -> vm.categories.firstOrNull { it.id == id }?.name ?: EmotesI18n.t("editor.filter.all_categories")
}

// ─── Grille ─────────────────────────────────────────────────────────────────────

@Composable
private fun CatalogGrid(vm: EmoteEditorViewModel) {
    val listings = vm.visibleListings
    val drafts = vm.drafts
    val state = rememberLazyGridState()
    // Fichiers importés : la grille remonte sur leur bandeau (elle garderait sinon la ligne affichée avant).
    val draftCount = drafts.size
    LaunchedEffect(draftCount) { if (draftCount > 0) state.scrollToItem(0) }
    if (listings.isEmpty() && drafts.isEmpty()) {
        if (vm.listings.isEmpty()) EmptyCatalog(vm) else NoMatch(vm)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (vm.selection.isNotEmpty()) 72.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (drafts.isNotEmpty()) {
            item(key = "drafts-banner", span = { GridItemSpan(maxLineSpan) }) { DraftsBanner(vm) }
            items(drafts.toList(), key = { "d-${it.id}" }) { DraftCard(vm, it) }
        }
        items(listings, key = { "l-${it.id}" }) { ListingCard(vm, it) }
    }
}

@Composable
private fun ListingCard(vm: EmoteEditorViewModel, listing: EmoteListing) {
    val now = System.currentTimeMillis()
    val editing = vm.selectedId == listing.id
    val checked = vm.isSelected(listing.id)
    val selecting = vm.selection.isNotEmpty()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rarity = EmotesTheme.rarityOf(listing.rarity)
    val discount = vm.discountOf(listing, now)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(CardShape)
            .background(
                when {
                    editing -> Amber.copy(alpha = 0.09f)
                    checked -> Amber.copy(alpha = 0.05f)
                    hovered -> EmotesTheme.SurfaceLight
                    else -> EmotesTheme.SurfaceTiny
                }
            )
            .border(
                1.dp,
                when {
                    editing -> Amber.copy(alpha = 0.65f)
                    checked -> Amber.copy(alpha = 0.4f)
                    hovered -> EmotesTheme.BorderMedium
                    else -> EmotesTheme.BorderSubtle
                },
                CardShape
            )
            .hoverable(interaction)
            // En sélection multiple, un clic coche ou décoche la carte.
            .clickable { if (selecting) vm.toggleSelection(listing.id) else vm.select(listing.id) }
            .padding(8.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (checked || hovered || selecting) SelectBox(checked) { vm.toggleSelection(listing.id) }
                StatusPill(listing.published)
                Spacer(Modifier.weight(1f))
                if (discount > 0) PromoBadge(discount)
                if (listing.isNew(now)) NewBadge()
            }
            Spacer(Modifier.height(6.dp))
            EmoteThumbnail(EmoteLibrary.entry(listing.id), Modifier.size(56.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                listing.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(EmotesI18n.rarityName(listing.rarity), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = rarity.color)
                Text("·", fontSize = 8.sp, color = EmotesTheme.TextMuted)
                when (listing.access) {
                    EmoteAccess.FREE -> Text(EmotesI18n.t("collection.free"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.StatusActive)
                    EmoteAccess.EXCLUSIVE -> Text(EmotesI18n.t("collection.exclusive"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.Accent)
                    EmoteAccess.SHOP -> PriceTag(
                        EmoteShopSettings.discounted(listing.price, discount), 9.sp,
                        color = if (discount > 0) EmotesTheme.ShopAccent else EmotesTheme.TextPrimary,
                        original = if (discount > 0) listing.price else null
                    )
                }
            }
        }
        // Publier ou dépublier d'un clic, sans ouvrir le panneau.
        if (hovered && !selecting) {
            QuickButton(
                EmotesI18n.t(if (listing.published) "editor.quick.unpublish" else "editor.quick.publish"),
                if (listing.published) EmotesTheme.Warning else EmotesTheme.ShopAccent,
                filled = !listing.published,
                modifier = Modifier.align(Alignment.BottomCenter)
            ) { vm.quickPublish(listing.id, !listing.published) }
        }
    }
}

@Composable
private fun DraftCard(vm: EmoteEditorViewModel, draft: EmoteEditorViewModel.Draft) {
    val editing = vm.selectedId == draft.id
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CARD_HEIGHT)
            .clip(CardShape)
            .background(DraftViolet.copy(alpha = if (editing) 0.12f else 0.05f))
            .border(1.dp, DraftViolet.copy(alpha = if (editing) 0.7f else 0.3f), CardShape)
            .clickable { vm.select(draft.id) }
            .padding(8.dp)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(EmotesI18n.t("editor.draft.badge"), DraftViolet)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(DraftViolet.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                DraftIcon(draft, 50.dp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                draft.parsed.name.ifBlank { draft.fileName }, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(3.dp))
            Text(EmotesI18n.t("editor.draft.meta", (draft.data.size + 1023) / 1024), fontSize = 8.sp, color = DraftViolet)
        }
    }
}

@Composable
private fun DraftsBanner(vm: EmoteEditorViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DraftViolet.copy(alpha = 0.07f))
            .border(1.dp, DraftViolet.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Outlined.UploadFile, null, tint = DraftViolet, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            Text(EmotesI18n.t("editor.drafts.title", vm.drafts.size), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle)
            Text(EmotesI18n.t("editor.drafts.hint"), fontSize = 8.sp, color = EmotesTheme.TextDim)
        }
        QuickButton(EmotesI18n.t("editor.drafts.save_all", vm.drafts.size), DraftViolet, filled = true) { vm.saveAllDrafts() }
    }
}

@Composable
private fun EmptyCatalog(vm: EmoteEditorViewModel) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Amber.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .background(Amber.copy(alpha = 0.03f))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.FileUpload, null, tint = Amber, modifier = Modifier.size(30.dp))
            Text(EmotesI18n.t("editor.empty.title"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextTitle)
            Text(EmotesI18n.t("editor.empty.hint"), fontSize = 10.sp, color = EmotesTheme.TextSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            QuickButton(EmotesI18n.t("editor.import"), Amber, filled = true) { vm.pickFiles() }
        }
    }
}

@Composable
private fun NoMatch(vm: EmoteEditorViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.SearchOff, null, tint = Color(0x40FFFFFF), modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text(EmotesI18n.t("editor.nomatch.title"), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSoft)
        Spacer(Modifier.height(10.dp))
        QuickButton(EmotesI18n.t("editor.filter.clear"), Amber, filled = false) { vm.clearFilters() }
    }
}

// ─── Petits éléments de carte ───────────────────────────────────────────────────

@Composable
private fun SelectBox(checked: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(15.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) Amber else Color(0x1AFFFFFF))
            .border(1.dp, if (checked) Amber else Color(0x4DFFFFFF), RoundedCornerShape(4.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (checked) Icon(Icons.Outlined.Check, null, tint = OnAmber, modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun StatusPill(published: Boolean) {
    Pill(
        EmotesI18n.t(if (published) "editor.status.published" else "editor.status.draft"),
        if (published) EmotesTheme.StatusActive else EmotesTheme.Warning
    )
}

@Composable
internal fun Pill(text: String, color: Color) {
    Text(
        text, fontSize = 7.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** Petit bouton : plein (action principale) ou teinté. */
@Composable
internal fun QuickButton(label: String, color: Color, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (filled) Color(0xFF0B0B0D) else color,
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (filled) color else color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.35f), RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}

// ─── Actions groupées ───────────────────────────────────────────────────────────

@Composable
private fun BulkBar(vm: EmoteEditorViewModel, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val confirming = vm.confirmBulkDelete
    BoxWithConstraints(modifier.padding(10.dp).fillMaxWidth()) {
        // Grille étroite (grande échelle d'interface) : publier et dépublier passent en icônes.
        val compact = maxWidth < 470.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .shadow(16.dp, shape)
                .clip(shape)
                .background(Color(0xFF19191C))
                .border(1.dp, (if (confirming) EmotesTheme.Danger else Amber).copy(alpha = 0.4f), shape)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                vm.selection.size.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = OnAmber,
                modifier = Modifier.clip(CircleShape).background(if (confirming) EmotesTheme.Danger else Amber).padding(horizontal = 7.dp, vertical = 2.dp)
            )
            if (confirming) {
                // La suppression se confirme dans la barre même.
                Text(
                    EmotesI18n.t("editor.bulk.delete.question", vm.selection.size), fontSize = 9.sp, color = EmotesTheme.TextPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 3.dp)
                )
                QuickButton(EmotesI18n.t("editor.bulk.cancel"), EmotesTheme.TextSoft, filled = false) { vm.cancelBulkDelete() }
                QuickButton(EmotesI18n.t("editor.bulk.delete_confirm"), EmotesTheme.Danger, filled = true) { vm.bulk(EmoteEditorBulkPayload.Action.DELETE) }
                return@Row
            }
            BulkIcon(Icons.Outlined.SelectAll, EmotesTheme.TextSoft) { vm.selectAllVisible() }
            if (compact) {
                BulkIcon(Icons.Outlined.Visibility, EmotesTheme.ShopAccent) { vm.bulk(EmoteEditorBulkPayload.Action.PUBLISH) }
                BulkIcon(Icons.Outlined.VisibilityOff, EmotesTheme.Warning) { vm.bulk(EmoteEditorBulkPayload.Action.UNPUBLISH) }
            } else {
                QuickButton(EmotesI18n.t("editor.bulk.publish"), EmotesTheme.ShopAccent, filled = true) { vm.bulk(EmoteEditorBulkPayload.Action.PUBLISH) }
                QuickButton(EmotesI18n.t("editor.bulk.unpublish"), EmotesTheme.Warning, filled = false) { vm.bulk(EmoteEditorBulkPayload.Action.UNPUBLISH) }
            }
            BulkMenuButton(vm, Menu.BULK_CATEGORY, EmotesI18n.t("editor.bulk.category"), EmotesTheme.TextPrimary)
            BulkMenuButton(vm, Menu.BULK_PRICE, EmotesI18n.t("editor.bulk.price"), EmotesTheme.TextPrimary)
            BulkMenuButton(vm, Menu.BULK_DISCOUNT, EmotesI18n.t("editor.bulk.discount"), EmotesTheme.Promo)
            BulkMenuButton(vm, Menu.BULK_NEW, EmotesI18n.t("editor.bulk.new"), EmotesTheme.New)
            Spacer(Modifier.weight(1f))
            BulkIcon(Icons.Outlined.DeleteOutline, EmotesTheme.StatusError) { vm.bulk(EmoteEditorBulkPayload.Action.DELETE) }
            BulkIcon(Icons.Outlined.Close, EmotesTheme.TextSoft) { vm.clearSelection() }
        }
    }
}

@Composable
private fun BulkMenuButton(vm: EmoteEditorViewModel, menu: Menu, label: String, color: Color) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val open = vm.menu == menu
    Row(
        modifier = Modifier
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .clip(RoundedCornerShape(7.dp))
            .background(if (open) color.copy(alpha = 0.14f) else Color(0x0DFFFFFF))
            .border(1.dp, if (open) color.copy(alpha = 0.45f) else Color(0x14FFFFFF), RoundedCornerShape(7.dp))
            .clickable { vm.openMenu(menu, bounds) }
            .padding(start = 7.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        Icon(Icons.Outlined.ExpandLess, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun BulkIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MENUS DÉROULANTS (au-dessus de tout l'éditeur)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Le menu ouvert, sous (ou au-dessus de) son bouton. Un clic à côté le ferme. Dessiné dans l'arbre de
 * l'écran plutôt qu'en fenêtre popup : sous Composite, c'est le chemin le plus sûr.
 */
@Composable
internal fun EditorMenuLayer(vm: EmoteEditorViewModel) {
    val menu = vm.menu ?: return
    val anchor = vm.menuAnchor
    val outside = remember { MutableInteractionSource() }
    val inside = remember { MutableInteractionSource() }
    var size by remember(menu) { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    BoxWithConstraints(Modifier.fillMaxSize().clickable(interactionSource = outside, indication = null) { vm.closeMenu() }) {
        val maxWidth = constraints.maxWidth
        val maxHeight = constraints.maxHeight
        val x = anchor.left.roundToInt().coerceIn(0, (maxWidth - size.width).coerceAtLeast(0))
        val below = anchor.bottom.roundToInt() + gap
        val y = if (below + size.height <= maxHeight) below else (anchor.top.roundToInt() - gap - size.height).coerceAtLeast(0)
        val shape = RoundedCornerShape(11.dp)
        Column(
            modifier = Modifier
                .offset { IntOffset(x, y) }
                .onSizeChanged { size = it }
                .onGloballyPositioned { vm.menuBounds = it.boundsInRoot() }
                .widthIn(min = 180.dp, max = 260.dp)
                .width(IntrinsicSize.Max)
                .shadow(18.dp, shape)
                .clip(shape)
                .background(Color(0xFF17171A))
                .border(1.dp, Color(0x1FFFFFFF), shape)
                .clickable(interactionSource = inside, indication = null) {}
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            when (menu) {
                Menu.STATUS -> StatusFilter.entries.forEach { filter ->
                    MenuItem(statusLabel(filter), vm.countWith(filter).toString(), vm.statusFilter == filter) {
                        vm.statusFilter = filter
                        vm.closeMenu()
                    }
                }
                Menu.CATEGORY -> {
                    MenuItem(EmotesI18n.t("editor.filter.all_categories"), vm.listings.size.toString(), vm.categoryFilter == null) {
                        vm.categoryFilter = null
                        vm.closeMenu()
                    }
                    MenuItem(EmotesI18n.t("editor.filter.no_category"), vm.listings.count { it.categoryId == null }.toString(), vm.categoryFilter == "") {
                        vm.categoryFilter = ""
                        vm.closeMenu()
                    }
                    vm.categories.forEach { category ->
                        MenuItem(category.name, vm.listings.count { it.categoryId == category.id }.toString(), vm.categoryFilter == category.id) {
                            vm.categoryFilter = category.id
                            vm.closeMenu()
                        }
                    }
                }
                Menu.SORT -> Sort.entries.forEach { sort ->
                    MenuItem(sortLabel(sort), null, vm.sort == sort) {
                        vm.sort = sort
                        vm.closeMenu()
                    }
                }
                Menu.BULK_CATEGORY -> {
                    MenuTitle(EmotesI18n.t("editor.bulk.category.title", vm.selection.size))
                    MenuItem(EmotesI18n.t("editor.filter.no_category"), null, false) { vm.bulk(EmoteEditorBulkPayload.Action.CATEGORY, categoryId = "") }
                    vm.categories.forEach { category ->
                        MenuItem(category.name, null, false) { vm.bulk(EmoteEditorBulkPayload.Action.CATEGORY, categoryId = category.id) }
                    }
                    if (vm.categories.isEmpty()) MenuHint(EmotesI18n.t("editor.bulk.category.none"))
                }
                Menu.BULK_PRICE -> {
                    MenuTitle(EmotesI18n.t("editor.bulk.price.title", vm.selection.size))
                    EditorField(null, vm.bulkPrice, { v -> vm.bulkPrice = v.filter(Char::isDigit).take(9) }, placeholder = "500", trailing = { CrystalGlyph(11.dp) })
                    MenuHint(EmotesI18n.t("editor.bulk.price.hint"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        QuickButton(EmotesI18n.t("editor.bulk.apply"), Amber, filled = true) { vm.applyBulkPrice() }
                    }
                }
                Menu.BULK_DISCOUNT -> {
                    MenuTitle(EmotesI18n.t("editor.bulk.discount.title", vm.selection.size))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(10, 20, 30, 50).forEach { percent ->
                            Chip("-$percent %", vm.bulkPercent == percent, EmotesTheme.Promo) { vm.bulkPercent = percent }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(EmotesI18n.t("editor.bulk.during"), fontSize = 9.sp, color = EmotesTheme.TextSoft)
                        EditorField(null, vm.bulkDays, { v -> vm.bulkDays = v.filter(Char::isDigit).take(3) }, Modifier.width(56.dp))
                        Text(EmotesI18n.t("editor.field.days_unit"), fontSize = 9.sp, color = EmotesTheme.TextSoft)
                    }
                    MenuHint(EmotesI18n.t("editor.bulk.price.hint"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        QuickButton(EmotesI18n.t("editor.bulk.discount.remove"), EmotesTheme.TextSoft, filled = false) { vm.applyBulkDiscount(remove = true) }
                        QuickButton(EmotesI18n.t("editor.bulk.apply"), EmotesTheme.Promo, filled = true) { vm.applyBulkDiscount(remove = false) }
                    }
                }
                Menu.BULK_NEW -> {
                    MenuTitle(EmotesI18n.t("editor.bulk.new.title", vm.selection.size))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(EmotesI18n.t("editor.bulk.during"), fontSize = 9.sp, color = EmotesTheme.TextSoft)
                        EditorField(null, vm.bulkNewDays, { v -> vm.bulkNewDays = v.filter(Char::isDigit).take(3) }, Modifier.width(56.dp))
                        Text(EmotesI18n.t("editor.field.days_unit"), fontSize = 9.sp, color = EmotesTheme.TextSoft)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                        QuickButton(EmotesI18n.t("editor.bulk.new.remove"), EmotesTheme.TextSoft, filled = false) { vm.applyBulkNew(remove = true) }
                        QuickButton(EmotesI18n.t("editor.bulk.new.apply"), EmotesTheme.New, filled = true) { vm.applyBulkNew(remove = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, trailing: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Amber.copy(alpha = 0.1f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = if (selected) Amber else EmotesTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 9.sp, color = EmotesTheme.TextDim) }
        if (selected) Icon(Icons.Outlined.Check, null, tint = Amber, modifier = Modifier.size(11.dp))
    }
}

@Composable
private fun MenuTitle(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextTitle, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun MenuHint(text: String) {
    Text(text, fontSize = 8.sp, color = EmotesTheme.TextDim, modifier = Modifier.padding(horizontal = 4.dp))
}
