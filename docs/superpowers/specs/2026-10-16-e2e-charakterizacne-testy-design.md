# Spec: charakterizačné E2E testy blacklistu (legacy → zmigrovaná verzia)

Dátum: 2026-10-16 · Stav: návrh na schválenie · Nadväzuje na: [`2026-10-12-legacy-liferay-app-design.md`](2026-10-12-legacy-liferay-app-design.md),
[`2026-10-12-crm-web-migration-design.md`](2026-10-12-crm-web-migration-design.md)

## 1. Cieľ

Sada end-to-end testov cez Chrome, ktorá sa napíše a odladí na **pôvodnej Liferay appke** a potom sa **bez zmeny**
pustí na zmigrovanej `crm-web`. Každý test overuje tri vrstvy: **UI**, **databázu** a **logy** (business audit + hygiena).

Primárny účel: **prenositeľný vzor** pre reálnu migráciu – ako písať charakterizačné testy na legacy a použiť ich ako
akceptačné kritérium migrácie. Nie úplné pokrytie blacklistu; stačí jeden vzorový test na každý typ správania portletu.

Úspech:
- `bash docker/e2e.sh legacy` → všetky testy zelené,
- `bash docker/e2e.sh migrated` → všetky testy zelené (po opravách odchýlok v `crm-web`, pozri 6.3),
- záznam zmenený v jednej appke je v druhej viditeľný (sdieľaná DB), overí `smoke-migration.sh`,
- existujúce `smoke.sh`, `smoke-migration.sh` a unit testy prechádzajú ďalej,
- `poznamky/` obsahuje postup prenositeľný do reálneho projektu.

Rozhodnutia používateľa (2026-10-16):
- účel: vzor pre reálny projekt; rozsah: 6 typov scenárov (čítanie, zápis, validácia, AJAX, vytvorenie, ACL),
- realita, ktorú demo napodobní: **zdieľaná DB backendu** pre starý aj nový frontend, **logy v súboroch** na serveri,
- DB: jeden Postgres, **jedna schéma zdieľaná oboma appkami** (nie oddelené schémy),
- logy: business audit (rovnaký formát v oboch appkách) **aj** hygiena (žiadny ERROR/stacktrace počas testu),
- nástroj: **Playwright Java + JUnit 5**, samostatný Maven modul,
- prísnosť: **legacy = pravda** – rozdiel na `crm-web` sa opravuje v `crm-web`, nie v teste,
- perzistencia: **legacy Spring JDBC** (DAO po tabuľkách – podklady ukazujú, že pôvodné DAO blacklistu je čistý
  `JdbcTemplate`/`RowMapper`; Hibernate/JPA má legacy backend len v iných častiach), **crm-web JPA** (Hibernate, entity,
  Spring Data repository, mapper na doménu) – demo ukazuje migračný krok JDBC → JPA nad tou istou schémou.

## 2. Technológie

| Vec | Voľba |
|---|---|
| E2E | Playwright for Java (Chrome cez `channel=chrome`, voliteľne pribalené Chromium), JUnit 5, AssertJ |
| E2E modul | `e2e/` – samostatný Maven projekt, Java 21, nie je súčasťou buildu appiek |
| DB | `postgres:16-alpine`, jedna schéma `blacklist` |
| Legacy prístup k DB | Spring 4.3 `JdbcTemplate` + `TransactionTemplate`, pool commons-dbcp2 2.1.1 (commons-logging a commons-pool2 už vo WAR sú, bez slf4j), ovládač PostgreSQL 42.7.x vo WAR |
| crm-web prístup k DB | `spring-boot-starter-data-jpa` (Hibernate, Spring Data JPA), ovládač PostgreSQL, Hikari zo Spring Boot |
| Testy adaptérov | Testcontainers Postgres (rovnaké SQL súbory ako docker init) |

Presné verzie (Playwright, Testcontainers, ovládač) sa pripnú v pláne.

## 3. Infraštruktúra

### 3.1 Postgres
- Nová služba `postgres` v `docker/docker-compose.yml`, volume `postgres-data`, port `5432` vystavený na host
  (e2e testy cez JDBC, prípadne DBeaver).
