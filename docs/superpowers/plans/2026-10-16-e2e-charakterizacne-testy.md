# Charakterizačné E2E testy blacklistu – implementačný plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sada Playwright E2E testov (UI + DB + logy), napísaná a zelená na legacy Liferay appke, ktorá po opravách odchýlok prejde bez zmeny aj na zmigrovanej `crm-web`.

**Architecture:** Obe appky prejdú z in-memory backendu na zdieľaný Postgres (jedna schéma `blacklist`) za existujúcim portom `BlacklistService`: legacy ako originál cez Spring JDBC (DAO po tabuľkách), crm-web cez JPA (Hibernate, entity, Spring Data repository, mapper na doménu) – typický migračný krok JDBC → JPA. Každá úspešná zmena zapíše riadok `AUDIT …` v rovnakom formáte do súborového logu appky. Samostatný Maven modul `e2e/` (Playwright Java + JUnit 5) ovláda UI cez page objects a overuje DB (`DbProbe`, JDBC) a log (`LogProbe`, súbor); cieľ (legacy/migrated) sa líši len v `AppTarget`.

**Tech Stack:** Postgres 16 (alpine), legacy Spring JDBC 4.3.6 (`NamedParameterJdbcTemplate`), crm-web Spring Data JPA + Hibernate (z BOM Spring Boot 4.1.1), commons-dbcp2 2.1.1 (legacy), Testcontainers (legacy 1.21.3, crm-web z BOM Spring Boot), log4j 1.2 `log4j.xml` (legacy), logback (crm-web), Playwright Java 1.52.0, JUnit 5.12.2, AssertJ 3.27.3, ovládač PostgreSQL 42.7.4.

**Spec:** `docs/superpowers/specs/2026-10-16-e2e-charakterizacne-testy-design.md`

## Global Constraints

- `input/` je read-only.
- Legacy moduly sa kompilujú s `--release 8` – **len Java 8 API** (žiadne `var`, records, `List.of`, `toList()`, `isBlank`, `Files.readString`).
- Identifikátory anglicky; komentáre v kóde **po slovensky** a vysvetľujú rozhodnutie / legacy ekvivalent, nie čo kód robí. Každá public metoda má JavaDoc.
- Testy `should_x_when_y`, `@DisplayName` po slovensky (ako ostatné testy v repe). Pokryť happy path, chybný vstup, prázdne, hranice.
- DB: databáza `crm`, schéma `blacklist`, superuser `postgres`/`postgres`, appky `crm_app`/`crm_app` (len DML). Image `postgres:16-alpine`.
- Seed je **jeden súbor** `docker/postgres/init/02-blacklist-seed.sql` – init kontajnera, reset v testoch Testcontainers, reset v smoke aj e2e.
- Audit riadok presne: `AUDIT event=<event> <k>=<v> …` – eventy a poradie kľúčov:
  `blacklist.create recordId operator` · `blacklist.change recordId operator state validTo` · `blacklist.note recordId operator` · `blacklist.identifier.remove recordId identifierId operator`. `operator` = login (`spravca`), chýbajúca hodnota = prázdny reťazec (`validTo=`). Žiadne osobné údaje.
- Logy na hoste: `docker/logs/liferay/customercare-portlet.log`, `docker/logs/crm-web/crm-web.log` (git-ignored).
- Legacy = pravda: očakávané texty v e2e sú z `legacy/customercare-portlets/src/main/resources/messages.properties`; odchýlky sa opravujú v `crm-web`.
- **Necommitovať** – commity robí používateľ; krok „Checkpoint“ = `git status` a krátke zhrnutie zmien. Nikdy `git push`.
- Anonymizácia podľa `CLAUDE.md` (žiadne mená klienta, interné URL, kód z `input/`).
- Docs (`README.md`, `docs/*.md`, `poznamky/`) po česky; `docs/superpowers/` po slovensky.
- Schému vlastní init skript: Hibernate v crm-web nikdy negeneruje DDL (`ddl-auto=none`, v profile `jpa` `validate`); `open-in-view=false`, entity sa na doménu mapujú v transakcii služby.
- crm-web vyberá implementáciu portu `crm.blacklist.store=memory|jpa` (default `memory`; Docker a `JpaBlacklistServiceTest` cez profil `jpa`).

## Review Focus

1. **Veľké písmená s diakritikou vo vyhľadávaní** (`MÁRIA`) – in-memory porovnáva `toLowerCase(Locale.ROOT)`, DB `ILIKE`; test `should_matchUppercaseDiacritics_when_searching` v `JdbcBlacklistServiceTest` (legacy) aj `JpaBlacklistServiceTest` (crm-web).
2. **Zástupné znaky LIKE vo vstupe** (`%`, `_`) – používateľ hľadá literál, nie vzor; test `should_treatWildcardsLiterally_when_searching` v `JdbcBlacklistServiceTest` aj `JpaBlacklistServiceTest`.
3. **Zlyhaná akcia nesmie zapísať audit** (validačná chyba, `BlacklistRuleException`, neexistujúci záznam) – testy v `BlacklistAuditTest` (crm-web) a v legacy controller testoch.
4. **Log súbor ešte neexistuje / bol zrotovaný** – `FileLogProbe` vráti prázdny zoznam, resp. číta od začiatku; testy v `FileLogProbeTest`.
5. **Súbežné odstránenie dvoch posledných identifikátorov** – zámok riadku (`FOR UPDATE`) v transakcii; postupné odstránenie testuje `should_refuse_when_removingLastIdentifier` po predchádzajúcom odstránení (`should_refuseSecond_when_removingBothIdentifiers`).

---

## Mapa súborov

| Súbor | Zodpovednosť |
|---|---|
| `docker/postgres/init/01-blacklist-schema.sql` | DDL schémy `blacklist` + rola `crm_app` |
| `docker/postgres/init/02-blacklist-seed.sql` | reset + seed (8 záznamov, 10 identifikátorov, 1 poznámka) |
| `docker/docker-compose.yml` | služba `postgres`, env/volumes pre `crm-web` a `liferay` |
| `.gitignore` | `/docker/logs/` |
| `crm-web/.../blacklist/ConditionType.java` | `allowedFor(StateReason)` – pravidlo zdieľané implementáciami portu |
| `crm-web/.../blacklist/jpa/BlacklistRecordEntity.java`, `IdentifierEntity.java`, `NoteEntity.java` | JPA entity nad tabuľkami schémy `blacklist` |
| `crm-web/.../blacklist/jpa/BlacklistRecordRepository.java`, `BlacklistSpecifications.java` | Spring Data repository (zámok riadku), vyhľadávanie ako Specification |
| `crm-web/.../blacklist/BlacklistEntityMapper.java` | entity → doménové objekty portu |
| `crm-web/.../blacklist/JpaBlacklistService.java` | JPA adaptér portu (transakcie, pravidlá) |
| `crm-web/src/main/resources/application-jpa.properties` | profil `jpa` (`crm.blacklist.store=jpa`, `ddl-auto=validate`) |
| `crm-web/.../blacklist/InMemoryBlacklistService.java` | podmienený bean (`crm.blacklist.store=memory`) |
| `crm-web/.../blacklist/AuditLog.java` | skladanie a zápis audit riadku |
| `crm-web/.../session/CrmSession.java` | `operatorLogin()` |
| `crm-web/.../blacklist/BlacklistDetailController.java`, `BlacklistCreateController.java` | volanie auditu |
| `crm-web/src/test/.../testsupport/PostgresTestDatabase.java` | Testcontainers Postgres nad init skriptmi |
| `crm-web/src/test/.../blacklist/JpaBlacklistServiceTest.java`, `ConditionTypeTest.java`, `AuditLogTest.java`, `BlacklistAuditTest.java` | testy |
| `legacy/customercare-portlets/.../backend/blacklist/ConditionType.java` | `allowedFor(StateReason)` |
| `legacy/customercare-portlets/.../backend/blacklist/BlacklistRecordDao.java`, `BlacklistIdentifierDao.java`, `BlacklistNoteDao.java` | JDBC DAO po tabuľkách (ako originál) |
| `legacy/customercare-portlets/.../backend/blacklist/JdbcBlacklistService.java` | adaptér portu – skladá DAO v transakcii (Java 8) |
| `legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml` | DataSource (dbcp2) + JDBC bean |
| `legacy/customercare-portlets/.../blacklist/AuditLog.java` | audit (commons-logging) |
| `legacy/frontend-base-customization/.../web/BaseCrmController.java` | `operatorLogin()` |
| `legacy/customercare-portlets/src/main/resources/log4j.xml` | konzola + súbor `/opt/liferay/logs/customercare-portlet.log` |
| `legacy/customercare-portlets/src/test/.../backend/blacklist/PostgresTestDatabase.java`, `JdbcBlacklistServiceTest.java`, `.../blacklist/AuditLogTest.java` | testy |
| `docker/smoke.sh`, `docker/smoke-migration.sh` | reset seedu, krok cez obe appky |
| `e2e/pom.xml` | samostatný Maven projekt |
| `e2e/src/test/java/com/example/crm/e2e/support/*` | `E2eConfig`, `AppTarget` (+2), `DbProbe` (+`JdbcDbProbe`, rows), `LogProbe` (+`FileLogProbe`, `AuditEvent`), `SsoLogin`, `Operator`, `E2eSession`, `E2eExtension`, `BrowserHolder` |
| `e2e/src/test/java/com/example/crm/e2e/pages/*` | `SearchPage`, `ResultPage`, `DetailPage`, `CreatePage` |
| `e2e/src/test/java/com/example/crm/e2e/BlacklistCharacterizationIT.java` | 7 scenárov |
| `docker/e2e.sh`, `docker/e2e.ps1` | spúšťanie cieľov za sebou |
| `e2e/README.md`, `poznamky/12-charakterizacni-e2e-testy.md`, `poznamky/README.md`, `docs/MIGRACIA.md`, `CLAUDE.md`, `README.md`, `legacy/README.md`, `crm-web/README.md` | dokumentácia |

---

### Task 1: Postgres v Dockeri (schéma, seed, rola)

**Files:**
- Create: `docker/postgres/init/01-blacklist-schema.sql`
- Create: `docker/postgres/init/02-blacklist-seed.sql`
- Modify: `docker/docker-compose.yml` (nová služba `postgres`, volume `postgres-data`)
- Modify: `.gitignore`

**Interfaces:**
- Produces: tabuľky `blacklist.blacklist_record(id, first_name, last_name, birth_date, state, state_reason, condition_type, valid_from, valid_to)`, `blacklist.blacklist_identifier(id, record_id, type, value)`, `blacklist.blacklist_note(id, record_id, text, author, created_at)`; rola `crm_app`/`crm_app`; seed s ID záznamov 1–8, identifikátorov 1–10 (záznam 1 → 1, 2; záznam 2 → 3; záznam 4 → 5; záznam 5 → 6, 7), 1 poznámka k záznamu 1. Služba `postgres` na `localhost:5432`, healthcheck.

- [ ] **Step 1: Schéma**

`docker/postgres/init/01-blacklist-schema.sql`:

```sql
-- Zdieľaná DB backendu blacklistu: tú istú schému používa stará Liferay appka aj crm-web (strangler nad jedným backendom).
-- Spúšťa sa raz pri prvom štarte kontajnera (docker-entrypoint-initdb.d) a v Testcontainers testoch.
CREATE SCHEMA blacklist;

CREATE TABLE blacklist.blacklist_record (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    birth_date     DATE         NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    state_reason   VARCHAR(20)  NOT NULL,
    condition_type VARCHAR(20)  NOT NULL,
    valid_from     DATE         NOT NULL,
    valid_to       DATE
);

CREATE TABLE blacklist.blacklist_identifier (
    id        BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    record_id BIGINT      NOT NULL REFERENCES blacklist.blacklist_record (id) ON DELETE CASCADE,
    type      VARCHAR(20) NOT NULL,
    value     VARCHAR(50) NOT NULL
);

CREATE TABLE blacklist.blacklist_note (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    record_id  BIGINT        NOT NULL REFERENCES blacklist.blacklist_record (id) ON DELETE CASCADE,
    text       VARCHAR(2000) NOT NULL,
    author     VARCHAR(100)  NOT NULL,
    created_at TIMESTAMP     NOT NULL
);

-- Appky dostanú len DML: schému spravuje backend (tu init skript), nie frontend.
CREATE ROLE crm_app LOGIN PASSWORD 'crm_app';
GRANT USAGE ON SCHEMA blacklist TO crm_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA blacklist TO crm_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA blacklist TO crm_app;
```

- [ ] **Step 2: Seed**

`docker/postgres/init/02-blacklist-seed.sql`:

```sql
-- Známy stav pre testy: rovnaké dáta ako InMemoryBlacklistService.seed(). RESTART IDENTITY = deterministické ID
-- (záznamy 1-8, identifikátory 1-10), na ktoré sa spoliehajú unit, smoke aj e2e testy. Spúšťa sa aj ako reset.
TRUNCATE blacklist.blacklist_note, blacklist.blacklist_identifier, blacklist.blacklist_record RESTART IDENTITY CASCADE;

INSERT INTO blacklist.blacklist_record
    (first_name, last_name, birth_date, state, state_reason, condition_type, valid_from, valid_to)
VALUES ('Peter', 'Vzorka', '1980-05-12', 'ACTIVE', 'FRAUD', 'BLOCK_ALL', '2024-01-10', NULL),
       ('Mária', 'Príkladová', '1975-11-03', 'ACTIVE', 'DEBT', 'REQUIRE_DEPOSIT', '2023-06-01', '2027-06-01'),
       ('Ján', 'Testovací', '1990-02-20', 'INACTIVE', 'DEBT', 'MANUAL_APPROVAL', '2022-03-15', '2024-03-15'),
       ('Eva', 'Ukážková', '1988-07-30', 'ACTIVE', 'ABUSE', 'MANUAL_APPROVAL', '2025-02-01', NULL),
       ('Tomáš', 'Fiktívny', '1969-12-24', 'ACTIVE', 'FRAUD', 'BLOCK_ALL', '2021-09-09', NULL),
       ('Lucia', 'Demová', '1995-04-18', 'INACTIVE', 'ABUSE', 'BLOCK_ALL', '2020-01-01', '2021-01-01'),
       ('Karol', 'Modelový', '1983-08-08', 'ACTIVE', 'DEBT', 'REQUIRE_DEPOSIT', '2025-10-01', NULL),
       ('Peter', 'Vzorkový', '1979-01-01', 'ACTIVE', 'DEBT', 'MANUAL_APPROVAL', '2024-05-05', NULL);

INSERT INTO blacklist.blacklist_identifier (record_id, type, value)
VALUES (1, 'BIRTH_CODE', '800512/1234'),
       (1, 'ID_CARD', 'AB123456'),
       (2, 'BIRTH_CODE', '755103/2345'),
       (3, 'ID_CARD', 'CD987654'),
       (4, 'PASSPORT', 'P1234567'),
       (5, 'BIRTH_CODE', '691224/3456'),
       (5, 'PASSPORT', 'X7654321'),
       (6, 'ID_CARD', 'EF555444'),
       (7, 'COMPANY_REG_NO', '12345678'),
       (8, 'BIRTH_CODE', '790101/4567');

INSERT INTO blacklist.blacklist_note (record_id, text, author, created_at)
VALUES (1, 'Opakované pokusy o objednávku na cudzie doklady.', 'Systém', '2024-01-10 09:00');
```

- [ ] **Step 3: Služba v compose**

V `docker/docker-compose.yml` pridaj službu hneď za `redis` a volume na koniec:

```yaml
  # Zdieľaná DB backendu blacklistu pre starú aj novú appku (schéma blacklist). Port na hoste kvôli e2e testom a DBeaveru.
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: crm
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports: ["5432:5432"]
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "postgres", "-d", "crm"]
      interval: 2s
      timeout: 3s
      retries: 30
```

```yaml
volumes:
  liferay-data:
  postgres-data:
```

Do `.gitignore` pod blok „Docker: WAR-y…“ pridaj:

```
# Logy appiek z bind mountov (čítajú ich e2e testy)
/docker/logs/
```

- [ ] **Step 4: Overenie**

Run:
```bash
docker compose -f docker/docker-compose.yml up -d postgres
docker compose -f docker/docker-compose.yml exec -T postgres sh -c 'until pg_isready -U postgres -d crm; do sleep 1; done'
docker compose -f docker/docker-compose.yml exec -T postgres psql -U postgres -d crm -tAc \
  "select (select count(*) from blacklist.blacklist_record), (select count(*) from blacklist.blacklist_identifier), (select count(*) from blacklist.blacklist_note), (select first_name from blacklist.blacklist_record where id = 2)"
docker compose -f docker/docker-compose.yml exec -T postgres psql "postgresql://crm_app:crm_app@localhost/crm" -tAc "select count(*) from blacklist.blacklist_record"
docker compose -f docker/docker-compose.yml exec -T postgres psql "postgresql://crm_app:crm_app@localhost/crm" -tAc "create table blacklist.x(i int)"
```
Expected: `8|10|1|Mária`, potom `8`, potom chyba `permission denied for schema blacklist`.

Ak volume už existoval z predchádzajúceho pokusu (init sa nespustí), `docker compose -f docker/docker-compose.yml down postgres && docker volume rm crm-legacy_postgres-data` a znova.

- [ ] **Step 5: Checkpoint** – `git status`; nové sú len súbory z tohto tasku.

---

### Task 2: crm-web – JPA adaptér `JpaBlacklistService` (entity, repository, mapovanie)

Legacy DAO blacklistu je čistý Spring JDBC (ručné SQL, RowMapper, jedno DAO na tabuľku). Nová appka ukazuje typický
migračný krok **JDBC DAO → JPA entity nad tými istými tabuľkami**. Schému vlastní init skript (Task 1) – Hibernate ju
nikdy negeneruje, v profile `jpa` ju pri štarte len validuje. Entity sú oddelené od domény (port, JSP aj in-memory
ostávajú bez zmeny); na doménu ich prevádza mapper v transakcii služby, takže do JSP sa nedostanú lazy kolekcie.

**Files:**
- Modify: `crm-web/pom.xml`
- Modify: `crm-web/src/main/resources/application.properties`
- Create: `crm-web/src/main/resources/application-jpa.properties`
- Modify: `crm-web/src/main/java/com/example/crm/blacklist/ConditionType.java`
- Modify: `crm-web/src/main/java/com/example/crm/blacklist/InMemoryBlacklistService.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/jpa/BlacklistRecordEntity.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/jpa/IdentifierEntity.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/jpa/NoteEntity.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/jpa/BlacklistRecordRepository.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/jpa/BlacklistSpecifications.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistEntityMapper.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/JpaBlacklistService.java`
- Create: `crm-web/src/test/java/com/example/crm/testsupport/PostgresTestDatabase.java`
- Create: `crm-web/src/test/java/com/example/crm/blacklist/JpaBlacklistServiceTest.java`
- Create: `crm-web/src/test/java/com/example/crm/blacklist/ConditionTypeTest.java`

