package com.webshop

import com.webshop.routes.authRoutes
import com.webshop.routes.cartRoutes
import com.webshop.routes.orderRoutes
import com.webshop.routes.paymentRoutes
import com.webshop.routes.productRoutes
import com.webshop.security.Security
import com.webshop.security.Security.configureJwt
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("server")

fun main() {
    Security.warnIfDefaultSecret()
    try {
        Database.init()
    } catch (e: Exception) {
        log.error("[server] Inditas sikertelen (DB): ${e.message}")
        exitProcess(1)
    }

    val port = Config.port
    log.info("[server] Fut: http://0.0.0.0:$port")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(appJson)
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowNonSimpleContentTypes = true
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("[error] ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("Szerverhiba", cause.message),
            )
        }
    }

    configureJwt()

    routing {
        // API dokumentacio / health
        get("/") {
            call.respond(buildJsonObject {
                put("name", JsonPrimitive("Webshop backend"))
                put("status", JsonPrimitive("ok"))
                put("endpoints", buildJsonObject {
                    put("auth", strArray(
                        "POST /api/auth/login", "POST /api/auth/register", "GET /api/auth/me",
                    ))
                    put("products", strArray(
                        "GET /api/products",
                        "GET /api/products/categories",
                        "GET /api/products/category/:category",
                        "GET /api/products/:id",
                        "POST /api/products (admin)",
                        "PUT /api/products/:id (admin)",
                        "DELETE /api/products/:id (admin)",
                        "POST /api/products/reseed (admin)",
                    ))
                    put("cart", strArray(
                        "GET /api/cart", "POST /api/cart", "PUT /api/cart/:productId",
                        "DELETE /api/cart/:productId", "DELETE /api/cart",
                    ))
                    put("orders", strArray(
                        "POST /api/orders/checkout", "GET /api/orders", "GET /api/orders/:id",
                    ))
                    put("payment", strArray(
                        "GET|POST /api/payment/callback", "GET /api/payment/status/:orderId",
                    ))
                })
            })
        }

        get("/health") {
            call.respond(buildJsonObject { put("status", JsonPrimitive("ok")) })
        }

        // Egyszeru visszatero oldal a Barion fizetes utan (ha nincs kulon frontend).
        get("/payment-result") {
            val paymentId = call.request.queryParameters["paymentId"]
            var status = "ismeretlen"
            if (!paymentId.isNullOrBlank()) {
                status = try {
                    val state = Barion.getPaymentState(paymentId)
                    Barion.statusOf(state) ?: "ismeretlen"
                } catch (_: Exception) {
                    "nem sikerult lekerdezni"
                }
            }
            call.respondText(paymentResultHtml(status, paymentId), ContentType.Text.Html)
        }

        // Vegpontok
        authRoutes()
        productRoutes()
        cartRoutes()
        orderRoutes()
        paymentRoutes()

        // 404 fallback (minden mas utvonalra)
        route("{...}") {
            get { call.respond(HttpStatusCode.NotFound, ErrorResponse("Nincs ilyen vegpont.")) }
            post { call.respond(HttpStatusCode.NotFound, ErrorResponse("Nincs ilyen vegpont.")) }
            put { call.respond(HttpStatusCode.NotFound, ErrorResponse("Nincs ilyen vegpont.")) }
            delete { call.respond(HttpStatusCode.NotFound, ErrorResponse("Nincs ilyen vegpont.")) }
        }
    }
}

private fun strArray(vararg items: String): JsonArray = buildJsonArray {
    items.forEach { add(JsonPrimitive(it)) }
}

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun paymentResultHtml(status: String, paymentId: String?): String = """
<!doctype html><html lang="hu"><head><meta charset="utf-8">
      <meta name="viewport" content="width=device-width, initial-scale=1">
      <title>Fizetes eredmenye</title>
      <style>body{font-family:system-ui,sans-serif;max-width:480px;margin:80px auto;text-align:center;color:#222}
      .s{font-size:1.4rem;font-weight:600;margin:1rem 0}</style></head>
      <body><h1>Fizetes eredmenye</h1>
      <div class="s">Allapot: ${esc(status)}</div>
      <p>Payment ID: ${esc(paymentId ?: "-")}</p>
      <p>Ezt az oldalt lecserelheted a sajat frontendedre a FRONTEND_URL valtozoval.</p>
      </body></html>
""".trimIndent()
