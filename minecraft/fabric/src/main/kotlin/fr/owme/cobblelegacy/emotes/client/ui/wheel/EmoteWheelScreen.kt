package fr.owme.cobblelegacy.emotes.client.ui.wheel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mojang.blaze3d.platform.InputConstants
import dev.aperso.composite.core.ComposeScreen
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.WheelConfig
import fr.owme.cobblelegacy.emotes.client.preview.EmotePlayerPreview
import fr.owme.cobblelegacy.emotes.client.ui.ComposeEmoteScreens
import fr.owme.cobblelegacy.emotes.client.ui.EmoteScreenRoot
import fr.owme.cobblelegacy.emotes.client.ui.EmotesI18n
import fr.owme.cobblelegacy.emotes.client.ui.EmotesTheme
import fr.owme.cobblelegacy.emotes.client.ui.Toast
import io.github.kosmx.emotes.fabric.ClientInit
import io.github.kosmx.emotes.inline.TmpGetters
import io.github.kosmx.emotes.main.EmoteHolder
import io.github.kosmx.emotes.main.network.ClientPacketManager
import kotlinx.coroutines.isActive
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW

// ═══════════════════════════════════════════════════════════════════════════════
// ÉCRAN
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * La roue d'émotes (touche B) : huit émotes par page, dix pages.
 *
 * Clic : jouer. Clic droit : changer l'émote de la case. Molette ou flèches : changer de page.
 * Touches 1 à 8 : jouer la case. Maintenir la touche de la roue puis la relâcher sur une émote : la jouer.
 */
class EmoteWheelScreen(internal val vm: WheelViewModel) : ComposeScreen(content = { EmoteScreenRoot { EmoteWheelContent(vm) } }) {

    override fun isPauseScreen(): Boolean = false

    /** Pas de flou ni de voile vanilla : le monde reste visible autour de la roue. */
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (vm.checkHoldRelease()) {
            onClose()
            return
        }
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_8 -> {
                if (vm.play(keyCode - GLFW.GLFW_KEY_1)) onClose()
                return true
            }
            GLFW.GLFW_KEY_LEFT -> {
                vm.changePage(-1)
                return true
            }
            GLFW.GLFW_KEY_RIGHT -> {
                vm.changePage(1)
                return true
            }
        }
        // La touche de la roue la referme (si elle n'est pas maintenue pour choisir).
        if (vm.isWheelKey(keyCode, scanCode) && !vm.holding) {
            onClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    /**
     * Un cran de molette = une page. Pas de passage par Compose : Composite y étale chaque cran sur
     * plusieurs images, et un seul cran faisait défiler plusieurs pages.
     */
    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0) vm.changePage(if (scrollY > 0) -1 else 1)
        return true
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ÉTAT
// ═══════════════════════════════════════════════════════════════════════════════

class WheelViewModel {
    var page by mutableIntStateOf(WheelConfig.page)
        private set
    var hovered by mutableStateOf<Int?>(null)
    var toastMessage by mutableStateOf<String?>(null)
        private set
    var toastSuccess by mutableStateOf(true)
        private set

    private var revision by mutableIntStateOf(-1)
    private val openedAt = System.currentTimeMillis()
    private val wheelKey: InputConstants.Key? = runCatching { KeyBindingHelper.getBoundKeyOf(ClientInit.openMenuKey) }.getOrNull()

    /** La touche de la roue est encore enfoncée depuis l'ouverture. */
    var holding = wheelKey != null && isDown(wheelKey)
        private set

    /** Compose ne voit pas le catalogue : on compare sa révision à chaque frame. */
    fun sync() {
        if (revision != ClientEmoteCatalog.revision) revision = ClientEmoteCatalog.revision
    }

    fun slots(): List<WheelSlot> {
        revision // lu pour se recomposer quand le catalogue change
        return (0 until WheelConfig.SLOTS).map { slot ->
            val id = WheelConfig.get(page, slot)
            WheelSlot(id?.let { EmoteLibrary.entry(it) }, id != null)
        }
    }

    fun changePage(delta: Int) {
        WheelConfig.page = page + delta
        page = WheelConfig.page
        hovered = null
    }

    fun selectPage(index: Int) {
        WheelConfig.page = index
        page = WheelConfig.page
    }

    /** Joue la case [slot] ; `true` si l'émote est lancée (la roue se ferme). */
    fun play(slot: Int): Boolean {
        val id = WheelConfig.get(page, slot) ?: return false
        val entry = EmoteLibrary.entry(id)
        if (entry == null || !entry.unlocked) {
            toast(EmotesI18n.t(if (entry == null) "wheel.missing" else "wheel.locked"), false)
            return false
        }
        val holder = EmoteHolder.list[id]
        if (holder == null) {
            toast(EmotesI18n.t("wheel.loading"), false)
            return false
        }
        val player = TmpGetters.getClientMethods().mainPlayer ?: return false
        if (!holder.playEmote(player)) {
            toast(EmotesI18n.t("wheel.cannot_play"), false)
            return false
        }
        return true
    }

    fun click(slot: Int, secondary: Boolean): Boolean {
        val id = WheelConfig.get(page, slot)
        if (secondary || id == null) {
            ComposeEmoteScreens.openCollection(id, WheelTarget(page, slot))
            return false
        }
        val entry = EmoteLibrary.entry(id)
        if (entry != null && !entry.unlocked) {
            ComposeEmoteScreens.openCollection(id, null)
            return false
        }
        return play(slot)
    }

    fun isWheelKey(keyCode: Int, scanCode: Int): Boolean =
        wheelKey != null && wheelKey == InputConstants.getKey(keyCode, scanCode)

