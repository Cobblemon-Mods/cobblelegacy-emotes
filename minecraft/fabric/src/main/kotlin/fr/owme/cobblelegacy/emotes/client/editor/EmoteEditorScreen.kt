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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aperso.composite.core.ComposeScreen
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.preview.EmotePlayerPreview
import fr.owme.cobblelegacy.emotes.client.ui.*
import fr.owme.cobblelegacy.emotes.network.EmoteCategoryEditPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import kotlinx.coroutines.isActive
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

private val Amber = EmotesTheme.Admin
private val DATE = SimpleDateFormat("dd/MM HH:mm")

// ═══════════════════════════════════════════════════════════════════════════════
// ÉCRAN
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Éditeur du catalogue d'émotes (administrateurs) : ajout d'émotes (fichiers choisis ou glissés sur
 * la fenêtre), prix, réductions, badge NEW, catégories, promotion globale. Tout est revérifié par le
 * serveur, et partagé par tous les serveurs.
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
                .widthIn(max = 1200.dp).fillMaxWidth(0.97f)
                .heightIn(max = 700.dp).fillMaxHeight(0.95f)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xF70A0A0A), Color(0xF20A0A0A), Color(0xF70A0A0A))))
                .border(1.dp, Amber.copy(alpha = 0.12f), shape)
        ) {
            EditorTopBar(vm)
            when (vm.tab) {
                EmoteEditorViewModel.Tab.EMOTES -> EmotesTab(vm)
                EmoteEditorViewModel.Tab.CATEGORIES -> CategoriesTab(vm)
                EmoteEditorViewModel.Tab.SHOP -> ShopTab(vm)
            }
        }
        vm.toastMessage?.let { Toast(it, vm.toastSuccess, Amber) { vm.dismissToast() } }
    }
}

@Composable
private fun EditorTopBar(vm: EmoteEditorViewModel) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .background(Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.04f), Color(0x05FFFFFF))))
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x08FFFFFF))
                    .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
                    .clickable { net.minecraft.client.Minecraft.getInstance().screen?.onClose() }
                    .padding(5.dp, 5.dp, 10.dp, 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color(0x73FFFFFF), modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(EmotesI18n.t("editor.back"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0x73FFFFFF))
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
                Text(EmotesI18n.t("editor.subtitle"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
            }
            Spacer(Modifier.weight(1f))
            TabButton(Icons.Outlined.EmojiPeople, EmotesI18n.t("editor.tab.emotes"), vm.tab == EmoteEditorViewModel.Tab.EMOTES) { vm.tab = EmoteEditorViewModel.Tab.EMOTES }
            Spacer(Modifier.width(6.dp))
            TabButton(Icons.Outlined.FolderCopy, EmotesI18n.t("editor.tab.categories"), vm.tab == EmoteEditorViewModel.Tab.CATEGORIES) { vm.tab = EmoteEditorViewModel.Tab.CATEGORIES }
            Spacer(Modifier.width(6.dp))
            TabButton(Icons.Outlined.LocalOffer, EmotesI18n.t("editor.tab.shop"), vm.tab == EmoteEditorViewModel.Tab.SHOP) {
                vm.loadShopFields()
                vm.tab = EmoteEditorViewModel.Tab.SHOP
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Amber.copy(alpha = 0.08f)))
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Amber.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (active) Amber.copy(alpha = 0.35f) else Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (active) Amber else EmotesTheme.TextSubtle, modifier = Modifier.size(12.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (active) Amber else EmotesTheme.TextSubtle)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ONGLET ÉMOTES
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmotesTab(vm: EmoteEditorViewModel) {
    val divider = Amber.copy(alpha = 0.06f)
    Row(Modifier.fillMaxSize()) {
        EmoteList(vm)
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        PreviewColumn(vm, Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        FormColumn(vm)
    }
}

@Composable
private fun EmoteList(vm: EmoteEditorViewModel) {
    Column(Modifier.width(250.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(vm.query, { vm.query = it }, EmotesI18n.t("editor.search"), Amber, Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Amber.copy(alpha = 0.16f)).border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(9.dp))
                    .clickable { vm.pickFiles() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Add, EmotesI18n.t("editor.add"), tint = Amber, modifier = Modifier.size(16.dp))
            }
        }
        Text(EmotesI18n.t("editor.add.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vm.drafts.isNotEmpty()) {
                SectionLabel(EmotesI18n.t("editor.list.drafts", vm.drafts.size))
                vm.drafts.forEach { draft -> DraftRow(vm, draft) }
                if (vm.drafts.size > 1) {
                    Spacer(Modifier.height(2.dp))
                    SecondaryButton(Icons.Outlined.LibraryAdd, EmotesI18n.t("editor.drafts.save_all", vm.drafts.size), color = Amber) { vm.saveAllDrafts() }
                }
                Spacer(Modifier.height(8.dp))
            }
            val listings = vm.listings
            SectionLabel(EmotesI18n.t("editor.list.catalog", listings.size))
            if (listings.isEmpty()) {
                Text(EmotesI18n.t("editor.list.empty"), fontSize = 9.sp, color = EmotesTheme.TextMuted, modifier = Modifier.padding(vertical = 12.dp))
            }
            listings.forEach { listing -> ListingRow(vm, listing) }
        }
    }
}

