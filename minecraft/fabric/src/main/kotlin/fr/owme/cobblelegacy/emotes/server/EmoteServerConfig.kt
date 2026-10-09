package fr.owme.cobblelegacy.emotes.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import fr.owme.cobblelegacy.emotes.CobbleLegacyEmotes
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** Connexion MariaDB, même format que `config/cobblelegacy-cape/database.json`. */
data class DatabaseSettings(
    val host: String = "CHANGE_ME",
    val port: Int = 3306,
    val database: String = "cobblelegacy",
    val user: String = "cobblelegacy",
    val password: String = "changeme",
    val poolSize: Int = 4
) {
    val configured: Boolean get() = host.isNotBlank() && host != "CHANGE_ME"
}

/** Réglages propres à chaque serveur (`config/cobblelegacy-emotes/emotes.json`). */
data class EmoteGameplaySettings(
    /**
     * Avec un catalogue publié, laisser jouer les émotes qui n'y sont pas (celles du dossier `emotes`
     * des joueurs). Faux par défaut : sinon n'importe qui jouerait gratuitement une émote vendue en
     * important son fichier.
     */
    val autoriserEmotesHorsCatalogue: Boolean = false,
    /** Les administrateurs jouent toutes les émotes du catalogue, brouillons compris (pour les tester). */
    val adminsJouentTout: Boolean = true,
    /** Niveau de permission vanilla de l'éditeur et des commandes d'administration. */
    val niveauAdmin: Int = 2,
    /** Prix donné aux émotes ajoutées par `/emoteshop importer` (modifiable ensuite dans l'éditeur). */
    val prixImportParDefaut: Long = 500
)

object EmoteServerConfig {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    val directory: Path get() = FabricLoader.getInstance().configDir.resolve("cobblelegacy-emotes")
    val importDirectory: Path get() = directory.resolve("import")
    val localStoreDirectory: Path get() = directory.resolve("local")

    @Volatile
    var database = DatabaseSettings()
        private set

    @Volatile
    var gameplay = EmoteGameplaySettings()
        private set

    fun load() {
        Files.createDirectories(directory)
        Files.createDirectories(importDirectory)
        database = loadDatabase()
        gameplay = loadOrCreate(directory.resolve("emotes.json"), EmoteGameplaySettings::class.java) { EmoteGameplaySettings() }
    }

    /**
     * Premier démarrage : on reprend la connexion du mod des capes s'il est configuré sur ce serveur,
     * c'est la même base MariaDB.
     */
    private fun loadDatabase(): DatabaseSettings {
        val file = directory.resolve("database.json")
        if (!Files.exists(file)) {
            val cape = FabricLoader.getInstance().configDir.resolve("cobblelegacy-cape").resolve("database.json")
            val imported = runCatching {
                if (Files.exists(cape)) gson.fromJson(Files.readString(cape), DatabaseSettings::class.java) else null
            }.getOrNull()?.takeIf { it.configured }
            val initial = imported ?: DatabaseSettings()
            Files.writeString(file, gson.toJson(initial))
            if (imported != null) {
                CobbleLegacyEmotes.logger.info("[Émotes] Connexion MariaDB reprise de config/cobblelegacy-cape/database.json")
            }
            return initial
        }
        return loadOrCreate(file, DatabaseSettings::class.java) { DatabaseSettings() }
    }

    private fun <T : Any> loadOrCreate(file: Path, type: Class<T>, defaults: () -> T): T {
        return try {
            if (!Files.exists(file)) {
                val value = defaults()
                Files.writeString(file, gson.toJson(value))
                value
            } else {
                // Les clés absentes (ajoutées dans une version plus récente) reprennent leur valeur par défaut.
                val stored = JsonParser.parseString(Files.readString(file)).asJsonObject
                val merged = gson.toJsonTree(defaults()).asJsonObject
                stored.entrySet().forEach { (key, value) -> merged.add(key, value) }
                val value = gson.fromJson(merged, type)
                if (merged.size() != stored.size()) Files.writeString(file, gson.toJson(value))
                value
            }
        } catch (e: Exception) {
            CobbleLegacyEmotes.logger.error("[Émotes] Lecture de {} impossible, valeurs par défaut utilisées", file.fileName, e)
            defaults()
        }
    }
}
