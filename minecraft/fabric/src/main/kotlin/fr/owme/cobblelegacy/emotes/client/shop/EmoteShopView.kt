package fr.owme.cobblelegacy.emotes.client.shop

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import fr.owme.cobblelegacy.emotes.client.preview.EmotePlayerPreview
import fr.owme.cobblelegacy.emotes.client.ui.*
import kotlinx.coroutines.isActive
import java.util.UUID

private val ShopAccent = EmotesTheme.ShopAccent
private val PromoColor = EmotesTheme.Promo
private val Surface = Color(0x05FFFFFF)
private val SurfaceStrong = Color(0x0AFFFFFF)
private val Border = Color(0x0FFFFFFF)
private val TextMuted = Color(0x59FFFFFF)
private val TextSoft = Color(0x99FFFFFF)

private val CARD_MIN_WIDTH = 118.dp
private val CATEGORY_MIN_WIDTH = 210.dp
private val GRID_GAP = 10.dp

/** La boutique d'émotes, hébergée par le Pokématos ou par [EmoteShopScreen]. */
@Composable
internal fun EmoteShopView(host: EmoteShopHost, modifier: Modifier, initialEmote: UUID?) {
    val vm = rememberEmoteShopViewModel(host, initialEmote)
    LaunchedEffect(vm) {
        while (isActive) withFrameNanos { vm.sync() }
    }
    // Les aperçus 3D sont dessinés par-dessus Compose : masqués sous une fenêtre de l'hôte.
    val hostRef = rememberUpdatedState(host)
    val glVisible = remember { { !hostRef.value.overlayOpen } }

    WithEmotesFont {
        Column(modifier.fillMaxSize()) {
            ShopTopBar(vm)
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val selected = vm.selected
                when {
                    !vm.serverSupported -> EmptyState(Icons.Outlined.CloudOff, EmotesI18n.t("shop.unavailable"))
                    selected != null -> EmoteDetail(vm, host, selected, glVisible)
                    else -> Catalog(vm, host, glVisible)
                }
            }
        }
    }
}

// ─── Barre du haut ───────────────────────────────────────────────────────────────

@Composable
private fun ShopTopBar(vm: EmoteShopViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(EmotesI18n.t("shop.title"), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(EmotesI18n.t("shop.subtitle"), fontSize = 9.sp, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vm.isAdmin) {
            Spacer(Modifier.width(10.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(EmotesTheme.Admin.copy(alpha = 0.1f))
                    .border(1.dp, EmotesTheme.Admin.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable { ComposeEmoteScreens.openEditor(vm.selectedId) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Outlined.Edit, null, tint = EmotesTheme.Admin, modifier = Modifier.size(12.dp))
                Text(EmotesI18n.t("shop.manage"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.Admin, maxLines = 1)
            }
        }
        Spacer(Modifier.width(10.dp))
        ModeToggle(vm)
        Spacer(Modifier.width(10.dp))
        SearchField(vm.query, vm::updateQuery, EmotesI18n.t("shop.search"), ShopAccent, Modifier.width(190.dp))
    }
}

@Composable
private fun ModeToggle(vm: EmoteShopViewModel) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        ModeSegment(Icons.Outlined.FolderCopy, EmotesI18n.t("shop.mode.categories"), vm.mode == EmoteShopViewModel.Mode.CATEGORIES && !vm.searching) {
            vm.clearQuery()
            vm.selectMode(EmoteShopViewModel.Mode.CATEGORIES)
        }
        ModeSegment(Icons.Outlined.Apps, EmotesI18n.t("shop.mode.all"), vm.mode == EmoteShopViewModel.Mode.ALL && !vm.searching) {
            vm.clearQuery()
            vm.selectMode(EmoteShopViewModel.Mode.ALL)
        }
    }
}

@Composable
private fun ModeSegment(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ShopAccent.copy(alpha = 0.14f) else Color.Transparent)
            .border(1.dp, if (active) ShopAccent.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (active) ShopAccent else TextMuted, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color.White else TextSoft, maxLines = 1)
    }
}

// ─── Catalogue ───────────────────────────────────────────────────────────────────