    /**
     * Touche relâchée après l'avoir maintenue : on joue l'émote survolée. Relâchée au centre, la roue
     * reste ouverte (on peut encore cliquer). `true` : fermer l'écran.
     */
    fun checkHoldRelease(): Boolean {
        val key = wheelKey ?: return false
        if (!holding || isDown(key)) return false
        holding = false
        if (System.currentTimeMillis() - openedAt < HOLD_MS) return false
        val slot = hovered ?: return false
        return play(slot)
    }

    private fun isDown(key: InputConstants.Key): Boolean {
        val window = Minecraft.getInstance().window.window
        return when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE -> GLFW.glfwGetMouseButton(window, key.value) == GLFW.GLFW_PRESS
            else -> false
        }
    }

    fun toast(message: String, success: Boolean) {
        toastMessage = message
        toastSuccess = success
    }

    fun dismissToast() {
        toastMessage = null
    }

    companion object {
        /** En dessous, un appui bref : la roue reste ouverte, on choisit au clic. */
        const val HOLD_MS = 220L
    }
}

/** Case visée quand on ouvre la collection depuis la roue pour y placer une émote. */
data class WheelTarget(val page: Int, val slot: Int)

// ═══════════════════════════════════════════════════════════════════════════════
// CONTENU
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmoteWheelContent(vm: WheelViewModel) {
    LaunchedEffect(vm) {
        while (isActive) withFrameNanos { vm.sync() }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(170), label = "appear")

    val accent = EmotesTheme.Accent
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0x8C000000), Color(0x40000000), Color(0x1A000000))))
    ) {
        val wheelSize = wheelSizeFor(maxWidth, maxHeight)
        val slots = vm.slots()

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    alpha = appear
                    scaleX = 0.9f + 0.1f * appear
                    scaleY = 0.9f + 0.1f * appear
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PageArrow(Icons.AutoMirrored.Outlined.KeyboardArrowLeft) { vm.changePage(-1) }
                Spacer(Modifier.width(18.dp))
                EmoteWheel(
                    slots = slots,
                    hovered = vm.hovered,
                    onHover = { vm.hovered = it },
                    onClick = { slot, secondary -> if (vm.click(slot, secondary)) Minecraft.getInstance().setScreen(null) },
                    size = wheelSize,
                    accent = accent
                ) { innerDiameter ->
                    WheelCenter(vm, slots, innerDiameter)
                }
                Spacer(Modifier.width(18.dp))
                PageArrow(Icons.AutoMirrored.Outlined.KeyboardArrowRight) { vm.changePage(1) }
            }

            Spacer(Modifier.height(10.dp))
            PageDots(vm)
            Spacer(Modifier.height(14.dp))

            WheelButton(Icons.Outlined.Apps, EmotesI18n.t("wheel.all"), accent) {
                ComposeEmoteScreens.openCollection(null, null)
            }
            Spacer(Modifier.height(8.dp))
            Text(EmotesI18n.t("wheel.hint"), fontSize = 9.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
        }

        if (!ClientPacketManager.isRemoteAvailable()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6140E05))
                    .border(1.dp, EmotesTheme.Warning.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Warning, null, tint = EmotesTheme.Warning, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(EmotesI18n.t("wheel.no_server"), fontSize = 10.sp, color = EmotesTheme.Warning)
            }
        }

        vm.toastMessage?.let { Toast(it, vm.toastSuccess, accent, Alignment.TopEnd) { vm.dismissToast() } }
    }
}

/** Disque central : aperçu de l'émote survolée sur le joueur, son nom, ou la page. */
@Composable
private fun WheelCenter(vm: WheelViewModel, slots: List<WheelSlot>, diameter: Dp) {
    val slot = vm.hovered?.let { slots.getOrNull(it) }
    val entry = slot?.entry
    Column(Modifier.size(diameter), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = diameter * 0.07f).size(diameter * 0.62f)) {
            EmotePlayerPreview(
                animation = entry?.animation,
                fill = 0.56f,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(diameter * 0.01f))
        when {
            entry != null -> {
                Text(
                    entry.name, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.width(diameter * 0.78f)
                )
                if (slot.locked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock, null, tint = EmotesTheme.Warning, modifier = Modifier.size(9.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(EmotesI18n.t("wheel.center.locked"), fontSize = 8.sp, color = EmotesTheme.Warning)
                    }
                } else {
                    Text(EmotesI18n.t("wheel.center.play"), fontSize = 8.sp, color = EmotesTheme.TextMuted)
                }
            }
            slot != null && slot.missing -> Text(EmotesI18n.t("wheel.center.missing"), fontSize = 9.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
            slot != null -> Text(EmotesI18n.t("wheel.center.empty"), fontSize = 9.sp, color = EmotesTheme.TextMuted, textAlign = TextAlign.Center)
            else -> {
                Text(EmotesI18n.t("wheel.page", vm.page + 1, WheelConfig.PAGES), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EmotesTheme.TextPrimary)
                Text(EmotesI18n.t("wheel.center.idle"), fontSize = 8.sp, color = EmotesTheme.TextMuted)
            }
        }
    }
}

@Composable
private fun PageDots(vm: WheelViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (index in 0 until WheelConfig.PAGES) {
            val active = index == vm.page
            val filled = !WheelConfig.pageIsEmpty(index)
            Box(
                Modifier
                    .size(if (active) 9.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            active -> EmotesTheme.Accent
                            filled -> Color(0x66FFFFFF)
                            else -> Color(0x26FFFFFF)
                        }
                    )
                    .clickable { vm.selectPage(index) }
            )
        }
    }
}

@Composable
private fun PageArrow(icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color(0xB30C0D10))
            .border(1.dp, Color(0x1AFFFFFF), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = EmotesTheme.TextSoft, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WheelButton(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xD90E0F13))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
    }
}
