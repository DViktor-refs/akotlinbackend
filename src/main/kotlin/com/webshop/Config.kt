package com.webshop

import io.github.cdimascio.dotenv.dotenv

/**
 * Kornyezeti valtozok kozponti olvasasa.
 * Railway-en a platform adja az env-eket (System.getenv).
 * Lokalisan a projekt gyokerebe tett .env fajlbol is olvas (ha van).
 */
object Config {
    private val dotenv = dotenv {
        ignoreIfMissing = true
    }

    fun get(key: String): String? =
        System.getenv(key) ?: dotenv[key]?.takeIf { it.isNotBlank() }

    fun get(key: String, default: String): String = get(key) ?: default

    // ---- Szerver ----
    val port: Int get() = get("PORT")?.toIntOrNull() ?: 3000
    val baseUrl: String? get() = get("BASE_URL")?.trimEnd('/')
    val frontendUrl: String? get() = get("FRONTEND_URL")

    // ---- Adatbazis ----
    val databaseUrl: String? get() = get("DATABASE_URL")
    val pgSsl: Boolean get() = get("PGSSL") == "true"

    // ---- Auth ----
    val jwtSecret: String
        get() = get("JWT_SECRET") ?: "fejlesztoi-titkos-kulcs-VALTOZTASD-MEG-productionben"

    val adminUser: String get() = get("ADMIN_USER", "admin")
    val adminPass: String get() = get("ADMIN_PASS", "admin")
    val userUser: String get() = get("USER_USER", "v")
    val userPass: String get() = get("USER_PASS", "v123")

    // ---- Barion ----
    val barionPosKey: String? get() = get("BARION_POSKEY")
    val barionPayee: String? get() = get("BARION_PAYEE")
    val barionEnv: String get() = if (get("BARION_ENV") == "prod") "prod" else "test"
    val barionLocale: String get() = get("BARION_LOCALE", "hu-HU")

    // ---- Bolt ----
    val currency: String get() = get("CURRENCY", "HUF")
    val productsSourceUrl: String
        get() = get("PRODUCTS_SOURCE_URL", "https://dummyjson.com/products?limit=0")
}