@Composable
private fun Catalog(vm: EmoteShopViewModel, host: EmoteShopHost, glVisible: () -> Boolean) {
    val openCategory = vm.openCategory
    val viewKey = when {
        vm.searching -> "search"
        vm.mode == EmoteShopViewModel.Mode.ALL -> "all"
        openCategory != null -> "category:${openCategory.id}"
        else -> "root"
    }
    key(viewKey) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (vm.promoActive && !vm.searching) PromoBanner(vm)
            if (!vm.shopOpen) Notice(EmotesI18n.t("shop.closed"), EmotesTheme.Warning)
            when {
                vm.searching -> {
                    val results = vm.visibleEntries
                    SectionTitle(EmotesI18n.t("shop.search.results", results.size, vm.query.trim()))
                    if (results.isEmpty()) EmptyState(Icons.Outlined.SearchOff, EmotesI18n.t("shop.search.empty"))
                    else EmoteGrid(vm, host, results, glVisible)
                }
                vm.mode == EmoteShopViewModel.Mode.ALL -> {
                    val all = vm.visibleEntries
                    SectionTitle(EmotesI18n.t("shop.all.title", all.size))
                    if (all.isEmpty()) EmptyState(Icons.Outlined.EmojiPeople, EmotesI18n.t("shop.empty"))
                    else EmoteGrid(vm, host, all, glVisible)
                }
                openCategory != null -> {
                    CategoryHeader(vm, openCategory)
                    EmoteGrid(vm, host, vm.visibleEntries, glVisible)
                }
                else -> {
                    val fresh = vm.newEntries
                    if (fresh.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NewBadge(fontSize = 8.sp)
                            Spacer(Modifier.width(8.dp))
                            SectionTitle(EmotesI18n.t("shop.new.title", fresh.size))
                        }
                        EmoteGrid(vm, host, fresh, glVisible)
                    }
                    val categories = vm.categories
                    SectionTitle(EmotesI18n.t("shop.categories.title"))
                    if (categories.isEmpty()) EmptyState(Icons.Outlined.EmojiPeople, EmotesI18n.t("shop.empty"))
                    else ResponsiveGrid(categories, CATEGORY_MIN_WIDTH, GRID_GAP) { category, cardModifier ->
                        CategoryCard(vm, host, category, cardModifier)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun PromoBanner(vm: EmoteShopViewModel) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(PromoColor.copy(alpha = 0.16f), PromoColor.copy(alpha = 0.04f))))
            .border(1.dp, PromoColor.copy(alpha = 0.4f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.LocalOffer, null, tint = PromoColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(EmotesI18n.t("shop.promo.all", vm.settings.promoPercent), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.Schedule, null, tint = PromoColor, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(EmotesI18n.t("shop.promo.ends_in", EmotesI18n.remaining(vm.promoRemainingMs)), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = PromoColor)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0x4DFFFFFF), letterSpacing = 1.sp)
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(28.dp))
        Text(text, fontSize = 11.sp, color = TextMuted, textAlign = TextAlign.Center)
    }
}

// ─── Catégories ──────────────────────────────────────────────────────────────────