- `docker/postgres/init/01-blacklist-schema.sql` – len DDL schémy `blacklist`:
  - `blacklist_record` (id, first_name, last_name, birth_date, state, state_reason, condition_type, valid_from, valid_to),
  - `blacklist_identifier` (id, record_id → FK, type, value),
  - `blacklist_note` (id, record_id → FK, text, author, created_at),
  - používateľ `crm_app` s DML právami len v schéme `blacklist` (bez DDL).
- `docker/postgres/init/02-blacklist-seed.sql` – `TRUNCATE … RESTART IDENTITY CASCADE` + `INSERT` rovnakých dát,
  aké dnes seeduje `InMemoryBlacklistService` (8 záznamov, 10 identifikátorov, 1 poznámka). ID sú deterministické:
  záznam 1 *Peter Vzorka* má identifikátory 1 a 2, záznam 2 *Mária Príkladová* identifikátor 3.
- **Jeden seed** pre init (`docker-entrypoint-initdb.d`) aj pre reset v testoch.
- Obe appky sa pripájajú ako `crm_app`; e2e testy ako superuser (reset).

### 3.2 Logy v súboroch
- **Liferay:** portál pri deployi vloží do WAR vlastný `log4j.jar` a `log4j.properties` len s konzolou – log portletu
  (commons-logging → log4j) by v súbore neskončil. Portlet preto pribalí `WEB-INF/classes/log4j.xml` (log4j 1.2 ho
  uprednostní pred `.properties`) s konzolou aj súborom `/opt/liferay/logs/customercare-portlet.log` – ako vlastný
  log aplikácie na reálnom serveri. Adresár `/opt/liferay/logs` sa bind mountom napojí na `docker/logs/liferay/`.
- **crm-web:** `logging.file.name=/logs/crm-web.log`, bind mount `docker/logs/crm-web/`; stdout ostáva (`docker logs`).
- `docker/logs/` je v `.gitignore`.

### 3.3 Sériový beh
Zdieľaná DB ⇒ ciele sa testujú len jeden po druhom; zaručuje to `docker/e2e.sh` (ciele spúšťa sekvenčne).

## 4. Zmeny v appkách

### 4.1 JDBC adaptér za existujúcim portom `BlacklistService`
Controllery sa nemenia, mení sa len implementácia portu.

- **Legacy** (`customercare-portlets`): ako originál – JDBC DAO po tabuľkách (`BlacklistRecordDao`,
  `BlacklistIdentifierDao`, `BlacklistNoteDao`; ručné SQL, `RowMapper`) a `JdbcBlacklistService`, ktorá ich skladá
  v transakcii; bean `blacklistService` v `backend-context.xml` sa prepojí z in-memory na JDBC. DataSource (Hikari) z `-Dcrm.db.url`, `-Dcrm.db.user`, `-Dcrm.db.password` v `CATALINA_OPTS`
  (ako dnes Redis). Len Java 8 API.
- **crm-web:** JPA – entity `BlacklistRecordEntity`, `IdentifierEntity`, `NoteEntity` (balík `blacklist.jpa`,
  `@Table(schema = "blacklist")`, `GenerationType.IDENTITY`, enumy ako `VARCHAR`, identifikátory a poznámky ako agregát
  s `cascade` + `orphanRemoval`), `BlacklistRecordRepository` (Spring Data, `JpaSpecificationExecutor`, zámok riadku
  `PESSIMISTIC_WRITE`), vyhľadávanie ako `Specification`, `BlacklistEntityMapper` (entity → doména v transakcii služby)
  a `JpaBlacklistService`. Port, JSP a in-memory ostávajú bez zmeny.
  Schému vlastní init skript: `ddl-auto=none`, v profile `jpa` `validate` (rozchod mapovania so schémou spadne pri štarte);
  `open-in-view=false`. Implementáciu vyberá `crm.blacklist.store=memory|jpa` (default `memory`, aby existujúce
  `@SpringBootTest` bežali bez DB – Hibernate sa vtedy pri štarte nepripája); compose zapne profil `jpa`.
