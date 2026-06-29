# Collective Dissent — MVP

A read-only public dashboard where each person can mark **one** entity — a party, the PM, or an
MP — that they will *not* vote for in the next election, as a collective show of dissent.

- One selection per person (keyed by mobile number). Changing it replaces the previous one.
- A short reason (min 10 chars) is required, to filter out non-serious selections.
- To select or change, the user verifies a **mobile OTP**, which grants a **time-bound token**
  (10 minutes) used to cast or change the vote.
- No accounts, no sessions, no cookies. The token lives in the browser's localStorage only.
- Plain black-and-white single page. List shows Parties first, then the PM, then 500 MPs, each
  with party, department, and live vote count.

## Stack

- **Backend:** Java 17 + Spring Boot (Maven), plain JDBC.
- **DB:** PostgreSQL. Schema + seed (8 parties, 1 PM, 500 MPs) load automatically on startup.
- **Frontend:** one static `index.html` (vanilla HTML/CSS/JS) served by the backend.
- **OTP delivery:** mocked — the code is printed to the backend console (look for `MOCK OTP`).

## Layout

```
dissent-vote/
├── docker-compose.yml            # Postgres (if you use Docker)
└── backend/
    ├── pom.xml
    ├── offline-settings.xml       # Maven settings used to build (see Build note)
    └── src/main/
        ├── java/com/dissent/...   # app, controller, service, repo, dto
        └── resources/
            ├── application.properties
            ├── schema.sql          # tables (idempotent)
            ├── data.sql            # seed: parties + PM + 500 MPs (idempotent)
            └── static/index.html   # the whole UI
```

## Run

### 1. Start Postgres

**Option A — Docker:**
```
docker compose up -d
```
This creates db `dissent`, user `dissent`, password `dissent` on port 5432.
> Note: this machine already runs a local Postgres on 5432. Use **either** Docker **or** the local
> instance, not both — otherwise the Docker container can't bind the port. To use Docker, stop the
> local one first (`brew services stop postgresql@15`).

**Option B — existing local Postgres** (what was used to verify this build):
```
createuser dissent --login || true
psql -d postgres -c "ALTER ROLE dissent PASSWORD 'dissent';"
createdb dissent -O dissent
```

Connection settings live in `backend/src/main/resources/application.properties`.

### 2. Build & run the backend
```
cd backend
mvn -s offline-settings.xml -DskipTests package
java -jar target/dissent-vote-1.0.0.jar
```

Then open **http://localhost:8080**.

> **Build note:** this machine's global Maven is mirrored to an auth-gated corporate Nexus, so
> `offline-settings.xml` (no mirror) is supplied to resolve dependencies from the local `~/.m2`
> cache. The parent is pinned to Spring Boot **3.2.0** because that version's starters are present
> in that cache. On a normal machine with open Maven Central access you can drop `-s
> offline-settings.xml` and bump the version freely.

### 3. Cast a vote
1. Click **Select** on any row.
2. Enter a reason (≥10 chars) and your mobile number, click **Send OTP**.
3. Read the OTP from the backend console (`=== MOCK OTP for ... is 123456 ===`), enter it.
4. Click **Cast vote**. Your current selection appears top-right with the time it was set
   (remembered in this browser's localStorage — there is no server read-back endpoint).
   Selecting a different row later warns you it will replace the previous one.

## API

| Method | Path                | Purpose                                              |
|--------|---------------------|------------------------------------------------------|
| GET    | `/api/entities`     | Public list with live vote counts.                   |
| POST   | `/api/otp/request`  | `{mobile}` → generates + (mock) sends OTP.           |
| POST   | `/api/otp/verify`   | `{mobile, code}` → `{token, validityMinutes}`.       |
| POST   | `/api/vote`         | `{token, entityId, reason}` → cast/change vote; returns the resulting selection. |

Vote casting is guarded by the time-bound token; OTP and token both expire after 10 minutes
(configurable in `application.properties`).

## Notes / limits (MVP)

- No SMS provider wired in — swap the `log.info` in `VoteService.sendOtp` for a real sender.
- One vote per mobile number is enforced by the DB primary key on `vote.mobile`.
- No unit tests, per request.
- Sample MP/party data is generated placeholder data; replace `data.sql` with real data later.
