package com.webshop.routes

import com.webshop.CartAddRequest
import com.webshop.CartItem
import com.webshop.CartQtyRequest
import com.webshop.CartResponse
import com.webshop.Database
import com.webshop.ErrorResponse
import com.webshop.security.Security
import com.webshop.security.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.sql.Connection
import kotlin.math.roundToLong

/** Kosar osszeallitasa termek-adatokkal + osszegekkel. Megosztva a checkouttal. */
fun getCart(conn: Connection, userId: Int): CartResponse {
    val items = mutableListOf<CartItem>()
    conn.prepareStatement(
        """SELECT c.product_id, c.quantity, p.title, p.price, p.thumbnail, p.stock
             FROM carts c
             JOIN products p ON p.id = c.product_id
            WHERE c.user_id = ?
            ORDER BY c.created_at"""
    ).use { ps ->
        ps.setInt(1, userId)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val price = rs.getBigDecimal("price")?.toDouble() ?: 0.0
                val qty = rs.getInt("quantity")
                val stockVal = rs.getInt("stock")
                val stock = if (rs.wasNull()) null else stockVal
                val lineTotal = (price * qty * 100).roundToLong() / 100.0
                items.add(
                    CartItem(
                        productId = rs.getInt("product_id"),
                        title = rs.getString("title"),
                        thumbnail = rs.getString("thumbnail"),
                        price = price,
                        quantity = qty,
                        lineTotal = lineTotal,
                        stock = stock,
                    )
                )
            }
        }
    }
    val total = (items.sumOf { it.lineTotal } * 100).roundToLong() / 100.0
    val count = items.sumOf { it.quantity }
    return CartResponse(items, total, count)
}

fun Route.cartRoutes() = authenticate(Security.JWT_AUTH) {
    route("/api/cart") {

        // GET /api/cart
        get {
            val uid = call.principal<JWTPrincipal>()!!.userId
            call.respond(Database.query { conn -> getCart(conn, uid) })
        }

        // POST /api/cart  { productId, quantity }
        post {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val body = runCatching { call.receive<CartAddRequest>() }.getOrElse { CartAddRequest() }
            val productId = body.productId
            val quantity = body.quantity ?: 1
            if (productId == null || quantity < 1) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("productId kotelezo, quantity >= 1."))
                return@post
            }

            val result = Database.query { conn ->
                val exists = conn.prepareStatement("SELECT id FROM products WHERE id = ?").use { ps ->
                    ps.setInt(1, productId)
                    ps.executeQuery().use { rs -> rs.next() }
                }
                if (!exists) return@query null

                conn.prepareStatement(
                    """INSERT INTO carts (user_id, product_id, quantity)
                       VALUES (?, ?, ?)
                       ON CONFLICT (user_id, product_id)
                       DO UPDATE SET quantity = carts.quantity + EXCLUDED.quantity"""
                ).use { ps ->
                    ps.setInt(1, uid)
                    ps.setInt(2, productId)
                    ps.setInt(3, quantity)
                    ps.executeUpdate()
                }
                getCart(conn, uid)
            }
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Termek nem letezik."))
            } else {
                call.respond(HttpStatusCode.Created, result)
            }
        }

        // PUT /api/cart/{productId}  { quantity }
        put("/{productId}") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val productId = call.parameters["productId"]?.toIntOrNull()
            val body = runCatching { call.receive<CartQtyRequest>() }.getOrElse { CartQtyRequest() }
            val quantity = body.quantity
            if (quantity == null || quantity < 1) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("quantity >= 1 szukseges (toroleshez hasznald a DELETE-et)."),
                )
                return@put
            }
            if (productId == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Ez a termek nincs a kosaradban."))
                return@put
            }

            val result = Database.query { conn ->
                val affected = conn.prepareStatement(
                    "UPDATE carts SET quantity = ? WHERE user_id = ? AND product_id = ?"
                ).use { ps ->
                    ps.setInt(1, quantity)
                    ps.setInt(2, uid)
                    ps.setInt(3, productId)
                    ps.executeUpdate()
                }
                if (affected == 0) null else getCart(conn, uid)
            }
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Ez a termek nincs a kosaradban."))
            } else {
                call.respond(result)
            }
        }

        // DELETE /api/cart/{productId}
        delete("/{productId}") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val productId = call.parameters["productId"]?.toIntOrNull()
            val result = Database.query { conn ->
                if (productId != null) {
                    conn.prepareStatement("DELETE FROM carts WHERE user_id = ? AND product_id = ?").use { ps ->
                        ps.setInt(1, uid)
                        ps.setInt(2, productId)
                        ps.executeUpdate()
                    }
                }
                getCart(conn, uid)
            }
            call.respond(result)
        }

        // DELETE /api/cart  -> kosar urites
        delete {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val result = Database.query { conn ->
                conn.prepareStatement("DELETE FROM carts WHERE user_id = ?").use { ps ->
                    ps.setInt(1, uid)
                    ps.executeUpdate()
                }
                getCart(conn, uid)
            }
            call.respond(result)
        }
    }
}
