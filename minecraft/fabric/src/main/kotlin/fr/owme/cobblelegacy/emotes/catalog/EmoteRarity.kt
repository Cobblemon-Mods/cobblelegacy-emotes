package fr.owme.cobblelegacy.emotes.catalog

/** Rareté d'une émote, mêmes paliers que les capes et les montures. */
enum class EmoteRarity(val key: String) {
    COMMUN("commun"),
    RARE("rare"),
    EPIQUE("epique"),
    LEGENDAIRE("legendaire"),
    LEGACY("legacy");

    val translationKey: String get() = "cobblelegacy-emotes.rarity.$key"

    companion object {
        /** Insensible à la casse ; COMMUN par défaut. */
        fun parse(value: String?): EmoteRarity =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true) } ?: COMMUN
    }
}
