# Nová aplikace „po“ (Spring Boot) – zmigrovaný blacklist

Portlet **blacklist ne-zákazníků** ze staré aplikace (`../legacy`, Liferay 6.2) přepsaný na Spring Boot 4.1.1 + JSP.
Běží **souběžně** se starou aplikací za jedním nginx: blacklist obsluhuje nová aplikace, dashboard zůstává v Liferay.
Obě aplikace sdílejí identitu (SSO mock, cookie `SSO_ID`) a session (Redis hash `crm:session:{SSO_ID}`).

## Spuštění

Předpoklady: Docker Desktop, JDK 21, Maven 3.9, Git Bash.

```bash
bash docker/build-and-deploy.sh      # build legacy + sso-mock + crm-web, docker compose up, deploy do Liferay
bash docker/smoke.sh                 # stará aplikace (blacklist přímo na :8080)
bash docker/smoke-migration.sh       # souběh – na konci "MIGRATION SMOKE OK"
```

| Adresa | Co |
|---|---|
| http://localhost:8000 | **vstup** – nginx: `/web/…` Liferay, `/crm/…` nová aplikace, `/sso/…` SSO mock |
| http://localhost:8000/crm/blacklist/search | blacklist v nové aplikaci (sem vede i starý odkaz „Blacklist“) |
| http://localhost:8000/web/crm/dashboard | dashboard ve staré aplikaci |
| http://localhost:8080/web/crm/blacklist | **starý** blacklist přímo v Liferay – pro srovnání „před/po“ |
| http://localhost:5540 | RedisInsight – sdílená session |

## Proklikání souběhu

1. Otevři **http://localhost:8000** – nová aplikace vyžaduje přihlášení, proto tě hned přesměruje na SSO mock:
   vyber *Správce*, zákazník *C-1001* → **Přihlásit**.
2. Navigace **Blacklist** v Liferay (nebo http://localhost:8000/web/crm/blacklist) → nginx přesměruje na
   `/crm/blacklist/search`. Hlavička vypadá stejně, ale je to Spring Boot – ladicí řádek ukazuje
   *zdroj čtení = Redis (nová aplikace), flag staré aplikace = PORTLET, SSO_ID = …*.
3. Vyhledej příjmení `vzork` → otevři **Petr Vzorka**. Hlavička nové aplikace ukáže *Naposledy otevřený záznam*.
   V terminálu: `bash docker/show-session.sh` → v hashi je `APP_LAST_VIEWED_RECORD {"id":1,"fullName":"Petr Vzorka"}`
   zapsaný **novou** aplikací.
4. Klikni na **Dashboard** (navigace nové aplikace vede do Liferay). Při flagu **PORTLET** hlavička staré aplikace poslední
   záznam **neukáže** – čte svou PortletSession, kam nová aplikace nezapisuje.
5. `bash docker/session-flag.sh REDIS` a obnov dashboard → hlavička Liferay ukáže
   **„Naposledy otevřený záznam: Petr Vzorka“** z nové aplikace. Ladicí řádek nové aplikace ukáže *flag staré aplikace = REDIS*.
   Poučení: flag se přepíná na `REDIS` nejpozději s nasazením první zmigrované stránky.
6. **Změnit zákazníka** (v libovolné aplikaci) → SSO mock → *C-1002*: obě aplikace ukážou nového zákazníka
   (dashboard jiné produkty). Zákazníka vlastní SSO portál.
7. V nové aplikaci změň stav záznamu → hláška se zobrazí jednou (prošla přes Redis). Odstranění identifikátoru (AJAX)
   jde jen přes POST.
8. **Odhlásit** → přihlas se jako *Čtenář* → v nové aplikaci nevidí změny ani „Nový záznam“, přímý POST dostane 403.
9. **Před/po:** otevři http://localhost:8080/web/crm/blacklist (starý portlet) a porovnej URL, chování a kód –
   `docs/MIGRACIA.md`.
10. `docker compose -f docker/docker-compose.yml stop redis` → nová aplikace běží dál (bez hlášek a posledního
    záznamu), `… start redis` ho vrátí.

## Co je kde

| Koncept | Soubor |
|---|---|
| fasáda session (kontrakt legacy, nová implementace) | `src/main/java/com/example/crm/session/CrmSession.java`, `SharedCrmSession.java` |
| identita ze SSO | `session/SsoClient.java`, `SsoIdentityResolver.java` |
| sdílená session (Redis, JSON kontrakt) | `session/RedisSharedSessionStore.java`, `SharedSessionKeys.java` |
| „portlet“ = skupina controllerů | `web/portlet/LegacyPortlet.java`, `LegacyPortletInterceptor.java` |
| hlavička (náhrada theme + portletu header) | `webapp/WEB-INF/jsp/layout/header.jspf`, `web/portlet/LayoutModelInterceptor.java` |
| tagy se stejným rozhraním | `web/tag/*`, `webapp/WEB-INF/tld/*.tld` |
| blacklist | `blacklist/*Controller.java`, `webapp/WEB-INF/jsp/blacklist/*.jsp` |
| perzistence (JPA nad sdílenou DB) | `blacklist/jpa/*Entity.java`, `BlacklistRecordRepository.java`, `BlacklistSpecifications.java`, `blacklist/BlacklistEntityMapper.java`, `JpaBlacklistService.java` |
| audit log (kontrakt se starou aplikací) | `blacklist/AuditLog.java` – řádek `AUDIT event=… k=v` v `docker/logs/crm-web/crm-web.log` |

## Testy

```bash
mvn -q -B -f crm-web/pom.xml verify    # unit, MockMvc, Tomcat + Jasper, Redis a Postgres přes Testcontainers (potřebuje Docker)
bash docker/e2e.sh migrated            # charakterizační e2e testy (UI + DB + log) proti běžícímu stacku, viz ../e2e/README.md
```

## Perzistence

- Blacklist čte a zapisuje **sdílenou DB se starou aplikací** (Postgres, schéma `blacklist`) přes **JPA** (Hibernate,
  Spring Data). Legacy má k týmž tabulkám JDBC DAO – demo tak ukazuje migrační krok JDBC → JPA nad stejnými daty.
- Schéma vlastní init skript `docker/postgres/init/` – Hibernate ho nikdy negeneruje; v profilu `jpa` ho jen validuje
  (`ddl-auto=validate`), takže rozchod mapování a tabulek spadne už při startu.
- Implementaci portu vybírá `crm.blacklist.store`: `memory` (výchozí – testy bez DB) nebo `jpa` (profil `jpa`, Docker).
- Entity se převádějí na doménové objekty v transakci služby (`open-in-view=false`) – JSP nedostanou lazy kolekce.

## Vědomá omezení

- Dashboard (zrušení služby) má dál in-memory data; blacklist obou aplikací je ve sdílené DB.
- Stránky nové aplikace berou CSS z Liferay (`/html/themes/classic/…`) přes nginx – mimo :8000 mají jen základní styl.
