package fr.owme.cobblelegacy.emotes.catalog

import java.util.UUID

/** Comment un joueur obtient une émote. */
enum class EmoteAccess {
    /** Offerte à tous les joueurs, sans achat. */
    FREE,

    /** Vendue dans la boutique du Pokématos. */
    SHOP,

    /** Ni gratuite ni en vente : donnée par commande (événement, Tebex, récompense…). */
    EXCLUSIVE;

    companion object {
        fun parse(value: String?): EmoteAccess = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SHOP
    }
}

/**
 * Une émote du catalogue du serveur. Son identifiant est l'UUID de l'animation Emotecraft, le même
 * des deux côtés : il sert à vérifier qui a le droit de la jouer.
 *
 * Le fichier `.emotecraft` (animation + icône) est rangé à part, par son SHA-256 ([fileSha256]) : les
 * clients le téléchargent une fois puis le gardent en cache.
 */
data class EmoteListing(
    val id: UUID,
    val name: String,
    val description: String,
    val author: String,
    /** `null` : sans catégorie. */
    val categoryId: String?,
    val rarity: EmoteRarity,
    val access: EmoteAccess,
    /** Prix de base en cristaux, pour une émote en vente. */
    val price: Long,
    /** Réduction propre à cette émote, en %, jusqu'à [discountEndsAtMs]. */
    val discountPercent: Int,
    val discountEndsAtMs: Long,
    /** `false` : brouillon, visible des seuls administrateurs. */
    val published: Boolean,
    /** Badge « NEW » affiché jusqu'à cette date (0 : pas de badge). */
    val newUntilMs: Long,
    val sortOrder: Int,
    val fileSha256: String,
    val fileSize: Int,
    /** Durée de l'animation en ticks (hors boucle). */
    val durationTicks: Int,
    val loops: Boolean,
    val hasIcon: Boolean,
    val addedAtMs: Long,
    /** Se joue en se déplaçant (une course…) : marcher ou voler ne l'arrête pas, s'accroupir oui. */
    val playableWhileMoving: Boolean = false
) {
    val forSale: Boolean get() = published && access == EmoteAccess.SHOP && price > 0

    fun isNew(nowMs: Long): Boolean = newUntilMs > nowMs

    /** Réduction propre à l'émote si elle court encore, sinon 0. */
    fun itemDiscount(nowMs: Long): Int =
        if (discountPercent in 1..EmoteShopSettings.MAX_PROMO_PERCENT && discountEndsAtMs > nowMs) discountPercent else 0

    companion object {
        const val MAX_NAME_LENGTH = 48
        const val MAX_DESCRIPTION_LENGTH = 160
        const val MAX_AUTHOR_LENGTH = 48
    }
}

/** Catégorie de la boutique et de la collection (« Danses », « Assis », …). */
data class EmoteCategory(val id: String, val name: String, val sortOrder: Int = 0) {
    companion object {
        const val MAX_NAME_LENGTH = 32
    }
}

/**
 * Réglages communs de la boutique, partagés par tous les serveurs.
 *
 * La promotion globale s'ajoute aux réductions propres à chaque émote : c'est la plus forte des deux
 * qui s'applique. Même calcul côté client (affichage) et côté serveur (débit).
 */
data class EmoteShopSettings(
    val promoPercent: Int = 0,
    val promoEndsAtMs: Long = 0L,
    /** Durée du badge « NEW » donnée à une émote ajoutée au catalogue. */
    val newDays: Int = DEFAULT_NEW_DAYS
) {
    fun promoActive(nowMs: Long): Boolean = promoPercent in 1..MAX_PROMO_PERCENT && promoEndsAtMs > nowMs

    /** Réduction appliquée à [listing] maintenant (0 si elle n'est pas en vente). */
    fun discountOf(listing: EmoteListing, nowMs: Long): Int {
        if (!listing.forSale) return 0
        val global = if (promoActive(nowMs)) promoPercent else 0
        return maxOf(global, listing.itemDiscount(nowMs))
    }

    /** Prix réellement débité, ou `null` si l'émote ne s'achète pas. */
    fun priceOf(listing: EmoteListing, nowMs: Long): Long? =
        if (listing.forSale) discounted(listing.price, discountOf(listing, nowMs)) else null

    /** Fin de la réduction qui s'applique à [listing] (0 s'il n'y en a pas). */
    fun discountEndOf(listing: EmoteListing, nowMs: Long): Long {
        val percent = discountOf(listing, nowMs)
        if (percent <= 0) return 0L
        val item = listing.itemDiscount(nowMs)
        return if (item >= percent && item > 0) listing.discountEndsAtMs else promoEndsAtMs
    }

    companion object {
        const val MAX_PRICE = 100_000_000L
        const val MAX_PROMO_PERCENT = 90
        const val MAX_DAYS = 365
        const val DEFAULT_NEW_DAYS = 14
        const val DAY_MS = 86_400_000L

        /** Arrondi en faveur du joueur, mais une émote payante ne devient jamais gratuite. */
        fun discounted(price: Long, percent: Int): Long =
            if (percent <= 0) price else maxOf(1L, price * (100 - percent) / 100)
    }
}