@Composable
private fun RowFrame(selected: Boolean, color: Color, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) color.copy(alpha = 0.08f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (selected) color.copy(alpha = 0.35f) else EmotesTheme.BorderSubtle, RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun ListingRow(vm: EmoteEditorViewModel, listing: EmoteListing) {
    val now = System.currentTimeMillis()
    val rarity = EmotesTheme.rarityOf(listing.rarity)
    RowFrame(vm.selectedId == listing.id, rarity.color, { vm.select(listing.id) }) {
        EmoteThumbnail(EmoteLibrary.entry(listing.id), Modifier.size(30.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(listing.name, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    EmotesI18n.t(if (listing.published) "editor.status.published" else "editor.status.draft"),
                    fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = if (listing.published) EmotesTheme.StatusActive else EmotesTheme.Warning
                )
                Text("·", fontSize = 8.sp, color = EmotesTheme.TextMuted)
                when (listing.access) {
                    EmoteAccess.FREE -> Text(EmotesI18n.t("collection.free"), fontSize = 8.sp, color = EmotesTheme.TextSoft)
                    EmoteAccess.EXCLUSIVE -> Text(EmotesI18n.t("collection.exclusive"), fontSize = 8.sp, color = EmotesTheme.TextSoft)
                    EmoteAccess.SHOP -> PriceTag(listing.price, 8.sp, color = EmotesTheme.TextSoft)
                }
            }
        }
        if (listing.isNew(now)) NewBadge()
        val discount = listing.itemDiscount(now)
        if (discount > 0) {
            Spacer(Modifier.width(3.dp))
            PromoBadge(discount)
        }
    }
}

@Composable
private fun DraftRow(vm: EmoteEditorViewModel, draft: EmoteEditorViewModel.Draft) {
    RowFrame(vm.selectedId == draft.id, Amber, { vm.select(draft.id) }) {
        DraftIcon(draft, 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(draft.parsed.name.ifBlank { draft.fileName }, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(EmotesI18n.t("editor.status.new_file"), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = Amber)
        }
    }
}

@Composable
private fun DraftIcon(draft: EmoteEditorViewModel.Draft, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val icon = draft.icon
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(0.86f), filterQuality = FilterQuality.Low)
        else Icon(Icons.Outlined.EmojiPeople, null, tint = Amber.copy(alpha = 0.7f), modifier = Modifier.fillMaxSize(0.55f))
    }
}