@Composable
private fun CategoryCard(vm: EmoteShopViewModel, host: EmoteShopHost, category: EmoteShopViewModel.ShopCategory, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val newCount = category.entries.count { it.isNew }
    Column(
        modifier = modifier
            .clip(shape)
            .background(Surface)
            .border(1.dp, ShopAccent.copy(alpha = 0.18f), shape)
            .clickable { vm.openCategory(category.id) }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Aperçu : les premières émotes de la catégorie.
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            category.entries.take(4).forEach { entry ->
                EmoteThumbnail(entry, Modifier.fillMaxHeight().aspectRatio(1f), locked = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, null, tint = ShopAccent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(category.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (newCount > 0) {
                NewBadge()
                Spacer(Modifier.width(4.dp))
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = TextMuted, modifier = Modifier.size(14.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(EmotesI18n.t("shop.count", category.entries.size), fontSize = 9.sp, color = TextMuted)
            Spacer(Modifier.weight(1f))
            vm.minPriceOf(category)?.let { minPrice ->
                Text(EmotesI18n.t("shop.from") + " ", fontSize = 9.sp, color = TextMuted)
                PriceTag(minPrice, 10.sp) { host.CrystalIcon(it) }
            }
        }
    }
}

@Composable
private fun CategoryHeader(vm: EmoteShopViewModel, category: EmoteShopViewModel.ShopCategory) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        BackButton(EmotesI18n.t("shop.categories.back")) { vm.closeCategory() }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.Outlined.Folder, null, tint = ShopAccent, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(category.name, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(EmotesI18n.t("shop.count", category.entries.size), fontSize = 10.sp, color = TextMuted)
    }
}

// ─── Émotes ──────────────────────────────────────────────────────────────────────

@Composable
private fun EmoteGrid(vm: EmoteShopViewModel, host: EmoteShopHost, entries: List<EmoteEntry>, glVisible: () -> Boolean) {
    ResponsiveGrid(entries, CARD_MIN_WIDTH, GRID_GAP) { entry, cardModifier ->
        ShopCard(vm, host, entry, glVisible, cardModifier)
    }
}

@Composable
private fun ShopCard(vm: EmoteShopViewModel, host: EmoteShopHost, entry: EmoteEntry, glVisible: () -> Boolean, modifier: Modifier) {
    val rarity = EmotesTheme.rarityOf(entry.rarity)
    val owned = vm.isOwned(entry)
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (hovered) rarity.color.copy(alpha = 0.05f) else Surface)
            .border(1.dp, rarity.color.copy(alpha = if (hovered) 0.35f else 0.16f), shape)
            .hoverable(interaction)
            .clickable { vm.open(entry) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                owned -> Icon(Icons.Outlined.CheckCircle, null, tint = EmotesTheme.StatusActive, modifier = Modifier.size(11.dp))
                vm.discountOf(entry) > 0 -> PromoBadge(vm.discountOf(entry))
            }
            Spacer(Modifier.weight(1f))
            if (entry.isNew) {
                NewBadge()
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(6.dp).background(rarity.color, CircleShape))
        }
        Spacer(Modifier.height(4.dp))
        // Au survol, la vignette laisse place au joueur qui fait l'émote.
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp), contentAlignment = Alignment.Center) {
            if (hovered && entry.ready) {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(rarity.color.copy(alpha = 0.14f), Color.Transparent))))
                EmotePlayerPreview(animation = entry.animation, fill = 0.62f, visible = glVisible, modifier = Modifier.fillMaxSize())
            } else {
                EmoteThumbnail(entry, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFE5E5E5),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.height(16.dp), contentAlignment = Alignment.Center) {
            val price = vm.priceOf(entry)
            when {
                owned -> Text(EmotesI18n.t(if (entry.listing?.access == EmoteAccess.FREE) "shop.free" else "shop.owned"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.StatusActive)
                price != null -> PriceTag(price, 11.sp, original = entry.listing?.price) { host.CrystalIcon(it) }
                else -> Text(EmotesI18n.t("shop.exclusive"), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = TextMuted)
            }
        }
    }
}

// ─── Fiche d'une émote ───────────────────────────────────────────────────────────

@Composable
private fun EmoteDetail(vm: EmoteShopViewModel, host: EmoteShopHost, entry: EmoteEntry, glVisible: () -> Boolean) {
    val rarity = EmotesTheme.rarityOf(entry.rarity)
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                BackButton(EmotesI18n.t("shop.back")) { vm.closeDetails() }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(SurfaceStrong).clickable { vm.resetPreview() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Refresh, EmotesI18n.t("collection.preview.reset"), tint = TextSoft, modifier = Modifier.size(13.dp))
                }
            }
            // Le joueur fait l'émote. Rendu 3D par-dessus Compose : rien ne doit le recouvrir.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.radialGradient(listOf(rarity.color.copy(alpha = 0.09f), rarity.color.copy(alpha = 0.02f), Color.Transparent)))
                    .border(1.dp, rarity.color.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            ) {
                if (entry.ready) {
                    EmotePlayerPreview(
                        animation = entry.animation,
                        yaw = vm.previewYaw,
                        pitch = vm.previewPitch,
                        zoom = vm.previewZoom,
                        onRotate = vm::rotatePreview,
                        onZoom = vm::zoomPreview,
                        visible = glVisible,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.CloudDownload, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(24.dp))
                        Text(EmotesI18n.t("shop.loading"), fontSize = 10.sp, color = TextMuted)
                    }
                }
            }
            Text(EmotesI18n.t("collection.preview.hint"), fontSize = 8.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(entry.name, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(EmotesI18n.rarityName(entry.rarity).uppercase(), rarity.color)
                if (entry.isNew) NewBadge(fontSize = 8.sp)
            }
            if (entry.description.isNotBlank()) Text(entry.description, fontSize = 10.sp, color = TextSoft)
            Spacer(Modifier.height(2.dp))

            vm.categoryName(entry)?.let { InfoRow(Icons.Outlined.Folder, EmotesI18n.t("collection.detail.category"), it) }
            if (entry.author.isNotBlank()) InfoRow(Icons.Outlined.Person, EmotesI18n.t("collection.detail.author"), entry.author)
            InfoRow(
                Icons.Outlined.Timer, EmotesI18n.t("collection.detail.duration"),
                if (entry.loops) EmotesI18n.t("collection.detail.loop") else EmotesI18n.animationLength(entry.durationTicks)
            )

            Spacer(Modifier.height(4.dp))
            PurchaseBlock(vm, host, entry)
            vm.feedback?.takeIf { it.target == entry.id }?.let { feedback ->
                Text(
                    feedback.message, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = if (feedback.success) ShopAccent else EmotesTheme.StatusError, modifier = Modifier.fillMaxWidth()
                )
            }
            if (vm.isAdmin) {
                SecondaryButton(Icons.Outlined.Edit, EmotesI18n.t("shop.edit_emote"), color = EmotesTheme.Admin) { ComposeEmoteScreens.openEditor(entry.id) }
            }
        }
    }
}