**Interfaces:**
- Consumes: init skripty z Tasku 1 (cesta `../docker/postgres/init` z modulu `crm-web`).
- Produces: `public static List<ConditionType> ConditionType.allowedFor(StateReason reason)`; profil `jpa` (`crm.blacklist.store=jpa`, `ddl-auto=validate`); bean `JpaBlacklistService(BlacklistRecordRepository records, Clock clock)` pri `crm.blacklist.store=jpa`; `InMemoryBlacklistService` pri `memory` (default). Test helper `PostgresTestDatabase` (`start()`, `stop()`, `jdbcUrl()`, `appDataSource()`, `resetSeed()`).

- [ ] **Step 1: Závislosti a konfigurácia**

`crm-web/pom.xml` – do `<dependencies>` za `spring-boot-starter-data-redis`:

```xml
        <!-- Zdieľaná DB backendu blacklistu so starou appkou: JPA (Hibernate) nad tými istými tabuľkami ako legacy JDBC DAO. -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

`application.properties` – na koniec:

```properties
# Backend blacklistu: memory = in-memory náhrada (default, testy bez DB) | jpa = zdieľaná DB so starou appkou (profil jpa).
crm.blacklist.store=memory
spring.datasource.url=jdbc:postgresql://localhost:5432/crm
spring.datasource.username=crm_app
spring.datasource.password=crm_app
# Pri výpadku DB nech stránka zlyhá rýchlo (Hikari sa pripája až pri prvom dotaze, nie pri štarte).
spring.datasource.hikari.connection-timeout=2000
# Schému vlastní backend (init skript) – Hibernate ju nikdy negeneruje; profil jpa ju len validuje.
spring.jpa.hibernate.ddl-auto=none
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
# Bez profilu jpa (testy bez DB) sa Hibernate pri štarte nesmie pripájať kvôli metadátam.
spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false
# Entity sa mapujú na doménu v transakcii služby – JSP nedostane lazy kolekcie, open-in-view netreba.
spring.jpa.open-in-view=false
```

`crm-web/src/main/resources/application-jpa.properties`:

```properties
# Profil jpa (Docker, JpaBlacklistServiceTest): blacklist zo zdieľanej DB so starou appkou.
crm.blacklist.store=jpa
# Mapovanie entít sa pri štarte overí voči schéme z init skriptu – rozchod spadne hneď, nie až pri prvom dotaze.
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=true
```

- [ ] **Step 2: Failing test pre `ConditionType.allowedFor`**

`crm-web/src/test/java/com/example/crm/blacklist/ConditionTypeTest.java`:

```java
package com.example.crm.blacklist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionTypeTest {

    @Test
    @DisplayName("Povolené podmienky závisia od dôvodu, null = všetky")
    void should_dependOnReason_when_listingAllowedConditionTypes() {
        assertThat(ConditionType.allowedFor(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(ConditionType.allowedFor(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(ConditionType.allowedFor(StateReason.ABUSE))
                .containsExactly(ConditionType.MANUAL_APPROVAL, ConditionType.BLOCK_ALL);
        assertThat(ConditionType.allowedFor(null)).containsExactly(ConditionType.values());
    }
}
```

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=ConditionTypeTest`
Expected: FAIL – kompilácia, `allowedFor` neexistuje.

- [ ] **Step 3: `ConditionType.allowedFor` a delegácia z in-memory**

Do enumu `ConditionType` (zachovaj konštanty a komentár) pridaj:

```java
    /** Povolené podmienky pre dôvod; {@code null} = všetky. Pravidlo backendu – zdieľajú ho všetky implementácie portu. */
    public static List<ConditionType> allowedFor(StateReason reason) {
        if (reason == null) {
            return List.of(values());
        }
        return switch (reason) {
            case FRAUD -> List.of(BLOCK_ALL);
            case DEBT -> List.of(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            case ABUSE -> List.of(MANUAL_APPROVAL, BLOCK_ALL);
        };
    }
```

(import `java.util.List`). V `InMemoryBlacklistService.allowedConditionTypes` nahraď telo za `return ConditionType.allowedFor(reason);`.

`InMemoryBlacklistService` – anotácie triedy:

```java
@Service
// Default bez DB: existujúce @SpringBootTest testy bežia bez Postgresu; v Dockeri profil jpa.
@ConditionalOnProperty(name = "crm.blacklist.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryBlacklistService implements BlacklistService {
```

(import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`).

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=ConditionTypeTest` → Expected: PASS.

- [ ] **Step 4: Celý build bez DB (JPA na classpath, profil memory)**

Run: `mvn -q -B -f crm-web/pom.xml verify`
Expected: PASS – Hibernate sa pri štarte nepripája (`allow_jdbc_metadata_access=false` + explicitný dialekt), existujúce `@SpringBootTest` bežia s in-memory.

Ak štart kontextu zlyhá na pripojení k DB: over, že `spring.jpa.database-platform` je nastavený; inak v `application.properties` doplň `spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect`. Ako krajná možnosť: JPA konfiguráciu (`@EnableJpaRepositories`, `@EntityScan`) presuň do `@Configuration @Profile("jpa")` a v defaulte vylúč `HibernateJpaAutoConfiguration` cez `spring.autoconfigure.exclude`.

- [ ] **Step 5: Entity**

`crm-web/src/main/java/com/example/crm/blacklist/jpa/BlacklistRecordEntity.java`:

```java
package com.example.crm.blacklist.jpa;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.example.crm.blacklist.BlacklistState;
import com.example.crm.blacklist.ConditionType;
import com.example.crm.blacklist.IdentifierType;
import com.example.crm.blacklist.StateReason;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Riadok tabuľky blacklist_record. Legacy DAO mapoval stĺpce ručne (RowMapper, SQL v DAO); tu JPA nad tou istou
 * tabuľkou. Identifikátory a poznámky sú agregát záznamu (cascade + orphanRemoval) – v legacy samostatné DAO.
 */
@Entity
@Table(name = "blacklist_record", schema = "blacklist")
public class BlacklistRecordEntity {

    @Id
    // IDENTITY = stĺpec GENERATED BY DEFAULT AS IDENTITY zo schémy; ID ostávajú rovnaké ako pri zápise starou appkou.
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "birth_date", nullable = false)
    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    private BlacklistState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_reason", nullable = false)
    private StateReason stateReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false)
    private ConditionType conditionType;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @OneToMany(mappedBy = "record", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<IdentifierEntity> identifiers = new ArrayList<>();

    @OneToMany(mappedBy = "record", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<NoteEntity> notes = new ArrayList<>();

    /** Pre JPA. */
    protected BlacklistRecordEntity() {
    }

    /** Nový záznam (ID pridelí DB). */
    public BlacklistRecordEntity(String firstName, String lastName, LocalDate birthDate, BlacklistState state,
                                 StateReason stateReason, ConditionType conditionType, LocalDate validFrom,
                                 LocalDate validTo) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.state = state;
        this.stateReason = stateReason;
        this.conditionType = conditionType;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    /** Zmena stavu a platnosti (uloží sa pri commite – dirty checking). */
    public void changeState(BlacklistState newState, LocalDate newValidTo) {
        this.state = newState;
        this.validTo = newValidTo;
    }

    /** Pridá identifikátor (vloží sa kaskádou). */
    public void addIdentifier(IdentifierType type, String value) {
        identifiers.add(new IdentifierEntity(this, type, value));
    }

    /** Odstráni identifikátor (zmaže sa cez orphanRemoval). */
    public void removeIdentifier(IdentifierEntity identifier) {
        identifiers.remove(identifier);
    }

    /** Pridá poznámku (vloží sa kaskádou). */
    public void addNote(String text, String author, LocalDateTime createdAt) {
        notes.add(new NoteEntity(this, text, author, createdAt));
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public LocalDate getBirthDate() { return birthDate; }
    public BlacklistState getState() { return state; }
    public StateReason getStateReason() { return stateReason; }
    public ConditionType getConditionType() { return conditionType; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public List<IdentifierEntity> getIdentifiers() { return Collections.unmodifiableList(identifiers); }
    public List<NoteEntity> getNotes() { return Collections.unmodifiableList(notes); }
}
```

`IdentifierEntity.java`:

```java
package com.example.crm.blacklist.jpa;

import com.example.crm.blacklist.IdentifierType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Riadok tabuľky blacklist_identifier (legacy: vlastné DAO identifikátorov). */
@Entity
@Table(name = "blacklist_identifier", schema = "blacklist")
public class IdentifierEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private BlacklistRecordEntity record;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private IdentifierType type;

    @Column(name = "value", nullable = false)
    private String value;

    /** Pre JPA. */
    protected IdentifierEntity() {
    }

    IdentifierEntity(BlacklistRecordEntity record, IdentifierType type, String value) {
        this.record = record;
        this.type = type;
        this.value = value;
    }

    public Long getId() { return id; }
    public IdentifierType getType() { return type; }
    public String getValue() { return value; }
}
```

`NoteEntity.java`:

```java
package com.example.crm.blacklist.jpa;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Riadok tabuľky blacklist_note (legacy: vlastné DAO poznámok). */
@Entity
@Table(name = "blacklist_note", schema = "blacklist")
public class NoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private BlacklistRecordEntity record;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Pre JPA. */
    protected NoteEntity() {
    }

    NoteEntity(BlacklistRecordEntity record, String text, String author, LocalDateTime createdAt) {
        this.record = record;
        this.text = text;
        this.author = author;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getText() { return text; }
    public String getAuthor() { return author; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 6: Repository a vyhľadávanie**

`BlacklistRecordRepository.java`:

```java
package com.example.crm.blacklist.jpa;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository – náhrada legacy DAO (ručné SQL) pre agregát záznamu blacklistu. */
public interface BlacklistRecordRepository extends JpaRepository<BlacklistRecordEntity, Long>,
        JpaSpecificationExecutor<BlacklistRecordEntity> {

    /**
     * Záznam so zámkom riadku (SELECT … FOR UPDATE): dve súbežné odstránenia nesmú zmazať oba posledné
     * identifikátory (in-memory: synchronized, legacy JDBC: FOR UPDATE).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from BlacklistRecordEntity r where r.id = :id")
    Optional<BlacklistRecordEntity> findForUpdate(@Param("id") long id);
}
```

`BlacklistSpecifications.java`:

```java
package com.example.crm.blacklist.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.crm.blacklist.BlacklistSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

/** Kritériá vyhľadávania ako JPA Specification – legacy DAO skladalo WHERE ručne podľa vyplnených polí. */
public final class BlacklistSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private BlacklistSpecifications() {
    }

    /** Meno/priezvisko ako podreťazec bez ohľadu na veľkosť písmen, identifikátor presne, stav presne. */
    public static Specification<BlacklistRecordEntity> matching(BlacklistSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (hasText(criteria.getFirstName())) {
                predicates.add(cb.like(cb.lower(root.<String>get("firstName")),
                        containsPattern(criteria.getFirstName()), LIKE_ESCAPE));
            }
            if (hasText(criteria.getLastName())) {
                predicates.add(cb.like(cb.lower(root.<String>get("lastName")),
                        containsPattern(criteria.getLastName()), LIKE_ESCAPE));
            }
            if (hasText(criteria.getIdentifierValue())) {
                Subquery<Long> identifier = query.subquery(Long.class);
                Root<IdentifierEntity> i = identifier.from(IdentifierEntity.class);
                identifier.select(i.<Long>get("id")).where(
                        cb.equal(i.get("record"), root),
                        cb.equal(cb.lower(i.<String>get("value")),
                                criteria.getIdentifierValue().trim().toLowerCase(Locale.ROOT)));
                predicates.add(cb.exists(identifier));
            }
            if (criteria.getState() != null) {
                predicates.add(cb.equal(root.get("state"), criteria.getState()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** Používateľ hľadá text, nie LIKE vzor – % a _ sa escapujú. */
    private static String containsPattern(String query) {
        String lower = query.trim().toLowerCase(Locale.ROOT);
        return "%" + lower.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
```

- [ ] **Step 7: Test helper Postgres**

`crm-web/src/test/java/com/example/crm/testsupport/PostgresTestDatabase.java`:

```java
package com.example.crm.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Skutočný Postgres s tými istými init skriptmi ako docker-compose – schéma, rola aj seed majú jeden zdroj.
 * Appka sa pripája ako crm_app (overí aj práva), reset robí superuser.
 */
public final class PostgresTestDatabase {

    private static final Path INIT = Path.of("..", "docker", "postgres", "init").toAbsolutePath().normalize();
    private static final String SCHEMA_SCRIPT = "01-blacklist-schema.sql";
    private static final String SEED_SCRIPT = "02-blacklist-seed.sql";

    private final GenericContainer<?> container = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", "crm")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve(SCHEMA_SCRIPT)),
                    "/docker-entrypoint-initdb.d/" + SCHEMA_SCRIPT)
            .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve(SEED_SCRIPT)),
                    "/docker-entrypoint-initdb.d/" + SEED_SCRIPT)
            .withExposedPorts(5432)
            // Hláška je dvakrát: dočasný server počas initu a potom ostrý.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    /** Spustí kontajner (init skripty prebehnú pri prvom štarte). */
    public void start() {
        container.start();
    }

    /** Zastaví kontajner. */
    public void stop() {
        container.stop();
    }

    /** JDBC URL bežiaceho kontajnera (pre spring.datasource.url). */
    public String jdbcUrl() {
        return "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/crm";
    }

    /** DataSource ako appka – používateľ crm_app len s DML právami. */
    public DataSource appDataSource() {
        return new DriverManagerDataSource(jdbcUrl(), "crm_app", "crm_app");
    }

    /** Vráti DB do stavu seedu (rovnaký skript ako init a e2e reset). */
    public void resetSeed() {
        try (Connection connection = new DriverManagerDataSource(jdbcUrl(), "postgres", "postgres").getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(INIT.resolve(SEED_SCRIPT), StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Reset seedu zlyhal", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 8: Failing test JPA adaptéra**

`crm-web/src/test/java/com/example/crm/blacklist/JpaBlacklistServiceTest.java`:

```java
package com.example.crm.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.crm.common.NotFoundException;
import com.example.crm.testsupport.PostgresTestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Kontrakt portu nad skutočnou DB cez JPA – rovnaké prípady ako in-memory a legacy JDBC (parita správania).
 * Kontext s profilom jpa: štart zároveň overí mapovanie entít voči schéme (ddl-auto=validate).
 */
@SpringBootTest
@ActiveProfiles("jpa")
@Import(JpaBlacklistServiceTest.FixedClock.class)
class JpaBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private static final PostgresTestDatabase DB = new PostgresTestDatabase();

    static {
        DB.start();
    }

    @Autowired
    BlacklistService service;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
    }

    @AfterAll
    static void stopDatabase() {
        DB.stop();
    }

    @BeforeEach
    void resetSeed() {
        DB.resetSeed();
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Test
    @DisplayName("Profil jpa zapojí JPA implementáciu portu")
    void should_useJpaImplementation_when_profileJpa() {
        assertThat(service).isInstanceOf(JpaBlacklistService.class);
    }

    @Test
    @DisplayName("Seed obsahuje 8 záznamov s identifikátormi a poznámkou")
    void should_haveSeedData_when_searchingWithoutCriteria() {
        List<BlacklistRecord> all = service.search(new BlacklistSearchCriteria());

        assertThat(all).extracting(BlacklistRecord::getId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(all.get(0).getIdentifiers()).extracting(Identifier::value).containsExactly("800512/1234", "AB123456");
        assertThat(all.get(0).getNotes()).extracting(Note::author).containsExactly("Systém");
    }

    @Test
    @DisplayName("Priezvisko sa hľadá ako podreťazec bez ohľadu na veľkosť písmen")
    void should_matchLastNameContainsIgnoreCase_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    @DisplayName("Hľadanie funguje s diakritikou")
    void should_matchDiacritics_when_searchingFirstName() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("mária"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Veľké písmená s diakritikou nájdu ten istý záznam ako in-memory")
    void should_matchUppercaseDiacritics_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("MÁRIA"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Zástupné znaky LIKE sa berú ako obyčajné znaky")
    void should_treatWildcardsLiterally_when_searching() {
        assertThat(service.search(criteria(c -> c.setLastName("%")))).isEmpty();
        assertThat(service.search(criteria(c -> c.setLastName("_")))).isEmpty();
    }

    @Test
    @DisplayName("Identifikátor sa hľadá presne, bez ohľadu na veľkosť písmen")
    void should_matchIdentifierExactly_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("ab123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    @DisplayName("Hľadanie podľa stavu")
    void should_filterByState_when_stateGiven() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    @DisplayName("Neexistujúci záznam je prázdny Optional")
    void should_returnEmpty_when_recordUnknown() {
        assertThat(service.findById(999)).isEmpty();
    }

    @Test
    @DisplayName("Deaktivácia bez „platné do“ doplní dnešok")
    void should_useToday_when_deactivatingWithoutValidTo() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).orElseThrow();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Aktivácia s „platné do“ ho uloží bez zmeny")
    void should_keepValidTo_when_activatingWithValidTo() {
        service.change(3, BlacklistState.ACTIVE, LocalDate.of(2030, 1, 1));

        assertThat(service.findById(3).orElseThrow().getValidTo()).isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu je NotFound")
    void should_throwNotFound_when_changingUnknownRecord() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Posledný identifikátor sa odstrániť nedá")
    void should_refuse_when_removingLastIdentifier() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
        assertThat(service.findById(4).orElseThrow().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Z dvoch identifikátorov ide odstrániť len prvý, druhý ostane")
    void should_refuseSecond_when_removingBothIdentifiers() {
        service.removeIdentifier(5, 6);

        assertThatThrownBy(() -> service.removeIdentifier(5, 7)).isInstanceOf(BlacklistRuleException.class);
        assertThat(service.findById(5).orElseThrow().getIdentifiers()).extracting(Identifier::id).containsExactly(7L);
    }

    @Test
    @DisplayName("Identifikátor iného záznamu (podvrhnuté ID) je NotFound a nič sa nezmaže")
    void should_throwNotFound_when_identifierBelongsToOtherRecord() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
        assertThat(service.findById(2).orElseThrow().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Odstránenie vráti zostávajúce identifikátory")
    void should_returnRemaining_when_identifierRemoved() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::id).containsExactly(1L);
    }

    @Test
    @DisplayName("Nový záznam dostane ďalšie ID, je aktívny a má jeden identifikátor")
    void should_assignIdsAndActivate_when_created() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getValidTo()).isNull();
        assertThat(created.getIdentifiers()).containsExactly(new Identifier(11L, IdentifierType.ID_CARD, "ZZ123456"));
    }

    @Test
    @DisplayName("Poznámka má čas z hodín a orezaný text")
    void should_useClockAndTrim_when_addingNote() {
        service.addNote(6, "  Overené telefonicky.  ", "Správca");

        assertThat(service.findById(6).orElseThrow().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správca", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    @DisplayName("Poznámka k neexistujúcemu záznamu je NotFound")
    void should_throwNotFound_when_addingNoteToUnknownRecord() {
        assertThatThrownBy(() -> service.addNote(999, "text", "Správca")).isInstanceOf(NotFoundException.class);
    }

    private static BlacklistSearchCriteria criteria(Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).toList();
    }
}
```

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=JpaBlacklistServiceTest`
Expected: FAIL – kompilácia, `JpaBlacklistService` neexistuje.

- [ ] **Step 9: Mapper a služba**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistEntityMapper.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.blacklist.jpa.BlacklistRecordEntity;

/**
 * Entity → doménový objekt portu. V balíku domény, lebo konštruktor a mutátory {@link BlacklistRecord} sú
 * package-private (mení ich len backend). Volá sa v transakcii služby – lazy kolekcie sa načítajú tu, nie v JSP.
 */
final class BlacklistEntityMapper {

    private BlacklistEntityMapper() {
    }

    static BlacklistRecord toDomain(BlacklistRecordEntity entity) {
        BlacklistRecord record = new BlacklistRecord(entity.getId(), entity.getFirstName(), entity.getLastName(),
                entity.getBirthDate(), entity.getState(), entity.getStateReason(), entity.getConditionType(),
                entity.getValidFrom(), entity.getValidTo());
        entity.getIdentifiers().forEach(i -> record.addIdentifier(new Identifier(i.getId(), i.getType(), i.getValue())));
        entity.getNotes().forEach(n -> record.addNote(new Note(n.getText(), n.getAuthor(), n.getCreatedAt())));
        return record;
    }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/JpaBlacklistService.java`:

```java
package com.example.crm.blacklist;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import com.example.crm.blacklist.jpa.BlacklistRecordEntity;
import com.example.crm.blacklist.jpa.BlacklistRecordRepository;
import com.example.crm.blacklist.jpa.BlacklistSpecifications;
import com.example.crm.blacklist.jpa.IdentifierEntity;
import com.example.crm.common.NotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adaptér portu na zdieľanú DB backendu cez JPA. Legacy: JDBC DAO po tabuľkách nad tou istou schémou – migrácia
 * mení spôsob prístupu, nie dáta ani pravidlá. Pravidlá, ktoré in-memory chránil {@code synchronized},
 * drží transakcia a zámok riadku záznamu.
 */
@Service
@ConditionalOnProperty(name = "crm.blacklist.store", havingValue = "jpa")
@Transactional
public class JpaBlacklistService implements BlacklistService {

    private final BlacklistRecordRepository records;
    private final Clock clock;

    public JpaBlacklistService(BlacklistRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BlacklistRecord> search(BlacklistSearchCriteria criteria) {
        return records.findAll(BlacklistSpecifications.matching(criteria), Sort.by("id")).stream()
                .map(BlacklistEntityMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BlacklistRecord> findById(long id) {
        return records.findById(id).map(BlacklistEntityMapper::toDomain);
    }

    @Override
    public BlacklistRecord create(NewBlacklistRecord data) {
        BlacklistRecordEntity entity = new BlacklistRecordEntity(data.firstName(), data.lastName(), data.birthDate(),
                BlacklistState.ACTIVE, data.stateReason(), data.conditionType(), data.validFrom(), data.validTo());
        entity.addIdentifier(data.identifierType(), data.identifierValue());
        // saveAndFlush: IDENTITY pridelí ID záznamu aj identifikátora hneď, doména ich dostane vyplnené.
        return BlacklistEntityMapper.toDomain(records.saveAndFlush(entity));
    }

    @Override
    public void change(long id, BlacklistState state, LocalDate validTo) {
        LocalDate effectiveValidTo = (state == BlacklistState.INACTIVE && validTo == null) ? LocalDate.now(clock) : validTo;
        require(id).changeState(state, effectiveValidTo);
    }

    @Override
    public void addNote(long id, String text, String author) {
        lockRecord(id).addNote(text.trim(), author, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES));
    }

    @Override
    public List<Identifier> removeIdentifier(long recordId, long identifierId) {
        BlacklistRecordEntity record = lockRecord(recordId);
        IdentifierEntity identifier = record.getIdentifiers().stream()
                .filter(i -> i.getId() == identifierId)
                .findFirst()
                // Identifikátor iného záznamu = podvrhnuté ID → 404, nie zmena cudzích dát.
                .orElseThrow(() -> new NotFoundException("identifikátor " + identifierId + " záznamu " + recordId));
        if (record.getIdentifiers().size() == 1) {
            throw new BlacklistRuleException("blacklist.detail.lastIdentifier");
        }
        record.removeIdentifier(identifier);
        records.flush();
        return BlacklistEntityMapper.toDomain(record).getIdentifiers();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConditionType> allowedConditionTypes(StateReason reason) {
        return ConditionType.allowedFor(reason);
    }

    private BlacklistRecordEntity require(long id) {
        return records.findById(id).orElseThrow(() -> new NotFoundException("záznam blacklistu " + id));
    }

    private BlacklistRecordEntity lockRecord(long id) {
        return records.findForUpdate(id).orElseThrow(() -> new NotFoundException("záznam blacklistu " + id));
    }
}
```

- [ ] **Step 10: Testy prejdú**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest='JpaBlacklistServiceTest,ConditionTypeTest'`
Expected: PASS (prvý beh stiahne `postgres:16-alpine`; štart kontextu zároveň overí mapovanie – `ddl-auto=validate`).

Ak štart spadne na `Schema-validation: wrong column type encountered in column [state] … found [varchar], but expecting [...]`: Hibernate mapuje enum na natívny typ; doplň na enum polia `@JdbcTypeCode(SqlTypes.VARCHAR)` (import `org.hibernate.annotations.JdbcTypeCode`, `org.hibernate.type.SqlTypes`) a zapíš to ako pascu do poznámky (Task 11).
Ak zlyhá `should_matchUppercaseDiacritics_when_searching`: DB má `lc_ctype` „C“ (lower() mení len ASCII). Riešenie: v `PostgresTestDatabase` aj v compose `POSTGRES_INITDB_ARGS: "--encoding=UTF8 --locale=C.UTF-8"`, zapíš do poznámky.

- [ ] **Step 11: Celý build crm-web**

Run: `mvn -q -B -f crm-web/pom.xml verify`
Expected: PASS – existujúce `@SpringBootTest` bežia bez DB (profil memory), `JpaBlacklistServiceTest` s Postgresom.

- [ ] **Step 12: Checkpoint** – `git status`.

---

### Task 3: crm-web – audit log a súborový log

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/blacklist/AuditLog.java`
- Modify: `crm-web/src/main/java/com/example/crm/session/CrmSession.java`
- Modify: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistDetailController.java`
- Modify: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistCreateController.java`
- Modify: `docker/docker-compose.yml` (služba `crm-web`)
- Create: `crm-web/src/test/java/com/example/crm/blacklist/AuditLogTest.java`
- Create: `crm-web/src/test/java/com/example/crm/blacklist/BlacklistAuditTest.java`

**Interfaces:**
- Produces: `@Component AuditLog` s `recordCreated(long recordId, String operator)`, `recordChanged(long recordId, String operator, BlacklistState state, LocalDate validTo)`, `noteAdded(long recordId, String operator)`, `identifierRemoved(long recordId, long identifierId, String operator)`; package-private konštruktor `AuditLog(Consumer<String> sink)` pre testy. `default String CrmSession.operatorLogin()`. Konštruktory `BlacklistDetailController(BlacklistService, AuditLog)`, `BlacklistCreateController(BlacklistService, Clock, AuditLog)`; `removeIdentifier(long detailId, long identId, Model model, CrmSession session)`.

- [ ] **Step 1: Failing test formátu**

`crm-web/src/test/java/com/example/crm/blacklist/AuditLogTest.java`:

```java
package com.example.crm.blacklist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    private final List<String> lines = new ArrayList<>();
    private final AuditLog audit = new AuditLog(lines::add);

    @Test
    @DisplayName("Riadky auditu majú pevný formát a poradie kľúčov (kontrakt so starou appkou)")
    void should_writeContractLines_when_eventsRecorded() {
        audit.recordCreated(9, "spravca");
        audit.recordChanged(1, "spravca", BlacklistState.INACTIVE, LocalDate.of(2026, 10, 16));
        audit.noteAdded(1, "spravca");
        audit.identifierRemoved(1, 2, "spravca");

        assertThat(lines).containsExactly(
                "AUDIT event=blacklist.create recordId=9 operator=spravca",
                "AUDIT event=blacklist.change recordId=1 operator=spravca state=INACTIVE validTo=2026-10-16",
                "AUDIT event=blacklist.note recordId=1 operator=spravca",
                "AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca");
    }

    @Test
    @DisplayName("Chýbajúca hodnota je prázdna, nie „null“")
    void should_writeEmptyValue_when_validToMissing() {
        audit.recordChanged(3, "spravca", BlacklistState.ACTIVE, null);

        assertThat(lines).containsExactly("AUDIT event=blacklist.change recordId=3 operator=spravca state=ACTIVE validTo=");
    }
}
```

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=AuditLogTest` → Expected: FAIL (kompilácia).

- [ ] **Step 2: `AuditLog`**

```java
package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Audit zmien blacklistu – kontrakt zdieľaný so starou appkou: rovnaký riadok v oboch logoch, kontrolujú ho e2e testy.
 * Len ID, operátor a stav – žiadne osobné údaje (mená, hodnoty identifikátorov, text poznámky).
 */
@Component
public class AuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("crm.audit");

    private final Consumer<String> sink;

    public AuditLog() {
        this(LOG::info);
    }

    AuditLog(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Zapíše vytvorenie záznamu. */
    public void recordCreated(long recordId, String operator) {
        write("blacklist.create", "recordId", recordId, "operator", operator);
    }

    /** Zapíše zmenu stavu a platnosti (platnosť po doplnení dneška službou). */
    public void recordChanged(long recordId, String operator, BlacklistState state, LocalDate validTo) {
        write("blacklist.change", "recordId", recordId, "operator", operator, "state", state, "validTo", validTo);
    }

    /** Zapíše pridanie poznámky (bez textu). */
    public void noteAdded(long recordId, String operator) {
        write("blacklist.note", "recordId", recordId, "operator", operator);
    }

    /** Zapíše odstránenie identifikátora (bez hodnoty). */
    public void identifierRemoved(long recordId, long identifierId, String operator) {
        write("blacklist.identifier.remove", "recordId", recordId, "identifierId", identifierId, "operator", operator);
    }

    private void write(String event, Object... keyValues) {
        StringBuilder line = new StringBuilder("AUDIT event=").append(event);
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            line.append(' ').append(keyValues[i]).append('=').append(value == null ? "" : value);
        }
        sink.accept(line.toString());
    }
}
```

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=AuditLogTest` → Expected: PASS.

- [ ] **Step 3: `CrmSession.operatorLogin()`**

Do rozhrania `CrmSession` za `getOperator()`:

```java
    /** Login operátora pre audit (displayName je pre UI); „?“ ak identita chýba – handler ju má zaručenú cez ACL. */
    default String operatorLogin() {
        return getOperator().map(OperatorUser::login).orElse("?");
    }
```

- [ ] **Step 4: Failing testy auditu v controlleroch**

`crm-web/src/test/java/com/example/crm/blacklist/BlacklistAuditTest.java`:

```java
package com.example.crm.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import com.example.crm.session.OperatorUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

/** Audit vzniká len po úspešnej zmene – controllery priamo, in-memory backend (bez zdieľaného stavu s web testami). */
class BlacklistAuditTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final List<String> lines = new ArrayList<>();
    private final AuditLog audit = new AuditLog(lines::add);
    private final CrmSession session = mock(CrmSession.class);
    private final BlacklistDetailController detail = new BlacklistDetailController(service, audit);
    private final BlacklistCreateController create = new BlacklistCreateController(service, CLOCK, audit);

    @BeforeEach
    void operator() {
        when(session.getOperator()).thenReturn(Optional.of(new OperatorUser("spravca", "Správca")));
        when(session.operatorLogin()).thenCallRealMethod();
    }

    @Test
    @DisplayName("Zmena stavu zapíše audit s platnosťou doplnenou službou")
    void should_auditEffectiveValidTo_when_stateChanged() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        form.setState(BlacklistState.INACTIVE);

        detail.change(5, form, binding(form, "changeForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly(
                "AUDIT event=blacklist.change recordId=5 operator=spravca state=INACTIVE validTo=2026-10-08");
    }

    @Test
    @DisplayName("Validačná chyba pri zmene audit nezapíše")
    void should_notAudit_when_changeInvalid() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        BindingResult result = binding(form, "changeForm");
        result.reject("invalid");

        detail.change(5, form, result, new ExtendedModelMap(), session);

        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu audit nezapíše")
    void should_notAudit_when_recordUnknown() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        form.setState(BlacklistState.ACTIVE);

        assertThatThrownBy(() -> detail.change(999, form, binding(form, "changeForm"), new ExtendedModelMap(), session))
                .isInstanceOf(NotFoundException.class);
        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("Pridanie poznámky zapíše audit bez textu poznámky")
    void should_auditWithoutText_when_noteAdded() {
        NoteForm form = new NoteForm();
        form.setText("Tajný text");

        detail.addNote(6, form, binding(form, "noteForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.note recordId=6 operator=spravca");
    }

    @Test
    @DisplayName("Odstránenie identifikátora zapíše audit, odmietnutie posledného nie")
    void should_auditOnlySuccessfulRemoval_when_removingIdentifiers() {
        detail.removeIdentifier(1, 2, new ExtendedModelMap(), session);
        detail.removeIdentifier(4, 5, new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca");
    }

    @Test
    @DisplayName("Vytvorenie záznamu zapíše audit s novým ID, neplatný formulár nie")
    void should_auditNewId_when_recordCreated() {
        BlacklistCreateForm invalid = new BlacklistCreateForm();
        BindingResult invalidResult = binding(invalid, "createForm");
        invalidResult.reject("invalid");
        create.create(invalid, invalidResult, new ExtendedModelMap(), session);

        BlacklistCreateForm form = validCreateForm();
        create.create(form, binding(form, "createForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.create recordId=9 operator=spravca");
    }

    private static BlacklistCreateForm validCreateForm() {
        BlacklistCreateForm form = new BlacklistCreateForm();
        form.setFirstName("Nový");
        form.setLastName("Záznam");
        form.setBirthDate(LocalDate.of(1990, 1, 1));
        form.setStateReason(StateReason.DEBT);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        form.setValidFrom(LocalDate.of(2026, 10, 8));
        form.setIdentifierType(IdentifierType.ID_CARD);
        form.setIdentifierValue("ZZ123456");
        return form;
    }

    private static BindingResult binding(Object target, String name) {
        return new BeanPropertyBindingResult(target, name);
    }
}
```

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=BlacklistAuditTest` → Expected: FAIL (kompilácia – konštruktory s `AuditLog`).

- [ ] **Step 5: Audit v controlleroch**

`BlacklistDetailController`:
- pole `private final AuditLog audit;`, konštruktor `public BlacklistDetailController(BlacklistService service, AuditLog audit)`.
- `change` – po `service.change(...)`:

```java
        BlacklistRecord changed = load(detailId);
        // Audit s platnosťou po doplnení dneška službou – rovnaká hodnota, akú vidí operátor a DB.
        audit.recordChanged(detailId, session.operatorLogin(), changed.getState(), changed.getValidTo());
```

- `addNote` – po `service.addNote(...)`: `audit.noteAdded(detailId, session.operatorLogin());`
- `removeIdentifier` – nová signatúra a audit len pri úspechu:

```java
    @PostMapping("/resource/removeIdentAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String removeIdentifier(@RequestParam long detailId, @RequestParam long identId, Model model,
                                   CrmSession session) {
        try {
            model.addAttribute("identifiers", service.removeIdentifier(detailId, identId));
            audit.identifierRemoved(detailId, identId, session.operatorLogin());
        } catch (BlacklistRuleException e) {
            model.addAttribute("identifiers", load(detailId).getIdentifiers());
            model.addAttribute("errorKey", e.getMessageKey());
        }
        model.addAttribute("detailId", detailId);
        return "blacklist/fragments/identifiers";
    }
```

`BlacklistCreateController`:
- pole `private final AuditLog audit;`, konštruktor `public BlacklistCreateController(BlacklistService service, Clock clock, AuditLog audit)`.
- `create` – po `BlacklistRecord created = service.create(form.toCommand());`: `audit.recordCreated(created.getId(), session.operatorLogin());`

Ak niektorý existujúci test volá `new BlacklistDetailController(...)`/`new BlacklistCreateController(...)` (`grep -rn "new Blacklist.*Controller(" crm-web/src/test`), doplň argument `new AuditLog()`.

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest='BlacklistAuditTest,AuditLogTest'` → Expected: PASS.

- [ ] **Step 6: crm-web v compose na DB a so súborovým logom**

V `docker/docker-compose.yml` nahraď službu `crm-web` (zachovaj komentár nad ňou):

```yaml
  crm-web:
    build: ../crm-web
    environment:
      CRM_SSO_APIURL: http://sso-mock:8090/sso
      SPRING_DATA_REDIS_HOST: redis
      # Zdieľaná DB backendu so starou appkou (rovnaká schéma blacklist) cez JPA.
      SPRING_PROFILES_ACTIVE: jpa
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/crm
      # Log aj do súboru – ako na reálnom serveri; e2e testy ho čítajú cez bind mount.
      LOGGING_FILE_NAME: /logs/crm-web.log
    ports: ["8081:8080"]
    volumes:
      - ./logs/crm-web:/logs
    depends_on:
      redis:
        condition: service_started
      sso-mock:
        condition: service_started
      postgres:
        condition: service_healthy
```

- [ ] **Step 7: Celý build**

Run: `mvn -q -B -f crm-web/pom.xml verify` → Expected: PASS.

- [ ] **Step 8: Checkpoint** – `git status`.

---

### Task 4: legacy – JDBC DAO po tabuľkách a `JdbcBlacklistService` (Java 8)

**Files:**
- Modify: `legacy/pom.xml` (dependencyManagement)
- Modify: `legacy/customercare-portlets/pom.xml`
- Modify: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/ConditionType.java`
- Modify: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/InMemoryBlacklistService.java`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/BlacklistRecordDao.java`, `BlacklistIdentifierDao.java`, `BlacklistNoteDao.java`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/JdbcBlacklistService.java`
- Modify: `legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml`
- Create: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/PostgresTestDatabase.java`
- Create: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/JdbcBlacklistServiceTest.java`
- Modify: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/InMemoryBlacklistServiceTest.java` (nič – len overenie, že prechádza)

**Interfaces:**
- Consumes: init skripty z Tasku 1 (cesta `../../docker/postgres/init` z modulu `legacy/customercare-portlets`).
- Produces: `public static List<ConditionType> ConditionType.allowedFor(StateReason)`; `JdbcBlacklistService(DataSource dataSource, Clock clock)`; bean `blacklistService` = JDBC, bean `blacklistDataSource` (`BasicDataSource`) zo system properties `crm.db.url` / `crm.db.user` / `crm.db.password`.

- [ ] **Step 1: Závislosti**

`legacy/pom.xml` – do `<properties>`:

```xml
        <dbcp2.version>2.1.1</dbcp2.version>
        <postgresql.version>42.7.4</postgresql.version>
        <testcontainers.version>1.21.3</testcontainers.version>
```

do `<dependencyManagement><dependencies>`:

```xml
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-jdbc</artifactId>
                <version>${spring.version}</version>
            </dependency>
            <!-- Pool z éry legacy: commons-logging a commons-pool2 (Jedis) už vo WAR sú, nepribudne slf4j. -->
            <dependency>
                <groupId>org.apache.commons</groupId>
                <artifactId>commons-dbcp2</artifactId>
                <version>${dbcp2.version}</version>
            </dependency>
            <!-- Novší než postgresql.jar v lib/ext bundlu Liferay (ten nevie SCRAM); WAR classloader je child-first. -->
            <dependency>
                <groupId>org.postgresql</groupId>
                <artifactId>postgresql</artifactId>
                <version>${postgresql.version}</version>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers</artifactId>
                <version>${testcontainers.version}</version>
            </dependency>
```

`legacy/customercare-portlets/pom.xml` – do `<dependencies>` za `jackson-databind`:

```xml
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-dbcp2</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
```

a za `spring-test`:

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: `ConditionType.allowedFor` (test-first)**

Do `InMemoryBlacklistServiceTest` netreba zasahovať – existujúci test `should_dependOnReason_when_listingConditionTypes` pokryje delegáciu. Pridaj nový test do nového súboru `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/ConditionTypeTest.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConditionTypeTest {

    @Test
    @DisplayName("Povolené podmienky závisia od dôvodu, null = všetky")
    void should_dependOnReason_when_listingAllowedConditionTypes() {
        assertThat(ConditionType.allowedFor(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(ConditionType.allowedFor(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(ConditionType.allowedFor(StateReason.ABUSE))
                .containsExactly(ConditionType.MANUAL_APPROVAL, ConditionType.BLOCK_ALL);
        assertThat(ConditionType.allowedFor(null)).containsExactly(ConditionType.values());
    }
}
```

Run: `mvn -q -B -f legacy/pom.xml test -pl customercare-portlets -am -Dtest=ConditionTypeTest -Dsurefire.failIfNoSpecifiedTests=false` → Expected: FAIL (kompilácia).

`ConditionType.java` (Java 8):

```java
package com.example.crm.frontend.backend.blacklist;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Podmienka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType {
    BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL;

    /** Povolené podmienky pre dôvod; {@code null} = všetky. Pravidlo backendu – zdieľajú ho všetky implementácie portu. */
    public static List<ConditionType> allowedFor(StateReason reason) {
        if (reason == null) {
            return Arrays.asList(values());
        }
        switch (reason) {
            case FRAUD:
                return Collections.singletonList(BLOCK_ALL);
            case DEBT:
                return Arrays.asList(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            default:
                return Arrays.asList(MANUAL_APPROVAL, BLOCK_ALL);
        }
    }
}
```

V `InMemoryBlacklistService.allowedConditionTypes` telo → `return ConditionType.allowedFor(reason);` a odstráň nepoužitý `import java.util.Arrays;`.

Run (ten istý príkaz s `-Dtest='ConditionTypeTest,InMemoryBlacklistServiceTest'`) → Expected: PASS.

- [ ] **Step 3: Test helper Postgres (Java 8)**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/PostgresTestDatabase.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Skutočný Postgres s init skriptmi z docker/postgres/init – jeden zdroj schémy a seedu pre obe appky. */
final class PostgresTestDatabase {

    private static final Path INIT = Paths.get("..", "..", "docker", "postgres", "init").toAbsolutePath().normalize();
    private static final String SCHEMA_SCRIPT = "01-blacklist-schema.sql";
    private static final String SEED_SCRIPT = "02-blacklist-seed.sql";

    private final GenericContainer<?> container = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", "crm")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve(SCHEMA_SCRIPT)),
                    "/docker-entrypoint-initdb.d/" + SCHEMA_SCRIPT)
            .withCopyFileToContainer(MountableFile.forHostPath(INIT.resolve(SEED_SCRIPT)),
                    "/docker-entrypoint-initdb.d/" + SEED_SCRIPT)
            .withExposedPorts(5432)
            // Hláška je dvakrát: dočasný server počas initu a potom ostrý.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));

    void start() {
        container.start();
    }

    void stop() {
        container.stop();
    }

    /** DataSource ako appka – používateľ crm_app len s DML právami. */
    DataSource appDataSource() {
        return dataSource("crm_app", "crm_app");
    }

    /** Vráti DB do stavu seedu. */
    void resetSeed() {
        try (Connection connection = dataSource("postgres", "postgres").getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(new String(Files.readAllBytes(INIT.resolve(SEED_SCRIPT)), StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Reset seedu zlyhal", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DataSource dataSource(String user, String password) {
        String url = "jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/crm";
        return new DriverManagerDataSource(url, user, password);
    }
}
```

- [ ] **Step 4: Failing test JDBC adaptéra (Java 8)**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/JdbcBlacklistServiceTest.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kontrakt portu nad skutočnou DB – rovnaké prípady ako InMemoryBlacklistServiceTest (parita správania). */
class JdbcBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private static final PostgresTestDatabase DB = new PostgresTestDatabase();

    private static JdbcBlacklistService service;

    @BeforeAll
    static void startDatabase() {
        DB.start();
        service = new JdbcBlacklistService(DB.appDataSource(), CLOCK);
    }

    @AfterAll
    static void stopDatabase() {
        DB.stop();
    }

    @BeforeEach
    void resetSeed() {
        DB.resetSeed();
    }

    @Test
    @DisplayName("Seed obsahuje 8 záznamov s identifikátormi a poznámkou")
    void should_haveSeedData_when_searchingWithoutCriteria() {
        List<BlacklistRecord> all = service.search(new BlacklistSearchCriteria());

        assertThat(ids(all)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(all.get(0).getIdentifiers()).extracting(Identifier::getValue).containsExactly("800512/1234", "AB123456");
        assertThat(all.get(0).getNotes()).extracting(Note::getAuthor).containsExactly("Systém");
    }

    @Test
    @DisplayName("Priezvisko sa hľadá ako podreťazec bez ohľadu na veľkosť písmen")
    void should_matchLastNameContainsIgnoreCase_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    @DisplayName("Hľadanie funguje s diakritikou")
    void should_matchDiacritics_when_searchingFirstName() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("mária"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Veľké písmená s diakritikou nájdu ten istý záznam ako in-memory")
    void should_matchUppercaseDiacritics_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("MÁRIA"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Zástupné znaky LIKE sa berú ako obyčajné znaky")
    void should_treatWildcardsLiterally_when_searching() {
        assertThat(service.search(criteria(c -> c.setLastName("%")))).isEmpty();
        assertThat(service.search(criteria(c -> c.setLastName("_")))).isEmpty();
    }

    @Test
    @DisplayName("Identifikátor sa hľadá presne, bez ohľadu na veľkosť písmen")
    void should_matchIdentifierExactly_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("ab123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    @DisplayName("Hľadanie podľa stavu")
    void should_filterByState_when_stateGiven() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    @DisplayName("Neexistujúci záznam je prázdny Optional")
    void should_returnEmpty_when_recordUnknown() {
        assertThat(service.findById(999).isPresent()).isFalse();
    }

    @Test
    @DisplayName("Deaktivácia bez „platné do“ doplní dnešok")
    void should_useToday_when_deactivatingWithoutValidTo() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).get();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Aktivácia s „platné do“ ho uloží bez zmeny")
    void should_keepValidTo_when_activatingWithValidTo() {
        service.change(3, BlacklistState.ACTIVE, LocalDate.of(2030, 1, 1));

        assertThat(service.findById(3).get().getValidTo()).isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu je NotFound")
    void should_throwNotFound_when_changingUnknownRecord() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Posledný identifikátor sa odstrániť nedá")
    void should_refuse_when_removingLastIdentifier() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
        assertThat(service.findById(4).get().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Z dvoch identifikátorov ide odstrániť len prvý, druhý ostane")
    void should_refuseSecond_when_removingBothIdentifiers() {
        service.removeIdentifier(5, 6);

        assertThatThrownBy(() -> service.removeIdentifier(5, 7)).isInstanceOf(BlacklistRuleException.class);
        assertThat(service.findById(5).get().getIdentifiers()).extracting(Identifier::getId).containsExactly(7L);
    }

    @Test
    @DisplayName("Identifikátor iného záznamu (podvrhnuté ID) je NotFound a nič sa nezmaže")
    void should_throwNotFound_when_identifierBelongsToOtherRecord() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
        assertThat(service.findById(2).get().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Odstránenie vráti zostávajúce identifikátory")
    void should_returnRemaining_when_identifierRemoved() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("Nový záznam dostane ďalšie ID, je aktívny a má jeden identifikátor")
    void should_assignIdsAndActivate_when_created() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getValidTo()).isNull();
        assertThat(created.getIdentifiers()).containsExactly(new Identifier(11L, IdentifierType.ID_CARD, "ZZ123456"));
    }

    @Test
    @DisplayName("Poznámka má čas z hodín a orezaný text")
    void should_useClockAndTrim_when_addingNote() {
        service.addNote(6, "  Overené telefonicky.  ", "Správca");

        assertThat(service.findById(6).get().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správca", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    @DisplayName("Poznámka k neexistujúcemu záznamu je NotFound")
    void should_throwNotFound_when_addingNoteToUnknownRecord() {
        assertThatThrownBy(() -> service.addNote(999, "text", "Správca")).isInstanceOf(NotFoundException.class);
    }

    private static BlacklistSearchCriteria criteria(Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).collect(Collectors.toList());
    }
}
```

Run: `mvn -q -B -f legacy/pom.xml test -pl customercare-portlets -am -Dtest=JdbcBlacklistServiceTest -Dsurefire.failIfNoSpecifiedTests=false` → Expected: FAIL (kompilácia).

- [ ] **Step 5: Implementácia (Java 8) – DAO po tabuľkách ako v legacy backende**

Pôvodný backend má pre blacklist jedno JDBC DAO na tabuľku (ručné SQL, `RowMapper`, žiadne JPA). Demo to kopíruje:
tri package-private DAO a služba, ktorá ich skladá v transakcii a drží biznis pravidlá.

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/BlacklistRecordDao.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** DAO tabuľky blacklist_record – ako v legacy backende: ručné SQL a RowMapper, jedno DAO na tabuľku. */
class BlacklistRecordDao {

    private static final String SELECT = "SELECT r.id, r.first_name, r.last_name, r.birth_date, r.state, "
            + "r.state_reason, r.condition_type, r.valid_from, r.valid_to FROM blacklist.blacklist_record r";

    private static final RowMapper<BlacklistRecord> MAPPER = new RowMapper<BlacklistRecord>() {
        @Override
        public BlacklistRecord mapRow(ResultSet rs, int row) throws SQLException {
            return new BlacklistRecord(rs.getLong("id"), rs.getString("first_name"), rs.getString("last_name"),
                    rs.getObject("birth_date", LocalDate.class), BlacklistState.valueOf(rs.getString("state")),
                    StateReason.valueOf(rs.getString("state_reason")),
                    ConditionType.valueOf(rs.getString("condition_type")),
                    rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    BlacklistRecordDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Záznamy podľa kritérií (bez identifikátorov a poznámok). */
    List<BlacklistRecord> search(BlacklistSearchCriteria criteria) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (hasText(criteria.getFirstName())) {
            sql.append(" AND r.first_name ILIKE :firstName");
            params.addValue("firstName", containsPattern(criteria.getFirstName()));
        }
        if (hasText(criteria.getLastName())) {
            sql.append(" AND r.last_name ILIKE :lastName");
            params.addValue("lastName", containsPattern(criteria.getLastName()));
        }
        if (hasText(criteria.getIdentifierValue())) {
            sql.append(" AND EXISTS (SELECT 1 FROM blacklist.blacklist_identifier i"
                    + " WHERE i.record_id = r.id AND lower(i.value) = lower(:identifier))");
            params.addValue("identifier", criteria.getIdentifierValue().trim());
        }
        if (criteria.getState() != null) {
            sql.append(" AND r.state = :state");
            params.addValue("state", criteria.getState().name());
        }
        return jdbc.query(sql.append(" ORDER BY r.id").toString(), params, MAPPER);
    }

    /** Záznam podľa ID alebo prázdny zoznam. */
    List<BlacklistRecord> findById(long id) {
        return jdbc.query(SELECT + " WHERE r.id = :id", new MapSqlParameterSource("id", id), MAPPER);
    }

    /** Zamkne riadok záznamu do konca transakcie; false = záznam neexistuje. */
    boolean lock(long id) {
        return !jdbc.queryForList("SELECT id FROM blacklist.blacklist_record WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), Long.class).isEmpty();
    }

    /** Vloží aktívny záznam a vráti jeho ID. */
    long insert(NewBlacklistRecord data) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("firstName", data.getFirstName())
                .addValue("lastName", data.getLastName())
                .addValue("birthDate", data.getBirthDate(), Types.DATE)
                .addValue("state", BlacklistState.ACTIVE.name())
                .addValue("stateReason", data.getStateReason().name())
                .addValue("conditionType", data.getConditionType().name())
                .addValue("validFrom", data.getValidFrom(), Types.DATE)
                .addValue("validTo", data.getValidTo(), Types.DATE);
        return jdbc.queryForObject("INSERT INTO blacklist.blacklist_record (first_name, last_name, birth_date, state, "
                + "state_reason, condition_type, valid_from, valid_to) VALUES (:firstName, :lastName, :birthDate, "
                + ":state, :stateReason, :conditionType, :validFrom, :validTo) RETURNING id", params, Long.class);
    }

    /** Zmení stav a platnosť; vráti počet zmenených riadkov (0 = záznam neexistuje). */
    int updateState(long id, BlacklistState state, LocalDate validTo) {
        return jdbc.update("UPDATE blacklist.blacklist_record SET state = :state, valid_to = :validTo WHERE id = :id",
                new MapSqlParameterSource("state", state.name())
                        .addValue("validTo", validTo, Types.DATE)
                        .addValue("id", id));
    }

    /** Používateľ hľadá text, nie LIKE vzor – % a _ sa escapujú (Postgres má \ ako default ESCAPE). */
    private static String containsPattern(String query) {
        String escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
```

`BlacklistIdentifierDao.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** DAO tabuľky blacklist_identifier. */
class BlacklistIdentifierDao {

    private static final RowMapper<Identifier> MAPPER = new RowMapper<Identifier>() {
        @Override
        public Identifier mapRow(ResultSet rs, int row) throws SQLException {
            return new Identifier(rs.getLong("id"), IdentifierType.valueOf(rs.getString("type")), rs.getString("value"));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    BlacklistIdentifierDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Identifikátory záznamu v poradí vloženia. */
    List<Identifier> findByRecord(long recordId) {
        return jdbc.query("SELECT id, type, value FROM blacklist.blacklist_identifier WHERE record_id = :id ORDER BY id",
                new MapSqlParameterSource("id", recordId), MAPPER);
    }

    /** Vloží identifikátor k záznamu. */
    void insert(long recordId, IdentifierType type, String value) {
        jdbc.update("INSERT INTO blacklist.blacklist_identifier (record_id, type, value) VALUES (:recordId, :type, :value)",
                new MapSqlParameterSource("recordId", recordId).addValue("type", type.name()).addValue("value", value));
    }

    /** Zmaže identifikátor podľa ID. */
    void delete(long identifierId) {
        jdbc.update("DELETE FROM blacklist.blacklist_identifier WHERE id = :id",
                new MapSqlParameterSource("id", identifierId));
    }
}
```

`BlacklistNoteDao.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** DAO tabuľky blacklist_note. */
class BlacklistNoteDao {

    private static final RowMapper<Note> MAPPER = new RowMapper<Note>() {
        @Override
        public Note mapRow(ResultSet rs, int row) throws SQLException {
            return new Note(rs.getString("text"), rs.getString("author"), rs.getObject("created_at", LocalDateTime.class));
        }
    };

    private final NamedParameterJdbcTemplate jdbc;

    BlacklistNoteDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Poznámky záznamu v poradí vloženia. */
    List<Note> findByRecord(long recordId) {
        return jdbc.query("SELECT text, author, created_at FROM blacklist.blacklist_note WHERE record_id = :id ORDER BY id",
                new MapSqlParameterSource("id", recordId), MAPPER);
    }

    /** Vloží poznámku k záznamu. */
    void insert(long recordId, String text, String author, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO blacklist.blacklist_note (record_id, text, author, created_at) "
                        + "VALUES (:recordId, :text, :author, :createdAt)",
                new MapSqlParameterSource("recordId", recordId)
                        .addValue("text", text)
                        .addValue("author", author)
                        .addValue("createdAt", createdAt, Types.TIMESTAMP));
    }
}
```

`JdbcBlacklistService.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adaptér portu na zdieľanú DB backendu (schéma {@code blacklist}) – tú istú používa nová appka cez JPA.
 * Ako legacy backend: služba skladá DAO po tabuľkách v transakcii; pravidlá, ktoré in-memory chránil
 * {@code synchronized}, drží transakcia a zámok riadku záznamu.
 */
public class JdbcBlacklistService implements BlacklistService {

    private final BlacklistRecordDao records;
    private final BlacklistIdentifierDao identifiers;
    private final BlacklistNoteDao notes;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcBlacklistService(DataSource dataSource, Clock clock) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.records = new BlacklistRecordDao(jdbc);
        this.identifiers = new BlacklistIdentifierDao(jdbc);
        this.notes = new BlacklistNoteDao(jdbc);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.clock = clock;
    }

    @Override
    public List<BlacklistRecord> search(BlacklistSearchCriteria criteria) {
        List<BlacklistRecord> found = records.search(criteria);
        for (BlacklistRecord record : found) {
            loadDetails(record);
        }
        return found;
    }

    @Override
    public Optional<BlacklistRecord> findById(long id) {
        List<BlacklistRecord> found = records.findById(id);
        return found.isEmpty() ? Optional.<BlacklistRecord>empty() : Optional.of(loadDetails(found.get(0)));
    }

    @Override
    public BlacklistRecord create(final NewBlacklistRecord data) {
        return tx.execute(status -> {
            long id = records.insert(data);
            identifiers.insert(id, data.getIdentifierType(), data.getIdentifierValue());
            return require(id);
        });
    }

    @Override
    public void change(long id, BlacklistState state, LocalDate validTo) {
        LocalDate effectiveValidTo = (state == BlacklistState.INACTIVE && validTo == null) ? LocalDate.now(clock) : validTo;
        if (records.updateState(id, state, effectiveValidTo) == 0) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
    }

    @Override
    public void addNote(final long id, final String text, final String author) {
        tx.execute(status -> {
            lockRecord(id);
            notes.insert(id, text.trim(), author, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES));
            return null;
        });
    }

    @Override
    public List<Identifier> removeIdentifier(final long recordId, final long identifierId) {
        return tx.execute(status -> {
            lockRecord(recordId);
            List<Identifier> current = identifiers.findByRecord(recordId);
            boolean owned = false;
            for (Identifier identifier : current) {
                if (identifier.getId() == identifierId) {
                    owned = true;
                }
            }
            if (!owned) {
                // Identifikátor iného záznamu = podvrhnuté ID → 404, nie zmena cudzích dát.
                throw new NotFoundException("identifikátor " + identifierId + " záznamu " + recordId);
            }
            if (current.size() == 1) {
                throw new BlacklistRuleException("blacklist.detail.lastIdentifier");
            }
            identifiers.delete(identifierId);
            return Collections.unmodifiableList(identifiers.findByRecord(recordId));
        });
    }

    @Override
    public List<ConditionType> allowedConditionTypes(StateReason reason) {
        return ConditionType.allowedFor(reason);
    }

    /** Zámok riadku: dve súbežné odstránenia nesmú zmazať oba posledné identifikátory (in-memory: synchronized). */
    private void lockRecord(long id) {
        if (!records.lock(id)) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
    }

    private BlacklistRecord require(long id) {
        Optional<BlacklistRecord> record = findById(id);
        if (!record.isPresent()) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
        return record.get();
    }

    private BlacklistRecord loadDetails(BlacklistRecord record) {
        for (Identifier identifier : identifiers.findByRecord(record.getId())) {
            record.addIdentifier(identifier);
        }
        for (Note note : notes.findByRecord(record.getId())) {
            record.addNote(note);
        }
        return record;
    }
}
```

Poznámka: lambdy v `tx.execute(...)` sú Java 8 (`TransactionCallback` je funkčné rozhranie). Ak kompilátor nevie odvodiť typ pri `return null;` v `addNote`, použi `tx.execute((TransactionCallback<Void>) status -> { …; return null; })` s importom `org.springframework.transaction.support.TransactionCallback`.

Run (príkaz zo Step 4) → Expected: PASS.

- [ ] **Step 6: Prepojenie v XML**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml` – nahraď bean `blacklistService` za:

```xml
    <!--
      Zdieľaná DB backendu blacklistu (schéma blacklist, rovnaká ako v novej appke). V legacy DataSource z JNDI
      kontajnera; tu pool vo WAR-e, nastavenie z CATALINA_OPTS (-Dcrm.db.*). Pool sa pripojí až pri prvom dotaze,
      takže kontext sa načíta aj bez DB (PortletContextWiringTest).
    -->
    <bean id="blacklistDataSource" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
        <property name="driverClassName" value="org.postgresql.Driver"/>
        <property name="url" value="${crm.db.url:jdbc:postgresql://localhost:5432/crm}"/>
        <property name="username" value="${crm.db.user:crm_app}"/>
        <property name="password" value="${crm.db.password:crm_app}"/>
        <property name="maxTotal" value="10"/>
        <property name="maxWaitMillis" value="2000"/>
    </bean>
    <bean id="blacklistService" class="com.example.crm.frontend.backend.blacklist.JdbcBlacklistService">
        <constructor-arg ref="blacklistDataSource"/>
        <constructor-arg ref="clock"/>
    </bean>
```

Uprav aj komentár nad beanmi – blacklist už nie je in-memory (dashboard ostáva).

- [ ] **Step 7: Celý legacy build**

Run: `mvn -q -B -f legacy/pom.xml verify`
Expected: PASS (vrátane `PortletContextWiringTest` – načíta kontext s dbcp2 bez pripojenia).

- [ ] **Step 8: Checkpoint** – `git status`.

---

### Task 5: legacy – audit log, `operatorLogin()`, súborový log, compose

**Files:**
- Modify: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/web/BaseCrmController.java`
- Modify: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/web/BaseCrmControllerTest.java`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/AuditLog.java`
- Create: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/AuditLogTest.java`
- Modify: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/BlacklistDetailController.java`
- Modify: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/BlacklistCreateController.java`
- Modify: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistDetailControllerTest.java`
- Modify: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistCreateControllerTest.java`
- Create: `legacy/customercare-portlets/src/main/resources/log4j.xml`
- Modify: `docker/docker-compose.yml` (služba `liferay`)

**Interfaces:**
- Produces: `protected String BaseCrmController.operatorLogin()`; `public class AuditLog` (rovnaké metódy ako crm-web, `public AuditLog()`, package-private `AuditLog(Consumer<String> sink)`); package-private `setAuditLog(AuditLog)` v oboch controlleroch.

- [ ] **Step 1: `operatorLogin()` (test-first)**

Do `BaseCrmControllerTest`:

```java
    @Test
    @DisplayName("Login operátora pre audit, „?“ bez identity")
    void should_returnLogin_when_operatorPresent() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));
        assertThat(controller().operatorLogin()).isEqualTo("spravca");

        when(sessionManager.getOperator()).thenReturn(null);
        assertThat(controller().operatorLogin()).isEqualTo("?");
    }
```

Run: `mvn -q -B -f legacy/pom.xml test -pl frontend-base-customization -am -Dtest=BaseCrmControllerTest -Dsurefire.failIfNoSpecifiedTests=false` → FAIL.

Do `BaseCrmController` za `operatorName()`:

```java
    /** Login operátora pre audit (meno je pre UI); „?“ ak identita chýba. */
    protected String operatorLogin() {
        PortalOperator operator = sessionManager.getOperator();
        return operator == null ? "?" : operator.getLogin();
    }
```

Run znova → PASS.

- [ ] **Step 2: `AuditLog` (test-first)**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/AuditLogTest.java` – rovnaký obsah ako crm-web `AuditLogTest` z Tasku 3 Step 1, len balík `com.example.crm.frontend.blacklist`, import `com.example.crm.frontend.backend.blacklist.BlacklistState` a v Java 8 `new ArrayList<String>()`:

```java
package com.example.crm.frontend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.frontend.backend.blacklist.BlacklistState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditLogTest {

    private final List<String> lines = new ArrayList<String>();
    private final AuditLog audit = new AuditLog(lines::add);

    @Test
    @DisplayName("Riadky auditu majú pevný formát a poradie kľúčov (kontrakt s novou appkou)")
    void should_writeContractLines_when_eventsRecorded() {
        audit.recordCreated(9, "spravca");
        audit.recordChanged(1, "spravca", BlacklistState.INACTIVE, LocalDate.of(2026, 10, 16));
        audit.noteAdded(1, "spravca");
        audit.identifierRemoved(1, 2, "spravca");

        assertThat(lines).containsExactly(
                "AUDIT event=blacklist.create recordId=9 operator=spravca",
                "AUDIT event=blacklist.change recordId=1 operator=spravca state=INACTIVE validTo=2026-10-16",
                "AUDIT event=blacklist.note recordId=1 operator=spravca",
                "AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca");
    }

    @Test
    @DisplayName("Chýbajúca hodnota je prázdna, nie „null“")
    void should_writeEmptyValue_when_validToMissing() {
        audit.recordChanged(3, "spravca", BlacklistState.ACTIVE, null);

        assertThat(lines).containsExactly("AUDIT event=blacklist.change recordId=3 operator=spravca state=ACTIVE validTo=");
    }
}
```

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/AuditLog.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistState;
import java.time.LocalDate;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Audit zmien blacklistu – kontrakt zdieľaný s novou appkou: rovnaký riadok v oboch logoch, kontrolujú ho e2e testy.
 * Len ID, operátor a stav – žiadne osobné údaje. Kategória crm.audit ide cez log4j portletu do súboru (log4j.xml).
 */
public class AuditLog {

    private static final Log LOG = LogFactory.getLog("crm.audit");

    private final Consumer<String> sink;

    public AuditLog() {
        this(new Consumer<String>() {
            @Override
            public void accept(String line) {
                LOG.info(line);
            }
        });
    }

    AuditLog(Consumer<String> sink) {
        this.sink = sink;
    }

    /** Zapíše vytvorenie záznamu. */
    public void recordCreated(long recordId, String operator) {
        write("blacklist.create", "recordId", recordId, "operator", operator);
    }

    /** Zapíše zmenu stavu a platnosti (platnosť po doplnení dneška službou). */
    public void recordChanged(long recordId, String operator, BlacklistState state, LocalDate validTo) {
        write("blacklist.change", "recordId", recordId, "operator", operator, "state", state, "validTo", validTo);
    }

    /** Zapíše pridanie poznámky (bez textu). */
    public void noteAdded(long recordId, String operator) {
        write("blacklist.note", "recordId", recordId, "operator", operator);
    }

    /** Zapíše odstránenie identifikátora (bez hodnoty). */
    public void identifierRemoved(long recordId, long identifierId, String operator) {
        write("blacklist.identifier.remove", "recordId", recordId, "identifierId", identifierId, "operator", operator);
    }

    private void write(String event, Object... keyValues) {
        StringBuilder line = new StringBuilder("AUDIT event=").append(event);
        for (int i = 0; i < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            line.append(' ').append(keyValues[i]).append('=').append(value == null ? "" : value);
        }
        sink.accept(line.toString());
    }
}
```

Run: `mvn -q -B -f legacy/pom.xml test -pl customercare-portlets -am -Dtest=AuditLogTest -Dsurefire.failIfNoSpecifiedTests=false` → PASS (po napísaní triedy; najprv over FAIL bez nej).

- [ ] **Step 3: Failing testy auditu v controlleroch**

Do `BlacklistDetailControllerTest` (setup už mockuje operátora `spravca`) pridaj pole a prepojenie:

```java
    private final List<String> auditLines = new java.util.ArrayList<String>();
```

v `@BeforeEach wire()` doplň `controller.setAuditLog(new AuditLog(auditLines::add));` a testy:

```java
    @Test
    @DisplayName("Zmena stavu zapíše audit s platnosťou doplnenou službou")
    void should_auditEffectiveValidTo_when_stateChanged() {
        controller.change(5, BlacklistState.INACTIVE, null, response);

        assertThat(auditLines).containsExactly(
                "AUDIT event=blacklist.change recordId=5 operator=spravca state=INACTIVE validTo=2026-10-08");
    }

    @Test
    @DisplayName("Prázdna poznámka audit nezapíše, platná áno (bez textu)")
    void should_auditOnlyValidNote_when_addingNotes() {
        controller.addNote(6, "   ", response);
        controller.addNote(6, "Tajný text", response);

        assertThat(auditLines).containsExactly("AUDIT event=blacklist.note recordId=6 operator=spravca");
    }

    @Test
    @DisplayName("Odstránenie identifikátora zapíše audit, odmietnutie posledného nie")
    void should_auditOnlySuccessfulRemoval_when_removingIdentifiers() {
        MockResourceRequest post = new MockResourceRequest();
        post.setMethod("POST");

        controller.removeIdentifier(1, 2, post, new MockResourceResponse());
        controller.removeIdentifier(4, 5, post, new MockResourceResponse());

        assertThat(auditLines).containsExactly(
                "AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca");
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu audit nezapíše")
    void should_notAudit_when_recordUnknown() {
        assertThatThrownBy(() -> controller.change(999, BlacklistState.ACTIVE, null, response))
                .isInstanceOf(NotFoundException.class);
        assertThat(auditLines).isEmpty();
    }
```

(Ak `MockResourceRequest` nemá `setMethod`, pozri, ako existujúci test volá POST variant `removeIdentifier`, a použi rovnaký spôsob.)

Do `BlacklistCreateControllerTest`: pole `auditLines`, v `wire()` `controller.setAuditLog(new AuditLog(auditLines::add));` a `when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));` (import `com.example.sso.api.PortalOperator`, `org.mockito.Mockito.when`, ak chýbajú). Test – použi rovnaké vyplnenie formulára ako existujúci `should_createAndGoToDetail_when_formValid` a over:

```java
    @Test
    @DisplayName("Vytvorenie záznamu zapíše audit s novým ID")
    void should_auditNewId_when_recordCreated() {
        CreateForm form = validForm();
        controller.createAction(form, new BeanPropertyBindingResult(form, BlacklistCreateController.FORM),
                new SimpleSessionStatus(), response);

        assertThat(auditLines).containsExactly("AUDIT event=blacklist.create recordId=9 operator=spravca");
    }

    @Test
    @DisplayName("Neplatný formulár audit nezapíše")
    void should_notAudit_when_formInvalid() {
        CreateForm form = new CreateForm();
        controller.createAction(form, new BeanPropertyBindingResult(form, BlacklistCreateController.FORM),
                new SimpleSessionStatus(), response);

        assertThat(auditLines).isEmpty();
    }
```

kde `validForm()` je privátna metóda vytiahnutá z existujúceho testu `should_createAndGoToDetail_when_formValid` (ak tam vyplnenie nie je v metóde, extrahuj ho a použi v oboch testoch). Importy: `org.springframework.validation.BeanPropertyBindingResult`, `org.springframework.web.bind.support.SimpleSessionStatus`.

Run: `mvn -q -B -f legacy/pom.xml test -pl customercare-portlets -am -Dtest='BlacklistDetailControllerTest,BlacklistCreateControllerTest' -Dsurefire.failIfNoSpecifiedTests=false` → FAIL (`setAuditLog` neexistuje).

- [ ] **Step 4: Audit v controlleroch**

`BlacklistDetailController` – pole a setter:

```java
    // Bez Spring beanu: audit nemá závislosti; setter len pre testy (legacy štýl setter injection).
    private AuditLog auditLog = new AuditLog();

    void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }
```

- `change`: po `blacklistService.change(detailId, state, validTo);`

```java
        BlacklistRecord changed = require(detailId);
        // Audit s platnosťou po doplnení dneška službou – rovnaká hodnota, akú vidí operátor a DB.
        auditLog.recordChanged(detailId, operatorLogin(), changed.getState(), changed.getValidTo());
```

- `addNote`: v `else` vetve po `blacklistService.addNote(...)`: `auditLog.noteAdded(detailId, operatorLogin());`
- `removeIdentifier`: v `try` po `view.addObject("identifiers", blacklistService.removeIdentifier(detailId, identId));`: `auditLog.identifierRemoved(detailId, identId, operatorLogin());`

`BlacklistCreateController` – rovnaké pole + setter; v `createAction` po `BlacklistRecord created = blacklistService.create(form.toNewRecord());`: `auditLog.recordCreated(created.getId(), operatorLogin());`

Run príkaz zo Step 3 → PASS.

- [ ] **Step 5: `log4j.xml` portletu**

`legacy/customercare-portlets/src/main/resources/log4j.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE log4j:configuration SYSTEM "log4j.dtd">
<!--
  Liferay pri deployi vloží do WAR-u log4j.jar a log4j.properties len s konzolou – log portletu by v súbore neskončil.
  log4j 1.2 uprednostní log4j.xml pred .properties: portlet má vlastný súbor ako aplikácia na reálnom serveri
  (/opt/liferay/logs je v Dockeri bind mount docker/logs/liferay – čítajú ho e2e testy).
-->
<log4j:configuration xmlns:log4j="http://jakarta.apache.org/log4j/">
    <appender name="CONSOLE" class="org.apache.log4j.ConsoleAppender">
        <layout class="org.apache.log4j.EnhancedPatternLayout">
            <param name="ConversionPattern" value="%d{ABSOLUTE} %-5p [%c{1}:%L] %m%n"/>
        </layout>
    </appender>
    <appender name="FILE" class="org.apache.log4j.RollingFileAppender">
        <param name="File" value="/opt/liferay/logs/customercare-portlet.log"/>
        <param name="Append" value="true"/>
        <param name="Encoding" value="UTF-8"/>
        <param name="MaxFileSize" value="10MB"/>
        <param name="MaxBackupIndex" value="3"/>
        <layout class="org.apache.log4j.EnhancedPatternLayout">
            <param name="ConversionPattern" value="%d{ISO8601} %-5p [%t] [%c] %m%n"/>
        </layout>
    </appender>
    <root>
        <priority value="INFO"/>
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
</log4j:configuration>
```

- [ ] **Step 6: Liferay v compose**

V `docker/docker-compose.yml` služba `liferay`:
- do `CATALINA_OPTS` pridaj riadky:

```
        -Dcrm.db.url=jdbc:postgresql://postgres:5432/crm
        -Dcrm.db.user=crm_app
        -Dcrm.db.password=crm_app
```

- do `volumes` pridaj `- ./logs/liferay:/opt/liferay/logs`
- `depends_on` prepíš na mapu:

```yaml
    depends_on:
      redis:
        condition: service_started
      sso-mock:
        condition: service_started
      postgres:
        condition: service_healthy
```

- [ ] **Step 7: Celý legacy build**

Run: `mvn -q -B -f legacy/pom.xml verify` → Expected: PASS.

- [ ] **Step 8: Checkpoint** – `git status`.

---

### Task 6: Stack s DB – deploy, overenie logov, smoke skripty

**Files:**
- Modify: `docker/smoke.sh`, `docker/smoke-migration.sh`

**Interfaces:**
- Consumes: všetko z Taskov 1–5.
- Produces: bežiaci stack, v ktorom obe appky čítajú/zapisujú `blacklist` v Postgrese; audit v `docker/logs/liferay/customercare-portlet.log` a `docker/logs/crm-web/crm-web.log`. Smoke skripty začínajú resetom seedu.

- [ ] **Step 1: Reset seedu na začiatku smoke skriptov**

V `docker/smoke.sh` aj `docker/smoke-migration.sh` hneď za definíciu funkcie `rc()` pridaj:

```bash
# Obe appky zdieľajú DB blacklistu – výsledok nesmie závisieť od toho, čo zmenil predchádzajúci beh.
reset_db() { docker compose exec -T postgres psql -q -U postgres -d crm -f /docker-entrypoint-initdb.d/02-blacklist-seed.sql > /dev/null; }
```

a pred prvý blok `echo "== 1. …"` (po čakacej slučke) volanie `reset_db`.

- [ ] **Step 2: Krok cez obe appky v `smoke-migration.sh`**

Do `smoke-migration.sh` pridaj premennú `L="http://localhost:8080"` (vedľa `B`) a helper `attr_of` skopírovaný zo `smoke.sh`. Za blok „== 5. Zdieľaná session v Redise“ vlož:

```bash
echo "== 5b. Zdieľaná DB: zmena z novej appky je vidieť v starej"
# Záznam 7 zmenila nová appka v kroku 4 (INACTIVE); Liferay ho číta z tej istej DB. Starý blacklist ide mimo nginx (strangler).
curl -s -L -b "$JAR" -c "$JAR" -o "$WORK/l-search.html" "$L/web/crm/blacklist"
curl -s -L -b "$JAR" -c "$JAR" -o "$WORK/l-result.html" -d firstName= -d "lastName=Modelov" -d identifierValue= -d state= \
    "$(attr_of searchForm "$WORK/l-search.html" action)"
curl -s -L -b "$JAR" -c "$JAR" -o "$WORK/l-detail.html" "$(attr_of detail-7 "$WORK/l-result.html" href)"
expect "Liferay: záznam 7 je Neaktívny" 'id="recordState">Neaktívny' "$WORK/l-detail.html"
```

Na koniec skriptu (pred `rm -rf "$WORK"`) `reset_db`.

- [ ] **Step 3: Build a deploy**

Run: `bash docker/build-and-deploy.sh` (beží na pozadí; čaká na deploy portletov – vlastné čakacie slučky nepíš).
Expected: `Hotovo za ~N s.`

- [ ] **Step 4: Smoke testy**

Run: `bash docker/smoke.sh` → Expected: `SMOKE OK`.
Run: `bash docker/smoke-migration.sh` → Expected: `MIGRATION SMOKE OK` (vrátane kroku 5b).

Ak `smoke.sh` zlyhá na DB (500 v blackliste), pozri `docker compose -f docker/docker-compose.yml logs liferay | grep -iE "postgres|SQLException|dbcp" | tail -20`:
- `The authentication type 10 is not supported` → WAR používa starý `postgresql.jar` z `lib/ext`; over `ls /opt/liferay/tomcat-7.0.62/webapps/customercare-portlet/WEB-INF/lib | grep postgres` a že `driverClassName` je nastavený (dbcp2 načíta ovládač classloaderom WAR).

- [ ] **Step 5: Audit v súboroch**

Run:
```bash
grep -c "AUDIT event=blacklist.change" docker/logs/liferay/customercare-portlet.log
grep -c "AUDIT event=blacklist.change" docker/logs/crm-web/crm-web.log
grep -E " (ERROR|FATAL) " docker/logs/liferay/customercare-portlet.log | tail -5
```
Expected: obe počítadlá ≥ 1 (smoke mení stav v oboch appkách); posledný príkaz bez ERROR riadkov z bežného toku (WARN pri výpadku Redisu v smoke je v poriadku).

Ak `customercare-portlet.log` neexistuje: deployer prepísal alebo odstránil `log4j.xml`. Over
`docker compose -f docker/docker-compose.yml exec -T liferay ls /opt/liferay/tomcat-7.0.62/webapps/customercare-portlet/WEB-INF/classes/`.
Záloha (system property `log4j.configuration` by platila pre celý Tomcat, nie pre jeden WAR): premenuj súbor na
`customercare-log4j.xml` a načítaj ho explicitne `DOMConfigurator.configure(getClass().getResource("/customercare-log4j.xml"))`
v `ServletContextListener` registrovanom vo `web.xml` portletu (log4j je vo WAR-e až po deployi – závislosť
`log4j:log4j:1.2.17` pridaj so `<scope>provided</scope>`). Postup zapíš do poznámky (Task 11).

- [ ] **Step 6: Checkpoint** – `git status`.

---

### Task 7: e2e modul – kostra, konfigurácia, ciele a sondy (unit testy)

**Files:**
- Create: `e2e/pom.xml`
- Create: `e2e/src/test/java/com/example/crm/e2e/support/E2eConfig.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/support/AppTarget.java`, `LegacyTarget.java`, `MigratedTarget.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/support/AuditEvent.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/support/LogProbe.java`, `FileLogProbe.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/support/DbProbe.java`, `JdbcDbProbe.java`, `RecordRow.java`, `IdentifierRow.java`, `NoteRow.java`
- Test: `e2e/src/test/java/com/example/crm/e2e/support/AuditEventTest.java`, `FileLogProbeTest.java`, `AppTargetTest.java`

**Interfaces:**
- Produces:
  - `record E2eConfig(String target, String ssoUrl, String legacyUrl, String migratedUrl, String jdbcUrl, String jdbcUser, String jdbcPassword, Path logsDir, String browser, boolean headed, Path seedScript)` + `static E2eConfig fromSystemProperties()`.
  - `sealed interface AppTarget permits LegacyTarget, MigratedTarget` – `String name()`, `String entryUrl()`, `Path appLog()`, `static AppTarget of(E2eConfig)`.
  - `record AuditEvent(String event, Map<String, String> fields)` + `static Optional<AuditEvent> parse(String line)`.
  - `interface LogProbe` – `void mark()`, `List<AuditEvent> auditEvents()`, `List<String> errors()`, `String excerpt()`, `default List<AuditEvent> awaitAuditEvents(int expected, Duration timeout)`; `FileLogProbe(Path file)`.
  - `interface DbProbe` – `void reset()`, `Optional<RecordRow> record(long id)`, `List<IdentifierRow> identifiers(long recordId)`, `List<NoteRow> notes(long recordId)`, `int recordCount()`, `LocalDate today()`; `JdbcDbProbe(E2eConfig)`.
  - `record RecordRow(long id, String firstName, String lastName, String state, LocalDate validTo)`, `record IdentifierRow(long id, String type, String value)`, `record NoteRow(String text, String author)`.

- [ ] **Step 1: `e2e/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <!--
      Charakterizačné e2e testy blacklistu: napísané na legacy (Liferay), spúšťané bez zmeny aj na zmigrovanej appke.
      Samostatný projekt – nie je súčasťou buildu appiek, potrebuje bežiaci stack (docker/build-and-deploy.sh).
      *Test = unit testy podpory (surefire), *IT = e2e scenáre proti stacku (failsafe).
    -->
    <groupId>com.example.crm</groupId>
    <artifactId>crm-e2e</artifactId>
    <version>0.1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <playwright.version>1.52.0</playwright.version>
        <junit.version>5.12.2</junit.version>
        <assertj.version>3.27.3</assertj.version>
        <postgresql.version>42.7.4</postgresql.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <version>${playwright.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <version>${postgresql.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <argLine>-Dfile.encoding=UTF-8</argLine>
                </configuration>
            </plugin>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <version>3.5.2</version>
                <configuration>
                    <argLine>-Dfile.encoding=UTF-8</argLine>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>integration-test</goal>
                            <goal>verify</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Failing unit testy podpory**

`AuditEventTest.java`:

```java
package com.example.crm.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    @Test
    @DisplayName("Riadok logu s AUDIT sa rozparsuje na event a polia bez ohľadu na prefix logu")
    void should_parseEventAndFields_when_lineContainsAudit() {
        String line = "2026-10-16 10:00:00,123 INFO  [http-exec-1] [crm.audit] "
                + "AUDIT event=blacklist.change recordId=1 operator=spravca state=INACTIVE validTo=2026-10-16";

        assertThat(AuditEvent.parse(line)).contains(new AuditEvent("blacklist.change",
                Map.of("recordId", "1", "operator", "spravca", "state", "INACTIVE", "validTo", "2026-10-16")));
    }

    @Test
    @DisplayName("Prázdna hodnota ostane prázdna")
    void should_keepEmptyValue_when_valueMissing() {
        assertThat(AuditEvent.parse("AUDIT event=blacklist.change recordId=3 validTo="))
                .hasValueSatisfying(e -> assertThat(e.fields()).containsEntry("validTo", ""));
    }

    @Test
    @DisplayName("Riadok bez AUDIT nie je audit")
    void should_returnEmpty_when_lineIsNotAudit() {
        assertThat(AuditEvent.parse("INFO  [crm] niečo iné")).isEmpty();
        assertThat(AuditEvent.parse("")).isEmpty();
    }
}
```

`FileLogProbeTest.java`:

```java
package com.example.crm.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLogProbeTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("Neexistujúci log súbor = žiadne udalosti ani chyby")
    void should_returnNothing_when_fileMissing() {
        FileLogProbe probe = new FileLogProbe(dir.resolve("missing.log"));
        probe.mark();

        assertThat(probe.auditEvents()).isEmpty();
        assertThat(probe.errors()).isEmpty();
    }

    @Test
    @DisplayName("Číta len riadky zapísané po značke")
    void should_readOnlyLinesAfterMark_when_fileGrows() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "INFO AUDIT event=blacklist.note recordId=1 operator=starý\n");
        FileLogProbe probe = new FileLogProbe(file);
        probe.mark();

        append(file, "INFO AUDIT event=blacklist.note recordId=2 operator=spravca\n");

        assertThat(probe.auditEvents()).extracting(AuditEvent::event).containsExactly("blacklist.note");
        assertThat(probe.auditEvents().get(0).fields()).containsEntry("recordId", "2");
    }

    @Test
    @DisplayName("Zmenšený súbor (rotácia) sa číta od začiatku")
    void should_readFromStart_when_fileRotated() throws IOException {
        Path file = dir.resolve("app.log");
        append(file, "x".repeat(200) + "\n");
        FileLogProbe probe = new FileLogProbe(file);
        probe.mark();

        Files.writeString(file, "INFO AUDIT event=blacklist.create recordId=9 operator=spravca\n", StandardCharsets.UTF_8);

        assertThat(probe.auditEvents()).extracting(AuditEvent::event).containsExactly("blacklist.create");
    }

    @Test
    @DisplayName("ERROR/FATAL a riadky stacktrace sú chyby, INFO so slovom error nie")
    void should_detectErrorsAndStackFrames_when_present() throws IOException {
        Path file = dir.resolve("app.log");
        FileLogProbe probe = new FileLogProbe(file);
        probe.mark();
        append(file, """
                2026-10-16 10:00:00 INFO  [crm] počet errors: 0
                2026-10-16 10:00:01 ERROR [crm] Zlyhalo spracovanie
                java.lang.IllegalStateException: boom
                \tat com.example.Foo.bar(Foo.java:10)
                2026-10-16 10:00:02 FATAL [crm] koniec
                """);

        assertThat(probe.errors()).hasSize(3);
        assertThat(probe.errors().get(0)).contains("Zlyhalo spracovanie");
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
```

`AppTargetTest.java`:

```java
package com.example.crm.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTargetTest {

    @Test
    @DisplayName("Legacy ide na Liferay priamo, migrated cez nginx; každý má svoj log")
    void should_resolveEntryAndLog_when_targetSelected() {
        AppTarget legacy = AppTarget.of(config("legacy"));
        AppTarget migrated = AppTarget.of(config("migrated"));

        assertThat(legacy.entryUrl()).isEqualTo("http://localhost:8080/web/crm/blacklist");
        assertThat(legacy.appLog()).isEqualTo(Path.of("logs", "liferay", "customercare-portlet.log"));
        assertThat(migrated.entryUrl()).isEqualTo("http://localhost:8000/crm/blacklist/search");
        assertThat(migrated.appLog()).isEqualTo(Path.of("logs", "crm-web", "crm-web.log"));
    }

    @Test
    @DisplayName("Neznámy cieľ je chyba s nápovedou")
    void should_fail_when_targetUnknown() {
        assertThatThrownBy(() -> AppTarget.of(config("unknown"))).hasMessageContaining("legacy|migrated");
    }

    private static E2eConfig config(String target) {
        return new E2eConfig(target, "http://localhost:8000/sso/login", "http://localhost:8080/web/crm/blacklist",
                "http://localhost:8000/crm/blacklist/search", "jdbc:postgresql://localhost:5432/crm", "postgres",
                "postgres", Path.of("logs"), "chrome", false, Path.of("seed.sql"));
    }
}
```

Run: `mvn -q -B -f e2e/pom.xml test` → Expected: FAIL (kompilácia).

- [ ] **Step 3: Implementácia podpory**

`E2eConfig.java`:

```java
package com.example.crm.e2e.support;

import java.nio.file.Path;

/**
 * Nastavenie behu zo system properties (-De2e.*). Defaulty = lokálny Docker stack; na reálnom projekte sa mení
 * len toto (URL, JDBC, cesta k logom) – testy a page objects ostávajú.
 */
public record E2eConfig(String target, String ssoUrl, String legacyUrl, String migratedUrl, String jdbcUrl,
                        String jdbcUser, String jdbcPassword, Path logsDir, String browser, boolean headed,
                        Path seedScript) {

    /** Načíta nastavenie zo system properties s lokálnymi defaultmi. */
    public static E2eConfig fromSystemProperties() {
        return new E2eConfig(
                prop("e2e.target", "legacy"),
                prop("e2e.ssoUrl", "http://localhost:8000/sso/login"),
                prop("e2e.legacyUrl", "http://localhost:8080/web/crm/blacklist"),
                prop("e2e.migratedUrl", "http://localhost:8000/crm/blacklist/search"),
                prop("e2e.jdbcUrl", "jdbc:postgresql://localhost:5432/crm"),
                prop("e2e.jdbcUser", "postgres"),
                prop("e2e.jdbcPassword", "postgres"),
                Path.of(prop("e2e.logsDir", "../docker/logs")),
                prop("e2e.browser", "chrome"),
                Boolean.parseBoolean(prop("e2e.headed", "false")),
                Path.of(prop("e2e.seedScript", "../docker/postgres/init/02-blacklist-seed.sql")));
    }

    private static String prop(String key, String defaultValue) {
        return System.getProperty(key, defaultValue);
    }
}
```

`AppTarget.java`:

```java
package com.example.crm.e2e.support;

import java.nio.file.Path;

/**
 * Testovaný cieľ. Jediné miesto pre zámerné odchýlky medzi starou a novou appkou – všetko ostatné je spoločné.
 * Vedomá medzera: vynútený POST čitateľa sa netestuje (legacy portlet URL sa bez formulára zložiť nedá);
 * 403 v novej appke overuje docker/smoke-migration.sh.
 */
public sealed interface AppTarget permits LegacyTarget, MigratedTarget {

    /** Názov cieľa (legacy | migrated) – aj adresár artefaktov. */
    String name();

    /** URL, kde test začína po prihlásení. */
    String entryUrl();

    /** Log súbor aplikácie (audit + hygiena). */
    Path appLog();

    /** Cieľ podľa {@code e2e.target}. */
    static AppTarget of(E2eConfig config) {
        return switch (config.target()) {
            case "legacy" -> new LegacyTarget(config);
            case "migrated" -> new MigratedTarget(config);
            default -> throw new IllegalArgumentException(
                    "Neznámy cieľ e2e.target=" + config.target() + " (povolené: legacy|migrated)");
        };
    }
}
```

`LegacyTarget.java`:

```java
package com.example.crm.e2e.support;

import java.nio.file.Path;

/** Stará Liferay appka. */
public record LegacyTarget(E2eConfig config) implements AppTarget {

    @Override
    public String name() {
        return "legacy";
    }

    /** Liferay priamo (:8080) – cez nginx (:8000) by strangler presmeroval blacklist na novú appku. */
    @Override
    public String entryUrl() {
        return config.legacyUrl();
    }

    /** Vlastný log portletu (log4j.xml vo WAR-e), nie celý log portálu – šum Liferay sa nemieša do hygieny. */
    @Override
    public Path appLog() {
        return config.logsDir().resolve("liferay").resolve("customercare-portlet.log");
    }
}
```

`MigratedTarget.java`:

```java
package com.example.crm.e2e.support;

import java.nio.file.Path;

/** Zmigrovaná Spring Boot appka. */
public record MigratedTarget(E2eConfig config) implements AppTarget {

    @Override
    public String name() {
        return "migrated";
    }

    /** Nová URL (render fáza → GET /blacklist/search) – zámerná odchýlka, adresa portálu už neexistuje. */
    @Override
    public String entryUrl() {
        return config.migratedUrl();
    }

    @Override
    public Path appLog() {
        return config.logsDir().resolve("crm-web").resolve("crm-web.log");
    }
}
```

`AuditEvent.java`:

```java
package com.example.crm.e2e.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Riadok auditu {@code AUDIT event=… k=v …} – kontrakt zdieľaný starou aj novou appkou. */
public record AuditEvent(String event, Map<String, String> fields) {

    private static final String MARKER = "AUDIT event=";

    /** Rozparsuje riadok logu; prefix (čas, level, logger) sa ignoruje. */
    public static Optional<AuditEvent> parse(String line) {
        int start = line.indexOf(MARKER);
        if (start < 0) {
            return Optional.empty();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String token : line.substring(start + "AUDIT ".length()).trim().split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        String event = fields.remove("event");
        return Optional.of(new AuditEvent(event, Collections.unmodifiableMap(fields)));
    }
}
```

`LogProbe.java`:

```java
package com.example.crm.e2e.support;

import java.time.Duration;
import java.util.List;

/** Pohľad testu do logu aplikácie od značky (začiatok testu). Na reálnom projekte iná implementácia (Loki, SSH). */
public interface LogProbe {

    /** Zapamätá si aktuálny koniec logu – ďalšie metódy vidia len novšie riadky. */
    void mark();

    /** Audit udalosti od značky. */
    List<AuditEvent> auditEvents();

    /** Riadky s ERROR/FATAL alebo stacktrace od značky (hygiena). */
    List<String> errors();

    /** Všetky riadky od značky – artefakt pri zlyhaní. */
    String excerpt();

    /**
     * Počká, kým v logu bude aspoň {@code expected} udalostí (zápis cez bind mount môže mierne meškať za HTTP odpoveďou).
     * Vráti, čo našiel, aj keď ich je menej – asercia v teste tak ukáže skutočný stav.
     */
    default List<AuditEvent> awaitAuditEvents(int expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<AuditEvent> events = auditEvents();
        while (events.size() < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return events;
            }
            events = auditEvents();
        }
        return events;
    }
}
```

`FileLogProbe.java`:

```java
package com.example.crm.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Log v súbore (ako na reálnom serveri; v Dockeri bind mount docker/logs/...). */
public final class FileLogProbe implements LogProbe {

    private static final Pattern ERROR_LEVEL = Pattern.compile("\\b(ERROR|FATAL)\\b");
    private static final Pattern STACK_FRAME = Pattern.compile("^\\s+at [\\w.$]+\\(.*\\)$");

    private final Path file;
    private long offset;

    public FileLogProbe(Path file) {
        this.file = file;
    }

    @Override
    public void mark() {
        offset = size();
    }

    @Override
    public List<AuditEvent> auditEvents() {
        return linesSinceMark().stream().map(AuditEvent::parse).flatMap(Optional::stream).toList();
    }

    @Override
    public List<String> errors() {
        return linesSinceMark().stream()
                .filter(line -> ERROR_LEVEL.matcher(line).find() || STACK_FRAME.matcher(line).matches())
                .toList();
    }

    @Override
    public String excerpt() {
        return String.join("\n", linesSinceMark());
    }

    private List<String> linesSinceMark() {
        if (!Files.exists(file)) {
            return List.of();
        }
        long size = size();
        // Menší súbor než pri značke = rotácia; nový obsah je od začiatku.
        long from = size < offset ? 0 : offset;
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(from);
            ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // číta, kým nie je buffer plný
            }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Log " + file + " sa nedá prečítať", e);
        }
    }

    private long size() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`RecordRow.java`, `IdentifierRow.java`, `NoteRow.java`:

```java
package com.example.crm.e2e.support;

import java.time.LocalDate;

/** Riadok tabuľky blacklist_record – len stĺpce, ktoré testy overujú. */
public record RecordRow(long id, String firstName, String lastName, String state, LocalDate validTo) {
}
```

```java
package com.example.crm.e2e.support;

/** Riadok tabuľky blacklist_identifier. */
public record IdentifierRow(long id, String type, String value) {
}
```

```java
package com.example.crm.e2e.support;

/** Riadok tabuľky blacklist_note (bez času – ten testy neoverujú). */
public record NoteRow(String text, String author) {
}
```

`DbProbe.java`:

```java
package com.example.crm.e2e.support;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Pohľad testu do DB backendu. Na reálnom projekte iná implementácia (Oracle, read-only používateľ, iný reset). */
public interface DbProbe {

    /** Vráti DB do stavu seedu (rovnaký skript ako init kontajnera). */
    void reset();

    /** Záznam podľa ID. */
    Optional<RecordRow> record(long id);

    /** Identifikátory záznamu podľa ID. */
    List<IdentifierRow> identifiers(long recordId);

    /** Poznámky záznamu v poradí vloženia. */
    List<NoteRow> notes(long recordId);

    /** Počet záznamov. */
    int recordCount();

    /** Dnešný dátum podľa DB – appky bežia v tom istom pásme (UTC v Dockeri), hostiteľ nemusí. */
    LocalDate today();
}
```

`JdbcDbProbe.java`:

```java
package com.example.crm.e2e.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** DbProbe nad JDBC (superuser kvôli resetu). Spojenie na každé volanie – testy volajú pár dotazov, jednoduchosť vyhráva. */
public final class JdbcDbProbe implements DbProbe {

    private final E2eConfig config;

    public JdbcDbProbe(E2eConfig config) {
        this.config = config;
    }

    @Override
    public void reset() {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(config.seedScript(), StandardCharsets.UTF_8));
        } catch (SQLException e) {
            throw new IllegalStateException("Reset DB zlyhal (beží postgres? " + config.jdbcUrl() + ")", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Seed " + config.seedScript() + " sa nedá prečítať", e);
        }
    }

    @Override
    public Optional<RecordRow> record(long id) {
        return query("SELECT id, first_name, last_name, state, valid_to FROM blacklist.blacklist_record WHERE id = ?",
                rs -> new RecordRow(rs.getLong("id"), rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("state"), rs.getObject("valid_to", LocalDate.class)), id).stream().findFirst();
    }

    @Override
    public List<IdentifierRow> identifiers(long recordId) {
        return query("SELECT id, type, value FROM blacklist.blacklist_identifier WHERE record_id = ? ORDER BY id",
                rs -> new IdentifierRow(rs.getLong("id"), rs.getString("type"), rs.getString("value")), recordId);
    }

    @Override
    public List<NoteRow> notes(long recordId) {
        return query("SELECT text, author FROM blacklist.blacklist_note WHERE record_id = ? ORDER BY id",
                rs -> new NoteRow(rs.getString("text"), rs.getString("author")), recordId);
    }

    @Override
    public int recordCount() {
        return query("SELECT count(*) FROM blacklist.blacklist_record", rs -> rs.getInt(1)).get(0);
    }

    @Override
    public LocalDate today() {
        return query("SELECT current_date", rs -> rs.getObject(1, LocalDate.class)).get(0);
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Dotaz zlyhal: " + sql, e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.jdbcUser(), config.jdbcPassword());
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
```

- [ ] **Step 4: Unit testy prejdú**

Run: `mvn -q -B -f e2e/pom.xml test` → Expected: PASS (`AuditEventTest`, `FileLogProbeTest`, `AppTargetTest`).

- [ ] **Step 5: Checkpoint** – `git status`.

---

### Task 8: e2e – JUnit extension, prihlásenie, page objects, scenáre bez zmien (1 a 6), spúšťacie skripty

**Files:**
- Create: `e2e/src/test/java/com/example/crm/e2e/support/Operator.java`, `E2eSession.java`, `SsoLogin.java`, `BrowserHolder.java`, `E2eExtension.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/pages/SearchPage.java`, `ResultPage.java`, `DetailPage.java`, `CreatePage.java`
- Create: `e2e/src/test/java/com/example/crm/e2e/BlacklistCharacterizationIT.java`
- Create: `docker/e2e.sh`, `docker/e2e.ps1`

**Interfaces:**
- Consumes: Task 7 (`E2eConfig`, `AppTarget`, `DbProbe`, `LogProbe`, `AuditEvent`, rows).
- Produces: `@Operator(String value)`; `record E2eSession(Page page, AppTarget target, DbProbe db, LogProbe log)`; `E2eExtension` (resolvuje parameter `E2eSession`); page objects:
  - `SearchPage(Page)` – `ResultPage searchByFirstName(String)`, `ResultPage searchByLastName(String)`, `boolean canCreate()`, `CreatePage openCreate()`.
  - `ResultPage(Page)` – `List<Long> recordIds()`, `DetailPage openDetail(long id)`.
  - `DetailPage(Page)` – `String state()`, `List<String> messages()`, `String bodyText()`, `Locator identifiers()`, `DetailPage changeState(String state, String validTo)`, `DetailPage addNote(String text)`, `void removeIdentifier(long identifierId)`, `boolean canModify()`, `void markPage()`, `boolean isSamePage()`, `String heading()`.
  - `CreatePage(Page)` – `CreatePage selectReason(String reason)`, `Locator conditionTypeOptions()`, `DetailPage submit(String firstName, String lastName, String birthDate, String conditionType, String identifierType, String identifierValue)`.

- [ ] **Step 1: Podpora**

`Operator.java`:

```java
package com.example.crm.e2e.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Operátor, ktorým sa test prihlási do SSO (default {@code spravca} – všetky oprávnenia). */
@Retention(RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(ElementType.METHOD)
public @interface Operator {

    /** Login operátora v SSO mocku ({@code spravca} | {@code citatel}). */
    String value();
}
```

`E2eSession.java`:

```java
package com.example.crm.e2e.support;

import com.microsoft.playwright.Page;

/** Všetko, čo test potrebuje: prihlásená stránka na vstupe cieľa, DB a log od začiatku testu. */
public record E2eSession(Page page, AppTarget target, DbProbe db, LogProbe log) {
}
```

`SsoLogin.java`:

```java
package com.example.crm.e2e.support;

import com.microsoft.playwright.Page;

/** Prihlásenie cez UI SSO mocku – identitu vlastní SSO portál, nie CRM (cookie SSO_ID platí pre obe appky). */
public final class SsoLogin {

    private SsoLogin() {
    }

    /** Prihlási operátora so zákazníkom; po návrate ostane na stránke SSO (cieľ otvorí volajúci). */
    public static void login(Page page, String ssoLoginUrl, String operator, String customer) {
        // returnUrl na SSO stránku: návrat nesmie viesť do žiadnej z testovaných appiek (log testu by mal šum).
        page.navigate(ssoLoginUrl + "?returnUrl=%2Fsso%2Flogin");
        page.locator("select[name=operator]").selectOption(operator);
        page.locator("select[name=customer]").selectOption(customer);
        page.locator("form[action='/sso/login'] button[type=submit]").click();
    }
}
```

`BrowserHolder.java`:

```java
package com.example.crm.e2e.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.util.Map;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Jeden prehliadač na celý beh (drahý štart); každý test dostane vlastný čistý BrowserContext. */
final class BrowserHolder implements ExtensionContext.Store.CloseableResource {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(BrowserHolder.class);

    private final Playwright playwright;
    private final Browser browser;

    private BrowserHolder(E2eConfig config) {
        boolean chrome = "chrome".equals(config.browser());
        // Kanál chrome = nainštalovaný Google Chrome; Playwright vtedy nesťahuje vlastné Chromium.
        playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(chrome ? Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1") : Map.of()));
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(!config.headed());
        if (chrome) {
            options.setChannel("chrome");
        }
        browser = playwright.chromium().launch(options);
    }

    /** Prehliadač pre celý beh testov (uzavrie ho JUnit na konci). */
    static Browser browser(ExtensionContext context, E2eConfig config) {
        return context.getRoot().getStore(NAMESPACE)
                .getOrComputeIfAbsent(BrowserHolder.class, key -> new BrowserHolder(config), BrowserHolder.class)
                .browser;
    }

    @Override
    public void close() {
        browser.close();
        playwright.close();
    }
}
```

`E2eExtension.java`:

```java
package com.example.crm.e2e.support;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Životný cyklus e2e testu: reset DB → značka v logu → čistý prehliadač → SSO prihlásenie → vstup cieľa.
 * Po teste hygiena logu (ERROR/stacktrace = zlyhanie, aj keď UI prešlo) a pri zlyhaní artefakty.
 */
public class E2eExtension implements BeforeEachCallback, AfterTestExecutionCallback, AfterEachCallback,
        ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(E2eExtension.class);
    private static final String DEFAULT_OPERATOR = "spravca";
    private static final String CUSTOMER = "C-1001";

    @Override
    public void beforeEach(ExtensionContext context) {
        E2eConfig config = E2eConfig.fromSystemProperties();
        AppTarget target = AppTarget.of(config);
        DbProbe db = new JdbcDbProbe(config);
        db.reset();
        LogProbe log = new FileLogProbe(target.appLog());
        log.mark();
        BrowserContext browserContext = BrowserHolder.browser(context, config).newContext();
        browserContext.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
        Page page = browserContext.newPage();
        SsoLogin.login(page, config.ssoUrl(), operator(context), CUSTOMER);
        page.navigate(target.entryUrl());
        context.getStore(NAMESPACE).put(E2eSession.class, new E2eSession(page, target, db, log));
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        E2eSession e2e = session(context);
        if (e2e == null) {
            return; // beforeEach zlyhal (DB/prehliadač) – jeho výnimka je už príčinou zlyhania testu
        }
        List<String> errors = e2e.log().errors();
        Tracing tracing = e2e.page().context().tracing();
        if (context.getExecutionException().isPresent() || !errors.isEmpty()) {
            Path dir = Path.of("target", "e2e-artifacts", e2e.target().name(),
                    context.getRequiredTestClass().getSimpleName() + "." + context.getRequiredTestMethod().getName());
            saveArtifacts(e2e, dir);
            tracing.stop(new Tracing.StopOptions().setPath(dir.resolve("trace.zip")));
        } else {
            tracing.stop();
        }
        if (!errors.isEmpty()) {
            throw new AssertionError("Hygiena logu " + e2e.target().appLog() + " – počas testu vznikli chyby:\n"
                    + String.join("\n", errors));
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        E2eSession e2e = session(context);
        if (e2e != null) {
            e2e.page().context().close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == E2eSession.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return session(extensionContext);
    }

    private static E2eSession session(ExtensionContext context) {
        return context.getStore(NAMESPACE).get(E2eSession.class, E2eSession.class);
    }

    private static String operator(ExtensionContext context) {
        return context.getTestMethod()
                .map(method -> method.getAnnotation(Operator.class))
                .map(Operator::value)
                .orElse(DEFAULT_OPERATOR);
    }

    private static void saveArtifacts(E2eSession e2e, Path dir) {
        try {
            Files.createDirectories(dir);
            e2e.page().screenshot(new Page.ScreenshotOptions().setPath(dir.resolve("screenshot.png")).setFullPage(true));
            Files.writeString(dir.resolve("app.log"), e2e.log().excerpt(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Artefakty do " + dir + " sa nepodarilo uložiť", e);
        }
    }
}
```

- [ ] **Step 2: Page objects**

`SearchPage.java`:

```java
package com.example.crm.e2e.pages;

import com.microsoft.playwright.Page;

/** Vyhľadávanie blacklistu – rovnaké ID elementov v starej aj novej appke. */
public final class SearchPage {

    private final Page page;

    public SearchPage(Page page) {
        this.page = page;
        page.locator("#searchForm").waitFor();
    }

    /** Hľadá podľa mena (ostatné polia prázdne). */
    public ResultPage searchByFirstName(String firstName) {
        return search("firstName", firstName);
    }

    /** Hľadá podľa priezviska (ostatné polia prázdne). */
    public ResultPage searchByLastName(String lastName) {
        return search("lastName", lastName);
    }

    /** Či operátor vidí tlačidlo „Nový záznam“. */
    public boolean canCreate() {
        return page.locator("#gotoCreateForm").count() > 0;
    }

    /** Otvorí formulár nového záznamu. */
    public CreatePage openCreate() {
        page.locator("#gotoCreateForm button[type=submit]").click();
        return new CreatePage(page);
    }

    private ResultPage search(String field, String value) {
        page.locator("#searchForm [name=" + field + "]").fill(value);
        page.locator("#searchForm button[type=submit]").click();
        return new ResultPage(page);
    }
}
```

`ResultPage.java`:

```java
package com.example.crm.e2e.pages;

import com.microsoft.playwright.Page;
import java.util.List;

/** Výsledky vyhľadávania. */
public final class ResultPage {

    private static final String DETAIL_PREFIX = "detail-";

    private final Page page;

    public ResultPage(Page page) {
        this.page = page;
        page.locator("#resultCount").waitFor();
    }

    /** ID nájdených záznamov v poradí zobrazenia. */
    public List<Long> recordIds() {
        return page.locator("a[id^='" + DETAIL_PREFIX + "']").all().stream()
                .map(link -> Long.parseLong(link.getAttribute("id").substring(DETAIL_PREFIX.length())))
                .toList();
    }

    /** Otvorí detail kliknutím – portlet URL nie sú stabilné, používateľ aj tak kliká. */
    public DetailPage openDetail(long id) {
        page.locator("#" + DETAIL_PREFIX + id).click();
        return new DetailPage(page);
    }
}
```

`DetailPage.java`:

```java
package com.example.crm.e2e.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.List;

/** Detail záznamu: zmena stavu, poznámky, odstránenie identifikátora (AJAX). */
public final class DetailPage {

    private static final String PAGE_MARKER = "() => window.__e2eMarker = 'detail'";

    private final Page page;

    public DetailPage(Page page) {
        this.page = page;
        page.locator("#recordSummary").waitFor();
    }

    /** Nadpis stránky (obsahuje meno osoby). */
    public String heading() {
        return page.locator("h2").first().innerText().trim();
    }

    /** Zobrazený stav (text v jazyku UI). */
    public String state() {
        return page.locator("#recordState").innerText().trim();
    }

    /** Hlášky z hlavičky (info aj chyby). */
    public List<String> messages() {
        return page.locator(".crm-message").allInnerTexts().stream().map(String::trim).toList();
    }

    /** Celý viditeľný text – na chyby, ktoré appky zobrazujú na rôznych miestach. */
    public String bodyText() {
        return page.locator("body").innerText();
    }

    /** Zoznam identifikátorov (prekresľuje ho AJAX). */
    public Locator identifiers() {
        return page.locator("#identifiers");
    }

    /** Zmení stav a platnosť; prázdne {@code validTo} = bez dátumu. */
    public DetailPage changeState(String state, String validTo) {
        page.locator("#changeForm [name=state]").selectOption(state);
        page.locator("#changeForm [name=validTo]").fill(validTo);
        page.locator("#changeForm button[type=submit]").click();
        return new DetailPage(page);
    }

    /** Pridá poznámku. */
    public DetailPage addNote(String text) {
        page.locator("#noteForm [name=text]").fill(text);
        page.locator("#noteForm button[type=submit]").click();
        return new DetailPage(page);
    }

    /** Odstráni identifikátor cez AJAX a počká na odpoveď servera. */
    public void removeIdentifier(long identifierId) {
        page.waitForResponse(response -> "POST".equals(response.request().method())
                        && response.url().contains("removeIdent"),
                () -> page.locator("#remove-ident-" + identifierId).click());
    }

    /** Či operátor vidí ovládacie prvky zmien. */
    public boolean canModify() {
        return page.locator("#changeForm").count() > 0
                || page.locator("#noteForm").count() > 0
                || page.locator("a[id^='remove-ident-']").count() > 0;
    }

    /** Označí aktuálny dokument – po AJAX akcii sa overí, že sa stránka neprenačítala. */
    public void markPage() {
        page.evaluate(PAGE_MARKER);
    }

    /** Či je to stále ten istý dokument ako pri {@link #markPage()}. */
    public boolean isSamePage() {
        return "detail".equals(page.evaluate("() => window.__e2eMarker"));
    }
}
```

`CreatePage.java`:

```java
package com.example.crm.e2e.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/** Nový záznam: zmena dôvodu cez AJAX prekreslí povolené podmienky. */
public final class CreatePage {

    private final Page page;

    public CreatePage(Page page) {
        this.page = page;
        page.locator("#createForm").waitFor();
    }

    /** Zvolí dôvod a počká na AJAX odpoveď s podmienkami. */
    public CreatePage selectReason(String reason) {
        page.waitForResponse(response -> response.url().contains("refreshCreateConditionTypes"),
                () -> page.locator("#createForm [name=stateReason]").selectOption(reason));
        return this;
    }

    /** Ponúkané podmienky (bez prázdnej voľby). */
    public Locator conditionTypeOptions() {
        return page.locator("#conditionTypeBox select option:not([value=''])");
    }

    /** Vyplní a odošle formulár; „platné od“ ostáva predvyplnené (dnes). Dátum vo formáte yyyy-MM-dd. */
    public DetailPage submit(String firstName, String lastName, String birthDate, String conditionType,
                             String identifierType, String identifierValue) {
        page.locator("#createForm [name=firstName]").fill(firstName);
        page.locator("#createForm [name=lastName]").fill(lastName);
        page.locator("#createForm [name=birthDate]").fill(birthDate);
        page.locator("#conditionTypeBox select").selectOption(conditionType);
        page.locator("#createForm [name=identifierType]").selectOption(identifierType);
        page.locator("#createForm [name=identifierValue]").fill(identifierValue);
        page.locator("#createForm button[type=submit]").click();
        return new DetailPage(page);
    }
}
```

- [ ] **Step 3: Prvé dva scenáre (bez zmien dát)**

`e2e/src/test/java/com/example/crm/e2e/BlacklistCharacterizationIT.java`:

```java
package com.example.crm.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.e2e.pages.DetailPage;
import com.example.crm.e2e.pages.ResultPage;
import com.example.crm.e2e.pages.SearchPage;
import com.example.crm.e2e.support.E2eExtension;
import com.example.crm.e2e.support.E2eSession;
import com.example.crm.e2e.support.Operator;
import com.example.crm.e2e.support.RecordRow;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Charakterizačné testy blacklistu: správanie starej appky (UI + DB + audit) je špecifikácia pre novú.
 * Očakávané texty sú z legacy messages.properties; odchýlka na migrated sa opravuje v crm-web, nie tu.
 */
@ExtendWith(E2eExtension.class)
class BlacklistCharacterizationIT {

    private static final Duration AUDIT_WAIT = Duration.ofSeconds(3);

    @Test
    @DisplayName("1 Čítanie: vyhľadanie podľa mena s diakritikou nájde jeden záznam a nič nezmení")
    void should_findOneRecord_when_searchingFirstNameWithDiacritics(E2eSession e2e) {
        ResultPage result = new SearchPage(e2e.page()).searchByFirstName("Mária");

        assertThat(result.recordIds()).containsExactly(2L);
        assertThat(e2e.db().recordCount()).isEqualTo(8);
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @Operator("citatel")
    @DisplayName("6 ACL: čitateľ vidí záznam, ale nemá ovládacie prvky zmien")
    void should_hideModifyControls_when_operatorIsReader(E2eSession e2e) {
        SearchPage search = new SearchPage(e2e.page());
        assertThat(search.canCreate()).isFalse();

        DetailPage detail = search.searchByLastName("Vzorka").openDetail(1);

        assertThat(detail.canModify()).isFalse();
        assertThat(e2e.db().record(1)).map(RecordRow::state).contains("ACTIVE");
        assertThat(e2e.log().auditEvents()).isEmpty();
    }
}
```

(`AUDIT_WAIT` sa použije v Tasku 9.)

- [ ] **Step 4: Spúšťacie skripty**

`docker/e2e.sh`:

```bash
#!/usr/bin/env bash
# Charakterizačné e2e testy blacklistu (Playwright + Chrome) proti bežiacemu stacku: legacy | migrated | all.
# Ciele idú za sebou – zdieľajú jednu DB a každý test ju resetuje na seed. Ďalšie argumenty idú do Mavenu (-De2e.*).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-}" in
    legacy|migrated) TARGETS=("$1") ;;
    all) TARGETS=(legacy migrated) ;;
    *) echo "Použitie: $0 legacy|migrated|all [-De2e.headed=true] [-De2e.browser=chromium]" >&2; exit 2 ;;
esac
shift
FAILS=0
for target in "${TARGETS[@]}"; do
    echo "== e2e: $target"
    mvn -q -B -f "$ROOT/e2e/pom.xml" verify -De2e.target="$target" "$@" || FAILS=$((FAILS + 1))
done
echo
if [ "$FAILS" -eq 0 ]; then echo "E2E OK"; else echo "E2E: $FAILS cieľ(e) zlyhali – artefakty v e2e/target/e2e-artifacts/"; fi
exit "$FAILS"
```

`docker/e2e.ps1`:

```powershell
# E2e testy sú bash skript (Maven + Playwright); tento wrapper ho spustí cez Git Bash.
$bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw 'e2e potrebuje Git Bash (C:\Program Files\Git\bin\bash.exe)' }
& $bash "$PSScriptRoot/e2e.sh" @args
exit $LASTEXITCODE
```

- [ ] **Step 5: Beh na legacy**

Predpoklad: stack beží (Task 6). Run: `bash docker/e2e.sh legacy`
Expected: `E2E OK` (2 IT + unit testy).

Pri zlyhaní otvor `e2e/target/e2e-artifacts/legacy/<test>/screenshot.png` a `app.log`; trace: `mvn -q -f e2e/pom.xml exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="show-trace e2e/target/e2e-artifacts/legacy/<test>/trace.zip" -Dexec.classpathScope=test` (alebo `https://trace.playwright.dev` – súbor sa nahrá len lokálne v prehliadači).
Ak zlyhá prihlásenie (`selectOption` nenájde hodnotu), over hodnoty `<option>` na `http://localhost:8000/sso/login`.
Ak Chrome nie je nainštalovaný: `bash docker/e2e.sh legacy -De2e.browser=chromium`.

- [ ] **Step 6: Checkpoint** – `git status`.

---

### Task 9: e2e – scenáre so zmenou dát (2, 3, 4a, 4b, 5) na legacy

**Files:**
- Modify: `e2e/src/test/java/com/example/crm/e2e/BlacklistCharacterizationIT.java`

**Interfaces:**
- Consumes: page objects a sondy z Taskov 7–8.

- [ ] **Step 1: Scenáre**

Do `BlacklistCharacterizationIT` pridaj (a importy `com.example.crm.e2e.pages.CreatePage`, `com.example.crm.e2e.support.AuditEvent`, `com.example.crm.e2e.support.IdentifierRow`, `com.example.crm.e2e.support.NoteRow`, `com.microsoft.playwright.assertions.PlaywrightAssertions`, `java.time.LocalDate`, `java.util.Map`):

```java
    @Test
    @DisplayName("2 Zápis: zmena stavu bez „platné do“ – hláška, DB s dnešným dátumom, audit")
    void should_deactivateWithToday_when_stateChangedWithoutValidTo(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1)
                .changeState("INACTIVE", "");

        LocalDate today = e2e.db().today();
        assertThat(detail.messages()).contains("Stav záznamu bol zmenený.");
        assertThat(detail.state()).isEqualTo("Neaktívny");
        assertThat(e2e.db().record(1)).hasValueSatisfying(row -> {
            assertThat(row.state()).isEqualTo("INACTIVE");
            assertThat(row.validTo()).isEqualTo(today);
        });
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(new AuditEvent("blacklist.change",
                Map.of("recordId", "1", "operator", "spravca", "state", "INACTIVE", "validTo", today.toString())));
    }

    @Test
    @DisplayName("3 Validácia: prázdna poznámka sa neuloží a nezapíše audit")
    void should_rejectNote_when_textEmpty(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1).addNote("");

        assertThat(detail.bodyText()).contains("Poznámka nesmie byť prázdna.");
        assertThat(e2e.db().notes(1)).extracting(NoteRow::author).containsExactly("Systém");
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @DisplayName("4a AJAX: odstránenie identifikátora prekreslí zoznam bez prenačítania stránky")
    void should_removeIdentifierWithoutReload_when_removedViaAjax(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1);
        detail.markPage();

        detail.removeIdentifier(2);

        PlaywrightAssertions.assertThat(detail.identifiers()).not().containsText("AB123456");
        PlaywrightAssertions.assertThat(detail.identifiers()).containsText("800512/1234");
        assertThat(detail.isSamePage()).isTrue();
        assertThat(e2e.db().identifiers(1)).extracting(IdentifierRow::id).containsExactly(1L);
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(new AuditEvent(
                "blacklist.identifier.remove", Map.of("recordId", "1", "identifierId", "2", "operator", "spravca")));
    }

    @Test
    @DisplayName("4b AJAX: posledný identifikátor nejde odstrániť – hláška vo fragmente, DB bez zmeny, bez auditu")
    void should_keepLastIdentifier_when_removingIt(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByFirstName("Mária").openDetail(2);

        detail.removeIdentifier(3);

        PlaywrightAssertions.assertThat(detail.identifiers())
                .containsText("Posledný identifikátor nie je možné odstrániť.");
        assertThat(e2e.db().identifiers(2)).extracting(IdentifierRow::id).containsExactly(3L);
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @DisplayName("5 Vytvorenie: AJAX podmienky podľa dôvodu, nový záznam v DB a audit")
    void should_createRecord_when_formFilled(E2eSession e2e) {
        CreatePage create = new SearchPage(e2e.page()).openCreate().selectReason("DEBT");
        PlaywrightAssertions.assertThat(create.conditionTypeOptions()).hasCount(2);

        DetailPage detail = create.submit("Nový", "Záznam", "1990-01-01", "MANUAL_APPROVAL", "ID_CARD", "ZZ123456");

        assertThat(detail.heading()).contains("Nový Záznam");
        assertThat(detail.messages()).contains("Záznam bol vytvorený.");
        assertThat(e2e.db().recordCount()).isEqualTo(9);
        assertThat(e2e.db().record(9)).map(RecordRow::state).contains("ACTIVE");
        assertThat(e2e.db().identifiers(9)).containsExactly(new IdentifierRow(11L, "ID_CARD", "ZZ123456"));
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(
                new AuditEvent("blacklist.create", Map.of("recordId", "9", "operator", "spravca")));
    }
```

- [ ] **Step 2: Beh na legacy**

Run: `bash docker/e2e.sh legacy` → Expected: `E2E OK` (7 IT).

Legacy je pravda – ak test zlyhá, chyba je v **teste** (selektor, čakanie, očakávaný text), nie v appke: oprav test podľa skutočného správania legacy (screenshot/trace) a zapíš dôvod komentárom. Výnimka: zjavná chyba v Tasku 4/5 (JDBC adaptér, audit) – oprav tam a zopakuj `mvn verify` daného modulu + redeploy.

- [ ] **Step 3: Stabilita**

Run 3× za sebou: `bash docker/e2e.sh legacy` → Expected: zakaždým `E2E OK` (reset DB a značka logu robia testy nezávislými od poradia).

- [ ] **Step 4: Checkpoint** – `git status`.

---

### Task 10: Beh na zmigrovanej appke a oprava odchýlok v crm-web

**Files:**
- Modify: `crm-web/src/main/resources/messages.properties`
- Modify: `crm-web/src/main/java/com/example/crm/blacklist/NoteForm.java`
- Modify: `docker/smoke-migration.sh` (očakávaný text hlášky)
- Modify: ďalšie súbory `crm-web/` podľa zistených odchýlok
- Test: existujúce testy `crm-web` s textami (`grep -rn "Záznam bol zmenený" crm-web/src/test docker/`)

**Interfaces:**
- Consumes: sada z Taskov 8–9 (bez zmeny – len ak test obsahoval chybu nezávislú od cieľa).

- [ ] **Step 1: Prvý beh (očakávane červený)**

Run: `bash docker/e2e.sh migrated`
Expected: FAIL. Známe odchýlky z analýzy:
- test 2: hláška `Záznam bol zmenený.` (crm-web) vs. legacy `Stav záznamu bol zmenený.`,
- test 3: crm-web zobrazí pri prázdnej poznámke `validation.required` namiesto `Poznámka nesmie byť prázdna.`.

Zapíš si zoznam všetkých zlyhaní (názov testu, očakávané vs. skutočné) – použije sa v poznámke (Task 11).

- [ ] **Step 2: Oprava hlášky po zmene stavu**

`crm-web/src/main/resources/messages.properties`: `blacklist.detail.changed=Stav záznamu bol zmenený.`
Uprav všetky miesta, ktoré overujú starý text: `grep -rln "Záznam bol zmenený" crm-web/src/test docker` – v `crm-web/src/test` a `docker/smoke-migration.sh` zmeň na `Stav záznamu bol zmenený.`.

- [ ] **Step 3: Oprava hlášky prázdnej poznámky**

`NoteForm.java`: `@NotBlank(message = "{blacklist.detail.noteEmpty}")` (kľúč v crm-web `messages.properties` už existuje).

Run: `mvn -q -B -f crm-web/pom.xml verify` → Expected: PASS (ak web test overoval `validation.required` pri poznámke, uprav ho na nový text).

- [ ] **Step 4: Ostatné odchýlky**

Pre každé ďalšie zlyhanie z kroku 1: porovnaj s legacy (rovnaký test je na legacy zelený), oprav `crm-web` (text, ID elementu, správanie), pridaj/uprav unit/web test v `crm-web`, `mvn -q -B -f crm-web/pom.xml verify`. Ak je odchýlka **zámerná** (nedá sa a nemá sa zladiť), presuň ju do `AppTarget` (`LegacyTarget`/`MigratedTarget`) s komentárom PREČO – nikdy nie podmienkou v teste.

- [ ] **Step 5: Redeploy a zelená na oboch**

Run: `bash docker/build-and-deploy.sh` → `Hotovo …`
Run: `bash docker/e2e.sh all` → Expected: `E2E OK`.
Run: `bash docker/smoke.sh` → `SMOKE OK`; `bash docker/smoke-migration.sh` → `MIGRATION SMOKE OK`.

- [ ] **Step 6: Checkpoint** – `git status`.

---

### Task 11: Dokumentácia a znalostná báza

**Files:**
- Create: `e2e/README.md`
- Create: `poznamky/12-charakterizacni-e2e-testy.md`
- Modify: `poznamky/README.md`, `docs/MIGRACIA.md`, `CLAUDE.md`, `README.md`, `legacy/README.md`, `crm-web/README.md`

- [ ] **Step 1: `e2e/README.md` (česky)**

Obsah (sekcie, konkrétne príkazy):
- **Co to je** – charakterizační testy blacklistu: napsané na legacy, stejná sada na migrované verzi; každý test kontroluje UI, DB a log (audit + hygiena).
- **Předpoklady** – běžící stack (`bash docker/build-and-deploy.sh`), JDK 21, Maven, nainstalovaný Google Chrome (jinak `-De2e.browser=chromium`, první běh stáhne Chromium).
- **Spuštění** – `bash docker/e2e.sh legacy|migrated|all` (PowerShell `docker\e2e.ps1`), přepínače `-De2e.headed=true`, `-De2e.browser=chromium`; tabulka všech `-De2e.*` z `E2eConfig` s defaulty.
- **Scénáře** – tabulka 7 testů (typ, co ověřuje v UI/DB/logu) podle specu, kap. 6.
- **Když test spadne** – `e2e/target/e2e-artifacts/<cíl>/<test>/` (`screenshot.png`, `trace.zip`, `app.log`), jak otevřít trace.
- **Struktura** – `support/` (konfigurace, cíle, sondy, extension), `pages/`, `BlacklistCharacterizationIT`.
- **Pravidla** – legacy = pravda; odchylky jen v `AppTarget` s komentářem PROČ; testy sériově (sdílená DB).

- [ ] **Step 2: `poznamky/12-charakterizacni-e2e-testy.md` (česky, přenositelné na reálný projekt)**

Sekce:
1. **Proč** – migrace bez specifikace (navíc se změnou datové vrstvy: originální DAO blacklistu je čistý Spring JDBC, JPA/Hibernate má legacy backend jen v jiných částech; nová appka přechází na JPA): chování legacy je specifikace; e2e na legacy = akceptační kritérium migrace.
2. **Postup** – (a) vybrat typy chování portletu (čtení, action+PRG, validace, AJAX resource, vytvoření, ACL) a ke každému 1 vzorový test; (b) napsat a odladit na legacy, 3× za sebou zeleně; (c) přepnout cíl → očekávaně červená; (d) opravit odchylky v nové appce, ne v testu; (e) záměrné odchylky jen v `AppTarget`.
3. **Tři vrstvy kontroly** – UI (ID elementů, texty z legacy `messages.properties`), DB (reset na seed před každým testem, deterministická ID, „dnes“ z DB), log (audit kontrakt `AUDIT event=… k=v`, hygiena ERROR/stacktrace od značky).
4. **Co se mění při přenosu** – `E2eConfig` (URL, JDBC, cesta k logům), `DbProbe` (Oracle, read-only uživatel, reset přes testovací data backendu), `LogProbe` (vzdálený soubor / Loki); testy a page objects zůstávají.
5. **Pasti** – JDBC → JPA nad cizí schématem: Hibernate nesmí generovat DDL (`validate`), `IDENTITY` kvůli stejným ID z obou appek, entity mapovat na doménu v transakci (lazy kolekce v JSP / open-in-view), enum jako `VARCHAR`; nestabilní portlet URL (klikat, neskládat URL); strangler v nginx přesměruje legacy URL (legacy testovat přímo na Liferay); Liferay při deployi podstrčí `log4j.properties` jen s konzolí (portlet potřebuje vlastní `log4j.xml`); starý `postgresql.jar` v `lib/ext` bundlu neumí SCRAM; sdílená DB ⇒ sériový běh; rotace logů; žádné osobní údaje v auditu; hygiena vidí jen log aplikace (chyby portálu mimo portlet ne); AJAX – čekat na odpověď, ne `sleep`.
6. **Odchylky nalezené v demu** – seznam z Tasku 10 Step 1 (test, legacy vs. crm-web, oprava).
7. **Ukázka v demu** – odkazy `e2e/`, `docker/e2e.sh`, spec `docs/superpowers/specs/2026-10-16-e2e-charakterizacne-testy-design.md`.

Do `poznamky/README.md` přidej řádek tabulky:
`| [12-charakterizacni-e2e-testy.md](12-charakterizacni-e2e-testy.md) | charakterizační e2e testy (UI + DB + log) napsané na legacy jako akceptační kritérium migrace; co se mění při přenosu |`
a do doporučeného pořadí za `06`: `→ 12`.

- [ ] **Step 3: `docs/MIGRACIA.md`**

- Do mapovací tabulky: `| backend DAO v tom samém JVM (Spring JDBC, DAO po tabulkách, ruční SQL + RowMapper) | JPA entity + Spring Data repository + mapper na doménu nad stejnými tabulkami; schéma zůstává backendu (ddl-auto=validate) |` a `| log portletu (log4j z deploye Liferay) | soubor aplikace + audit kontrakt AUDIT event=… |`.
- Nová sekce **Audit log (kontrakt)** – formát, eventy a pořadí klíčů (z Global Constraints), kdo zapisuje (controller po úspěchu), co se nezapisuje (osobní údaje, neúspěch), kde je log (`docker/logs/...`).
- V sekci 10 „Co demo záměrně neřeší“ uprav větu o in-memory backendu: blacklist je na sdílené DB, dashboard zůstává in-memory.

- [ ] **Step 4: README a CLAUDE.md**

- `CLAUDE.md`: v „Runtime architecture“ přidat Postgres (`:5432`, schéma `blacklist` sdílená legacy + crm-web, dashboard in-memory) a `docker/logs/`; nahradit větu „Each app has its **own** in-memory blacklist data“ za sdílenou DB; v „Build & run“ přidat `bash docker/e2e.sh legacy|migrated|all` (sériově, ne současně se smoke skripty – oba resetují DB) a poznámku, že `mvn verify` legacy i crm-web potřebuje Docker (Testcontainers Postgres); do Status přidat bod 4 (e2e, spec + plán `2026-10-16-e2e-charakterizacne-testy*`).
- `README.md` (kořen): Postgres v tabulce služeb, odkaz na `e2e/README.md`.
- `legacy/README.md`: tabulka stacku – sdílená DB Postgres, log `docker/logs/liferay/customercare-portlet.log`.
- `crm-web/README.md`: perzistence JPA (entity v `blacklist/jpa`, mapper, `ddl-auto=validate`), `crm.blacklist.store` (`memory` default / profil `jpa` v Dockeru), log `docker/logs/crm-web/crm-web.log`, audit.

- [ ] **Step 5: Kontrola anonymizácie a záverečné overenie**

Run: `bash poznamky/LOCAL-anonymizacia.sh` → Expected: žiadny výstup (ak skript na stroji chýba, uveď to v zhrnutí – kopíruje sa ručne).
Run (finálne, sekvenčne): `mvn -q -B -f legacy/pom.xml verify`, `mvn -q -B -f crm-web/pom.xml verify`, `mvn -q -B -f e2e/pom.xml test`, `bash docker/e2e.sh all`, `bash docker/smoke.sh`, `bash docker/smoke-migration.sh`.
Expected: všetko zelené (`E2E OK`, `SMOKE OK`, `MIGRATION SMOKE OK`).

- [ ] **Step 6: Checkpoint** – `git status`; zhrnutie zmien pre používateľa (commit robí on).
