# Migrace Liferay 6.2 → Spring Boot: demo projektového vedení

![Souběh staré a nové aplikace za nginx se sdílenou session a databází](docs/img/architektura-subeh.svg)

Reálný projekt je velký: legacy CRM pro zákaznickou podporu na **Liferay 6.2** (Java 8, Spring Portlet MVC, JSP),
13 portletů, **1 241 JSP pohledů**, ~235 tis. řádků Javy v modulu portletů, backend ve stejném JVM
(čísla a metodika v [`docs/ANALYZA.md`](docs/ANALYZA.md)). Takový rozsah nelze migrovat najednou ani „přepsat AI 1:1“
– to ukázal předchozí PoC.

Toto repo proto **na malém, ale úplném výřezu** ukazuje, jak migraci vést: jednotlivé kroky, strategii, rozhodnutí,
ověření a podklady pro plánování a odhady. Vše tady běží lokálně v Dockeru: stará aplikace, nová aplikace, společná
identita, session a databáze, nginx jako jediný vstup a e2e testy, které drží novou aplikaci funkční.

## Co tu běží

| Komponenta | Role | Adresa |
|---|---|---|
| **nginx** | jediný vstup; zmigrované stránky směruje do nové aplikace, zbytek do Liferay (strangler) | http://localhost:8000 |
| **Liferay 6.2** – [`legacy/`](legacy/README.md) | stará aplikace „před“: portlety *blacklist* a *dashboard*, stejný stack a mechanismy jako legacy | :8080 (přímo) |
| **crm-web** – [`crm-web/`](crm-web/README.md) | nová aplikace „po“ (Spring Boot 4.1.1, Java 21, JSP): zmigrovaný blacklist | `/crm/…` |
| **SSO mock** – [`sso-mock/`](sso-mock) | vlastník identity (operátor, zákazník), cookie `SSO_ID` pro obě aplikace | `/sso/login` |
| **Redis** | sdílená session `crm:session:{SSO_ID}` (hlášky, poslední záznam, kritéria) | :6379, RedisInsight :5540 |
| **Postgres** | sdílená DB blacklistu (schéma `blacklist`) – stará aplikace přes JDBC, nová přes JPA | :5433 |
| **e2e** – [`e2e/`](e2e/README.md) | charakterizační testy (Chrome): UI + DB + audit log, stejná sada na obě aplikace | `bash docker/e2e.sh` |

## Strategie

1. **Funkcionalita 1:1, technická filozofie 1:1** (podle podkladů klienta): server-side JSP, žádné SPA, žádný přesun
   logiky mezi FE a BE. Mění se platforma, ne aplikace.
2. **Strangler místo velkého třesku.** Stará a nová aplikace běží souběžně za jedním nginx; portlety se přesouvají
   po jednom. Návrat zpět = odebrat jedno přesměrování v nginx.
3. **Sdílené vše, co uživatel vnímá jako jednu aplikaci:** identita (SSO), session (Redis, stejný JSON kontrakt,
   feature flag pro zdroj čtení ve staré aplikaci), data (jedna DB, jedna schéma).
4. **Legacy je specifikace.** Chování staré aplikace se zachytí charakterizačními e2e testy; co je zelené na legacy,
   musí být zelené i na nové aplikaci. Odchylky se opravují v nové aplikaci, ne v testu.
5. **Stejné rozhraní, nová implementace.** Tagy `portal:*`/`common:*`, session fasáda a ACL drží kontrakt legacy,
   takže JSP se přenášejí téměř beze změny ([`docs/MIGRACIA.md`](docs/MIGRACIA.md)).
6. **Pokrok se měří funkčními scénáři**, ne počtem přepsaných souborů.
7. **Řez mezi frontendem a backendem vede po hranici transakce.** V legacy otevírá transakci frontendový kód
   a uvnitř volá několik backendových služeb ve stejném JVM ([`docs/ANALYZA.md`](docs/ANALYZA.md) kap. 3.5).
   Přes síť transakce nejde, proto se transakční operace přesouvá k backendu jako **jedna API operace**
   s původní hranicí transakce; nový frontend ji volá jedním voláním. Modul se přepne, až když existují
   operace pro všechny jeho transakce, a atomičnost ověří e2e scénář s vyvolanou chybou uprostřed operace.

## Plán krok po kroku

Každý krok má výstup, který lze předvést, a ověření, které ho drží funkční.

