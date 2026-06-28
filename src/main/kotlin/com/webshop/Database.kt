package com.webshop

import at.favre.lib.crypto.bcrypt.BCrypt
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.postgresql.util.PGobject
import org.slf4j.LoggerFactory
import java.net.URI
import java.sql.Connection
import java.sql.PreparedStatement

object Database {
    private val log = LoggerFactory.getLogger("db")

    private val dataSource: HikariDataSource by lazy { buildDataSource() }

    private fun buildDataSource(): HikariDataSource {
        val raw = Config.databaseUrl
        if (raw.isNullOrBlank()) {
            log.warn(
                "[db] FIGYELEM: DATABASE_URL nincs beallitva. " +
                    "Railway-en add hozza a PostgreSQL plugint, az automatikusan beallitja."
            )
        }
        val cfg = HikariConfig()
        if (!raw.isNullOrBlank()) {
            val (jdbcUrl, user, pass) = toJdbc(raw)
            cfg.jdbcUrl = jdbcUrl
            if (user != null) cfg.username = user
            if (pass != null) cfg.password = pass
        }
        cfg.driverClassName = "org.postgresql.Driver"
        cfg.maximumPoolSize = 10
        cfg.poolName = "webshop-pool"
        return HikariDataSource(cfg)
    }

    /**
     * postgres://user:pass@host:port/db?... -> jdbc:postgresql://host:port/db (+ user/pass kulon).
     * Railway belso halozatan nem kell SSL; kulso/publikus stringnel PGSSL=true -> sslmode=require.
     */
    private fun toJdbc(url: String): Triple<String, String?, String?> {
        val uri = URI(url)
        val userInfo = uri.userInfo
        var user: String? = null
        var pass: String? = null
        if (userInfo != null) {
            val parts = userInfo.split(":", limit = 2)
            user = parts.getOrNull(0)
            pass = parts.getOrNull(1)
        }
        val host = uri.host
        val port = if (uri.port != -1) uri.port else 5432
        val db = uri.path.removePrefix("/")
        val sb = StringBuilder("jdbc:postgresql://$host:$port/$db")
        if (Config.pgSsl) sb.append("?sslmode=require")
        return Triple(sb.toString(), user, pass)
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    /** Suspend valtozat: a blokkolo JDBC-t az IO dispatcheren futtatja. */
    suspend fun <T> query(block: (Connection) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dataSource.connection.use(block)
        }

    // ---------- Sema ----------
    private val SCHEMA_SQL = """
        CREATE TABLE IF NOT EXISTS users (
          id            SERIAL PRIMARY KEY,
          username      TEXT UNIQUE NOT NULL,
          password_hash TEXT NOT NULL,
          role          TEXT NOT NULL DEFAULT 'user',
          email         TEXT,
          created_at    TIMESTAMPTZ DEFAULT now()
        );

        CREATE TABLE IF NOT EXISTS products (
          id                     INTEGER PRIMARY KEY,
          title                  TEXT,
          description            TEXT,
          category               TEXT,
          price                  NUMERIC,
          discount_percentage    NUMERIC,
          rating                 NUMERIC,
          stock                  INTEGER,
          brand                  TEXT,
          sku                    TEXT,
          weight                 NUMERIC,
          warranty_information   TEXT,
          shipping_information   TEXT,
          availability_status    TEXT,
          return_policy          TEXT,
          minimum_order_quantity INTEGER,
          thumbnail              TEXT,
          tags                   JSONB,
          dimensions             JSONB,
          reviews                JSONB,
          images                 JSONB,
          meta                   JSONB,
          raw                    JSONB,
          updated_at             TIMESTAMPTZ DEFAULT now()
        );

        CREATE TABLE IF NOT EXISTS carts (
          id         SERIAL PRIMARY KEY,
          user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
          quantity   INTEGER NOT NULL DEFAULT 1,
          created_at TIMESTAMPTZ DEFAULT now(),
          UNIQUE (user_id, product_id)
        );

        CREATE TABLE IF NOT EXISTS orders (
          id                 SERIAL PRIMARY KEY,
          user_id            INTEGER REFERENCES users(id),
          status             TEXT NOT NULL DEFAULT 'created',
          total              NUMERIC NOT NULL,
          currency           TEXT NOT NULL,
          items              JSONB NOT NULL,
          barion_payment_id  TEXT,
          payment_request_id TEXT UNIQUE,
          created_at         TIMESTAMPTZ DEFAULT now(),
          updated_at         TIMESTAMPTZ DEFAULT now()
        );

        CREATE INDEX IF NOT EXISTS idx_products_category ON products(category);
        CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id);
        CREATE INDEX IF NOT EXISTS idx_orders_barion ON orders(barion_payment_id);
    """.trimIndent()

    // ---------- Default userek ----------
    private data class DefaultUser(val username: String, val password: String, val role: String)

    private val defaultUsers
        get() = listOf(
            DefaultUser(Config.adminUser, Config.adminPass, "admin"),
            DefaultUser(Config.userUser, Config.userPass, "user"),
        )

    private fun seedUsers() = withConnection { conn ->
        defaultUsers.forEach { u ->
            val hash = BCrypt.withDefaults().hashToString(10, u.password.toCharArray())
            conn.prepareStatement(
                """INSERT INTO users (username, password_hash, role)
                   VALUES (?, ?, ?)
                   ON CONFLICT (username) DO NOTHING"""
            ).use { ps ->
                ps.setString(1, u.username)
                ps.setString(2, hash)
                ps.setString(3, u.role)
                ps.executeUpdate()
            }
        }
        log.info("[db] Default userek rendben (admin, v).")
    }

    // ---------- JSONB segedek ----------
    private fun jsonb(value: JsonElement?): PGobject? {
        if (value == null) return null
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = value.toString()
        return obj
    }

    private fun PreparedStatement.setJsonb(idx: Int, value: JsonElement?) {
        val v = jsonb(value)
        if (v == null) setNull(idx, java.sql.Types.OTHER) else setObject(idx, v)
    }

    /** JSON elem mezo kiolvasasa egy JsonObject-bol (kulcs lehet null). */
    private fun JsonObject.el(key: String): JsonElement? = this[key]

    // ---------- Termek upsert (dummyjson alak) ----------
    private fun insertProduct(conn: Connection, p: JsonObject) {
        conn.prepareStatement(
            """INSERT INTO products
                (id, title, description, category, price, discount_percentage, rating, stock,
                 brand, sku, weight, warranty_information, shipping_information, availability_status,
                 return_policy, minimum_order_quantity, thumbnail, tags, dimensions, reviews, images, meta, raw)
               VALUES
                (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
               ON CONFLICT (id) DO UPDATE SET
                 title=EXCLUDED.title, description=EXCLUDED.description, category=EXCLUDED.category,
                 price=EXCLUDED.price, discount_percentage=EXCLUDED.discount_percentage, rating=EXCLUDED.rating,
                 stock=EXCLUDED.stock, brand=EXCLUDED.brand, sku=EXCLUDED.sku, weight=EXCLUDED.weight,
                 warranty_information=EXCLUDED.warranty_information, shipping_information=EXCLUDED.shipping_information,
                 availability_status=EXCLUDED.availability_status, return_policy=EXCLUDED.return_policy,
                 minimum_order_quantity=EXCLUDED.minimum_order_quantity, thumbnail=EXCLUDED.thumbnail,
                 tags=EXCLUDED.tags, dimensions=EXCLUDED.dimensions, reviews=EXCLUDED.reviews,
                 images=EXCLUDED.images, meta=EXCLUDED.meta, raw=EXCLUDED.raw, updated_at=now()"""
        ).use { ps ->
            ps.setObject(1, Jx.int(p, "id"))
            ps.setString(2, Jx.str(p, "title"))
            ps.setString(3, Jx.str(p, "description"))
            ps.setString(4, Jx.str(p, "category"))
            ps.setObject(5, Jx.num(p, "price"))
            ps.setObject(6, Jx.num(p, "discountPercentage"))
            ps.setObject(7, Jx.num(p, "rating"))
            ps.setObject(8, Jx.int(p, "stock"))
            ps.setString(9, Jx.str(p, "brand"))
            ps.setString(10, Jx.str(p, "sku"))
            ps.setObject(11, Jx.num(p, "weight"))
            ps.setString(12, Jx.str(p, "warrantyInformation"))
            ps.setString(13, Jx.str(p, "shippingInformation"))
            ps.setString(14, Jx.str(p, "availabilityStatus"))
            ps.setString(15, Jx.str(p, "returnPolicy"))
            ps.setObject(16, Jx.int(p, "minimumOrderQuantity"))
            ps.setString(17, Jx.str(p, "thumbnail"))
            ps.setJsonb(18, p.el("tags"))
            ps.setJsonb(19, p.el("dimensions"))
            ps.setJsonb(20, p.el("reviews"))
            ps.setJsonb(21, p.el("images"))
            ps.setJsonb(22, p.el("meta"))
            ps.setJsonb(23, p)
            ps.executeUpdate()
        }
    }

    /** dummyjson-rol az OSSZES termek behuzasa (limit=0 -> minden). */
    fun seedProductsFromDummyJson(force: Boolean = false): Int = withConnection { conn ->
        if (!force) {
            conn.prepareStatement("SELECT COUNT(*)::int AS c FROM products").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    val c = rs.getInt("c")
                    if (c > 0) {
                        log.info("[db] Mar van $c termek, kihagyom a seedelest (force-szal ujrahuzhato).")
                        return@withConnection c
                    }
                }
            }
        }

        val url = Config.productsSourceUrl
        log.info("[db] Termekek letoltese: $url")
        val data: JsonObject = kotlinx.coroutines.runBlocking {
            val res: HttpResponse = httpClient.get(url)
            if (res.status.value !in 200..299) {
                throw RuntimeException("dummyjson letoltes hiba: HTTP ${res.status.value}")
            }
            res.body<JsonElement>().jsonObject
        }
        val products = (data["products"] as? JsonArray) ?: JsonArray(emptyList())
        products.forEach { el -> insertProduct(conn, el.jsonObject) }
        log.info("[db] ${products.size} termek beszurva/frissitve.")
        products.size
    }

    /** Indulaskor lefuto inicializalas. */
    fun init() {
        withConnection { conn ->
            conn.createStatement().use { st -> st.execute(SCHEMA_SQL) }
        }
        log.info("[db] Sema rendben.")
        seedUsers()
        try {
            seedProductsFromDummyJson()
        } catch (e: Exception) {
            log.error("[db] Termek seedeles sikertelen (a szerver tovabb indul): ${e.message}")
        }
    }
}
