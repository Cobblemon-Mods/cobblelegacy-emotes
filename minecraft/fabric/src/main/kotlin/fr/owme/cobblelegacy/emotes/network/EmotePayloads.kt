package fr.owme.cobblelegacy.emotes.network

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import java.util.UUID

// Les dates voyagent en durées restantes (ou en âge) : chaque côté les recale sur sa propre horloge,
// une horloge de joueur décalée ne fausse ni les promotions ni les badges « NEW ».

private fun remaining(atMs: Long, nowMs: Long): Long = if (atMs > nowMs) atMs - nowMs else 0L

private fun fromRemaining(remainingMs: Long, nowMs: Long): Long = if (remainingMs > 0) nowMs + remainingMs else 0L

private fun <T : CustomPacketPayload> type(path: String): CustomPacketPayload.Type<T> =
    CustomPacketPayload.Type(CobbleLegacyEmotes.id(path))

private inline fun <T : CustomPacketPayload> codec(
    crossinline write: (FriendlyByteBuf, T) -> Unit,
    crossinline read: (FriendlyByteBuf) -> T
): StreamCodec<FriendlyByteBuf, T> = StreamCodec.of({ buf, value -> write(buf, value) }, { buf -> read(buf) })

private fun FriendlyByteBuf.writeOptionalUuid(id: UUID?) {
    writeBoolean(id != null)
    if (id != null) writeUUID(id)
}

private fun FriendlyByteBuf.readOptionalUuid(): UUID? = if (readBoolean()) readUUID() else null

internal object EmoteCodecs {
    const val SHA_LENGTH = 64

    fun writeListing(buf: FriendlyByteBuf, listing: EmoteListing, nowMs: Long) {
        buf.writeUUID(listing.id)
        buf.writeUtf(listing.name, EmoteListing.MAX_NAME_LENGTH)
        buf.writeUtf(listing.description, EmoteListing.MAX_DESCRIPTION_LENGTH)
        buf.writeUtf(listing.author, EmoteListing.MAX_AUTHOR_LENGTH)
        buf.writeUtf(listing.categoryId ?: "", 64)
        buf.writeEnum(listing.rarity)
        buf.writeEnum(listing.access)
        buf.writeVarLong(listing.price)
        buf.writeVarInt(listing.discountPercent)
        buf.writeVarLong(remaining(listing.discountEndsAtMs, nowMs))
        buf.writeBoolean(listing.published)
        buf.writeVarLong(remaining(listing.newUntilMs, nowMs))
        buf.writeVarInt(listing.sortOrder)
        buf.writeUtf(listing.fileSha256, SHA_LENGTH)
        buf.writeVarInt(listing.fileSize)
        buf.writeVarInt(listing.durationTicks)
        buf.writeBoolean(listing.loops)
        buf.writeBoolean(listing.hasIcon)
        buf.writeVarLong((nowMs - listing.addedAtMs).coerceAtLeast(0))
    }

    fun readListing(buf: FriendlyByteBuf, nowMs: Long): EmoteListing = EmoteListing(
        id = buf.readUUID(),
        name = buf.readUtf(EmoteListing.MAX_NAME_LENGTH),
        description = buf.readUtf(EmoteListing.MAX_DESCRIPTION_LENGTH),
        author = buf.readUtf(EmoteListing.MAX_AUTHOR_LENGTH),
        categoryId = buf.readUtf(64).ifEmpty { null },
        rarity = buf.readEnum(EmoteRarity::class.java),
        access = buf.readEnum(EmoteAccess::class.java),
        price = buf.readVarLong(),
        discountPercent = buf.readVarInt(),
        discountEndsAtMs = fromRemaining(buf.readVarLong(), nowMs),
        published = buf.readBoolean(),
        newUntilMs = fromRemaining(buf.readVarLong(), nowMs),
        sortOrder = buf.readVarInt(),
        fileSha256 = buf.readUtf(SHA_LENGTH),
        fileSize = buf.readVarInt(),
        durationTicks = buf.readVarInt(),
        loops = buf.readBoolean(),
        hasIcon = buf.readBoolean(),
        addedAtMs = nowMs - buf.readVarLong()
    )

    fun writeSettings(buf: FriendlyByteBuf, settings: EmoteShopSettings, nowMs: Long) {
        buf.writeVarInt(settings.promoPercent)
        buf.writeVarLong(remaining(settings.promoEndsAtMs, nowMs))
        buf.writeVarInt(settings.newDays)
    }