@Composable
private fun PreviewColumn(vm: EmoteEditorViewModel, modifier: Modifier) {
    Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(EmotesI18n.t("collection.preview.title"))
        val form = vm.form
        val rarity = EmotesTheme.rarityOf(form?.rarity ?: EmoteRarity.COMMUN).color
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.radialGradient(listOf(rarity.copy(alpha = 0.08f), rarity.copy(alpha = 0.02f), Color.Transparent)))
                .border(1.dp, rarity.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
        ) {
            if (form == null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.UploadFile, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(30.dp))
                    Text(EmotesI18n.t("editor.preview.none"), fontSize = 10.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
                }
            } else {
                EmotePlayerPreview(
                    animation = vm.selectedAnimation,
                    yaw = vm.previewYaw,
                    pitch = vm.previewPitch,
                    zoom = vm.previewZoom,
                    onRotate = vm::rotatePreview,
                    onZoom = vm::zoomPreview,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        vm.selectedInfo?.let { info ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                InfoChip(Icons.Outlined.Timer, if (info.loops) EmotesI18n.t("collection.detail.loop") else EmotesI18n.animationLength(info.durationTicks))
                InfoChip(if (info.hasIcon || vm.form?.pendingIcon != null) Icons.Outlined.Image else Icons.Outlined.HideImage, EmotesI18n.t(if (info.hasIcon || vm.form?.pendingIcon != null) "editor.file.icon" else "editor.file.no_icon"))
                vm.selectedId?.let { id ->
                    (vm.draft(id)?.data?.size ?: vm.listing(id)?.fileSize)?.let { size -> InfoChip(Icons.Outlined.Description, "${(size + 1023) / 1024} Ko") }
                }
            }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(EmotesTheme.SurfaceLight).padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, null, tint = EmotesTheme.TextSubtle, modifier = Modifier.size(10.dp))
        Text(text, fontSize = 8.sp, color = EmotesTheme.TextSoft, maxLines = 1)
    }
}

// ─── Formulaire ──────────────────────────────────────────────────────────────────