| # | Krok | Co se ukazuje | Výstup | Ověření | Stav |
|---|---|---|---|---|---|
| 0 | **Analýza** legacy a podkladů | rozsah, rizika, kandidáti na pilot | [`docs/ANALYZA.md`](docs/ANALYZA.md), [`poznamky/`](poznamky/README.md) | – | ✅ |
| 1 | **Spustitelná výchozí verze** | legacy v Dockeru se stejnými mechanismy (portlety, fáze, tagy, session fasáda, ACL, SSO) | [`legacy/`](legacy/README.md), [`docker/`](docker) | `docker/smoke.sh` | ✅ |
| 2 | **Infrastruktura souběhu** | nginx strangler, identita ze SSO v obou aplikacích, sdílená session v Redisu, feature flag | `docker/`, `crm-web/session/` | `docker/smoke-migration.sh` | ✅ |
| 3 | **Pilotní migrace portletu** (blacklist) | render/action/resource → GET / POST+PRG / AJAX, validace, ACL, tagy se stejným rozhraním | [`crm-web/`](crm-web/README.md), [`docs/MIGRACIA.md`](docs/MIGRACIA.md) | `mvn verify` (unit, MockMvc) | ✅ |
| 4 | **Záchranná síť: charakterizační e2e testy** | testy napsané na legacy, beze změny zelené na nové aplikaci; kontrola UI, DB a audit logu | [`e2e/`](e2e/README.md), [`poznamky/12`](poznamky/12-charakterizacni-e2e-testy.md) | `docker/e2e.sh all` | ✅ **aktuální krok** |
| 5 | **Sdílená data a datová vrstva** | jedna DB pro obě aplikace; legacy JDBC DAO → JPA (Hibernate) nad stejnými tabulkami | `docker/postgres/`, `crm-web/blacklist/jpa/` | e2e + Testcontainers | ✅ (součást kroku 4) |
| 6 | Druhý portlet: **dashboard** (zrušení služby) | vlastní dispatch podle parametru `page` → URL, kontext zákazníka, JSON endpointy | – | e2e scénáře dashboardu | další |
| 7 | Průřezová témata | deeplinky a odkazy „zpět“, i18n z CMS, kompozice stránky, security | – | e2e + review | plán |
| 8 | **Přepnutí a vyřazení Liferay** | všechny cesty v nginx na novou aplikaci, flag session na `REDIS`, vypnutí portálu | – | plná e2e sada, provozní metriky | plán |

### Co krok 4 přinesl

- 7 scénářů – po jednom na každý typ chování portletu (čtení, zápis s PRG, validace, AJAX, byznys pravidlo, vytvoření, ACL).
- Na nové aplikaci odhalil **2 odchylky** (texty hlášek) a na legacy **1 skutečnou chybu** (AJAX fragmenty v ISO-8859-1
  – „ž“, „ť“ jako „?“). Všechny opraveny, sada je zelená na obou aplikacích.
- Audit log se stal **kontraktem**: stejný řádek `AUDIT event=…` v obou aplikacích, bez osobních údajů.

## Plánování a odhady

Demo není měřením pracnosti; dává **jednotku a postup**, kterými se odhad reálného projektu sestaví a zpřesní.

**Jednotka práce = portlet (oblast)**, ne soubor. Pracnost určují tyto faktory (z analýzy):

| Faktor | Proč zvyšuje pracnost | Kde v legacy |
|---|---|---|
| počet JSP pohledů | přenos a ověření stránek | dashboard 529, objednávky 444, vyúčtování 62, ostatní 1–28 |
| vlastní dispatch | převod na URL routování | dashboard (`page`), objednávky (page-flow scénář) |
| resource/AJAX volání | každé = endpoint + JS | např. zrušení služby 9× resource |
| backendové služby za portem | adaptéry, testovací data | blacklist 1 služba, zrušení služby 7+ |
| stav v session (`@SessionAttributes`, fasáda) | sdílený kontrakt, pasti PRG | všechny formuláře |
| transakční operace nad více službami | každá = nová API operace backendu, DTO místo entit, test atomičnosti | 8 tříd *manager* (12× `@Transactional`, 4× `TransactionTemplate`) |

**Postup odhadu:**
1. Rozdělit oblasti do tříd složitosti (S: blacklist, vyhledávání; M: vyúčtování, požadavky; XL: dashboard, objednávky).
2. **Pilot změřit**: skutečná pracnost migrace blacklistu včetně e2e sady = kalibrační bod třídy S.
3. Pro každou oblast: (pracnost třídy) × (podíl JSP a resource volání vůči pilotu) + přirážka za vlastní dispatch.
4. Přidat jednorázové položky: infrastruktura souběhu (krok 2 – v demu hotová), průřezová témata (krok 7), přepnutí (krok 8).
5. Po každé zmigrované oblasti odhad přepočítat podle skutečnosti – plán je klouzavý, ne pevný.

**Milníky pro řízení:** každá oblast je hotová, až když (a) běží za nginx v nové aplikaci, (b) její e2e scénáře jsou
zelené na legacy i na nové aplikaci a (c) je možné ji vrátit jedním přesměrováním.

