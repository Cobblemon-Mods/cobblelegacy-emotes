package fr.owme.cobblelegacy.emotes.client

import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.network.EmoteCatalogPayload
import java.util.UUID

/**
 * Ce que le serveur a envoyé : catalogue visible, réglages de la boutique, émotes possédées.
 *
 * Objet simple, pas un état Compose : les menus comparent [revision] à chaque frame et se
 * recomposent quand elle change.
 */
object ClientEmoteCatalog {
    /** Le serveur a le mod : un catalogue (même vide) a été reçu. */
    var received = false
        private set

    var listings: List<EmoteListing> = emptyList()
        private set
    var byId: Map<UUID, EmoteListing> = emptyMap()
        private set
    var categories: List<EmoteCategory> = emptyList()
        private set
    var settings = EmoteShopSettings()
        private set

    var managed = false
        private set
    var editorEnabled = false
        private set
    var shopOpen = false
        private set
    var playsAll = false
        private set
    var allowsLocal = true
        private set

    var owned: Set<UUID> = emptySet()
        private set

    /** Solde de cristaux connu (-1 : pas encore reçu), pour la boutique ouverte hors du Pokématos. */
    var balance = -1L
        private set

    fun applyBalance(value: Long) {
        balance = value
        markChanged()
    }

    /** Augmentée à chaque changement (catalogue, possession, fichier téléchargé…). */
    @Volatile
    var revision = 0
        private set

    fun markChanged() {
        revision++
    }

    fun apply(payload: EmoteCatalogPayload) {
        received = true
        listings = payload.listings.sortedWith(compareBy({ it.sortOrder }, { it.addedAtMs }))
        byId = listings.associateBy { it.id }
        categories = payload.categories.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
        settings = payload.settings
        managed = payload.managed
        editorEnabled = payload.editor
        shopOpen = payload.shopOpen
        playsAll = payload.playsAll
        allowsLocal = payload.allowsLocal
        ClientEmoteFiles.sync(listings)
        markChanged()
    }

    fun applyOwned(owned: Set<UUID>) {
        this.owned = owned
        markChanged()
    }

    fun category(id: String?): EmoteCategory? = id?.let { wanted -> categories.firstOrNull { it.id == wanted } }

    fun clear() {
        received = false
        listings = emptyList()
        byId = emptyMap()
        categories = emptyList()
        settings = EmoteShopSettings()
        managed = false
        editorEnabled = false
        shopOpen = false
        playsAll = false
        allowsLocal = true
        owned = emptySet()
        balance = -1L
        markChanged()
    }
}
