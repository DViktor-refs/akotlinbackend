# Webshop backend (Kotlin + Ktor)

Az eredeti Node.js + Express backend **1:1 atirasa Kotlin + Ktor** kombora.
Ugyanazok a vegpontok, ugyanaz a viselkedes, ugyanugy Railway-re keszitve.

Tartalom:

- **User authentikacio** (JWT). Ket default user: `admin/admin` (admin) es `v/v123` (sima user).
- **Barion fizetes** (Smart Gateway, `Payment/Start` + callback + allapot lekerdezes).
- **Termekek a dummyjson-rol** – induláskor letolti az **osszes** terméket
  (`https://dummyjson.com/products?limit=0`) minden mezojevel (nev, ar, kategoria, keszlet,
  reviews, tags, dimensions, meta, kepek, stb.).
- **Kosar** es **rendeles** kezeles.
- Kesz **Railway** deployra (GitHub-rol importalhato).

A korabbi Node-os verziohoz kepest **az API valtozatlan** – a meglevo frontend, a Barion
beallitasok es a Railway env valtozok ugyanugy mukodnek.

---

## Technologiai stack

| Reteg | Node (eredeti) | Kotlin (ez a verzio) |
|-------|----------------|----------------------|
| Webszerver | Express | Ktor 2.3 (Netty) |
| JSON | express.json | kotlinx.serialization |
| Adatbazis | pg (Pool) | JDBC + HikariCP (nyers SQL, ugyanaz a sema) |
| Jelszo hash | bcryptjs | at.favre.lib:bcrypt |
| JWT | jsonwebtoken | com.auth0:java-jwt + ktor-auth-jwt |
| HTTP hivasok (Barion/dummyjson) | fetch | Ktor client (CIO) |
| Build | npm | Gradle (shadow fat-jar) |

Az adatbazis-sema, az SQL lekerdezesek, az upsertek (`ON CONFLICT`) es a JSONB mezok
**pontosan ugyanazok**, mint az eredetiben.

---

## Vegpontok

| Metodus | Utvonal | Leiras | Auth |
|--------|---------|--------|------|
| POST | `/api/auth/login` | Bejelentkezes, JWT tokent ad vissza | – |
| POST | `/api/auth/register` | Uj (sima) user | – |
| GET | `/api/auth/me` | Aktualis user | token |
| GET | `/api/products` | Lista (`?limit=&skip=&q=&category=&sortBy=&order=`) | – |
| GET | `/api/products/categories` | Kategoriak | – |
| GET | `/api/products/category/:cat` | Kategoria szerinti lista | – |
| GET | `/api/products/:id` | Egy termek minden mezovel | – |
| POST | `/api/products` | Uj termek | admin |
| PUT | `/api/products/:id` | Termek modositas | admin |
| DELETE | `/api/products/:id` | Termek torles | admin |
| POST | `/api/products/reseed` | Ujratoltes dummyjson-rol | admin |
| GET | `/api/cart` | Kosar tartalma | token |
| POST | `/api/cart` | `{productId, quantity}` hozzaadas | token |
| PUT | `/api/cart/:productId` | `{quantity}` beallitas | token |
| DELETE | `/api/cart/:productId` | Termek torlese a kosarbol | token |
| DELETE | `/api/cart` | Kosar urites | token |
| POST | `/api/orders/checkout` | Rendeles + Barion fizetes inditasa | token |
| GET | `/api/orders` | Sajat rendelesek | token |
| GET | `/api/orders/:id` | Egy rendeles | token |
| GET\|POST | `/api/payment/callback` | Barion IPN callback (a Barion hivja) | – |
| GET | `/api/payment/status/:orderId` | Fizetes elo allapota | token |

A tokent igy kuldd: `Authorization: Bearer <token>`.

---

## 1. Helyi futtatas

Szukseged lesz **JDK 17+**-ra es egy futo **PostgreSQL**-re.

```bash
cp .env.example .env      # toltsd ki az ertekeket (lasd lent)
./gradlew shadowJar       # fat jar: build/libs/app.jar
java -jar build/libs/app.jar
```

