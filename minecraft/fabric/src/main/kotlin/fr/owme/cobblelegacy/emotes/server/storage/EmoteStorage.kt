package fr.owme.cobblelegacy.emotes.server.storage

import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Le catalogue complet tel qu'il est rangé : émotes (brouillons compris), catégories, réglages. */
data class CatalogSnapshot(
    val listings: List<EmoteListing>,
    val categories: List<EmoteCategory>,
    val settings: EmoteShopSettings,
    val revision: Long
)

enum class PurchaseOutcome { RECORDED, ALREADY_OWNED, FAILED }

/**
 * Où vivent le catalogue, les fichiers d'émotes et les émotes possédées.
 *
 * Toutes les opérations passent par un seul thread, dans l'ordre : une écriture suivie d'une lecture
 * reste cohérente. Les résultats reviennent sur ce thread ; l'appelant repasse sur celui du serveur.
 * En cas d'erreur, la valeur de repli (`false`, `null`, [PurchaseOutcome.FAILED]) est renvoyée et
 * l'erreur journalisée : rien n'est levé.
 */
interface EmoteStorage {
    /** Base partagée par plusieurs serveurs : il faut surveiller les changements faits ailleurs. */
    val shared: Boolean
    val label: String

    fun start(): CompletableFuture<Boolean>
    fun stop()

    /** Compteur augmenté à chaque modification du catalogue. */
    fun revision(): CompletableFuture<Long?>
    fun loadCatalog(): CompletableFuture<CatalogSnapshot?>
    fun readFile(sha256: String): CompletableFuture<ByteArray?>

    /** Crée ou remplace l'émote ; [file] (déjà vérifié) est rangé avec si non nul. */
    fun saveListing(listing: EmoteListing, file: ByteArray?, author: UUID?): CompletableFuture<Boolean>
    fun deleteListing(id: UUID): CompletableFuture<Boolean>
    fun saveSortOrders(orders: Map<UUID, Int>): CompletableFuture<Boolean>
    fun setPublished(ids: Collection<UUID>, published: Boolean): CompletableFuture<Boolean>

    /** Remplace la liste des catégories ; [deletedId] retire aussi cette catégorie des émotes. */
    fun saveCategories(categories: List<EmoteCategory>, deletedId: String?): CompletableFuture<Boolean>
    fun saveSettings(settings: EmoteShopSettings): CompletableFuture<Boolean>

    fun loadOwned(player: UUID): CompletableFuture<Set<UUID>?>
    fun loadOwned(players: Collection<UUID>): CompletableFuture<Map<UUID, Set<UUID>>?>
    fun grant(player: UUID, emote: UUID, source: String): CompletableFuture<Boolean>
    fun revoke(player: UUID, emote: UUID): CompletableFuture<Boolean>

    /** Donne l'émote et garde une trace de l'achat, d'un seul coup. */
    fun recordPurchase(player: UUID, playerName: String, emote: UUID, price: Long): CompletableFuture<PurchaseOutcome>
}
