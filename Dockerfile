# ---- Build stage: a fat (shadow) jar legyartasa ----
FROM gradle:8.7-jdk17 AS build
WORKDIR /app

# Eloszor csak a build-fajlok -> jobb reteg-cache a fuggosegekhez
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle

# Forras + minden egyeb
COPY . .

# Determinisztikus: tisztitas + CSAK a fat jart epitjuk (build/libs/app.jar)
RUN gradle clean shadowJar --no-daemon

# ---- Runtime stage: csak a JRE + a kesz jar ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/app.jar /app/app.jar

# A Railway a PORT env-et futasidoben adja; az app ezt olvassa (Config.port).
CMD ["java", "-jar", "/app/app.jar"]
