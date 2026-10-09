package fr.owme.cobblelegacy.emotes.selftest

import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.platform.InputConstants
import dev.kosmx.playerAnim.core.data.KeyframeAnimation
import fr.owme.cobblelegacy.emotes.EmoteFiles
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.client.ClientEmoteCatalog
import fr.owme.cobblelegacy.emotes.client.ClientEmoteFiles
import fr.owme.cobblelegacy.emotes.client.EmoteClientEvents
import fr.owme.cobblelegacy.emotes.client.EmoteLibrary
import fr.owme.cobblelegacy.emotes.client.WheelConfig
import fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorScreen
import fr.owme.cobblelegacy.emotes.client.shop.emoteShopNewCount
import fr.owme.cobblelegacy.emotes.client.ui.ComposeEmoteScreens
import fr.owme.cobblelegacy.emotes.client.ui.collection.EmoteCollectionScreen
import fr.owme.cobblelegacy.emotes.client.ui.wheel.EmoteWheelScreen
import fr.owme.cobblelegacy.emotes.client.ui.wheel.wheelSizeFor
import fr.owme.cobblelegacy.emotes.network.EmoteBuyPayload
import fr.owme.cobblelegacy.emotes.network.EmoteCategoryEditPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopResultPayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import fr.owme.cobblelegacy.emotes.server.EmoteAdmin
import fr.owme.cobblelegacy.emotes.server.EmoteEconomy
import fr.owme.cobblelegacy.emotes.server.EmoteNetworking
import fr.owme.cobblelegacy.emotes.server.EmoteOwnership
import fr.owme.cobblelegacy.emotes.server.EmoteServerConfig
import fr.owme.cobblelegacy.emotes.server.ServerEmoteCatalog
import io.github.kosmx.emotes.api.events.server.ServerEmoteAPI
import io.github.kosmx.emotes.common.network.EmotePacket
import io.github.kosmx.emotes.executor.EmoteInstance
import io.github.kosmx.emotes.main.EmoteHolder
import io.github.kosmx.emotes.main.config.ClientConfig
import io.github.kosmx.emotes.main.network.ClientEmotePlay
import io.github.kosmx.emotes.main.network.ClientPacketManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.WorldDimensions
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.Supplier
import kotlin.math.cos
import kotlin.math.sin

/**
 * Autotest EN JEU, lancé par `./gradlew :minecraft:fabric:runSelftest` (développement seulement :
 * ce code n'entre pas dans le jar du mod).
 *
 * Monde plat, catalogue local (pas de MariaDB), économie de test (100 000 cristaux). Les émotes de
 * `-Dcobblelegacy.emotes.selftestEmotes` (par défaut le dossier Téléchargements) sont importées, puis
 * le test règle le catalogue, joue des émotes, achète, vérifie les refus côté serveur, ouvre la roue,
 * la collection, la boutique, l'onglet « Émotes » du Pokématos (s'il est buildé à côté) et l'éditeur.
 * Captures dans run/screenshots/selftest-*.png ; le journal résume « SELFTEST ok » / « SELFTEST ECHEC »
 * ligne par ligne, puis le jeu se ferme.
 */
object SelfTest {
    private val logger = LoggerFactory.getLogger("Emotes-SelfTest")

    private const val MAX_TICKS = 20 * 900

    /** La course de Naruto du dossier de test (nom en russe). */
    private const val NARUTO = "наруто"

    private var stage = 0
    private var wait = 0
    private var ticks = 0
    private var failures = 0
    private var checks = 0

    private class Step(val label: String, val delay: Int, val ready: (Minecraft) -> Boolean, val action: (Minecraft) -> Boolean)

    private val script = ArrayList<Step>()
    private var cursor = 0
    private var stepTicks = 0

    // État partagé entre les étapes
    private var expected = 0
    private val pending = AtomicInteger(0)
    private val shopResults = ArrayList<EmoteShopResultPayload>()
    private var balanceBefore = 0L
    private var testEmoteId: UUID? = null
    private var pokematosOpened = false
    private var walkedFrom = Vec3.ZERO
    private var pool = BlockPos.ZERO
    private var wheelPageBefore = 0
    private val ids = HashMap<String, UUID>()

