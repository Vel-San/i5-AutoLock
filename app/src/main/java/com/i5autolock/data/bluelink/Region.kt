package com.i5autolock.data.bluelink

/**
 * Supported BlueLink / UVO regions. EU is the primary target (email + password via the
 * OneApp/CCI flow); other regions are not yet implemented.
 */
enum class Region(val displayName: String) {
    EU("Europe"),
    US("United States"),
    CA("Canada"),
    AU("Australia"),
    ;

    companion object {
        fun fromNameOrDefault(value: String?): Region =
            entries.firstOrNull { it.name == value } ?: EU
    }
}

/** Vehicle brand. The API host/client differs per brand within a region. */
enum class Brand(val displayName: String) {
    HYUNDAI("Hyundai"),
    KIA("Kia"),
    GENESIS("Genesis"),
}
