plugins {
    // Lehetove teszi, hogy a Gradle automatikusan letoltse a megfelelo JDK-t (toolchain),
    // fuggetlenul attol, milyen JDK-val fut a build (pl. Railway / Nixpacks).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "dummybackend"
