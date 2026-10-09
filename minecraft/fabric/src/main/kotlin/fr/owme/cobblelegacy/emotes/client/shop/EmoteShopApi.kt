package fr.owme.cobblelegacy.emotes.client.shop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary

/*
 * API publique de la boutique d'émotes, pour l'onglet « Émotes » du Pokématos.
 *
 * Le Pokématos compile contre ce jar sans en dépendre à l'exécution (libs-api/, compileOnly) : il
 * vérifie FabricLoader.isModLoaded("emotecraft") avant d'appeler EmoteShopContent. Seuls des types
 * Compose et Java apparaissent ici (le jar est en noms intermédiaires, le Pokématos en Yarn) : ces
 * signatures ne doivent pas changer sans recompiler le Pokématos.
 */

/** Ce que la boutique hôte fournit à la section des émotes. */
interface EmoteShopHost {
    /** Solde de cristaux affiché par la boutique hôte. */
    val crystals: Long

    /** Le serveur a renvoyé le solde après un achat. */
    fun onCrystalsChanged(balance: Long)

    /** Une surimpression de l'hôte recouvre la section : les rendus 3D (dessinés hors Compose) se masquent. */
    val overlayOpen: Boolean

    /** L'icône de cristal de l'hôte (taille en dp). */
    @Composable
    fun CrystalIcon(sizeDp: Float)
}

/** La boutique d'émotes complète (catalogue, aperçu sur le joueur, achat), à la taille donnée par [modifier]. */
@Composable
fun EmoteShopContent(host: EmoteShopHost, modifier: Modifier = Modifier) {
    EmoteShopView(host, modifier, initialEmote = null)
}

/**
 * Émotes « NEW » que le joueur ne possède pas encore : de quoi afficher une pastille sur l'onglet.
 * 0 sur un serveur sans catalogue.
 */
fun emoteShopNewCount(): Int {
    if (!ClientEmoteCatalog.managed) return 0
    return EmoteLibrary.entries().count { it.isNew && !it.unlocked }
}

@Composable
internal fun rememberEmoteShopViewModel(host: EmoteShopHost, initialEmote: java.util.UUID?): EmoteShopViewModel {
    val hostRef = rememberUpdatedState(host)
    val vm = remember { EmoteShopViewModel({ hostRef.value }, initialEmote) }
    DisposableEffect(vm) { onDispose { vm.dispose() } }
    return vm
}
