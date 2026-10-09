package fr.owme.cobblelegacy.emotes.server.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import fr.owme.cobblelegacy.emotes.catalog.EmoteAccess
import fr.owme.cobblelegacy.emotes.catalog.EmoteCategory
import fr.owme.cobblelegacy.emotes.catalog.EmoteListing
import fr.owme.cobblelegacy.emotes.catalog.EmoteRarity
import fr.owme.cobblelegacy.emotes.catalog.EmoteShopSettings
import fr.owme.cobblelegacy.emotes.server.DatabaseSettings
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Catalogue et émotes possédées dans la base MariaDB du réseau, partagée par tous les serveurs.
 *
 * Même organisation que le mod des capes : un pool HikariCP, un seul thread pour toutes les requêtes,
 * des transactions pour les écritures en plusieurs morceaux, et un compteur de révision que chaque
 * serveur relit régulièrement pour voir les changements faits ailleurs.
 */
class SqlEmoteStorage(private val settings: DatabaseSettings) : EmoteStorage {

    override val shared = true
    override val label = "MariaDB ${settings.host}:${settings.port}/${settings.database}"

    private val logger = CobbleLegacyEmotes.logger
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CobbleLegacyEmotes-DB").apply { isDaemon = true }
    }

    @Volatile
    private var dataSource: HikariDataSource? = null

    private fun <T> submit(task: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(task, executor)

    override fun start(): CompletableFuture<Boolean> = submit {
        try {
            val config = HikariConfig().apply {
                jdbcUrl = "jdbc:mariadb://${settings.host}:${settings.port}/${settings.database}"
                username = settings.user
                password = settings.password
                driverClassName = "org.mariadb.jdbc.Driver"
                maximumPoolSize = settings.poolSize.coerceIn(1, 16)
                minimumIdle = 1
                connectionTimeout = 10_000
                idleTimeout = 300_000
                maxLifetime = 600_000
                poolName = "CobbleLegacyEmotes-Pool"
            }
            val source = HikariDataSource(config)
            source.connection.use { createTables(it) }
            dataSource = source
            logger.info("[Émotes] Connecté à {}", label)
            true
        } catch (e: Exception) {
            logger.error("[Émotes] Connexion à {} impossible", label, e)
            false
        }
    }

    override fun stop() {
        dataSource?.close()
        dataSource = null
    }

    private fun createTables(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}catalog (
                    id               CHAR(36)     NOT NULL PRIMARY KEY,
                    name             VARCHAR(64)  NOT NULL,
                    description      VARCHAR(255) NOT NULL DEFAULT '',
                    author           VARCHAR(64)  NOT NULL DEFAULT '',
                    category_id      VARCHAR(64)  NULL,
                    rarity           VARCHAR(16)  NOT NULL,
                    access           VARCHAR(16)  NOT NULL,
                    price            BIGINT       NOT NULL DEFAULT 0,
                    discount_percent INT          NOT NULL DEFAULT 0,
                    discount_ends_at BIGINT       NOT NULL DEFAULT 0,
                    published        BOOLEAN      NOT NULL DEFAULT FALSE,
                    new_until        BIGINT       NOT NULL DEFAULT 0,
                    sort_order       INT          NOT NULL DEFAULT 0,
                    file_sha256      CHAR(64)     NOT NULL,
                    file_size        INT          NOT NULL DEFAULT 0,
                    duration_ticks   INT          NOT NULL DEFAULT 0,
                    loops            BOOLEAN      NOT NULL DEFAULT FALSE,
                    has_icon         BOOLEAN      NOT NULL DEFAULT FALSE,
                    added_at         BIGINT       NOT NULL,
                    created_by       CHAR(36)     NULL,
                    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    playable_while_moving BOOLEAN NOT NULL DEFAULT FALSE
                )
                """.trimIndent()
            )
            // Ajoutée après la première version : les tables déjà créées la reçoivent ici.
            statement.executeUpdate(
                "ALTER TABLE ${PREFIX}catalog ADD COLUMN IF NOT EXISTS playable_while_moving BOOLEAN NOT NULL DEFAULT FALSE"
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}files (
                    sha256     CHAR(64)  NOT NULL PRIMARY KEY,
                    data       LONGBLOB  NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}categories (
                    id         VARCHAR(64) NOT NULL PRIMARY KEY,
                    name       VARCHAR(64) NOT NULL,
                    sort_order INT         NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}owned (
                    player_uuid CHAR(36)    NOT NULL,
                    emote_id    CHAR(36)    NOT NULL,
                    source      VARCHAR(16) NOT NULL DEFAULT 'shop',
                    acquired_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_uuid, emote_id)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}purchases (
                    id          BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    player_uuid CHAR(36)    NOT NULL,
                    player_name VARCHAR(16) NOT NULL,
                    emote_id    CHAR(36)    NOT NULL,
                    price       BIGINT      NOT NULL,
                    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_player (player_uuid)
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}settings (
                    name  VARCHAR(64)  NOT NULL PRIMARY KEY,
                    value VARCHAR(255) NOT NULL
                )
                """.trimIndent()
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS ${PREFIX}meta (
                    name  VARCHAR(64) NOT NULL PRIMARY KEY,
                    value BIGINT      NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    // ─── Catalogue ───────────────────────────────────────────────────────────────

    override fun revision(): CompletableFuture<Long?> = submit {
        withConnection(null) { connection -> readRevision(connection) }
    }

    private fun readRevision(connection: Connection): Long =
        connection.prepareStatement("SELECT value FROM ${PREFIX}meta WHERE name = 'catalog_revision'").use { statement ->
            statement.executeQuery().use { if (it.next()) it.getLong(1) else 0L }
        }

    private fun bumpRevision(connection: Connection) {
        connection.prepareStatement(
            "INSERT INTO ${PREFIX}meta (name, value) VALUES ('catalog_revision', 1) ON DUPLICATE KEY UPDATE value = value + 1"
        ).use { it.executeUpdate() }
    }

    override fun loadCatalog(): CompletableFuture<CatalogSnapshot?> = submit {
        withConnection(null) { connection ->
            val revision = readRevision(connection)
            val listings = connection.prepareStatement("SELECT * FROM ${PREFIX}catalog ORDER BY sort_order, added_at").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) readListing(rows)?.let(::add) }
                }
            }
            val categories = connection.prepareStatement("SELECT id, name, sort_order FROM ${PREFIX}categories ORDER BY sort_order, name").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(EmoteCategory(rows.getString(1), rows.getString(2), rows.getInt(3))) }
                }
            }
            val values = connection.prepareStatement("SELECT name, value FROM ${PREFIX}settings").use { statement ->
                statement.executeQuery().use { rows ->
                    buildMap { while (rows.next()) put(rows.getString(1), rows.getString(2)) }
                }
            }
            val shop = EmoteShopSettings(
                promoPercent = values["promo_percent"]?.toIntOrNull()?.coerceIn(0, EmoteShopSettings.MAX_PROMO_PERCENT) ?: 0,
                promoEndsAtMs = values["promo_ends_at"]?.toLongOrNull() ?: 0L,
                newDays = values["new_days"]?.toIntOrNull()?.coerceIn(0, EmoteShopSettings.MAX_DAYS) ?: EmoteShopSettings.DEFAULT_NEW_DAYS
            )
            CatalogSnapshot(listings, categories, shop, revision)
        }
    }

    private fun readListing(rows: ResultSet): EmoteListing? = try {
        EmoteListing(
            id = UUID.fromString(rows.getString("id")),
            name = rows.getString("name"),
            description = rows.getString("description"),
            author = rows.getString("author"),
            categoryId = rows.getString("category_id"),
            rarity = EmoteRarity.parse(rows.getString("rarity")),
            access = EmoteAccess.parse(rows.getString("access")),
            price = rows.getLong("price"),
            discountPercent = rows.getInt("discount_percent"),
            discountEndsAtMs = rows.getLong("discount_ends_at"),
            published = rows.getBoolean("published"),
            newUntilMs = rows.getLong("new_until"),
            sortOrder = rows.getInt("sort_order"),
            fileSha256 = rows.getString("file_sha256"),
            fileSize = rows.getInt("file_size"),
            durationTicks = rows.getInt("duration_ticks"),
            loops = rows.getBoolean("loops"),
            hasIcon = rows.getBoolean("has_icon"),
            addedAtMs = rows.getLong("added_at"),
            playableWhileMoving = rows.getBoolean("playable_while_moving")
        )
    } catch (e: Exception) {
        logger.warn("[Émotes] Ligne de catalogue illisible ignorée : {}", e.message)
        null
    }

    override fun readFile(sha256: String): CompletableFuture<ByteArray?> = submit {
        withConnection(null) { connection ->
            connection.prepareStatement("SELECT data FROM ${PREFIX}files WHERE sha256 = ?").use { statement ->
                statement.setString(1, sha256)
                statement.executeQuery().use { if (it.next()) it.getBytes(1) else null }
            }
        }
    }

    override fun saveListing(listing: EmoteListing, file: ByteArray?, author: UUID?): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                if (file != null) {
                    connection.prepareStatement("INSERT IGNORE INTO ${PREFIX}files (sha256, data) VALUES (?, ?)").use { statement ->
                        statement.setString(1, listing.fileSha256)
                        statement.setBytes(2, file)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    """
                    INSERT INTO ${PREFIX}catalog (id, name, description, author, category_id, rarity, access, price,
                        discount_percent, discount_ends_at, published, new_until, sort_order, file_sha256, file_size,
                        duration_ticks, loops, has_icon, added_at, created_by, playable_while_moving)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), author = VALUES(author),
                        category_id = VALUES(category_id), rarity = VALUES(rarity), access = VALUES(access), price = VALUES(price),
                        discount_percent = VALUES(discount_percent), discount_ends_at = VALUES(discount_ends_at),
                        published = VALUES(published), new_until = VALUES(new_until), sort_order = VALUES(sort_order),
                        file_sha256 = VALUES(file_sha256), file_size = VALUES(file_size), duration_ticks = VALUES(duration_ticks),
                        loops = VALUES(loops), has_icon = VALUES(has_icon), playable_while_moving = VALUES(playable_while_moving)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, listing.id.toString())
                    statement.setString(2, listing.name)
                    statement.setString(3, listing.description)
                    statement.setString(4, listing.author)
                    statement.setString(5, listing.categoryId)
                    statement.setString(6, listing.rarity.key)
                    statement.setString(7, listing.access.name)
                    statement.setLong(8, listing.price)
                    statement.setInt(9, listing.discountPercent)
                    statement.setLong(10, listing.discountEndsAtMs)
                    statement.setBoolean(11, listing.published)
                    statement.setLong(12, listing.newUntilMs)
                    statement.setInt(13, listing.sortOrder)
                    statement.setString(14, listing.fileSha256)
                    statement.setInt(15, listing.fileSize)
                    statement.setInt(16, listing.durationTicks)
                    statement.setBoolean(17, listing.loops)
                    statement.setBoolean(18, listing.hasIcon)
                    statement.setLong(19, listing.addedAtMs)
                    statement.setString(20, author?.toString())
                    statement.setBoolean(21, listing.playableWhileMoving)
                    statement.executeUpdate()
                }
                deleteOrphanFiles(connection)
                bumpRevision(connection)
            }
            true
        }
    }

    override fun deleteListing(id: UUID): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                connection.prepareStatement("DELETE FROM ${PREFIX}catalog WHERE id = ?").use { statement ->
                    statement.setString(1, id.toString())
                    statement.executeUpdate()
                }
                // Les émotes possédées restent : remise au catalogue, l'émote revient à ses propriétaires.
                deleteOrphanFiles(connection)
                bumpRevision(connection)
            }
            true
        }
    }

    private fun deleteOrphanFiles(connection: Connection) {
        connection.prepareStatement(
            "DELETE FROM ${PREFIX}files WHERE sha256 NOT IN (SELECT file_sha256 FROM ${PREFIX}catalog)"
        ).use { it.executeUpdate() }
    }

    override fun saveSortOrders(orders: Map<UUID, Int>): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                connection.prepareStatement("UPDATE ${PREFIX}catalog SET sort_order = ? WHERE id = ?").use { statement ->
                    orders.forEach { (id, order) ->
                        statement.setInt(1, order)
                        statement.setString(2, id.toString())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                bumpRevision(connection)
            }
            true
        }
    }

    override fun setPublished(ids: Collection<UUID>, published: Boolean): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                connection.prepareStatement("UPDATE ${PREFIX}catalog SET published = ? WHERE id = ?").use { statement ->
                    ids.forEach { id ->
                        statement.setBoolean(1, published)
                        statement.setString(2, id.toString())
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                bumpRevision(connection)
            }
            true
        }
    }

    override fun saveCategories(categories: List<EmoteCategory>, deletedId: String?): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                if (deletedId != null) {
                    connection.prepareStatement("DELETE FROM ${PREFIX}categories WHERE id = ?").use { statement ->
                        statement.setString(1, deletedId)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("UPDATE ${PREFIX}catalog SET category_id = NULL WHERE category_id = ?").use { statement ->
                        statement.setString(1, deletedId)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement(
                    "INSERT INTO ${PREFIX}categories (id, name, sort_order) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE name = VALUES(name), sort_order = VALUES(sort_order)"
                ).use { statement ->
                    categories.forEach { category ->
                        statement.setString(1, category.id)
                        statement.setString(2, category.name)
                        statement.setInt(3, category.sortOrder)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                bumpRevision(connection)
            }
            true
        }
    }

    override fun saveSettings(settings: EmoteShopSettings): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.transaction {
                connection.prepareStatement(
                    "INSERT INTO ${PREFIX}settings (name, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)"
                ).use { statement ->
                    mapOf(
                        "promo_percent" to settings.promoPercent.toString(),
                        "promo_ends_at" to settings.promoEndsAtMs.toString(),
                        "new_days" to settings.newDays.toString()
                    ).forEach { (name, value) ->
                        statement.setString(1, name)
                        statement.setString(2, value)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
                bumpRevision(connection)
            }
            true
        }
    }

    // ─── Émotes possédées ────────────────────────────────────────────────────────

    override fun loadOwned(player: UUID): CompletableFuture<Set<UUID>?> = submit {
        withConnection(null) { connection ->
            connection.prepareStatement("SELECT emote_id FROM ${PREFIX}owned WHERE player_uuid = ?").use { statement ->
                statement.setString(1, player.toString())
                statement.executeQuery().use { rows ->
                    buildSet { while (rows.next()) parseUuid(rows.getString(1))?.let(::add) }
                }
            }
        }
    }

    override fun loadOwned(players: Collection<UUID>): CompletableFuture<Map<UUID, Set<UUID>>?> = submit {
        if (players.isEmpty()) return@submit emptyMap()
        withConnection(null) { connection ->
            val result = HashMap<UUID, MutableSet<UUID>>()
            players.forEach { result[it] = HashSet() }
            players.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                connection.prepareStatement("SELECT player_uuid, emote_id FROM ${PREFIX}owned WHERE player_uuid IN ($placeholders)").use { statement ->
                    chunk.forEachIndexed { index, uuid -> statement.setString(index + 1, uuid.toString()) }
                    statement.executeQuery().use { rows ->
                        while (rows.next()) {
                            val player = parseUuid(rows.getString(1)) ?: continue
                            val emote = parseUuid(rows.getString(2)) ?: continue
                            result.getOrPut(player) { HashSet() }.add(emote)
                        }
                    }
                }
            }
            result
        }
    }

    override fun grant(player: UUID, emote: UUID, source: String): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.prepareStatement("INSERT IGNORE INTO ${PREFIX}owned (player_uuid, emote_id, source) VALUES (?, ?, ?)").use { statement ->
                statement.setString(1, player.toString())
                statement.setString(2, emote.toString())
                statement.setString(3, source.take(16))
                statement.executeUpdate()
            }
            true
        }
    }

    override fun revoke(player: UUID, emote: UUID): CompletableFuture<Boolean> = submit {
        withConnection(false) { connection ->
            connection.prepareStatement("DELETE FROM ${PREFIX}owned WHERE player_uuid = ? AND emote_id = ?").use { statement ->
                statement.setString(1, player.toString())
                statement.setString(2, emote.toString())
                statement.executeUpdate()
            }
            true
        }
    }

    override fun recordPurchase(player: UUID, playerName: String, emote: UUID, price: Long): CompletableFuture<PurchaseOutcome> = submit {
        withConnection(PurchaseOutcome.FAILED) { connection ->
            var outcome = PurchaseOutcome.FAILED
            connection.transaction {
                val inserted = connection.prepareStatement(
                    "INSERT IGNORE INTO ${PREFIX}owned (player_uuid, emote_id, source) VALUES (?, ?, 'shop')"
                ).use { statement ->
                    statement.setString(1, player.toString())
                    statement.setString(2, emote.toString())
                    statement.executeUpdate()
                }
                if (inserted == 0) {
                    // Achetée entre-temps sur un autre serveur : rien à enregistrer, l'appelant rembourse.
                    outcome = PurchaseOutcome.ALREADY_OWNED
                    return@transaction
                }
                connection.prepareStatement(
                    "INSERT INTO ${PREFIX}purchases (player_uuid, player_name, emote_id, price) VALUES (?, ?, ?, ?)"
                ).use { statement ->
                    statement.setString(1, player.toString())
                    statement.setString(2, playerName.take(16))
                    statement.setString(3, emote.toString())
                    statement.setLong(4, price)
                    statement.executeUpdate()
                }
                outcome = PurchaseOutcome.RECORDED
            }
            outcome
        }
    }

    // ─── Outils ──────────────────────────────────────────────────────────────────

    private fun parseUuid(value: String?): UUID? = value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private inline fun <T> withConnection(fallback: T, block: (Connection) -> T): T {
        val source = dataSource ?: return fallback
        return try {
            source.connection.use(block)
        } catch (e: Exception) {
            logger.error("[Émotes] Erreur base de données", e)
            fallback
        }
    }

    private inline fun Connection.transaction(block: () -> Unit) {
        val previous = autoCommit
        autoCommit = false
        try {
            block()
            commit()
        } catch (t: Throwable) {
            runCatching { rollback() }
            throw t
        } finally {
            autoCommit = previous
        }
    }

    companion object {
        const val PREFIX = "cobblelegacy_emotes_"
    }
}