    fun readSettings(buf: FriendlyByteBuf, nowMs: Long): EmoteShopSettings = EmoteShopSettings(
        promoPercent = buf.readVarInt(),
        promoEndsAtMs = fromRemaining(buf.readVarLong(), nowMs),
        newDays = buf.readVarInt()
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// SERVEUR → CLIENT
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Le catalogue visible par ce joueur (brouillons compris pour un administrateur), remplacé en entier
 * à chaque envoi.
 */
data class EmoteCatalogPayload(
    val listings: List<EmoteListing>,
    val categories: List<EmoteCategory>,
    val settings: EmoteShopSettings,
    /** Le serveur ne laisse jouer que les émotes du catalogue possédées (ou gratuites). */
    val managed: Boolean,
    /** Ce joueur peut ouvrir l'éditeur du catalogue. */
    val editor: Boolean,
    /** Achats possibles (base et économie disponibles). */
    val shopOpen: Boolean,
    /** Ce joueur joue toutes les émotes du catalogue (administrateur). */
    val playsAll: Boolean,
    /** Les émotes hors catalogue (dossier `emotes` du joueur) restent jouables. */
    val allowsLocal: Boolean
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteCatalogPayload> = TYPE

    companion object {
        const val MAX_LISTINGS = 4096
        const val MAX_CATEGORIES = 256

        val TYPE: CustomPacketPayload.Type<EmoteCatalogPayload> = type("catalog")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteCatalogPayload> = codec({ buf, p ->
            val now = System.currentTimeMillis()
            buf.writeVarInt(p.listings.size)
            p.listings.forEach { EmoteCodecs.writeListing(buf, it, now) }
            buf.writeVarInt(p.categories.size)
            p.categories.forEach {
                buf.writeUtf(it.id, 64)
                buf.writeUtf(it.name, EmoteCategory.MAX_NAME_LENGTH)
                buf.writeVarInt(it.sortOrder)
            }
            EmoteCodecs.writeSettings(buf, p.settings, now)
            buf.writeBoolean(p.managed)
            buf.writeBoolean(p.editor)
            buf.writeBoolean(p.shopOpen)
            buf.writeBoolean(p.playsAll)
            buf.writeBoolean(p.allowsLocal)
        }, { buf ->
            val now = System.currentTimeMillis()
            val listingCount = buf.readVarInt().coerceIn(0, MAX_LISTINGS)
            val listings = List(listingCount) { EmoteCodecs.readListing(buf, now) }
            val categoryCount = buf.readVarInt().coerceIn(0, MAX_CATEGORIES)
            val categories = List(categoryCount) { EmoteCategory(buf.readUtf(64), buf.readUtf(EmoteCategory.MAX_NAME_LENGTH), buf.readVarInt()) }
            EmoteCatalogPayload(
                listings, categories, EmoteCodecs.readSettings(buf, now),
                managed = buf.readBoolean(), editor = buf.readBoolean(), shopOpen = buf.readBoolean(),
                playsAll = buf.readBoolean(), allowsLocal = buf.readBoolean()
            )
        })
    }
}

/** Les émotes que ce joueur possède (achetées ou données), remplacées en entier. */
data class EmoteOwnedPayload(val owned: Set<UUID>) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteOwnedPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteOwnedPayload> = type("owned")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteOwnedPayload> = codec({ buf, p ->
            buf.writeVarInt(p.owned.size)
            p.owned.forEach(buf::writeUUID)
        }, { buf ->
            val count = buf.readVarInt().coerceIn(0, EmoteCatalogPayload.MAX_LISTINGS)
            EmoteOwnedPayload(HashSet<UUID>(count).apply { repeat(count) { add(buf.readUUID()) } })
        })
    }
}

/** Un morceau d'un fichier `.emotecraft` demandé par le client. */
data class EmoteFileChunkPayload(val sha256: String, val index: Int, val count: Int, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteFileChunkPayload> = TYPE

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Les paquets serveur → client sont limités à 1 Mo. */
        const val CHUNK_SIZE = 256 * 1024

        val TYPE: CustomPacketPayload.Type<EmoteFileChunkPayload> = type("file_chunk")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteFileChunkPayload> = codec({ buf, p ->
            buf.writeUtf(p.sha256, EmoteCodecs.SHA_LENGTH)
            buf.writeVarInt(p.index)
            buf.writeVarInt(p.count)
            buf.writeByteArray(p.data)
        }, { buf ->
            EmoteFileChunkPayload(buf.readUtf(EmoteCodecs.SHA_LENGTH), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_SIZE))
        })
    }
}

