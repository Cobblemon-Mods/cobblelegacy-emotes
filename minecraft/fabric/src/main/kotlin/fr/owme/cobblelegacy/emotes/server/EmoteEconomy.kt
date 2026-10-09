package fr.owme.cobblelegacy.emotes.server

import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import net.fabricmc.loader.api.FabricLoader
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Les cristaux, via l'API de cobblelegacy-economie (par réflexion, comme la boutique du Pokématos et
 * celle des capes) : aucune dépendance de compilation, et le débit est atomique côté base
 * (`UPDATE … WHERE cristaux >= montant`), donc sûr entre serveurs.
 *
 * En développement seulement, sans le mod d'économie, `-Dcobblelegacy.emotes.testEconomy=<solde>`
 * active un porte-monnaie en mémoire pour les auto-tests.
 */
object EmoteEconomy {
    private val logger = CobbleLegacyEmotes.logger

    private var instance: Any? = null
    private var balanceMethod: Method? = null
    private var withdrawMethod: Method? = null
    private var depositMethod: Method? = null

    private val testBalances = ConcurrentHashMap<UUID, Long>()
    private var testStartBalance = -1L

    val available: Boolean by lazy {
        try {
            val type = Class.forName("fr.owme.cobblelegacy.economy.EconomyAPI")
            instance = type.getDeclaredField("INSTANCE").get(null)
            balanceMethod = type.getMethod("getCristaux", UUID::class.java)
            withdrawMethod = type.getMethod("withdrawCristaux", UUID::class.java, Long::class.javaPrimitiveType)
            depositMethod = type.getMethod("depositCristaux", UUID::class.java, Long::class.javaPrimitiveType)
            logger.info("[Émotes] Économie CobbleLegacy détectée (cristaux)")
            true
        } catch (e: ClassNotFoundException) {
            val test = System.getProperty("cobblelegacy.emotes.testEconomy")?.toLongOrNull()
            if (test != null && FabricLoader.getInstance().isDevelopmentEnvironment) {
                testStartBalance = test
                logger.warn("[Émotes] Économie de TEST (développement) : {} cristaux par joueur", test)
                true
            } else {
                logger.warn("[Émotes] cobblelegacy-economie absent : la boutique d'émotes est fermée")
                false
            }
        } catch (e: Exception) {
            logger.error("[Émotes] API d'économie inutilisable", e)
            false
        }
    }

    private val testMode: Boolean get() = available && instance == null

    /** Solde en cristaux, -1 si inconnu. */
    fun balance(player: UUID): Long {
        if (!available) return -1
        if (testMode) return testBalances.getOrPut(player) { testStartBalance }
        return try {
            (balanceMethod!!.invoke(instance, player) as Number).toLong()
        } catch (e: Exception) {
            logger.error("[Émotes] Lecture du solde de {} impossible", player, e)
            -1
        }
    }

    /** Débite [amount] cristaux ; `false` si le solde ne suffit pas ou en cas d'erreur. */
    fun withdraw(player: UUID, amount: Long): Boolean {
        if (!available || amount <= 0) return false
        if (testMode) {
            var ok = false
            testBalances.compute(player) { _, current ->
                val balance = current ?: testStartBalance
                if (balance >= amount) {
                    ok = true
                    balance - amount
                } else balance
            }
            return ok
        }
        return try {
            withdrawMethod!!.invoke(instance, player, amount) as Boolean
        } catch (e: Exception) {
            logger.error("[Émotes] Débit de {} cristaux pour {} impossible", amount, player, e)
            false
        }
    }

    fun deposit(player: UUID, amount: Long): Boolean {
        if (!available || amount <= 0) return false
        if (testMode) {
            testBalances.compute(player) { _, current -> (current ?: testStartBalance) + amount }
            return true
        }
        return try {
            depositMethod!!.invoke(instance, player, amount) as Boolean
        } catch (e: Exception) {
            logger.error("[Émotes] Remboursement de {} cristaux à {} impossible", amount, player, e)
            false
        }
    }
}