Vagy fejlesztoi modban, build nelkul:

```bash
./gradlew run
```

A `DATABASE_URL`-t be kell allitani (`postgres://user:pass@localhost:5432/webshop`).
Inditaskor a backend automatikusan letrehozza a tablakat, beteszi a ket usert, es letolti
a termekeket.

Teszt login:
```bash
curl -X POST http://localhost:3000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}'
```

---

## 2. Railway deploy

1. Toltsd fel ezt a repot GitHub-ra, es a Railway-en **New Project → Deploy from GitHub repo**.
2. Add hozza a **PostgreSQL** plugint – ez automatikusan beallitja a `DATABASE_URL`-t.
3. Allitsd be a kornyezeti valtozokat (lasd `.env.example`): legalabb a `JWT_SECRET`,
   `BARION_POSKEY`, `BARION_PAYEE`, es deploy utan a `BASE_URL` (a kapott domain).
4. A Railway a **`Dockerfile`** alapjan epit (`railway.json` -> builder: DOCKERFILE).
   A Dockerfile ket lepcsoben dolgozik:
   - **build:** `gradle clean shadowJar` -> `build/libs/app.jar` (fat jar, minden fuggoseggel),
   - **runtime:** egy kis JRE image, ami a kesz jart inditja: `java -jar /app/app.jar`.

A fat jar manifest `Main-Class`-a a `com.webshop.ApplicationKt` (a top-level `main()`).

> A `PORT`-ot a Railway adja futasidoben; a szerver `0.0.0.0`-n erre a portra all be.
> Igy a build/inditas determinisztikus, nem fugg a Nixpacks auto-detektalastol.

> A `PORT`-ot a Railway adja; a szerver `0.0.0.0`-n erre a portra all be.

---

## 3. Barion fizetes folyamata

1. A bejelentkezett user feltolt par terméket a kosárba, majd `POST /api/orders/checkout`.
2. A backend letrehoz egy rendelest (`created`), elindit egy Barion fizetest, es visszaad
   egy `gatewayUrl`-t. **Ide kell atiranyitani a vasarlot** (ez a Barion fizeto oldal).
3. Fizetes utan a Barion:
   - visszairanyitja a bongeszot a `RedirectUrl`-re (`FRONTEND_URL`, vagy a beepitett
     `/payment-result` oldal),
   - es szerver-szerver hivassal ertesiti a `CallbackUrl`-t
     (`/api/payment/callback`), ahol a rendeles statusza frissul. Sikeres fizetes utan
     a user kosara urul.
4. A statusz barmikor lekerdezheto: `GET /api/payment/status/:orderId`.

---

## 4. Frontend

A `frontend/index.html` egy onallo, build nelkuli demo oldal (vanilla JS), amit a backend
**nem szolgal ki** – nyisd meg kozvetlenul a bongeszoben, vagy hosztold barhol
(pl. `npx serve frontend`). Az API CORS-a engedelyezve van.

A backend tisztan API-t ad; a megjelenitest a sajat frontended (vagy egy mobil app) vegzi.
A `/payment-result` csak egy egyszeru visszajelzo oldalt mutat, ha meg nincs frontended.

---

## Projekt-struktura

```
src/main/kotlin/com/webshop/
  Application.kt          // belepesi pont, szerver, pluginek, routing, 404/hibakezelo
  Config.kt               // kornyezeti valtozok
  Database.kt             // HikariCP pool, sema, seed (userek + termekek)
  Barion.kt               // Barion Smart Gateway integracio
  Http.kt                 // megosztott Ktor HTTP kliens
  Models.kt               // kerés/valasz DTO-k
  JsonHelpers.kt          // JSON segedfuggvenyek
  security/Security.kt    // JWT token + Ktor auth
  routes/
    AuthRoutes.kt         // login, register, me
    ProductRoutes.kt      // lista, kereses, kategoriak, admin CRUD, reseed
    CartRoutes.kt         // kosar
    OrderRoutes.kt        // checkout + rendelesek
    PaymentRoutes.kt      // Barion callback + statusz
```