/** Réponse à un achat : message déjà rédigé, nouveau solde (-1 : inconnu). */
data class EmoteShopResultPayload(val success: Boolean, val message: String, val emoteId: UUID?, val balance: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteShopResultPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteShopResultPayload> = type("shop_result")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteShopResultPayload> = codec({ buf, p ->
            buf.writeBoolean(p.success)
            buf.writeUtf(p.message, 512)
            buf.writeOptionalUuid(p.emoteId)
            buf.writeLong(p.balance)
        }, { buf ->
            EmoteShopResultPayload(buf.readBoolean(), buf.readUtf(512), buf.readOptionalUuid(), buf.readLong())
        })
    }
}

/** Réponse de l'éditeur (enregistrement, suppression, catégories, réglages). */
data class EmoteEditorResultPayload(val success: Boolean, val message: String, val emoteId: UUID?) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteEditorResultPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteEditorResultPayload> = type("editor_result")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteEditorResultPayload> = codec({ buf, p ->
            buf.writeBoolean(p.success)
            buf.writeUtf(p.message, 512)
            buf.writeOptionalUuid(p.emoteId)
        }, { buf ->
            EmoteEditorResultPayload(buf.readBoolean(), buf.readUtf(512), buf.readOptionalUuid())
        })
    }
}

/** Solde de cristaux du joueur (-1 : inconnu), pour la boutique ouverte hors du Pokématos. */
data class EmoteBalancePayload(val balance: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteBalancePayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteBalancePayload> = type("balance")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteBalancePayload> = codec({ buf, p -> buf.writeLong(p.balance) }, { buf -> EmoteBalancePayload(buf.readLong()) })
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CLIENT → SERVEUR
// ═══════════════════════════════════════════════════════════════════════════════

/** Demande du solde de cristaux. */
class EmoteBalanceRequestPayload : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteBalanceRequestPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteBalanceRequestPayload> = type("balance_request")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteBalanceRequestPayload> = codec({ _, _ -> }, { EmoteBalanceRequestPayload() })
    }
}

/** Le client n'a pas ce fichier en cache. */
data class EmoteFileRequestPayload(val sha256: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteFileRequestPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteFileRequestPayload> = type("file_request")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteFileRequestPayload> = codec({ buf, p ->
            buf.writeUtf(p.sha256, EmoteCodecs.SHA_LENGTH)
        }, { buf -> EmoteFileRequestPayload(buf.readUtf(EmoteCodecs.SHA_LENGTH)) })
    }
}

/** Achat : le client annonce le prix qu'il a affiché, le serveur refuse s'il a changé entre-temps. */
data class EmoteBuyPayload(val emoteId: UUID, val expectedPrice: Long) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteBuyPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteBuyPayload> = type("buy")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteBuyPayload> = codec({ buf, p ->
            buf.writeUUID(p.emoteId)
            buf.writeVarLong(p.expectedPrice)
        }, { buf -> EmoteBuyPayload(buf.readUUID(), buf.readVarLong()) })
    }
}

/** Un morceau d'un fichier envoyé depuis l'éditeur. */
data class EmoteUploadChunkPayload(val sha256: String, val index: Int, val count: Int, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteUploadChunkPayload> = TYPE

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        /** Les paquets client → serveur sont limités à 32 Ko. */
        const val CHUNK_SIZE = 30_000

        val TYPE: CustomPacketPayload.Type<EmoteUploadChunkPayload> = type("upload_chunk")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteUploadChunkPayload> = codec({ buf, p ->
            buf.writeUtf(p.sha256, EmoteCodecs.SHA_LENGTH)
            buf.writeVarInt(p.index)
            buf.writeVarInt(p.count)
            buf.writeByteArray(p.data)
        }, { buf ->
            EmoteUploadChunkPayload(buf.readUtf(EmoteCodecs.SHA_LENGTH), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(CHUNK_SIZE))
        })
    }
}

/**
 * Création ou modification d'une émote du catalogue.
 *
 * Les fins de réduction et de badge « NEW » partent en durées : [KEEP] garde la fin actuelle, 0 retire.
 * [fileSha256] vide : le fichier ne change pas (sinon il a été envoyé juste avant, morceau par morceau).
 */
