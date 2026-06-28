import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    // Fat (uber) jar keszitese a Railway deployhoz.
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.webshop"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "2.3.12"

dependencies {
    // ---- Ktor szerver ----
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jwt:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")

    // ---- Ktor kliens (Barion + dummyjson hivasokhoz) ----
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // ---- Adatbazis ----
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.3")

    // ---- Auth ----
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("com.auth0:java-jwt:4.4.0")

    // ---- .env betoltes (lokalis fejlesztes; Railway-en a platform adja az env-eket) ----
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // ---- Naplozas ----
    implementation("ch.qos.logback:logback-classic:1.5.6")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.webshop.ApplicationKt")
}

tasks.withType<ShadowJar> {
    // Determinisztikus nev, hogy a Railway start parancs mindig ezt indithassa.
    archiveFileName.set("app.jar")
    mergeServiceFiles()
}