@Composable
private fun FormColumn(vm: EmoteEditorViewModel) {
    val form = vm.form
    Column(
        modifier = Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (form == null) {
            SectionLabel(EmotesI18n.t("editor.form.title"))
            Text(EmotesI18n.t("editor.form.none"), fontSize = 10.sp, color = EmotesTheme.TextMuted)
            return@Column
        }
        val listing = vm.listing(form.id)
        val now = System.currentTimeMillis()

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(EmotesI18n.t(if (form.creating) "editor.form.new" else "editor.form.edit"))
            Spacer(Modifier.weight(1f))
            if (listing != null) {
                SmallIconButton(Icons.Outlined.ArrowUpward) { vm.action(EmoteEditorActionPayload.Action.MOVE_UP) }
                Spacer(Modifier.width(4.dp))
                SmallIconButton(Icons.Outlined.ArrowDownward) { vm.action(EmoteEditorActionPayload.Action.MOVE_DOWN) }
            }
        }

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

        FieldLabel(EmotesI18n.t("editor.field.access"))
        ChipRow {
            Chip(EmotesI18n.t("editor.access.shop"), form.access == EmoteAccess.SHOP, EmotesTheme.ShopAccent) { vm.update { it.copy(access = EmoteAccess.SHOP) } }
            Chip(EmotesI18n.t("editor.access.free"), form.access == EmoteAccess.FREE, EmotesTheme.StatusActive) { vm.update { it.copy(access = EmoteAccess.FREE) } }
            Chip(EmotesI18n.t("editor.access.exclusive"), form.access == EmoteAccess.EXCLUSIVE, EmotesTheme.Accent) { vm.update { it.copy(access = EmoteAccess.EXCLUSIVE) } }
        }
        Text(EmotesI18n.t("editor.access.hint." + form.access.name.lowercase()), fontSize = 8.sp, color = EmotesTheme.TextMuted)

        if (form.access == EmoteAccess.SHOP) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(EmotesI18n.t("editor.field.price"), form.price, { v -> vm.update { it.copy(price = v.filter(Char::isDigit).take(9)) } }, Modifier.weight(1f))
                Box(Modifier.padding(bottom = 9.dp)) { CrystalGlyph(14.dp) }
            }

            FieldLabel(EmotesI18n.t("editor.field.discount"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(EmotesI18n.t("editor.field.discount_percent"), form.discountPercent, { v -> vm.update { it.copy(discountPercent = v.filter(Char::isDigit).take(2)) } }, Modifier.weight(1f))
                EditorField(EmotesI18n.t("editor.field.days"), form.discountDays, { v -> vm.update { it.copy(discountDays = v.filter(Char::isDigit).take(3)) } }, Modifier.weight(1f), placeholder = "—")
            }
            val active = listing?.itemDiscount(now) ?: 0
            Text(
                if (active > 0) EmotesI18n.t("editor.discount.active", active, DATE.format(Date(listing!!.discountEndsAtMs))) else EmotesI18n.t("editor.discount.hint"),
                fontSize = 8.sp, color = if (active > 0) EmotesTheme.Promo else EmotesTheme.TextMuted
            )
            val price = form.price.toLongOrNull()
            val percent = form.discountPercent.toIntOrNull() ?: 0
            if (price != null && price > 0 && percent in 1..90) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(EmotesI18n.t("editor.discount.result") + " ", fontSize = 9.sp, color = EmotesTheme.TextSoft)
                    PriceTag(fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings.discounted(price, percent), 10.sp, original = price)
                }
            }
        }

        FieldLabel(EmotesI18n.t("editor.field.new"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle(form.newEnabled) { enabled -> vm.update { it.copy(newEnabled = enabled) } }
            NewBadge(fontSize = 8.sp)
            if (form.newEnabled) {
                EditorField(null, form.newDays, { v -> vm.update { it.copy(newDays = v.filter(Char::isDigit).take(3)) } }, Modifier.width(70.dp), placeholder = EmotesI18n.t("editor.field.days"))
                Text(EmotesI18n.t("editor.field.days_unit"), fontSize = 9.sp, color = EmotesTheme.TextSoft)
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

        FieldLabel(EmotesI18n.t("editor.field.visibility"))
        ChipRow {
            Chip(EmotesI18n.t("editor.status.draft"), !form.published, EmotesTheme.Warning) { vm.update { it.copy(published = false) } }
            Chip(EmotesI18n.t("editor.status.published"), form.published, EmotesTheme.StatusActive) { vm.update { it.copy(published = true) } }
        }

        SecondaryButton(Icons.Outlined.Image, EmotesI18n.t("editor.icon.change")) { vm.pickIcon() }
        form.pendingIcon?.let { icon ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(icon, null, Modifier.size(28.dp), filterQuality = FilterQuality.Low)
                Spacer(Modifier.width(6.dp))
                Text(EmotesI18n.t("editor.icon.pending"), fontSize = 8.sp, color = Amber)
            }
        }

        Spacer(Modifier.height(4.dp))
        PrimaryButton(if (vm.saving) Icons.Outlined.HourglassTop else Icons.Outlined.Save, EmotesI18n.t(if (vm.saving) "editor.saving" else "editor.save"), Amber) { vm.save() }
        if (form.creating) {
            SecondaryButton(Icons.Outlined.Close, EmotesI18n.t("editor.draft.discard")) { vm.discardDraft() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShopButton(
                    Icons.Outlined.DeleteOutline,
                    EmotesI18n.t(if (vm.confirmDelete) "editor.delete.confirm" else "editor.delete"),
                    EmotesTheme.Danger, filled = vm.confirmDelete, modifier = Modifier.weight(1f)
                ) { vm.action(EmoteEditorActionPayload.Action.DELETE) }
            }
            if (vm.confirmDelete) Text(EmotesI18n.t("editor.delete.hint"), fontSize = 8.sp, color = EmotesTheme.TextMuted)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ONGLET CATÉGORIES
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CategoriesTab(vm: EmoteEditorViewModel) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(EmotesI18n.t("editor.categories.title"))
            Text(EmotesI18n.t("editor.categories.hint"), fontSize = 9.sp, color = EmotesTheme.TextMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorField(EmotesI18n.t("editor.categories.new"), vm.newCategoryName, { vm.newCategoryName = it.take(32) }, Modifier.weight(1f), placeholder = EmotesI18n.t("editor.categories.placeholder"))
                Box(Modifier.width(120.dp)) {
                    ShopButton(Icons.Outlined.Add, EmotesI18n.t("editor.categories.create"), Amber, filled = true, enabled = vm.newCategoryName.isNotBlank()) {
                        vm.category(EmoteCategoryEditPayload.Action.CREATE, name = vm.newCategoryName)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val categories = vm.categories
            if (categories.isEmpty()) Text(EmotesI18n.t("editor.categories.empty"), fontSize = 10.sp, color = EmotesTheme.TextMuted)
            categories.forEach { category ->
                val count = vm.listings.count { it.categoryId == category.id }
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(EmotesTheme.SurfaceTiny)
                        .border(1.dp, EmotesTheme.BorderSubtle, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = Amber, modifier = Modifier.size(14.dp))
                    if (vm.renaming == category.id) {
                        EditorField(null, vm.renameText, { vm.renameText = it.take(32) }, Modifier.weight(1f))
                        SmallIconButton(Icons.Outlined.Check) { vm.category(EmoteCategoryEditPayload.Action.RENAME, category.id, vm.renameText) }
                        SmallIconButton(Icons.Outlined.Close) { vm.renaming = null }
                    } else {
                        Text(category.name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(EmotesI18n.t("shop.count", count), fontSize = 9.sp, color = EmotesTheme.TextMuted)
                        SmallIconButton(Icons.Outlined.ArrowUpward) { vm.category(EmoteCategoryEditPayload.Action.MOVE_UP, category.id) }
                        SmallIconButton(Icons.Outlined.ArrowDownward) { vm.category(EmoteCategoryEditPayload.Action.MOVE_DOWN, category.id) }
                        SmallIconButton(Icons.Outlined.Edit) {
                            vm.renaming = category.id
                            vm.renameText = category.name
                        }
                        SmallIconButton(Icons.Outlined.DeleteOutline, tint = if (vm.confirmDeleteCategory == category.id) EmotesTheme.Danger else EmotesTheme.TextSoft) {
                            vm.category(EmoteCategoryEditPayload.Action.DELETE, category.id)
                        }
                    }
                }
                if (vm.confirmDeleteCategory == category.id) {
                    Text(EmotesI18n.t("editor.categories.delete_confirm", category.name), fontSize = 8.sp, color = EmotesTheme.Danger)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ONGLET BOUTIQUE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ShopTab(vm: EmoteEditorViewModel) {
    val now = System.currentTimeMillis()
    val settings = vm.settings
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            val listings = vm.listings
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
private fun FieldLabel(text: String) {
    Text(text.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, color = EmotesTheme.TextMuted, letterSpacing = 1.sp)
}

@Composable
private fun EditorField(label: String?, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x08FFFFFF))
                        .border(1.dp, Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, fontSize = 11.sp, color = EmotesTheme.TextMuted)
                    inner()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) color.copy(alpha = 0.14f) else EmotesTheme.SurfaceTiny)
            .border(1.dp, if (selected) color.copy(alpha = 0.45f) else EmotesTheme.BorderSubtle, RoundedCornerShape(7.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = if (selected) color else EmotesTheme.TextSubtle, maxLines = 1)
    }
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 17.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (checked) Amber.copy(alpha = 0.6f) else Color(0x1FFFFFFF))
            .clickable { onChange(!checked) }
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(13.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun SmallIconButton(icon: ImageVector, tint: Color = EmotesTheme.TextSoft, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(EmotesTheme.SurfaceLight).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
    }
}