- Biznis pravidlá sa z `synchronized` metód presunú do transakcie: deaktivácia bez „platné do“ doplní dnešný dátum
  (z `Clock`); posledný identifikátor nejde odstrániť (`BlacklistRuleException`); neexistujúci záznam ako doteraz.
- In-memory implementácie ostávajú ako test double pre existujúce testy controllerov.
- Nové testy adaptérov – `JdbcBlacklistServiceTest` (legacy) a `JpaBlacklistServiceTest` (crm-web, `@SpringBootTest`
  s profilom `jpa`) – s rovnakými prípadmi proti Testcontainers Postgresu nad SQL z `docker/postgres/init/`.

### 4.2 Audit log – kontrakt, ktorý migrácia nesmie zmeniť
Každá **úspešná** zmena zapíše jeden riadok cez logger `crm.audit` (INFO), formát `key=value`, rovnaký v oboch appkách:

```
AUDIT event=blacklist.create recordId=9 operator=spravca
AUDIT event=blacklist.change recordId=1 operator=spravca state=INACTIVE validTo=2026-10-16
AUDIT event=blacklist.note recordId=1 operator=spravca
AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca
```

- Zapisuje controller po úspešnom volaní služby (pozná operátora). Validačná chyba, `BlacklistRuleException`
  ani odmietnutie ACL audit nezapíšu.
- Jedna trieda `AuditLog` v každej appke skladá riadok (poradie kľúčov pevné, `validTo` prázdne ⇒ `validTo=`);
  pokrytá unit testmi.
- **Žiadne osobné údaje** – len ID, operátor, stav a dátum; žiadne mená, hodnoty identifikátorov, text poznámky.
- Kontrakt sa zapíše do `docs/MIGRACIA.md`.

### 4.3 Čo sa nemení
Dashboard (zrušenie služby) ostáva in-memory.

## 5. E2E modul `e2e/`

### 5.1 Jednotky (balík `com.example.crm.e2e`)

| Jednotka | Čo robí | Závisí od |
|---|---|---|
| `E2eConfig` | nastavenia zo system properties s lokálnymi defaultmi: cieľ, URL, JDBC, cesta k logom, prehliadač, headed | – |
| `AppTarget` + `LegacyTarget`, `MigratedTarget` | vstupná URL blacklistu, cesta k log súborom cieľa; **jediné miesto pre zámerné odchýlky**, každá s komentárom PREČO | `E2eConfig` |
| `SsoLogin` | prihlásenie cez UI SSO mocku (`:8000/sso/login`, operátor, zákazník `C-1001`) | Playwright `Page` |
| `SearchPage`, `ResultPage`, `DetailPage`, `CreatePage` | page objects cez ID elementov (`searchForm`, `detail-N`, `changeForm`, `noteForm`, `identifiers`, `createForm`, …) | `Page` |
| `DbProbe` + `JdbcDbProbe` | `reset()` (spustí seed), `record(id)`, `identifiers(id)`, `notes(id)`, `recordCount()` – vracajú recordy | JDBC |
| `LogProbe` + `FileLogProbe` | `mark()` (zapamätá koniec súboru), `auditEvents()` (riadky `AUDIT` od značky ako mapy), `errors()` (ERROR/FATAL/stacktrace od značky); pri zmenšení súboru (rotácia) číta od začiatku | súbory |
| `E2eExtension` | životný cyklus testu (5.2) | všetko vyššie |

Ciele:

| | `LegacyTarget` | `MigratedTarget` |
|---|---|---|
| vstup | `http://localhost:8080/web/crm/blacklist` (priamo – na `:8000` strangler presmeruje na novú appku) | `http://localhost:8000/crm/blacklist/search` |
| logy | `docker/logs/liferay/customercare-portlet.log` | `docker/logs/crm-web/crm-web.log` |

Cookie `SSO_ID` z `localhost:8000` platí aj pre `localhost:8080` (cookies nerozlišujú port).
Detail a ďalšie stránky sa otvárajú **klikaním**, nie skladaním URL – portlet URL nie sú stabilné.

