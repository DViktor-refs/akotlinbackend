package com.webshop.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.webshop.AuthResponse
import com.webshop.Database
import com.webshop.ErrorResponse
import com.webshop.LoginRequest
import com.webshop.MeResponse
import com.webshop.PublicUser
import com.webshop.RegisterRequest
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.authRoutes() = route("/api/auth") {

    // POST /api/auth/login
    post("/login") {
        val body = runCatching { call.receive<LoginRequest>() }.getOrElse { LoginRequest() }
        val username = body.username
        val password = body.password
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("username es password kotelezo."))
            return@post
        }

        val row = Database.query { conn ->
            conn.prepareStatement("SELECT id, username, role, password_hash FROM users WHERE username = ?").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs ->
                    if (rs.next()) {
                        arrayOf(
                            rs.getInt("id").toString(),
                            rs.getString("username"),
                            rs.getString("role"),
                            rs.getString("password_hash"),
                        )
                    } else null
                }
            }
        }

        val valid = row != null &&
            BCrypt.verifyer().verify(password.toCharArray(), row[3]).verified
        if (!valid) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Hibas felhasznalonev vagy jelszo."))
            return@post
        }

        val id = row!![0].toInt()
        val token = Security.makeToken(id, row[1], row[2])
        call.respond(AuthResponse(token, PublicUser(id, row[1], row[2])))
    }

    // POST /api/auth/register
    post("/register") {
        val body = runCatching { call.receive<RegisterRequest>() }.getOrElse { RegisterRequest() }
        val username = body.username
        val password = body.password
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("username es password kotelezo."))
            return@post
        }

        val exists = Database.query { conn ->
            conn.prepareStatement("SELECT 1 FROM users WHERE username = ?").use { ps ->
                ps.setString(1, username)
                ps.executeQuery().use { rs -> rs.next() }
            }
        }
        if (exists) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Ez a felhasznalonev mar foglalt."))
            return@post
        }

        val hash = BCrypt.withDefaults().hashToString(10, password.toCharArray())
        val created = Database.query { conn ->
            conn.prepareStatement(
                """INSERT INTO users (username, password_hash, role, email)
                   VALUES (?, ?, 'user', ?)
                   RETURNING id, username, role"""
            ).use { ps ->
                ps.setString(1, username)
                ps.setString(2, hash)
                ps.setString(3, body.email)
                ps.executeQuery().use { rs ->
                    rs.next()
                    PublicUser(rs.getInt("id"), rs.getString("username"), rs.getString("role"))
                }
            }
        }

        val token = Security.makeToken(created.id, created.username, created.role)
        call.respond(HttpStatusCode.Created, AuthResponse(token, created))
    }

    // GET /api/auth/me
    authenticate(Security.JWT_AUTH) {
        get("/me") {
            val uid = call.principal<JWTPrincipal>()!!.userId
            val me = Database.query { conn ->
                conn.prepareStatement(
                    "SELECT id, username, role, email, created_at FROM users WHERE id = ?"
                ).use { ps ->
                    ps.setInt(1, uid)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            MeResponse(
                                id = rs.getInt("id"),
                                username = rs.getString("username"),
                                role = rs.getString("role"),
                                email = rs.getString("email"),
                                created_at = rs.getString("created_at"),
                            )
                        } else null
                    }
                }
            }
            if (me == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User nem talalhato."))
            } else {
                call.respond(me)
            }
        }
    }
}
