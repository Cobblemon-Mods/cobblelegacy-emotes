package fr.owme.cobblelegacy.emotes.client.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aperso.composite.core.ComposeScreen
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.ui.CrystalGlyph
import fr.owme.cobblelegacy.emotes.client.ui.EmoteScreenRoot
import fr.owme.cobblelegacy.emotes.client.ui.EmotesI18n
import fr.owme.cobblelegacy.emotes.client.ui.EmotesTheme
import fr.owme.cobblelegacy.emotes.client.ui.PriceTag
import fr.owme.cobblelegacy.emotes.network.EmoteBalanceRequestPayload
import kotlinx.coroutines.isActive
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * La boutique d'émotes hors du Pokématos (bouton « Débloquer » de la collection).
 *
 * [back] reconstruit l'écran précédent : un écran Compose fermé ne peut pas être rouvert.
 */
class EmoteShopScreen(initialEmote: UUID?, private val back: (() -> Unit)?) :
    ComposeScreen(content = { EmoteScreenRoot { StandaloneShop(initialEmote) } }) {

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        super.init()
        if (ClientPlayNetworking.canSend(EmoteBalanceRequestPayload.TYPE)) ClientPlayNetworking.send(EmoteBalanceRequestPayload())
    }

    override fun onClose() {
        val previous = back
        if (previous != null) previous() else super.onClose()
    }
}

/** Hôte de la boutique ouverte seule : solde demandé au serveur, cristal dessiné par le mod. */
private object StandaloneHost : EmoteShopHost {
    override val crystals: Long get() = ClientEmoteCatalog.balance
    override fun onCrystalsChanged(balance: Long) = ClientEmoteCatalog.applyBalance(balance)
    override val overlayOpen: Boolean get() = false

    @Composable
    override fun CrystalIcon(sizeDp: Float) = CrystalGlyph(sizeDp.dp)
}

@Composable
private fun StandaloneShop(initialEmote: UUID?) {
    var revision by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (isActive) withFrameNanos { if (revision != ClientEmoteCatalog.revision) revision = ClientEmoteCatalog.revision }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .widthIn(max = 1120.dp).fillMaxWidth(0.96f)
                .heightIn(max = 640.dp).fillMaxHeight(0.94f)
                .clip(shape)
                .background(Color(0xFF0A0A0A))
                .border(1.dp, Color(0x0FFFFFFF), shape)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color(0x08FFFFFF))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x08FFFFFF))
                        .border(1.dp, Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
                        .clickable { Minecraft.getInstance().screen?.onClose() }
                        .padding(5.dp, 5.dp, 10.dp, 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, tint = Color(0x73FFFFFF), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(EmotesI18n.t("shop.back_collection"), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0x73FFFFFF))
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(EmotesTheme.ShopAccent.copy(alpha = 0.1f))
                        .border(1.dp, EmotesTheme.ShopAccent.copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.ShoppingBag, null, tint = EmotesTheme.ShopAccent, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(EmotesI18n.t("shop.window_title"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.weight(1f))
                revision // relu : le solde arrive après l'ouverture
                if (ClientEmoteCatalog.balance >= 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0x14A855F7), Color(0x08A855F7))))
                            .border(1.dp, Color(0x30A855F7), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PriceTag(ClientEmoteCatalog.balance, 12.sp, color = Color.White)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0FFFFFFF)))
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp, 16.dp, 20.dp, 16.dp)) {
                EmoteShopView(StandaloneHost, Modifier.fillMaxSize(), initialEmote)
            }
        }
    }
}
