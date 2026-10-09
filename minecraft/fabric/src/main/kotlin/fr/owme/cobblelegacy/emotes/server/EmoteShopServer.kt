package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.network.EmoteBuyPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import fr.owme.cobblelegacy.emotes.server.storage.PurchaseOutcome
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import java.util.Locale
import java.util.UUID

/**
 * Achats d'émotes en cristaux.
 *
 * Le serveur recalcule le prix (promotions comprises) et le compare à celui que le joueur a vu ;
 * le débit passe par l'économie (atomique), puis l'achat est enregistré. Si l'enregistrement échoue,
 * les cristaux sont rendus. Messages en français, déjà rédigés pour le client.
 */
object EmoteShopServer {
    private val logger = CobbleLegacyEmotes.logger

    /** Un achat à la fois par joueur : un double clic ne débite pas deux fois. */
    private val busy = HashSet<UUID>()

    fun onBuy(player: ServerPlayer, payload: EmoteBuyPayload) {
        val uuid = player.uuid
        val emoteId = payload.emoteId
        if (uuid in busy) return reply(player, false, "Un achat est déjà en cours.", emoteId)

        val storage = ServerEmoteCatalog.storage
        if (storage == null || !ServerEmoteCatalog.ready) return reply(player, false, "La boutique d'émotes est indisponible pour le moment.", emoteId)
        if (!EmoteEconomy.available) return reply(player, false, "La boutique est fermée : économie indisponible.", emoteId)
        if (!EmoteOwnership.isLoaded(uuid)) return reply(player, false, "Tes émotes sont encore en chargement, réessaie dans un instant.", emoteId)

        val listing = ServerEmoteCatalog.listings[emoteId]
        if (listing == null || !listing.published) return reply(player, false, "Cette émote n'est plus disponible.", emoteId)
        if (EmoteOwnership.canUse(uuid, listing)) return reply(player, false, "Tu possèdes déjà cette émote.", emoteId)
        val now = System.currentTimeMillis()
        val price = ServerEmoteCatalog.settings.priceOf(listing, now)
            ?: return reply(player, false, "Cette émote n'est pas en vente.", emoteId)
        if (price != payload.expectedPrice) {
            // Une promotion a commencé ou fini, ou un administrateur vient de changer le prix.
            EmoteNetworking.sendCatalog(player)
            return reply(player, false, "Le prix a changé : ${crystals(price)} cristaux. Vérifie avant d'acheter.", emoteId)
        }

        busy.add(uuid)
        if (!EmoteEconomy.withdraw(uuid, price)) {
            busy.remove(uuid)
            return reply(player, false, "Il te manque des cristaux pour cette émote.", emoteId)
        }

        val server = player.server
        val name = player.gameProfile.name
        storage.recordPurchase(uuid, name, emoteId, price).thenAccept { outcome ->
            server.execute {
                busy.remove(uuid)
                val online = server.playerList.getPlayer(uuid)
                when (outcome) {
                    PurchaseOutcome.RECORDED -> {
                        logger.info("[Émotes] {} a acheté « {} » pour {} cristaux", name, listing.name, price)
                        EmoteOwnership.addLocal(server, uuid, emoteId)
                        if (online != null) reply(online, true, "« ${listing.name} » est à toi ! Retrouve-la dans ta roue d'émotes.", emoteId)
                    }
                    PurchaseOutcome.ALREADY_OWNED -> {
                        refund(uuid, price, name)
                        EmoteOwnership.addLocal(server, uuid, emoteId)
                        if (online != null) reply(online, false, "Tu possédais déjà cette émote : tes cristaux ont été rendus.", emoteId)
                    }
                    else -> {
                        val refunded = refund(uuid, price, name)
                        if (online != null) {
                            reply(
                                online, false,
                                if (refunded) "L'achat a échoué : tes cristaux ont été rendus." else "L'achat a échoué. Contacte un administrateur.",
                                emoteId
                            )
                        }
                    }
                }
            }
        }
    }

    private fun refund(player: UUID, price: Long, name: String): Boolean {
        val ok = EmoteEconomy.deposit(player, price)
        if (ok) {
            logger.warn("[Émotes] Achat non enregistré pour {} : {} cristaux remboursés", name, price)
        } else {
            logger.error("[Émotes] Achat non enregistré pour {} : remboursement de {} cristaux EN ÉCHEC", name, price)
        }
        return ok
    }

    private fun reply(player: ServerPlayer, success: Boolean, message: String, emoteId: UUID?) {
        if (!ServerPlayNetworking.canSend(player, EmoteShopResultPayload.TYPE)) return
        ServerPlayNetworking.send(player, EmoteShopResultPayload(success, message, emoteId, EmoteEconomy.balance(player.uuid)))
    }

    fun forget(player: UUID) {
        busy.remove(player)
    }

    fun clear() {
        busy.clear()
    }

    fun crystals(amount: Long): String = "%,d".format(Locale.ROOT, amount).replace(',', ' ')
}