@Composable
private fun PurchaseBlock(vm: EmoteShopViewModel, host: EmoteShopHost, entry: EmoteEntry) {
    val shape = RoundedCornerShape(12.dp)
    val price = vm.priceOf(entry)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Surface)
            .border(1.dp, Border, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when {
            vm.isOwned(entry) -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = EmotesTheme.StatusActive, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        EmotesI18n.t(if (entry.listing?.access == EmoteAccess.FREE) "shop.detail.free" else "shop.detail.owned"),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.StatusActive
                    )
                }
                ShopButton(Icons.Outlined.DonutLarge, EmotesI18n.t("shop.detail.collection"), ShopAccent, filled = true) {
                    ComposeEmoteScreens.openCollection(entry.id, null)
                }
            }
            price != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(EmotesI18n.t("shop.detail.price"), fontSize = 11.sp, color = TextSoft)
                    Spacer(Modifier.weight(1f))
                    PriceTag(price, 18.sp, original = entry.listing?.price) { host.CrystalIcon(it) }
                }
                val discount = vm.discountOf(entry)
                if (discount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PromoBadge(discount)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Schedule, null, tint = PromoColor, modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(EmotesI18n.t("shop.promo.ends_in", EmotesI18n.remaining(vm.discountRemainingMs(entry))), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = PromoColor)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(EmotesI18n.t("shop.detail.balance"), fontSize = 9.sp, color = TextMuted)
                    Spacer(Modifier.weight(1f))
                    PriceTag(host.crystals.coerceAtLeast(0), 10.sp, color = TextSoft) { host.CrystalIcon(it) }
                }
                when {
                    !vm.shopOpen -> ShopButton(Icons.Outlined.Block, EmotesI18n.t("shop.closed_short"), TextMuted, filled = false, enabled = false) {}
                    vm.confirmingBuy -> {
                        Text(EmotesI18n.t("shop.detail.confirm_text", entry.name, EmotesI18n.crystals(price)), fontSize = 9.sp, color = TextSoft)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShopButton(null, EmotesI18n.t("shop.cancel"), TextSoft, filled = false, modifier = Modifier.weight(1f)) { vm.cancelBuy() }
                            ShopButton(Icons.Outlined.CheckCircle, EmotesI18n.t("shop.detail.confirm"), ShopAccent, filled = true, enabled = !vm.busy, modifier = Modifier.weight(1f)) { vm.buy(entry) }
                        }
                    }
                    host.crystals in 0 until price ->
                        ShopButton(Icons.Outlined.Lock, EmotesI18n.t("shop.missing", EmotesI18n.crystals(price - host.crystals)), TextMuted, filled = false, enabled = false) {}
                    else -> ShopButton(Icons.Outlined.ShoppingCart, EmotesI18n.t("shop.detail.buy"), ShopAccent, filled = true, enabled = !vm.busy) { vm.buy(entry) }
                }
            }
            else -> Text(EmotesI18n.t("shop.detail.exclusive"), fontSize = 10.sp, color = TextSoft)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextMuted, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 10.sp, color = TextSoft)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x08FFFFFF))
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = TextSoft, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = TextSoft)
    }
}