data class EmoteEditorSavePayload(
    val creating: Boolean,
    val id: UUID,
    val name: String,
    val description: String,
    val author: String,
    val categoryId: String,
    val rarity: EmoteRarity,
    val access: EmoteAccess,
    val price: Long,
    val discountPercent: Int,
    val discountDurationMs: Long,
    val published: Boolean,
    val newDurationMs: Long,
    val fileSha256: String
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteEditorSavePayload> = TYPE

    companion object {
        const val KEEP = -1L

        val TYPE: CustomPacketPayload.Type<EmoteEditorSavePayload> = type("editor_save")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteEditorSavePayload> = codec({ buf, p ->
            buf.writeBoolean(p.creating)
            buf.writeUUID(p.id)
            buf.writeUtf(p.name, EmoteListing.MAX_NAME_LENGTH)
            buf.writeUtf(p.description, EmoteListing.MAX_DESCRIPTION_LENGTH)
            buf.writeUtf(p.author, EmoteListing.MAX_AUTHOR_LENGTH)
            buf.writeUtf(p.categoryId, 64)
            buf.writeEnum(p.rarity)
            buf.writeEnum(p.access)
            buf.writeVarLong(p.price)
            buf.writeVarInt(p.discountPercent)
            buf.writeLong(p.discountDurationMs)
            buf.writeBoolean(p.published)
            buf.writeLong(p.newDurationMs)
            buf.writeUtf(p.fileSha256, EmoteCodecs.SHA_LENGTH)
        }, { buf ->
            EmoteEditorSavePayload(
                creating = buf.readBoolean(),
                id = buf.readUUID(),
                name = buf.readUtf(EmoteListing.MAX_NAME_LENGTH),
                description = buf.readUtf(EmoteListing.MAX_DESCRIPTION_LENGTH),
                author = buf.readUtf(EmoteListing.MAX_AUTHOR_LENGTH),
                categoryId = buf.readUtf(64),
                rarity = buf.readEnum(EmoteRarity::class.java),
                access = buf.readEnum(EmoteAccess::class.java),
                price = buf.readVarLong(),
                discountPercent = buf.readVarInt(),
                discountDurationMs = buf.readLong(),
                published = buf.readBoolean(),
                newDurationMs = buf.readLong(),
                fileSha256 = buf.readUtf(EmoteCodecs.SHA_LENGTH)
            )
        })
    }
}

/** Actions de l'éditeur sur une émote : suppression ou déplacement dans l'ordre d'affichage. */
data class EmoteEditorActionPayload(val action: Action, val id: UUID) : CustomPacketPayload {
    enum class Action { DELETE, MOVE_UP, MOVE_DOWN, PUBLISH, UNPUBLISH }

    override fun type(): CustomPacketPayload.Type<EmoteEditorActionPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteEditorActionPayload> = type("editor_action")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteEditorActionPayload> = codec({ buf, p ->
            buf.writeEnum(p.action)
            buf.writeUUID(p.id)
        }, { buf -> EmoteEditorActionPayload(buf.readEnum(Action::class.java), buf.readUUID()) })
    }
}

/** Gestion des catégories. */
data class EmoteCategoryEditPayload(val action: Action, val id: String, val name: String) : CustomPacketPayload {
    enum class Action { CREATE, RENAME, DELETE, MOVE_UP, MOVE_DOWN }

    override fun type(): CustomPacketPayload.Type<EmoteCategoryEditPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteCategoryEditPayload> = type("editor_category")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteCategoryEditPayload> = codec({ buf, p ->
            buf.writeEnum(p.action)
            buf.writeUtf(p.id, 64)
            buf.writeUtf(p.name, EmoteCategory.MAX_NAME_LENGTH)
        }, { buf -> EmoteCategoryEditPayload(buf.readEnum(Action::class.java), buf.readUtf(64), buf.readUtf(EmoteCategory.MAX_NAME_LENGTH)) })
    }
}

/** Promotion globale et durée du badge « NEW ». [promoDurationMs] : [EmoteEditorSavePayload.KEEP] garde la fin actuelle. */
data class EmoteShopSettingsPayload(val promoPercent: Int, val promoDurationMs: Long, val newDays: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<EmoteShopSettingsPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<EmoteShopSettingsPayload> = type("editor_settings")
        val CODEC: StreamCodec<FriendlyByteBuf, EmoteShopSettingsPayload> = codec({ buf, p ->
            buf.writeVarInt(p.promoPercent)
            buf.writeLong(p.promoDurationMs)
            buf.writeVarInt(p.newDays)
        }, { buf -> EmoteShopSettingsPayload(buf.readVarInt(), buf.readLong(), buf.readVarInt()) })
    }
}
