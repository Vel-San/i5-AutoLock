package com.i5autolock.data.bluelink

/**
 * Supported BlueLink / UVO regions. EU is the primary target and uses an OAuth
 * authorization-code flow; other regions typically use direct username/password login.
 */
enum class Region(val displayName: String, val requiresOauthLogin: Boolean) {
    EU("Europe", requiresOauthLogin = true),
    US("United States", requiresOauthLogin = false),
    CA("Canada", requiresOauthLogin = false),
    AU("Australia", requiresOauthLogin = false),
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