    @JvmStatic
    fun start() {
        logger.info("SELFTEST démarre")
        buildScript()
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            try {
                tick(mc)
            } catch (t: Throwable) {
                failures++
                logger.error("SELFTEST étape « {} » en échec", script.getOrNull(cursor)?.label ?: stage, t)
                stage = 99
            }
        }
    }

    private fun tick(mc: Minecraft) {
        if (stage < 99 && ++ticks > MAX_TICKS) {
            failures++
            logger.error("SELFTEST délai dépassé à l'étape « {} » (écran : {})", script.getOrNull(cursor)?.label, mc.screen?.javaClass?.simpleName)
            stage = 99
        }
        if (stage == 2 && mc.screen is PauseScreen) {
            logger.warn("SELFTEST menu pause fermé (Échap reçu de l'extérieur)")
            mc.setScreen(null)
        }
        if (wait > 0) {
            wait--
            return
        }
        when (stage) {
            0 -> if (mc.screen is TitleScreen) {
                mc.options.pauseOnLostFocus = false
                prepareFiles(mc)
                createWorld(mc)
                stage = 1
                wait = 40
            }
            1 -> if (mc.player != null && mc.level != null && mc.screen == null && mc.singleplayerServer != null) {
                stage = 2
                wait = 60
            }
            2 -> {
                if (cursor >= script.size) {
                    stage = 3
                    return
                }
                val step = script[cursor]
                stepTicks++
                if (!step.ready(mc)) {
                    if (stepTicks > 20 * 60) {
                        check("${step.label} (attente trop longue)", false)
                        cursor++
                        stepTicks = 0
                    }
                    return
                }
                if (step.action(mc)) {
                    cursor++
                    stepTicks = 0
                    wait = step.delay
                }
            }
            3 -> {
                logger.info("SELFTEST terminé : {} vérification(s), {} échec(s)", checks, failures)
                stage = 100
                mc.stop()
            }
            99 -> {
                logger.info("SELFTEST interrompu : {} vérification(s), {} échec(s)", checks, failures)
                stage = 100
                mc.stop()
            }
        }
    }

    // ── Construction du script ──

    private fun then(label: String, delay: Int = 10, action: (Minecraft) -> Unit) {
        script += Step(label, delay, { true }) { mc -> action(mc); true }
    }

    private fun waitFor(label: String, delay: Int = 5, condition: (Minecraft) -> Boolean) {
        script += Step(label, delay, condition) { true }
    }

    private fun buildScript() {
        // ── Arrivée : catalogue vide ──
        waitFor("catalogue reçu", 10) { ClientEmoteCatalog.received }
        then("serveur sans catalogue", 5) { mc ->
            check("catalogue reçu du serveur intégré (vide)", ClientEmoteCatalog.listings.isEmpty())
            check("pas de mode boutique sans émote publiée (émotes locales libres)", !ClientEmoteCatalog.managed)
            check("éditeur ouvert à l'opérateur", ClientEmoteCatalog.editorEnabled)
            check("boutique ouverte (économie de test)", ClientEmoteCatalog.shopOpen && onServer { EmoteEconomy.available })
            check("émotes de base d'Emotecraft connues du serveur (${onServer { ServerEmoteCatalog.builtins.size }})", onServer { ServerEmoteCatalog.builtins.size } >= 9)
            shot(mc, "00-arrivee")
        }

        // ── Import des fichiers ──
        then("import du dossier", 10) { mc -> command(mc, "emoteshop importer") }
        waitFor("émotes importées", 20) { onServer { ServerEmoteCatalog.listings.size } >= expected && expected > 0 }
        then("vérification de l'import", 5) { _ ->
            val listings = onServer { ServerEmoteCatalog.listings.values.toList() }
            check("$expected fichiers importés (catalogue : ${listings.size})", listings.size == expected)
            check("importées en brouillon", listings.all { !it.published })
            check("badge NEW donné à l'import (${EmoteShopSettings.DEFAULT_NEW_DAYS} jours)", listings.all { it.isNew(System.currentTimeMillis()) })
            check("fichiers rangés dans import/importees", Files.list(EmoteServerConfig.importDirectory).use { s -> s.noneMatch { it.toString().endsWith(".emotecraft") } })
            listings.forEach { ids[it.name] = it.id }
            // Tailles : ce qui dépasse un paquet Emotecraft (32 Ko) ne pouvait pas être joué en multijoueur.
            var tooBig = 0
            listings.sortedByDescending { it.fileSize }.forEach { listing ->
                val animation = onServer { ServerEmoteCatalog.animationOf(listing.id) }
                val stream = animation?.let { EmoteFiles.streamSize(it) } ?: -1
                if (stream > Short.MAX_VALUE) tooBig++
                logger.info("SELFTEST émote « {} » : fichier {} Ko, paquet complet {} o, icône {}, {}", listing.name, listing.fileSize / 1024, stream, listing.hasIcon, if (listing.loops) "boucle" else "${listing.durationTicks} ticks")
            }
            logger.info("SELFTEST {} émote(s) trop grosse(s) pour un paquet Emotecraft : elles passent en version allégée", tooBig)
            check("toutes les animations lues par le serveur", listings.all { onServer { ServerEmoteCatalog.animationOf(it.id) } != null })
        }

        // ── Réglage du catalogue (comme dans l'éditeur, côté serveur) ──
        then("catégories", 5) { mc ->
            listOf("Danses", "Poses", "Gestes").forEach { name ->
                adminOnServer(mc) { server, done -> EmoteAdmin.category(server, EmoteCategoryEditPayload(EmoteCategoryEditPayload.Action.CREATE, "", name), done) }
            }
        }
        waitFor("catégories créées", 5) { pending.get() == 0 }
        then("prix, raretés, accès, publication", 5) { mc ->
            val categories = onServer { ServerEmoteCatalog.categories }
            check("3 catégories créées (${categories.joinToString { it.name }})", categories.size == 3)
            val dances = categories.first { it.name == "Danses" }.id
            val poses = categories.first { it.name == "Poses" }.id
            val gestures = categories.first { it.name == "Gestes" }.id
            onServer { ServerEmoteCatalog.listings.values.toList() }.forEach { listing ->
                val name = listing.name.lowercase()
                val category = when {
                    listOf("sit", "lay", "lie", "throne", "campfire").any { name.contains(it) } -> poses
                    listOf("dance", "griddy", "macarena", "moonwalk", "flare", "dab", "rat", "run").any { name.contains(it) } -> dances
                    else -> gestures
                }
                var payload = saveOf(listing).copy(categoryId = category, published = true, price = 500)
                when {
                    name.contains("griddy") -> payload = payload.copy(rarity = EmoteRarity.RARE, price = 800)
                    name.contains("moonwalk") -> payload = payload.copy(rarity = EmoteRarity.LEGENDAIRE, price = 2500)
                    name.contains("macarena") -> payload = payload.copy(rarity = EmoteRarity.EPIQUE, price = 600, discountPercent = 25, discountDurationMs = 3 * EmoteShopSettings.DAY_MS)
                    name.contains("blow kiss") -> payload = payload.copy(access = EmoteAccess.FREE, price = 0)
                    name.contains("honored") -> payload = payload.copy(access = EmoteAccess.EXCLUSIVE, price = 0, rarity = EmoteRarity.LEGACY)
                    name.contains("hug") -> payload = payload.copy(newDurationMs = 0)
                    name.contains("breakdance") -> payload = payload.copy(price = 200_000, rarity = EmoteRarity.LEGENDAIRE)
                }
                // La course de Naruto se joue en se déplaçant.
                if (name.contains(NARUTO)) payload = payload.copy(playableWhileMoving = true)
                adminOnServer(mc) { server, done -> EmoteAdmin.save(server, null, payload, done) }
            }
        }
        waitFor("catalogue réglé", 10) { pending.get() == 0 }
        waitFor("catalogue publié côté client et fichiers téléchargés", 20) {
            ClientEmoteCatalog.managed && ClientEmoteCatalog.listings.size == expected && ClientEmoteFiles.pendingCount == 0 &&
                ClientEmoteCatalog.listings.all { ClientEmoteFiles.isReady(it.id) }
        }
        then("téléchargements", 5) { mc ->
            val listings = ClientEmoteCatalog.listings
            check("catalogue publié : le serveur passe en mode boutique", ClientEmoteCatalog.managed)
            check("les $expected fichiers téléchargés et ajoutés à Emotecraft", listings.all { EmoteHolder.list.containsKey(it.id) })
            val cache = mc.gameDirectory.toPath().resolve("cache").resolve("cobblelegacy-emotes")
            check("fichiers gardés en cache (${Files.list(cache).use { it.count() }})", Files.list(cache).use { it.count() } >= expected)
            check("prix réduit de la Macarena : 450 (600 −25 %)", ClientEmoteCatalog.settings.priceOf(byName("macarena"), System.currentTimeMillis()) == 450L)
            check("Hug sans badge NEW", !byName("hug").isNew(System.currentTimeMillis()))
            check("administrateur : tout est jouable", ClientEmoteCatalog.playsAll && EmoteLibrary.entries().filter { it.listing != null }.all { it.unlocked })
            check("course de Naruto réglée « en mouvement »", byName(NARUTO).playableWhileMoving && !byName("cool sit").playableWhileMoving)
        }

        // ── Déplacements : marcher arrête une émote, sauf celles réglées « en mouvement » ──
        then("émote normale", 12) { mc -> check("une émote se lance à l'arrêt", playLocal(mc, "cool sit")) }
        then("marcher avec une émote normale", 15) { mc ->
            check("l'émote joue", playing(mc))
            mc.options.keyUp.setDown(true)
        }
        then("émote normale en marchant", 2) { mc ->
            mc.options.keyUp.setDown(false)
            check("marcher arrête une émote normale", !playing(mc))
        }
        then("émote en mouvement", 12) { mc -> check("la course de Naruto se lance", playLocal(mc, NARUTO)) }
        then("marcher avec l'émote en mouvement", 15) { mc ->
            walkedFrom = mc.player!!.position()
            mc.options.keyUp.setDown(true)
        }
        then("émote en mouvement en marchant", 2) { mc ->
            val distance = mc.player!!.position().distanceTo(walkedFrom)
            check("le joueur a marché (${"%.1f".format(distance)} blocs)", distance > 1.0)
            check("marcher n'arrête pas une émote « en mouvement »", playingEmote(mc) == id(NARUTO))
            ClientEmotePlay.clientStopLocalEmote()
        }
        then("lancer en marchant", 2) { mc ->
            check("en marchant, une émote normale ne se lance pas", !playLocal(mc, "cool sit"))
            check("en marchant, une émote « en mouvement » se lance", playLocal(mc, NARUTO))
        }
        then("s'accroupir", 12) { mc ->
            mc.options.keyUp.setDown(false)
            mc.options.keyShift.setDown(true)
        }
        then("accroupi", 5) { mc ->
            mc.options.keyShift.setDown(false)
            check("s'accroupir arrête l'émote", !playing(mc))
        }
        then("piscine", 12) { mc ->
            onServer {
                val level = player().serverLevel()
                pool = player().blockPosition().offset(12, 0, 0)
                for (dx in -2..2) for (dz in -2..2) for (dy in -3..1) level.setBlock(pool.offset(dx, dy, dz), Blocks.WATER.defaultBlockState(), 3)
            }
            check("course lancée avant de plonger", playLocal(mc, NARUTO))
        }
        then("plonger", 10) { _ -> onServer { player().teleportTo(pool.x + 0.5, pool.y - 1.0, pool.z + 0.5) } }
        then("dans l'eau", 5) { mc ->
            check("le joueur nage", mc.player!!.isInWater && !mc.player!!.onGround())
            check("nager arrête même une émote « en mouvement »", !playing(mc))
            check("pas d'émote à la nage", !playLocal(mc, NARUTO))
        }
        then("sortir de l'eau", 20) { _ -> onServer { player().teleportTo(pool.x + 0.5 - 12, pool.y.toDouble(), pool.z + 0.5) } }

        // ── Écrans en administrateur ──
        then("boutique (administrateur)", 30) { _ -> ComposeEmoteScreens.openShop(null) }
        then("capture boutique admin", 10) { mc -> shot(mc, "01-boutique-admin") }
        then("fermer", 5) { mc -> mc.setScreen(null) }

        // ── Point de vue d'un joueur ──
        then("passer en joueur", 10) { _ ->
            playerMode(true)
        }
        waitFor("catalogue joueur", 5) { !ClientEmoteCatalog.playsAll && !ClientEmoteCatalog.editorEnabled }
        then("droits d'un joueur", 5) { _ ->
            val entries = EmoteLibrary.entries()
            val free = entries.first { it.name.lowercase().contains("blow kiss") }
            check("joueur : émotes payantes verrouillées", entries.filter { it.listing?.access == EmoteAccess.SHOP }.none { it.unlocked })
            check("joueur : émote gratuite débloquée", free.unlocked)
            check("joueur : émotes de base débloquées (${entries.count { it.source.name == "BUILTIN" }})", entries.filter { it.source.name == "BUILTIN" }.all { it.unlocked })
            check("joueur : pas d'éditeur", !ClientEmoteCatalog.editorEnabled)
        }

        // ── Lecture : verrou local, client modifié, émote gratuite ──
        then("émote verrouillée refusée par le client", 10) { mc ->
            val griddy = animation("griddy")
            check("le client refuse de lancer une émote non achetée", !ClientEmotePlay.clientStartLocalEmote(griddy))
            check("le joueur ne joue rien", mc.player!!.`emotecraft$getEmote`()?.isActive != true)
        }
        then("client modifié : envoi direct", 15) { mc ->
            ClientPacketManager.send(EmotePacket.Builder().configureToStreamEmote(animation("griddy"), mc.player!!.uuid), null)
        }
        then("refus du serveur", 5) { mc ->
            check("le serveur refuse l'émote non achetée d'un client modifié", onServer { ServerEmoteAPI.getPlayedEmote(mc.player!!.uuid) } == null)
        }
        then("émote gratuite", 10) { _ ->
            check("une émote gratuite se lance", ClientEmotePlay.clientStartLocalEmote(animation("blow kiss")))
        }
        then("émote gratuite côté serveur", 5) { mc ->
            val played = onServer { ServerEmoteAPI.getPlayedEmote(mc.player!!.uuid) }
            check("le serveur diffuse l'émote gratuite", played?.left?.uuid == ids.entries.first { it.key.lowercase().contains("blow kiss") }.value)
            check("diffusée en version allégée", played?.left?.extraData?.get(EmoteFiles.STUB_MARKER) == true)
            ClientEmotePlay.clientStopLocalEmote()
        }

        // ── Achats ──
        then("achats : écoute", 2) { _ ->
            EmoteClientEvents.shopListener = { shopResults += it }
            balanceBefore = onServer { EmoteEconomy.balance(player().uuid) }
            check("solde de test : $balanceBefore cristaux", balanceBefore == 100_000L)
        }
        then("prix faux", 10) { _ -> buy("griddy", 1) }
        waitFor("réponse prix faux") { shopResults.isNotEmpty() }
        then("achat de la Griddy", 10) { _ ->
            val result = shopResults.removeLast()
            check("prix faux refusé (« ${result.message} »)", !result.success && result.message.contains("prix a changé"))
            buy("griddy", 800)
        }
        waitFor("réponse achat") { shopResults.isNotEmpty() }
        then("Griddy achetée", 10) { _ ->
            val result = shopResults.removeLast()
            check("achat réussi (« ${result.message} »)", result.success)
            check("solde renvoyé : ${result.balance} = $balanceBefore − 800", result.balance == balanceBefore - 800)
            check("possédée côté serveur", onServer { EmoteOwnership.ownedBy(player().uuid) }.contains(id("griddy")))
            buy("griddy", 800)
        }
        waitFor("réponse rachat") { shopResults.isNotEmpty() }
        then("rachat refusé", 10) { _ ->
            val result = shopResults.removeLast()
            check("rachat refusé (« ${result.message} »)", !result.success)
            check("pas de second débit", onServer { EmoteEconomy.balance(player().uuid) } == balanceBefore - 800)
            check("possédée côté client", ClientEmoteCatalog.owned.contains(id("griddy")))
            buy("macarena", 450)
        }
        waitFor("réponse Macarena") { shopResults.isNotEmpty() }
        then("Macarena en promotion", 10) { _ ->
            val result = shopResults.removeLast()
            check("Macarena achetée au prix réduit (« ${result.message} »)", result.success && result.balance == balanceBefore - 800 - 450)
            buy("breakdance", 200_000)
        }
        waitFor("réponse trop chère") { shopResults.isNotEmpty() }
        then("trop chère", 10) { _ ->
            val result = shopResults.removeLast()
            check("émote trop chère refusée (« ${result.message} »)", !result.success && result.message.contains("manque"))
            check("solde inchangé", onServer { EmoteEconomy.balance(player().uuid) } == balanceBefore - 1250)
            buy("honored", 1)
        }
        waitFor("réponse exclusive") { shopResults.isNotEmpty() }
        then("exclusive", 10) { _ ->
            val result = shopResults.removeLast()
            check("émote exclusive non vendue (« ${result.message} »)", !result.success)
            buy("moonwalk", 2500)
        }
        waitFor("réponse moonwalk") { shopResults.isNotEmpty() }
        then("moonwalk achetée", 10) { _ ->
            val result = shopResults.removeLast()
            check("moonwalk achetée (« ${result.message} »)", result.success)
        }
        waitFor("moonwalk débloquée côté client", 5) { EmoteLibrary.entry(id("moonwalk"))?.unlocked == true }
        then("jouer la moonwalk (grosse émote)", 15) { _ ->
            val full = animation("moonwalk")
            logger.info("SELFTEST moonwalk : paquet complet {} o, allégé {} o", EmoteFiles.streamSize(full), EmoteFiles.streamSize(EmoteFiles.stubOf(full)))
            check("la moonwalk se lance", ClientEmotePlay.clientStartLocalEmote(full))
        }
        then("moonwalk côté serveur", 5) { mc ->
            val played = onServer { ServerEmoteAPI.getPlayedEmote(mc.player!!.uuid) }
            check("le serveur diffuse la moonwalk (trop grosse pour Emotecraft d'origine)", played?.left?.uuid == id("moonwalk"))
            check("le joueur la joue avec l'animation complète", mc.player!!.`emotecraft$getEmote`()?.data?.let { it.uuid == id("moonwalk") && it.extraData[EmoteFiles.STUB_MARKER] != true } == true)
            ClientEmotePlay.clientStopLocalEmote()
            EmoteClientEvents.shopListener = null
        }

        // ── Roue ──
        then("préparer la roue", 5) { _ ->
            WheelConfig.page = 0
            listOf("griddy", "blow kiss", "macarena", "moonwalk", "hug").forEachIndexed { slot, name -> WheelConfig.set(0, slot, id(name)) }
            WheelConfig.set(0, 5, null)
            WheelConfig.set(0, 6, null)
            WheelConfig.set(0, 7, ids.values.first { it !in listOf("griddy", "blow kiss", "macarena", "moonwalk", "hug").map(::id) })
        }
        then("ouvrir la roue", 30) { _ -> ComposeEmoteScreens.openWheel() }
        then("capture roue", 5) { mc ->
            check("la roue Compose s'ouvre", mc.screen is EmoteWheelScreen)
            shot(mc, "02-roue")
            pointAtWheelSlot(mc, 0)
        }
        waitFor("survol de la case 1", 20) { mc -> (mc.screen as? EmoteWheelScreen)?.vm?.hovered == 0 }
        then("survol", 5) { mc ->
            check("le pointeur sur la case 1 la sélectionne", (mc.screen as EmoteWheelScreen).vm.hovered == 0)
            shot(mc, "03-roue-survol-griddy")
            pointAtWheelSlot(mc, 4)
        }
        waitFor("survol de la case 5", 5) { mc -> (mc.screen as? EmoteWheelScreen)?.vm?.hovered == 4 }
        then("case verrouillée", 15) { mc ->
            check("une case verrouillée ne se joue pas", !(mc.screen as EmoteWheelScreen).vm.play(4))
        }
        then("capture verrouillée", 5) { mc ->
            shot(mc, "04-roue-verrouillee")
            pointerAway(mc)
        }
        then("molette : un cran vers le bas", 20) { mc ->
            val screen = mc.screen as EmoteWheelScreen
            wheelPageBefore = screen.vm.page
            screen.mouseScrolled(10.0, 10.0, 0.0, -1.0)
        }
        then("molette : un cran vers le haut", 20) { mc ->
            val screen = mc.screen as EmoteWheelScreen
            check("un cran de molette = une page, sans dérive (page ${wheelPageBefore + 1} → ${screen.vm.page + 1})", screen.vm.page == (wheelPageBefore + 1) % WheelConfig.PAGES)
            screen.mouseScrolled(10.0, 10.0, 0.0, 1.0)
        }
        then("molette : retour", 2) { mc ->
            check("le cran inverse revient à la page ${wheelPageBefore + 1}", (mc.screen as EmoteWheelScreen).vm.page == wheelPageBefore)
        }
        then("touche 1", 10) { mc -> mc.screen!!.keyPressed(GLFW.GLFW_KEY_1, 0, 0) }
        then("Griddy depuis la roue", 10) { mc ->
            check("la touche 1 lance la case 1 et ferme la roue", mc.screen == null && mc.player!!.`emotecraft$getEmote`()?.data?.uuid == id("griddy"))
            ClientEmotePlay.clientStopLocalEmote()
        }

        // ── Collection ──
        then("ouvrir la collection", 30) { _ -> ComposeEmoteScreens.openCollection(id("macarena"), null) }
        then("capture collection", 5) { mc ->
            check("la collection Compose s'ouvre", mc.screen is EmoteCollectionScreen)
            shot(mc, "05-collection")
            (mc.screen as EmoteCollectionScreen).vm.select(id("moonwalk"))
        }
        then("collection : verrouillée", 20) { mc -> shot(mc, "06-collection-moonwalk") }
        then("collection : émote verrouillée", 20) { mc ->
            (mc.screen as EmoteCollectionScreen).vm.select(id("breakdance"))
        }
        then("capture verrouillée", 5) { mc -> shot(mc, "07-collection-verrouillee") }
        then("placer dans la roue", 20) { mc ->
            val vm = (mc.screen as EmoteCollectionScreen).vm
            vm.select(id("macarena"))
            vm.startPlacing()
        }
        then("capture placement", 5) { mc ->
            shot(mc, "08-collection-placement")
            val vm = (mc.screen as EmoteCollectionScreen).vm
            vm.assign(0, 5, id("macarena"))
            check("émote placée dans la roue (case 6)", WheelConfig.get(0, 5) == id("macarena"))
            vm.stopPlacing()
            vm.startKeyCapture()
        }
        then("raccourci clavier", 10) { mc ->
            mc.screen!!.keyPressed(GLFW.GLFW_KEY_G, GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_G), 0)
        }
        then("raccourci enregistré", 10) { mc ->
            val key = (EmoteInstance.config as ClientConfig).emoteKeyMap.getR(id("macarena"))
            check("raccourci G enregistré pour la Macarena (${key?.name})", key == InputConstants.getKey(GLFW.GLFW_KEY_G, GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_G)))
            shot(mc, "09-collection-raccourci")
            mc.setScreen(null)
        }
        then("touche G en jeu", 10) { _ ->
            EmoteHolder.handleKeyPress(InputConstants.getKey(GLFW.GLFW_KEY_G, GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_G)))
        }
        then("Macarena au clavier", 10) { mc ->
            check("le raccourci lance la Macarena", mc.player!!.`emotecraft$getEmote`()?.data?.uuid == id("macarena"))
            ClientEmotePlay.clientStopLocalEmote()
        }

        // ── Boutique ──
        then("promotion globale", 5) { mc ->
            adminOnServer(mc) { server, done -> EmoteAdmin.settings(server, EmoteShopSettingsPayload(15, 2 * EmoteShopSettings.DAY_MS, 14), done) }
        }
        waitFor("promotion enregistrée", 10) { pending.get() == 0 && ClientEmoteCatalog.settings.promoActive(System.currentTimeMillis()) }
        then("boutique joueur", 30) { _ ->
            check("promotion -15 % : Griddy (déjà à 800) passerait à 680", ClientEmoteCatalog.settings.priceOf(byName("griddy"), System.currentTimeMillis()) == 680L)
            check("la réduction la plus forte gagne (Macarena -25 %)", ClientEmoteCatalog.settings.discountOf(byName("macarena"), System.currentTimeMillis()) == 25)
            ComposeEmoteScreens.openShop(null)
        }
        then("capture boutique", 10) { mc -> shot(mc, "10-boutique-accueil") }
        then("boutique : fiche", 30) { _ -> ComposeEmoteScreens.openShop(id("rat")) }
        then("capture fiche", 10) { mc -> shot(mc, "11-boutique-fiche") }
        then("boutique : possédée", 30) { _ -> ComposeEmoteScreens.openShop(id("griddy")) }
        then("capture possédée", 10) { mc -> shot(mc, "12-boutique-possedee") }
        then("fermer la boutique", 5) { mc -> mc.setScreen(null) }

        // ── Onglet « Émotes » de la boutique du Pokématos (s'il est dans le client de test) ──
        then("boutique du Pokématos", 40) { mc -> pokematosOpened = openPokematosShop(mc) }
        then("capture Pokématos", 5) { mc ->
            if (pokematosOpened) {
                check("le Pokématos s'ouvre sur l'onglet Émotes", mc.screen != null)
                check("tag NEW de l'onglet : ${emoteShopNewCount()} nouveauté(s) à acheter", emoteShopNewCount() > 0)
                shot(mc, "12b-pokematos-emotes")
                mc.setScreen(null)
            }
        }

        // ── Restrictions d'un joueur ──
        then("joueur : éditeur interdit", 20) { _ ->
            val griddy = byName("griddy")
            ClientPlayNetworking.send(saveOf(griddy).copy(price = 1))
        }
        then("éditeur refusé", 5) { _ ->
            check("un joueur ne peut pas modifier le catalogue", onServer { ServerEmoteCatalog.listings[id("griddy")]?.price } == 800L)
        }

        // ── Éditeur (administrateur) ──
        then("repasser administrateur", 10) { _ -> playerMode(false) }
        waitFor("éditeur disponible", 5) { ClientEmoteCatalog.editorEnabled }
        then("ouvrir l'éditeur", 30) { _ -> ComposeEmoteScreens.openEditor(id("macarena")) }
        then("capture éditeur", 5) { mc ->
            check("l'éditeur Compose s'ouvre", mc.screen is EmoteEditorScreen)
            shot(mc, "13-editeur")
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.update { it.copy(price = "950", playableWhileMoving = true) }
            vm.save()
        }
        waitFor("prix modifié", 10) { onServer { ServerEmoteCatalog.listings[id("macarena")]?.price } == 950L }
        then("bas du formulaire", 40) { mc ->
            // Pointeur du jeu sur le formulaire (colonne de droite), puis quelques crans de molette.
            pointAt(mc, mc.window.screenWidth * 0.82, mc.window.screenHeight * 0.6)
            repeat(6) { mc.screen!!.mouseScrolled(0.0, 0.0, 0.0, -1.0) }
        }
        then("capture du formulaire", 5) { mc ->
            shot(mc, "13b-editeur-mouvement")
            pointerAway(mc)
        }
        then("nouvelle émote : fichier", 10) { mc ->
            check("prix changé depuis l'éditeur (950)", true)
            check("« en mouvement » activé depuis l'éditeur", onServer { ServerEmoteCatalog.listings[id("macarena")]?.playableWhileMoving } == true)
            val source = EmoteHolder.list[id("griddy")]!!.emote.mutableCopy()
            val newId = UUID.randomUUID()
            source.uuid = newId
            source.name = "\"Autotest\""
            val bytes = EmoteFiles.toBinary(source.build())
            val file = mc.gameDirectory.toPath().resolve("selftest").resolve("Autotest.emotecraft")
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
            testEmoteId = newId
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.importFiles(listOf(file))
            check("fichier ajouté en brouillon dans l'éditeur", vm.drafts.any { it.id == newId } && vm.form?.creating == true)
        }
        then("capture brouillon", 15) { mc -> shot(mc, "14-editeur-brouillon") }
        then("enregistrer la nouvelle émote", 5) { mc ->
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.update { it.copy(name = "Autotest", price = "1234", published = true, categoryId = onServer { ServerEmoteCatalog.categories.first().id }) }
            vm.save()
        }
        waitFor("émote envoyée et enregistrée", 10) { onServer { ServerEmoteCatalog.listings[testEmoteId!!] } != null }
        waitFor("émote téléchargée par le client", 10) { ClientEmoteFiles.isReady(testEmoteId!!) }
        then("nouvelle émote vérifiée", 10) { _ ->
            val listing = onServer { ServerEmoteCatalog.listings[testEmoteId!!] }!!
            check("nouvelle émote au catalogue (envoi par morceaux, ${listing.fileSize} o)", listing.price == 1234L && listing.published && listing.isNew(System.currentTimeMillis()))
            check("animation relue par le serveur", onServer { ServerEmoteCatalog.animationOf(testEmoteId!!) } != null)
        }
        then("catégories de l'éditeur", 20) { mc ->
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.tab = fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Tab.CATEGORIES
        }
        then("capture catégories", 5) { mc -> shot(mc, "15-editeur-categories") }
        then("onglet boutique", 20) { mc ->
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.loadShopFields()
            vm.tab = fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Tab.SHOP
        }
        then("capture réglages", 5) { mc ->
            shot(mc, "16-editeur-boutique")
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.promoPercent = "30"
            vm.promoDays = "1"
            vm.saveShopSettings()
        }
        waitFor("promotion modifiée", 10) { onServer { ServerEmoteCatalog.settings.promoPercent } == 30 }
        then("supprimer l'émote de test", 10) { mc ->
            check("promotion réglée depuis l'éditeur (-30 %)", true)
            val vm = (mc.screen as EmoteEditorScreen).vm
            vm.tab = fr.owme.cobblelegacy.emotes.client.editor.EmoteEditorViewModel.Tab.EMOTES
            vm.select(testEmoteId!!)
            vm.action(EmoteEditorActionPayload.Action.DELETE)
            vm.action(EmoteEditorActionPayload.Action.DELETE)
        }
        waitFor("émote supprimée", 10) { onServer { ServerEmoteCatalog.listings.containsKey(testEmoteId!!) } == false }
        then("fin", 20) { mc ->
            check("suppression depuis l'éditeur (deux clics)", true)
            check("l'émote supprimée quitte le client", !ClientEmoteCatalog.byId.containsKey(testEmoteId!!))
            mc.setScreen(null)
            ComposeEmoteScreens.openWheel()
        }
        then("capture finale", 10) { mc -> shot(mc, "17-roue-finale") }
    }

    // ── Outils du test ──

    private fun byName(part: String): EmoteListing = ClientEmoteCatalog.listings.first { it.name.lowercase().contains(part) }

    private fun id(part: String): UUID = ids.entries.first { it.key.lowercase().contains(part) }.value

    private fun animation(part: String): KeyframeAnimation = EmoteHolder.list[id(part)]!!.emote

    /** Comme la roue : par Emotecraft, qui vérifie posture et déplacement. */
    private fun playLocal(mc: Minecraft, part: String): Boolean = EmoteHolder.list[id(part)]!!.playEmote(mc.player!!)

    private fun playing(mc: Minecraft): Boolean = mc.player!!.isPlayingEmote

    private fun playingEmote(mc: Minecraft): UUID? = mc.player!!.`emotecraft$getEmote`()?.takeIf { it.isActive }?.data?.uuid

    private fun buy(part: String, expectedPrice: Long) {
        ClientPlayNetworking.send(EmoteBuyPayload(id(part), expectedPrice))
    }

    /** Une modification du catalogue côté serveur ; [pending] compte celles qui n'ont pas répondu. */
    private fun adminOnServer(mc: Minecraft, block: (net.minecraft.server.MinecraftServer, (String?) -> Unit) -> Unit) {
        pending.incrementAndGet()
        val server = mc.singleplayerServer!!
        server.execute {
            block(server) { error ->
                if (error != null) {
                    check("modification refusée : $error", false)
                }
                pending.decrementAndGet()
            }
        }
    }

    /** Joueur ordinaire (permission au-dessus de celle d'un opérateur solo) ou administrateur. */
    private fun playerMode(player: Boolean) {
        val file = EmoteServerConfig.directory.resolve("emotes.json")
        if (player) {
            Files.writeString(file, """{ "autoriserEmotesHorsCatalogue": false, "adminsJouentTout": false, "niveauAdmin": 5, "prixImportParDefaut": 500 }""")
        } else {
            Files.deleteIfExists(file)
        }
        onServer {
            EmoteServerConfig.load()
            EmoteNetworking.broadcastCatalog()
        }
    }

    private fun saveOf(listing: EmoteListing) = EmoteEditorSavePayload(
        creating = false, id = listing.id, name = listing.name, description = listing.description, author = listing.author,
        categoryId = listing.categoryId ?: "", rarity = listing.rarity, access = listing.access, price = listing.price,
        discountPercent = if (listing.discountEndsAtMs > System.currentTimeMillis()) listing.discountPercent else 0,
        discountDurationMs = EmoteEditorSavePayload.KEEP, published = listing.published,
        newDurationMs = EmoteEditorSavePayload.KEEP, playableWhileMoving = listing.playableWhileMoving, fileSha256 = ""
    )

    private fun player(): ServerPlayer {
        val mc = Minecraft.getInstance()
        return mc.singleplayerServer!!.playerList.getPlayer(mc.player!!.uuid)!!
    }

    private fun <T> onServer(block: () -> T): T {
        val server = Minecraft.getInstance().singleplayerServer!!
        return if (server.isSameThread) block() else server.submit(Supplier { block() }).join()
    }

    private fun command(mc: Minecraft, command: String) {
        mc.player!!.connection.sendCommand(command)
    }

    private fun check(what: String, ok: Boolean) {
        checks++
        if (ok) {
            logger.info("SELFTEST ok : {}", what)
        } else {
            failures++
            logger.error("SELFTEST ECHEC : {}", what)
        }
    }

    /**
     * Pose le pointeur DU JEU (pas celui de Windows, qu'on ne touche jamais) : Composite relit
     * MouseHandler à chaque image et envoie le déplacement à Compose, comme un vrai survol.
     */
    private fun pointAt(mc: Minecraft, x: Double, y: Double) {
        listOf("xpos" to x, "ypos" to y).forEach { (name, value) ->
            MouseHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.setDouble(mc.mouseHandler, value)
        }
    }

    private fun pointerAway(mc: Minecraft) = pointAt(mc, 0.0, 0.0)

    /** Au milieu de la case [slot] de la roue (case 0 en haut, sens horaire). */
    private fun pointAtWheelSlot(mc: Minecraft, slot: Int) {
        val window = mc.window
        val density = window.guiScale * 0.5
        val size = wheelSizeFor((window.width / density).dp, (window.height / density).dp).value * density
        // La colonne (roue, pages, bouton, aide) est centrée : la roue est 40 dp au-dessus du milieu.
        val centerX = window.width / 2.0
        val centerY = window.height / 2.0 - 40 * density
        val angle = Math.toRadians(-90.0 + slot * 45.0)
        val scale = window.screenWidth.toDouble() / window.width
        pointAt(mc, (centerX + cos(angle) * size * 0.37) * scale, (centerY + sin(angle) * size * 0.37) * scale)
    }

    /**
     * La boutique du Pokématos, ouverte sur l'onglet « Émotes » comme le ferait le serveur. Rien si le
     * Pokématos n'est pas dans le client de test (cf. build.gradle : il y entre s'il est buildé à côté).
     */
    private fun openPokematosShop(mc: Minecraft): Boolean {
        if (!FabricLoader.getInstance().isModLoaded("cobblelegacy-pokematos")) {
            logger.info("SELFTEST Pokématos absent du client de test : onglet Émotes non vérifié")
            return false
        }
        val vmClass = Class.forName("fr.owme.cobblelegacy.pokematos.ui.boutique.BoutiqueViewModel")
        val vm = vmClass.getConstructor().newInstance()
        vmClass.getMethod("setSelectedTab", String::class.java).invoke(vm, "emotes")
        vmClass.getMethod("setPlayerName", String::class.java).invoke(vm, mc.player!!.gameProfile.name)
        vmClass.getMethod("setPlayerCrystals", Long::class.javaPrimitiveType).invoke(vm, onServer { EmoteEconomy.balance(mc.player!!.uuid) })
        val screen = Class.forName("fr.owme.cobblelegacy.pokematos.ui.boutique.BoutiqueScreenKt")
            .getMethod("createBoutiqueScreen", vmClass).invoke(null, vm) as Screen
        mc.setScreen(screen)
        return true
    }

    private fun shot(mc: Minecraft, name: String) {
        mc.toasts.clear()
        Screenshot.grab(mc.gameDirectory, "selftest-$name.png", mc.mainRenderTarget) { }
        logger.info("SELFTEST capture {}", name)
    }

    // ── Préparation ──

    /** État propre : catalogue local vide, cache vidé, fichiers d'émotes de test dans import/. */
    private fun prepareFiles(mc: Minecraft) {
        val config = mc.gameDirectory.toPath().resolve("config").resolve("cobblelegacy-emotes")
        deleteTree(config.resolve("local"))
        Files.deleteIfExists(config.resolve("emotes.json"))
        deleteTree(config.resolve("import"))
        deleteTree(mc.gameDirectory.toPath().resolve("cache").resolve("cobblelegacy-emotes"))
        deleteTree(mc.gameDirectory.toPath().resolve("screenshots"))
        val import = config.resolve("import")
        Files.createDirectories(import)
        val source = Path.of(System.getProperty("cobblelegacy.emotes.selftestEmotes") ?: (System.getProperty("user.home") + "/Downloads"))
        Files.list(source).use { files ->
            files.filter { it.fileName.toString().endsWith(".emotecraft") }.forEach { Files.copy(it, import.resolve(it.fileName)) }
        }
        expected = Files.list(import).use { files -> files.filter { it.toString().endsWith(".emotecraft") }.count().toInt() }
        logger.info("SELFTEST {} fichier(s) d'émotes copiés depuis {}", expected, source)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    private fun createWorld(mc: Minecraft) {
        val name = "emotes-selftest-${System.currentTimeMillis()}"
        val settings = LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, GameRules(), WorldDataConfiguration.DEFAULT)
        val flat = Function<RegistryAccess, WorldDimensions> { registries ->
            registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions()
        }
        mc.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(), flat, mc.screen!!)
    }
}
