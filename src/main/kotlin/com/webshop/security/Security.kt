package com.webshop.security

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.webshop.Config
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import org.slf4j.LoggerFactory
import java.util.Date

object Security {
    private val log = LoggerFactory.getLogger("auth")
    const val JWT_AUTH = "auth-jwt"

    private val secret get() = Config.jwtSecret
    private val algorithm get() = Algorithm.HMAC256(secret)

    fun warnIfDefaultSecret() {
        if (Config.get("JWT_SECRET") == null) {
            log.warn("[auth] FIGYELEM: JWT_SECRET nincs beallitva, fejlesztoi kulcsot hasznalok. Allitsd be productionben!")
        }
    }

    /** 7 napos token, ugyanazokkal a claimekkel mint az eredeti (id, username, role). */
    fun makeToken(id: Int, username: String, role: String): String {
        val expires = Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000)
        return JWT.create()
            .withClaim("id", id)
            .withClaim("username", username)
            .withClaim("role", role)
            .withExpiresAt(expires)
            .sign(algorithm)
    }

    fun Application.configureJwt() {
        authentication {
            jwt(JWT_AUTH) {
                realm = "webshop"
                verifier(JWT.require(algorithm).build())
                validate { credential ->
                    val id = credential.payload.getClaim("id").asInt()
                    if (id != null) JWTPrincipal(credential.payload) else null
                }
            }
        }
    }
}

// ---- Principal segedek ----
val JWTPrincipal.userId: Int get() = payload.getClaim("id").asInt()
val JWTPrincipal.username: String get() = payload.getClaim("username").asString()
val JWTPrincipal.role: String get() = payload.getClaim("role").asString()
val JWTPrincipal.isAdmin: Boolean get() = role == "admin"
