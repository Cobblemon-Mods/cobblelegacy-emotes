package fr.owme.cobblelegacy.emotes.client.ui.collection

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojang.blaze3d.platform.InputConstants
import dev.aperso.composite.core.ComposeScreen
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.EmoteEntry
import fr.owme.cobblelegacy.emotes.client.EmoteSource
import fr.owme.cobblelegacy.emotes.client.WheelConfig
import fr.owme.cobblelegacy.emotes.client.preview.EmotePlayerPreview
import fr.owme.cobblelegacy.emotes.client.ui.*
import fr.owme.cobblelegacy.emotes.client.ui.wheel.EmoteWheel
import io.github.kosmx.emotes.main.network.ClientPacketManager
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

// ═══════════════════════════════════════════════════════════════════════════════
// ÉCRAN
// ═══════════════════════════════════════════════════════════════════════════════

class EmoteCollectionScreen(internal val vm: CollectionViewModel) :
    ComposeScreen(content = { EmoteScreenRoot { CollectionContent(vm) } }) {

    override fun isPauseScreen(): Boolean = false

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Choix d'un raccourci : la touche suivante est prise, Échap retire le raccourci.
        if (vm.capturingKey) {
            val id = vm.selectedId
            if (id != null) vm.bindKey(id, if (keyCode == GLFW.GLFW_KEY_ESCAPE) null else InputConstants.getKey(keyCode, scanCode))
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun onClose() {
        // Venu de la roue pour y placer une émote : on y retourne.
        if (vm.target != null) ComposeEmoteScreens.openWheel() else super.onClose()
    }

    /**
     * Molette sur la petite roue de placement : un cran = une page. Composite étale chaque cran sur
     * plusieurs images, passé à Compose il faisait tourner plusieurs pages. Ailleurs (grille, filtres),
     * le défilement reste celui de Compose.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val bounds = vm.placingWheelBounds
        val scale = minecraft?.window?.guiScale ?: 1.0
        if (vm.placing && bounds != null && bounds.contains(Offset((mouseX * scale).toFloat(), (mouseY * scale).toFloat()))) {
            if (scrollY != 0.0) vm.changePlacingPage(if (scrollY > 0) -1 else 1)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RACINE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CollectionContent(vm: CollectionViewModel) {
    LaunchedEffect(vm) {
        while (isActive) withFrameNanos { vm.sync() }
    }
    val accent = EmotesTheme.Accent

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        val panelShape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 1180.dp).fillMaxWidth(0.96f)
                .heightIn(max = 680.dp).fillMaxHeight(0.94f)
                .clip(panelShape)
                .background(Brush.linearGradient(listOf(Color(0xF70A0A0A), Color(0xF20A0A0A), Color(0xF70A0A0A))))
                .border(1.dp, accent.copy(alpha = 0.094f), panelShape)
        ) {
            TopBar(vm)
            vm.target?.let { AssignBanner(vm, it.page, it.slot) }
            Body(vm)
        }
        vm.toastMessage?.let { Toast(it, vm.toastSuccess) { vm.dismissToast() } }
    }
}

@Composable
private fun TopBar(vm: CollectionViewModel) {
    val accent = EmotesTheme.Accent
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.03f), Color(0x05FFFFFF))))
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.08f))
                    .border(1.dp, accent.copy(alpha = 0.19f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.EmojiPeople, null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(EmotesI18n.t("collection.title"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextTitle)
                Text(EmotesI18n.t("collection.subtitle"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
            }
            Spacer(Modifier.weight(1f))

            if (vm.editorAvailable) {
                TopButton(Icons.Outlined.Edit, EmotesI18n.t("collection.editor"), EmotesTheme.Admin) { ComposeEmoteScreens.openEditor(vm.selectedId) }
                Spacer(Modifier.width(8.dp))
            }
            CountBadge(EmotesI18n.t("collection.badge.unlocked", vm.unlockedCount, vm.entries.size), accent)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(accent.copy(alpha = 0.07f)))
    }
}

@Composable
private fun TopButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

/** Venu de la roue : on choisit l'émote d'une case. */
@Composable
private fun AssignBanner(vm: CollectionViewModel, page: Int, slot: Int) {
    val accent = EmotesTheme.Accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.08f))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.DonutLarge, null, tint = accent, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(8.dp))
        Text(EmotesI18n.t("collection.assign.banner", slot + 1, page + 1), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
        if (WheelConfig.get(page, slot) != null) {
            Text(
                EmotesI18n.t("collection.assign.clear"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.StatusError,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                    vm.assign(page, slot, null)
                    ComposeEmoteScreens.openWheel()
                }.padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            EmotesI18n.t("collection.assign.cancel"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSoft,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { ComposeEmoteScreens.openWheel() }.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun Body(vm: CollectionViewModel) {
    val divider = EmotesTheme.Accent.copy(alpha = 0.063f)
    Row(Modifier.fillMaxSize()) {
        Sidebar(vm)
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        Grid(vm, Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        PreviewPanel(vm)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILTRES
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun Sidebar(vm: CollectionViewModel) {
    val accent = EmotesTheme.Accent
    Column(
        modifier = Modifier
            .width(190.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SearchField(vm.query, { vm.query = it }, EmotesI18n.t("collection.search"), accent, Modifier.fillMaxWidth())

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(EmotesI18n.t("collection.section.show"))
            Spacer(Modifier.height(2.dp))
            FilterButton(EmotesI18n.t("collection.filter.all"), Icons.Outlined.Apps, vm.filter == CollectionViewModel.Filter.ALL, accent, vm.entries.size) {
                vm.selectFilter(CollectionViewModel.Filter.ALL)
            }
            FilterButton(EmotesI18n.t("collection.filter.unlocked"), Icons.Outlined.CheckCircle, vm.filter == CollectionViewModel.Filter.UNLOCKED, EmotesTheme.StatusActive, vm.unlockedCount) {
                vm.selectFilter(CollectionViewModel.Filter.UNLOCKED)
            }
            if (vm.managed) {
                FilterButton(EmotesI18n.t("collection.filter.locked"), Icons.Outlined.Lock, vm.filter == CollectionViewModel.Filter.LOCKED, EmotesTheme.Warning, vm.entries.size - vm.unlockedCount) {
                    vm.selectFilter(CollectionViewModel.Filter.LOCKED)
                }
                val newCount = vm.entries.count { it.isNew }
                if (newCount > 0) {
                    FilterButton(EmotesI18n.t("collection.filter.new"), Icons.Outlined.NewReleases, vm.filter == CollectionViewModel.Filter.NEW, EmotesTheme.New, newCount) {
                        vm.selectFilter(CollectionViewModel.Filter.NEW)
                    }
                }
            }
        }

        val categories = vm.categories
        val hasBase = vm.entries.any { it.source == EmoteSource.BUILTIN }
        val hasLocal = vm.entries.any { it.source == EmoteSource.LOCAL }
        if (categories.isNotEmpty() || hasBase || hasLocal) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(EmotesI18n.t("collection.section.categories"))
                Spacer(Modifier.height(2.dp))
                categories.forEach { category ->
                    FilterButton(category.name, Icons.Outlined.Folder, vm.category == category.id, accent, vm.countIn(category.id)) { vm.toggleCategory(category.id) }
                }
                if (hasBase) {
                    FilterButton(EmotesI18n.t("collection.category.base"), Icons.Outlined.Star, vm.category == CollectionViewModel.BASE, accent, vm.countIn(CollectionViewModel.BASE)) {
                        vm.toggleCategory(CollectionViewModel.BASE)
                    }
                }
                if (hasLocal) {
                    FilterButton(EmotesI18n.t("collection.category.local"), Icons.Outlined.FolderOpen, vm.category == CollectionViewModel.LOCAL, accent, vm.countIn(CollectionViewModel.LOCAL)) {
                        vm.toggleCategory(CollectionViewModel.LOCAL)
                    }
                }
            }
        }

        if (vm.managed) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel(EmotesI18n.t("collection.section.rarity"))
                Spacer(Modifier.height(2.dp))
                EmoteRarity.entries.forEach { rarity -> RarityFilterButton(rarity, vm.rarity == rarity) { vm.toggleRarity(rarity) } }
            }
        }

        if (vm.hasActiveFilter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(EmotesTheme.SurfaceLight)
                    .border(1.dp, EmotesTheme.BorderMedium, RoundedCornerShape(8.dp))
                    .clickable { vm.resetFilters() }
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(EmotesI18n.t("collection.reset_filters"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSubtle)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// GRILLE
// ═══════════════════════════════════════════════════════════════════════════════

private val MIN_CARD_WIDTH = 104.dp

@Composable
private fun Grid(vm: CollectionViewModel, modifier: Modifier) {
    val emotes = vm.filtered
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(EmotesI18n.t("collection.count", emotes.size), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextMuted)
            if (vm.downloading > 0) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.CloudDownload, null, tint = EmotesTheme.TextMuted, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(3.dp))
                Text(EmotesI18n.t("collection.downloading", vm.downloading), fontSize = 9.sp, color = EmotesTheme.TextMuted)
            }
        }
        Spacer(Modifier.height(10.dp))

        if (emotes.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Outlined.EmojiPeople, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(28.dp))
                Text(EmotesI18n.t(if (vm.hasActiveFilter) "collection.empty.filter" else "collection.empty"), fontSize = 12.sp, color = Color(0x40FFFFFF))
            }
            return@Column
        }

        ResponsiveGrid(emotes, MIN_CARD_WIDTH, maxColumns = 7) { entry, cardModifier ->
            EmoteCard(vm, entry, cardModifier)
        }
    }
}

@Composable
private fun EmoteCard(vm: CollectionViewModel, entry: EmoteEntry, modifier: Modifier) {
    val rarity = EmotesTheme.rarityOf(entry.rarity)
    val selected = vm.selectedId == entry.id
    val shape = RoundedCornerShape(12.dp)
    val inWheel = vm.wheelPositions(entry.id).isNotEmpty()
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) rarity.color.copy(alpha = 0.06f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (selected) rarity.color.copy(alpha = 0.3f) else EmotesTheme.BorderSubtle, shape)
            .clickable {
                val target = vm.target
                if (target != null && entry.unlocked) {
                    vm.assign(target.page, target.slot, entry.id)
                    ComposeEmoteScreens.openWheel()
                } else {
                    vm.select(entry.id)
                }
            }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().height(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                !entry.unlocked -> LockIcon()
                inWheel -> Icon(Icons.Outlined.DonutLarge, null, tint = EmotesTheme.Accent, modifier = Modifier.size(11.dp))
                else -> Icon(Icons.Outlined.CheckCircle, null, tint = EmotesTheme.StatusActive.copy(alpha = 0.7f), modifier = Modifier.size(11.dp))
            }
            Spacer(Modifier.weight(1f))
            if (entry.isNew) {
                NewBadge()
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(6.dp).background(rarity.color, CircleShape))
        }
        Spacer(Modifier.height(4.dp))
        EmoteThumbnail(entry, Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp), locked = !entry.unlocked)
        Spacer(Modifier.height(6.dp))
        Text(
            entry.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            color = if (entry.unlocked) EmotesTheme.TextPrimary else EmotesTheme.StatusLocked,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(3.dp))
        CardSubtitle(vm, entry)
    }
}