## Hlavní rizika a jak je demo řeší

| Riziko (pořadí z analýzy) | Mitigace ukázaná v demu |
|---|---|
| Session a identita ze SSO portálu | SSO vlastní identitu; Redis session se stejným kontraktem; feature flag zdroje čtení |
| Backend ve stejném JVM se starým stackem | port + adaptér; sdílená DB; e2e ověří, že přepis datové vrstvy (JDBC → JPA) nezměnil chování |
| Transakce otevřené frontendem nad více backendovými voláními | řez po hranici transakce (Strategie 7); v demu zatím nemodelováno – transakce blacklistu leží ve službě |
| Vlastní dispatch (~970 JSP) | krok 6 – dashboard |
| Skryté Liferay závislosti v tazích (~16 000 použití) | tagy se stejným rozhraním a servletovou implementací |
| Sémantika portletových fází | PRG, explicitní model, past `@SessionAttributes` popsaná v [`poznamky/07`](poznamky/07-pasce.md) |
| Mělký výsledek „AI 1:1“ přepisu | charakterizační e2e testy jako akceptační kritérium |

## Technologie

| | Před (legacy) | Po (nová aplikace) |
|---|---|---|
| Platforma | Liferay 6.2 CE, Tomcat 7, Java 8 | Spring Boot 4.1.1, vestavěný Tomcat, Java 21 |
| Web | Spring Portlet MVC 4.3, fáze render/action/resource | Spring MVC: GET / POST + redirect / AJAX endpointy |
| Pohledy | JSP + vlastní tagy nad Liferay API | tytéž JSP, tagy se stejným rozhraním bez Liferay |
| Session | PortletSession + Redis (duální, flag) | HttpSession + Redis (stejný JSON kontrakt) |
| Data | Spring JDBC DAO (po tabulkách) | JPA / Hibernate, Spring Data, schéma validované proti DB |
| Logy | log4j (portlet) | logback; společný audit kontrakt |
| Testy | JUnit 5, portlet mocky | JUnit 5, MockMvc, Testcontainers; společná e2e sada (Playwright) |

Detailní mapování koncept → náhrada: [`docs/MIGRACIA.md`](docs/MIGRACIA.md); přenositelný postup pro reálný projekt:
[`poznamky/`](poznamky/README.md) (playbook, mapa konceptů, pasti, e2e).

## Spuštění

Předpoklady: Docker Desktop (~3 GB RAM pro Liferay), JDK 21, Maven 3.9, Git Bash, Google Chrome (pro e2e).
Každý `docker/*.sh` má dvojče `.ps1`.

```bash
bash docker/build-and-deploy.sh     # build, docker compose up, deploy do Liferay (čeká na nasazení portletů)
bash docker/smoke.sh                # legacy end-to-end            → SMOKE OK
bash docker/smoke-migration.sh      # souběh obou aplikací         → MIGRATION SMOKE OK
bash docker/e2e.sh all              # charakterizační e2e testy    → E2E OK
```

Testy modulů: `mvn -q -B -f legacy/pom.xml verify`, `mvn -q -B -f crm-web/pom.xml verify` (potřebují Docker –
Testcontainers), `mvn -q -B -f sso-mock/pom.xml verify`. Proklikání souběhu: [`crm-web/README.md`](crm-web/README.md).

## Struktura

```
legacy/      stará aplikace na Liferay 6.2 (Java 8, Spring Portlet MVC) – 4 moduly jako legacy
crm-web/     nová aplikace (Spring Boot 4.1.1) se zmigrovaným blacklistem
e2e/         charakterizační e2e testy (Playwright Java + JUnit 5) pro obě aplikace
sso-mock/    mock SSO portálu
docker/      compose (nginx, Liferay, crm-web, SSO, Redis, Postgres), skripty build / smoke / e2e
docs/        analýza, migrace před → po; docs/superpowers = specifikace a plány kroků
poznamky/    přenositelná znalostní báze pro reálný projekt
input/       klientské podklady – git-ignorované, read-only, nejsou součástí repa
```

Každý krok má v `docs/superpowers/` specifikaci a implementační plán – lze z nich vyčíst, jak bylo rozhodnuto a proč.

Bonus mimo projekt migrace: [návod pro Neo4j – znalostní báze projektu v grafu](kb-graph/README.md).

## Anonymizace

Repo neobsahuje klientský kód, názvy klienta, interní URL, hesla ani data z logů; ukázky „před“ jsou napsané znovu.
Mapování aliasů na skutečné názvy je v lokálních souborech `poznamky/LOCAL-*`; ty jsou git-ignorované a přenášejí se ručně.
