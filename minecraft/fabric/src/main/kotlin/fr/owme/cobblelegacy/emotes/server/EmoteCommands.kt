package fr.owme.cobblelegacy.emotes.server

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.network.EmoteEditorActionPayload
import fr.owme.cobblelegacy.emotes.network.EmoteEditorSavePayload
import fr.owme.cobblelegacy.emotes.network.EmoteShopSettingsPayload
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.commands.arguments.GameProfileArgument
import net.minecraft.network.chat.Component

/**
 * `/emoteshop` : gestion du catalogue sans passer par l'éditeur (dons pour Tebex ou les événements,
 * import d'un dossier, promotions). Réservé au niveau de permission de l'éditeur.
 */
object EmoteCommands {

    private val emoteSuggestions = SuggestionProvider<CommandSourceStack> { _, builder ->
        SharedSuggestionProvider.suggest(ServerEmoteCatalog.listings.values.map { quote(it.name) }, builder)
    }

    private fun quote(name: String): String =
        if (name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == '+' }) name
        else "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("emoteshop")
                .requires { it.hasPermission(EmoteServerConfig.gameplay.niveauAdmin) }
                .then(Commands.literal("liste").executes(::list))
                .then(
                    Commands.literal("donner").then(
                        Commands.argument("joueurs", GameProfileArgument.gameProfile()).then(
                            Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions).executes { give(it, true) }
                        )
                    )
                )
                .then(
                    Commands.literal("retirer").then(
                        Commands.argument("joueurs", GameProfileArgument.gameProfile()).then(
                            Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions).executes { give(it, false) }
                        )
                    )
                )
                .then(
                    Commands.literal("possedees").then(
                        Commands.argument("joueurs", GameProfileArgument.gameProfile()).executes(::owned)
                    )
                )
                .then(
                    Commands.literal("importer")
                        .executes { import(it, false) }
                        .then(Commands.literal("publier").executes { import(it, true) })
                )
                .then(Commands.literal("recharger").executes(::reload))
                .then(
                    Commands.literal("promo")
                        .then(Commands.literal("stop").executes { promo(it, 0, 0) })
                        .then(
                            Commands.argument("pourcent", IntegerArgumentType.integer(1, EmoteShopSettings.MAX_PROMO_PERCENT)).then(
                                Commands.argument("jours", IntegerArgumentType.integer(1, EmoteShopSettings.MAX_DAYS)).executes {
                                    promo(it, IntegerArgumentType.getInteger(it, "pourcent"), IntegerArgumentType.getInteger(it, "jours"))
                                }
                            )
                        )
                )
                .then(
                    Commands.literal("publier").then(
                        Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions)
                            .executes { action(it, EmoteEditorActionPayload.Action.PUBLISH) }
                    )
                )
                .then(
                    Commands.literal("brouillon").then(
                        Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions)
                            .executes { action(it, EmoteEditorActionPayload.Action.UNPUBLISH) }
                    )
                )
                .then(
                    Commands.literal("prix").then(
                        Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions).then(
                            Commands.argument("prix", LongArgumentType.longArg(0, EmoteShopSettings.MAX_PRICE)).executes(::price)
                        )
                    )
                )
                .then(
                    Commands.literal("nouveau").then(
                        Commands.argument("emote", StringArgumentType.string()).suggests(emoteSuggestions).then(
                            Commands.argument("jours", IntegerArgumentType.integer(0, EmoteShopSettings.MAX_DAYS)).executes(::markNew)
                        )
                    )
                )
        )
    }

    private fun ok(context: CommandContext<CommandSourceStack>, message: String) =
        context.source.sendSuccess({ Component.literal(message).withStyle(ChatFormatting.GREEN) }, true)

    private fun fail(context: CommandContext<CommandSourceStack>, message: String) =
        context.source.sendFailure(Component.literal(message))

    private fun emoteArg(context: CommandContext<CommandSourceStack>): EmoteListing? {
        val name = StringArgumentType.getString(context, "emote")
        val listing = ServerEmoteCatalog.findByName(name)
        if (listing == null) fail(context, "Aucune émote « $name » dans le catalogue.")
        return listing
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val listings = ServerEmoteCatalog.listings.values.sortedBy { it.sortOrder }
        if (listings.isEmpty()) {
            context.source.sendSuccess({ Component.literal("Le catalogue d'émotes est vide.").withStyle(ChatFormatting.GRAY) }, false)
            return 0
        }
        val now = System.currentTimeMillis()
        context.source.sendSuccess({ Component.literal("Catalogue d'émotes (${listings.size}) :").withStyle(ChatFormatting.GOLD) }, false)
        listings.forEach { listing ->
            val price = when (listing.access) {
                EmoteAccess.FREE -> "gratuite"
                EmoteAccess.EXCLUSIVE -> "exclusive"
                EmoteAccess.SHOP -> ServerEmoteCatalog.settings.priceOf(listing, now)?.let { "${EmoteShopServer.crystals(it)} cristaux" } ?: "hors vente"
            }
            val flags = buildList {
                if (!listing.published) add("brouillon")
                if (listing.isNew(now)) add("NEW")
                ServerEmoteCatalog.settings.discountOf(listing, now).takeIf { it > 0 }?.let { add("-$it %") }
            }.joinToString(", ")
            context.source.sendSuccess({
                Component.literal(" • ${listing.name} — $price" + if (flags.isNotEmpty()) " ($flags)" else "").withStyle(ChatFormatting.GRAY)
            }, false)
        }
        return listings.size
    }

    private fun give(context: CommandContext<CommandSourceStack>, give: Boolean): Int {
        val storage = ServerEmoteCatalog.storage
        if (storage == null || !ServerEmoteCatalog.ready) {
            fail(context, "Le catalogue d'émotes est indisponible sur ce serveur.")
            return 0
        }
        val listing = emoteArg(context) ?: return 0
        val profiles = GameProfileArgument.getGameProfiles(context, "joueurs")
        val server = context.source.server
        profiles.forEach { profile ->
            val write = if (give) storage.grant(profile.id, listing.id, "command") else storage.revoke(profile.id, listing.id)
            write.thenAccept { done ->
                server.execute {
                    if (!done) {
                        fail(context, "Échec pour ${profile.name} (base de données).")
                        return@execute
                    }
                    if (give) EmoteOwnership.addLocal(server, profile.id, listing.id) else EmoteOwnership.removeLocal(server, profile.id, listing.id)
                    ok(context, if (give) "« ${listing.name} » donnée à ${profile.name}." else "« ${listing.name} » retirée à ${profile.name}.")
                }
            }
        }
        return profiles.size
    }

    private fun owned(context: CommandContext<CommandSourceStack>): Int {
        val storage = ServerEmoteCatalog.storage ?: return 0
        val server = context.source.server
        GameProfileArgument.getGameProfiles(context, "joueurs").forEach { profile ->
            storage.loadOwned(profile.id).thenAccept { set ->
                server.execute {
                    val names = set?.mapNotNull { ServerEmoteCatalog.listings[it]?.name }?.sorted() ?: emptyList()
                    context.source.sendSuccess({
                        Component.literal("${profile.name} possède ${names.size} émote(s)" + if (names.isNotEmpty()) " : ${names.joinToString(", ")}" else ".")
                            .withStyle(ChatFormatting.GRAY)
                    }, false)
                }
            }
        }
        return 1
    }

    private fun import(context: CommandContext<CommandSourceStack>, publish: Boolean): Int {
        val author = context.source.player?.uuid
        EmoteAdmin.importFolder(context.source.server, publish, author) { report, error ->
            if (report == null) {
                fail(context, error ?: "Import impossible.")
                return@importFolder
            }
            if (report.added.isEmpty() && report.skipped.isEmpty()) {
                fail(context, "Aucun fichier .emotecraft ou .json dans config/cobblelegacy-emotes/import/.")
                return@importFolder
            }
            if (report.added.isNotEmpty()) {
                ok(context, "${report.added.size} émote(s) importée(s)${if (publish) " et publiée(s)" else " en brouillon"} : ${report.added.joinToString(", ")}")
            }
            if (report.skipped.isNotEmpty()) fail(context, "Ignorées : ${report.skipped.joinToString(", ")}")
            error?.let { fail(context, it) }
        }
        return 1
    }

    private fun reload(context: CommandContext<CommandSourceStack>): Int {
        EmoteServerConfig.load()
        ServerEmoteCatalog.reload { ok(context, "Catalogue d'émotes rechargé (${ServerEmoteCatalog.listings.size} émotes).") }
        EmoteOwnership.syncOnline(context.source.server)
        return 1
    }

    private fun promo(context: CommandContext<CommandSourceStack>, percent: Int, days: Int): Int {
        val payload = EmoteShopSettingsPayload(percent, days * EmoteShopSettings.DAY_MS, ServerEmoteCatalog.settings.newDays)
        EmoteAdmin.settings(context.source.server, payload) { error ->
            if (error != null) fail(context, error)
            else ok(context, if (percent == 0) "Promotion arrêtée." else "Promotion de -$percent % sur toutes les émotes pendant $days jour(s).")
        }
        return 1
    }

    private fun action(context: CommandContext<CommandSourceStack>, action: EmoteEditorActionPayload.Action): Int {
        val listing = emoteArg(context) ?: return 0
        EmoteAdmin.action(context.source.server, EmoteEditorActionPayload(action, listing.id)) { error ->
            if (error != null) fail(context, error)
            else ok(context, if (action == EmoteEditorActionPayload.Action.PUBLISH) "« ${listing.name} » est publiée." else "« ${listing.name} » repasse en brouillon.")
        }
        return 1
    }

    private fun price(context: CommandContext<CommandSourceStack>): Int {
        val listing = emoteArg(context) ?: return 0
        val price = LongArgumentType.getLong(context, "prix")
        val access = if (price == 0L) EmoteAccess.FREE else EmoteAccess.SHOP
        save(context, listing.copyForSave(access = access, price = price)) {
            if (price == 0L) "« ${listing.name} » devient gratuite." else "« ${listing.name} » coûte maintenant ${EmoteShopServer.crystals(price)} cristaux."
        }
        return 1
    }

    private fun markNew(context: CommandContext<CommandSourceStack>): Int {
        val listing = emoteArg(context) ?: return 0
        val days = IntegerArgumentType.getInteger(context, "jours")
        save(context, listing.copyForSave(newDurationMs = days * EmoteShopSettings.DAY_MS)) {
            if (days == 0) "« ${listing.name} » n'a plus le badge NEW." else "« ${listing.name} » porte le badge NEW pendant $days jour(s)."
        }
        return 1
    }

    private fun save(context: CommandContext<CommandSourceStack>, payload: EmoteEditorSavePayload, success: () -> String) {
        EmoteAdmin.save(context.source.server, context.source.player?.uuid, payload) { error ->
            if (error != null) fail(context, error) else ok(context, success())
        }
    }

    /** L'émote telle quelle, sauf les champs donnés : les fins de réduction et de badge sont gardées. */
    private fun EmoteListing.copyForSave(
        access: EmoteAccess = this.access,
        price: Long = this.price,
        newDurationMs: Long = EmoteEditorSavePayload.KEEP
    ) = EmoteEditorSavePayload(
        creating = false,
        id = id,
        name = name,
        description = description,
        author = author,
        categoryId = categoryId ?: "",
        rarity = rarity,
        access = access,
        price = price,
        discountPercent = if (discountEndsAtMs > System.currentTimeMillis()) discountPercent else 0,
        discountDurationMs = EmoteEditorSavePayload.KEEP,
        published = published,
        newDurationMs = newDurationMs,
        fileSha256 = ""
    )
}