@Composable
private fun CardSubtitle(vm: CollectionViewModel, entry: EmoteEntry) {
    Box(Modifier.height(14.dp), contentAlignment = Alignment.Center) {
        val rarity = EmotesTheme.rarityOf(entry.rarity)
        when {
            entry.source == EmoteSource.BUILTIN -> Text(EmotesI18n.t("collection.source.base"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSubtle)
            entry.source == EmoteSource.LOCAL -> Text(EmotesI18n.t("collection.source.local"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextSubtle)
            entry.listing?.published == false -> Text(EmotesI18n.t("collection.draft"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.Warning)
            !entry.unlocked -> {
                val price = vm.priceOf(entry)
                if (price != null) PriceTag(price, 9.sp, color = rarity.color.copy(alpha = 0.85f))
                else Text(EmotesI18n.t("collection.exclusive"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = rarity.color.copy(alpha = 0.6f))
            }
            entry.listing?.access == EmoteAccess.FREE -> Text(EmotesI18n.t("collection.free"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = rarity.color)
            else -> Text(EmotesI18n.rarityName(entry.rarity), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = rarity.color)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// APERÇU ET DÉTAILS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PreviewPanel(vm: CollectionViewModel) {
    val entry = vm.selected
    val glow = entry?.let { EmotesTheme.rarityOf(it.rarity).color } ?: EmotesTheme.Accent
    Column(
        modifier = Modifier
            .width(300.dp)
            .fillMaxHeight()
            .background(Color(0x03FFFFFF))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SectionLabel(EmotesI18n.t(if (vm.placing) "collection.placing.title" else "collection.preview.title"))
            Spacer(Modifier.weight(1f))
            if (!vm.placing) {
                Box(
                    modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(EmotesTheme.SurfaceLight).clickable { vm.resetPreview() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Refresh, EmotesI18n.t("collection.preview.reset"), tint = EmotesTheme.TextSubtle, modifier = Modifier.size(12.dp))
                }
            }
        }

        if (entry == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.TouchApp, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(26.dp))
                    Text(EmotesI18n.t("collection.preview.none"), fontSize = 10.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
                }
            }
            if (!ClientPacketManager.isRemoteAvailable()) Notice(EmotesI18n.t("wheel.no_server"), EmotesTheme.Warning)
            return@Column
        }

        if (vm.placing) {
            PlacingPanel(vm, entry, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }

        // Mannequin. Rendu 3D par-dessus Compose : rien ne doit être superposé ici.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.radialGradient(listOf(glow.copy(alpha = 0.08f), glow.copy(alpha = 0.02f), Color.Transparent)))
                .border(1.dp, glow.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
        ) {
            EmotePlayerPreview(
                animation = entry.animation,
                yaw = vm.previewYaw,
                pitch = vm.previewPitch,
                zoom = vm.previewZoom,
                onRotate = vm::rotatePreview,
                onZoom = vm::zoomPreview,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(EmotesI18n.t("collection.preview.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

        Details(vm, entry)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Details(vm: CollectionViewModel, entry: EmoteEntry) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(entry.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF0F0F0), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (entry.source == EmoteSource.CATALOG) RarityChip(entry.rarity)
            if (entry.isNew) NewBadge(fontSize = 8.sp)
            ClientEmoteCatalog.category(entry.categoryId)?.let { Badge(it.name.uppercase(), EmotesTheme.TextSoft) }
        }
        if (entry.description.isNotBlank()) {
            Text(entry.description, fontSize = 9.sp, color = EmotesTheme.TextSubtle, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        // En pastilles plutôt qu'en lignes : la hauteur va à l'aperçu, même en grande échelle d'interface.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (entry.author.isNotBlank()) InfoChip(Icons.Outlined.Person, EmotesI18n.t("collection.chip.author", entry.author))
            InfoChip(Icons.Outlined.Timer, if (entry.loops) EmotesI18n.t("collection.detail.loop") else EmotesI18n.animationLength(entry.durationTicks))
            if (entry.listing?.playableWhileMoving == true) InfoChip(Icons.AutoMirrored.Outlined.DirectionsRun, EmotesI18n.t("collection.chip.moving"))
            if (entry.unlocked) {
                val key = vm.keyOf(entry.id)
                when {
                    vm.capturingKey -> InfoChip(Icons.Outlined.Keyboard, EmotesI18n.t("collection.key.waiting"), EmotesTheme.Accent)
                    key != null -> InfoChip(Icons.Outlined.Keyboard, EmotesI18n.t("collection.chip.key", key.displayName.string), EmotesTheme.Accent)
                    else -> InfoChip(Icons.Outlined.Keyboard, EmotesI18n.t("collection.chip.no_key"))
                }
                vm.wheelPositions(entry.id).firstOrNull()?.let { (page, slot) ->
                    InfoChip(Icons.Outlined.DonutLarge, EmotesI18n.t("collection.detail.wheel_position", page + 1, slot + 1), EmotesTheme.Accent)
                }
            }
        }

        Actions(vm, entry)
    }
}

@Composable
private fun InfoChip(icon: ImageVector, text: String, color: Color = EmotesTheme.TextSoft) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(EmotesTheme.SurfaceTiny)
            .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(10.dp))
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Actions(vm: CollectionViewModel, entry: EmoteEntry) {
    when {
        entry.unlocked -> {
            PrimaryButton(Icons.Outlined.PlayArrow, EmotesI18n.t("collection.action.play")) {
                if (vm.play(entry)) Minecraft.getInstance().setScreen(null)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(Icons.Outlined.DonutLarge, EmotesI18n.t("collection.action.place"), Modifier.weight(1f)) { vm.startPlacing() }
                if (vm.capturingKey) {
                    SecondaryButton(Icons.Outlined.Close, EmotesI18n.t("collection.key.cancel"), Modifier.weight(1f)) { vm.cancelKeyCapture() }
                } else {
                    SecondaryButton(Icons.Outlined.Keyboard, EmotesI18n.t("collection.action.key"), Modifier.weight(1f)) { vm.startKeyCapture() }
                }
            }
            if (vm.capturingKey) Text(EmotesI18n.t("collection.key.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
        }
        else -> {
            val price = vm.priceOf(entry)
            if (price != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(EmotesI18n.t("collection.locked.price"), fontSize = 10.sp, color = EmotesTheme.TextSoft)
                    Spacer(Modifier.weight(1f))
                    PriceTag(price, 13.sp, original = entry.listing?.price)
                }
                if (vm.serverHasShop) {
                    ShopButton(Icons.Outlined.ShoppingCart, EmotesI18n.t("collection.action.unlock"), EmotesTheme.ShopAccent, filled = true) {
                        ComposeEmoteScreens.openShop(entry.id)
                    }
                }
                Text(EmotesI18n.t("collection.locked.pokematos"), fontSize = 8.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
            } else {
                DisabledButton(Icons.Outlined.Lock, EmotesI18n.t("collection.locked"))
                Text(EmotesI18n.t("collection.locked.exclusive"), fontSize = 8.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Petite roue : on clique une case pour y mettre l'émote, clic droit pour la vider. */
@Composable
private fun PlacingPanel(vm: CollectionViewModel, entry: EmoteEntry, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(EmotesI18n.t("collection.placing.hint", entry.name), fontSize = 9.sp, color = EmotesTheme.TextSoft, textAlign = TextAlign.Center)
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val size = minOf(maxWidth, maxHeight)
            EmoteWheel(
                slots = vm.placingSlots(),
                hovered = vm.placingHovered,
                onHover = { vm.placingHovered = it },
                onClick = { slot, secondary -> vm.assign(vm.placingPage, slot, if (secondary) null else entry.id) },
                size = size,
                // La molette sur cette roue est lue par l'écran (un cran = une page), cf. mouseScrolled.
                modifier = Modifier.onGloballyPositioned { vm.placingWheelBounds = it.boundsInRoot() },
                compact = true
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(EmotesI18n.t("wheel.page", vm.placingPage + 1, WheelConfig.PAGES), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextPrimary)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallRoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { vm.changePlacingPage(-1) }
            Text(EmotesI18n.t("wheel.page", vm.placingPage + 1, WheelConfig.PAGES), fontSize = 10.sp, color = EmotesTheme.TextSoft)
            SmallRoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight) { vm.changePlacingPage(1) }
        }
        SecondaryButton(Icons.Outlined.Check, EmotesI18n.t("collection.placing.done")) { vm.stopPlacing() }
    }
}

@Composable
private fun SmallRoundButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(26.dp).clip(CircleShape).background(EmotesTheme.SurfaceLight).border(1.dp, EmotesTheme.Border, CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(15.dp))
    }
}