### 5.2 Životný cyklus testu
1. pred testom: `db.reset()`, `log.mark()`, nový browser context (čisté cookies), prihlásenie ako `spravca`
   (anotácia `@Operator("citatel")` prihlási iného operátora),
2. test: akcia v UI → asercie UI, DB, audit,
3. po teste: hygiena `log.errors()` musí byť prázdne (samostatná asercia – test spadne, aj keď UI prešlo),
4. pri zlyhaní: screenshot, Playwright trace a výrez logu do `e2e/target/e2e-artifacts/<test>/`.

### 5.3 Pravidlo „legacy = pravda“
Očakávané texty UI sú z legacy `messages.properties`. Rozdiel na `crm-web` sa opraví v `crm-web`. Do `AppTarget` smú len
zámerné, zdokumentované odchýlky (vstupná URL, cesta k logom).

### 5.4 Spúšťanie
- `bash docker/e2e.sh legacy|migrated|all` (+ `docker/e2e.ps1`) → `mvn -f e2e/pom.xml verify -De2e.target=…`;
  `all` spustí oba ciele za sebou. Predpoklad: bežiaci stack (`build-and-deploy.sh`).
- Default Chrome (`channel=chrome`) headless; `-De2e.headed=true`, `-De2e.browser=chromium`.

### 5.5 Prenos do reálneho projektu
Mení sa len `E2eConfig` (URL, JDBC, cesta k logom), prípadne implementácia `DbProbe` (Oracle) či `LogProbe`
(vzdialený súbor, Loki). Testy a page objects ostávajú.

## 6. Scenáre

Spoločné: každý test začína resetom DB a končí hygienou logov. Názvy `should_x_when_y`, `@DisplayName` po slovensky (ako ostatné testy v repe).

| # | Test | Kroky v UI | UI | DB | Audit |
|---|---|---|---|---|---|
| 1 | vyhľadanie s diakritikou | meno `Mária` → Hľadať | `detail-2`, práve 1 riadok | `recordCount()=8` | žiadny |
| 2 | zmena stavu | detail 1, *Neaktívny*, bez „platné do“, Uložiť | „Stav záznamu bol zmenený.“, `recordState` = Neaktívny | `state=INACTIVE`, `valid_to=dnes` | `blacklist.change recordId=1 operator=spravca state=INACTIVE validTo=<dnes>` |
| 3 | prázdna poznámka | detail 1, Pridať poznámku s prázdnym textom | „Poznámka nesmie byť prázdna.“ | `notes(1)` – 1 poznámka | žiadny |
| 4a | odstránenie identifikátora (AJAX) | detail 1, Odstrániť pri identifikátore 2 | fragment `identifiers` bez `AB123456`, bez prenačítania stránky | `identifiers(1)` = [1] | `blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca` |
| 4b | posledný identifikátor | detail 2, Odstrániť pri identifikátore 3 | „Posledný identifikátor nie je možné odstrániť.“ vo fragmente | `identifiers(2)` = [3] | žiadny |
| 5 | vytvorenie záznamu | Nový záznam, zmena Dôvodu → AJAX prekreslí Podmienky, vyplniť, Vytvoriť | detail nového záznamu, „Záznam bol vytvorený.“ | `recordCount()=9`, záznam 9 `ACTIVE` s 1 identifikátorom | `blacklist.create recordId=9 operator=spravca` |
| 6 | čitateľ nemôže meniť (`@Operator("citatel")`) | vyhľadať, otvoriť detail 1 | chýba `gotoCreateForm`, `changeForm`, `noteForm`, tlačidlá Odstrániť | bez zmeny | žiadny |

### 6.1 Vedomá medzera
Vynútený POST čitateľa nie je v e2e – legacy portlet URL sa bez formulára zložiť nedá. 403 v novej appke overuje
`smoke-migration.sh`. Zapíše sa ako komentár v `AppTarget` a do poznámky.

### 6.2 Doplnenie `smoke-migration.sh`
Nový krok: zmena stavu v `crm-web` je viditeľná v detaile v Liferay (sdieľaná DB); na konci reset seedu.
`smoke.sh` a `smoke-migration.sh` dnes menia dáta (napr. záznam 1, 5, 7) – po zavedení DB sa na začiatku oboch
spustí reset seedu, aby výsledok nezávisel od poradia.

### 6.3 Očakávaný priebeh (súčasť vzoru)
1. sada je zelená na `legacy`,
2. na `migrated` niečo spadne – minimálne hláška v teste 2 (`crm-web`: „Záznam bol zmenený.“) a chýbajúci audit,
3. opravy idú do `crm-web`, kým nie je sada zelená na oboch.

## 7. Dokumentácia (po česky, okrem tohto specu)
- `e2e/README.md` – spustenie, prepínače, artefakty pri zlyhaní.
- `poznamky/` – nová poznámka „Charakterizační e2e testy pro migraci“: postup (legacy = pravda → prepnutie cieľa →
  oprava odchýlok), čo sa mení pri prenose (`E2eConfig`, `DbProbe`, `LogProbe`), pasce (nestabilné portlet URL,
  zdieľaná DB a sériový beh, rotácia logov, osobné údaje v audite); odkaz z `poznamky/README.md`.
- `docs/MIGRACIA.md` – kontrakt audit logu, JDBC adaptér v mapovacej tabuľke.
- `CLAUDE.md`, `README.md`, `legacy/README.md`, `crm-web/README.md` – služba `postgres`,
  `docker/logs/`, `docker/e2e.sh`.
- Anonymizácia: žiadne skutočné mená/systémy; kontrola `bash poznamky/LOCAL-anonymizacia.sh`.

## 8. Mimo rozsahu
- Dashboard / zrušenie služby (ostáva v Liferay, in-memory).
- Cucumber/Gherkin vrstva (dá sa doplniť neskôr nad page objects a sondy).
- CI pipeline; paralelný beh cieľov.
- Migrácie schémy (Flyway/Liquibase) – DDL rieši init skript.

## 9. Riziká
- **Logovanie v Liferay 6.2:** overené na bežiacom stacku – deploy vloží `log4j.properties` len s konzolou; riešenie
  `log4j.xml` vo WAR (3.2). Ak by ho deployer prepísal, záloha: `log4j.configuration` cez system property.
- **Hygiena logov:** sleduje sa len log aplikácie (portletu / crm-web), nie celý log portálu – šum Liferay sa tak
  nemieša. Chyby portálu mimo portletu (napr. kompilácia JSP) hygiena nezachytí; zapíše sa do poznámky.
- **Ovládač PostgreSQL v Liferay:** `lib/ext` bundlu obsahuje starý `postgresql.jar` (bez SCRAM). WAR pribalí 42.7.x
  a dbcp2 načíta ovládač classloaderom WAR (child-first); overí sa pri prvom deployi.
- **Chrome kanál:** `channel=chrome` potrebuje nainštalovaný Chrome; inak `-De2e.browser=chromium`.
- **Dátumy:** test 2 počíta s „dnes“ podľa hodín kontajnera (UTC) – test porovnáva s dátumom z DB (`current_date`),
  nie z hostiteľa.
- **JPA nad cudzou schémou:** Hibernate nesmie generovať DDL; `validate` môže odmietnuť mapovanie enumov (natívny typ
  vs. `VARCHAR`) – riešenie `@JdbcTypeCode(SqlTypes.VARCHAR)`. Lazy kolekcie sa nesmú dostať do JSP (mapper v transakcii,
  `open-in-view=false`). V reálnom projekte znamená JPA prepis dátovej vrstvy namiesto prevzatia backend JAR-ov –
  samostatné rozhodnutie, zapíše sa do poznámky ako varianta.
- **Testcontainers v legacy:** moduly sa kompilujú s `--release 8` (aj testy), bežia na JDK 21 – použije sa
  Testcontainers 1.x (podporuje Java 8 API); ak by to nešlo, test adaptéra sa presunie do samostatného modulu.
