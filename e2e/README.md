# Charakterizační e2e testy blacklistu

Sada testů v prohlížeči (Playwright + Chrome), která je **napsaná a odladěná na staré Liferay aplikaci** a beze změny
běží i proti **zmigrované Spring Boot aplikaci**. Chování legacy je specifikace: co je zelené na legacy, musí být
zelené i na `crm-web`. Každý test kontroluje tři vrstvy:

| Vrstva | Co se ověřuje | Jak |
|---|---|---|
| UI | stránky, hlášky, AJAX fragmenty | Playwright, ID elementů společná pro obě aplikace, texty z legacy `messages.properties` |
| DB | co akce skutečně zapsala | JDBC do sdíleného Postgresu (schéma `blacklist`), před každým testem reset na seed |
| Log | audit řádek `AUDIT event=…` + hygiena (žádný ERROR / stacktrace) | čtení log souboru aplikace od značky na začátku testu |

## Předpoklady

- běžící stack: `bash docker/build-and-deploy.sh` (Postgres je na hostu na portu **5433** – 5432 bývá obsazený lokální instalací)
- JDK 21, Maven 3.9, nainstalovaný Google Chrome (jinak `-De2e.browser=chromium`, první běh stáhne Chromium)

## Spuštění

```bash
bash docker/e2e.sh legacy        # stará aplikace (Liferay přímo na :8080)
bash docker/e2e.sh migrated      # nová aplikace (crm-web přes nginx :8000)
bash docker/e2e.sh all           # obě, za sebou
bash docker/e2e.sh legacy -De2e.headed=true      # viditelné okno prohlížeče
```

PowerShell: `docker\e2e.ps1 legacy`. Cíle běží **sériově** – sdílejí jednu DB a každý test ji resetuje; nespouštět
současně se smoke skripty.

| Přepínač | Výchozí hodnota |
|---|---|
| `e2e.target` | `legacy` (`legacy` \| `migrated`) |
| `e2e.ssoUrl` | `http://localhost:8000/sso/login` |
| `e2e.legacyUrl` | `http://localhost:8080/web/crm/blacklist` |
| `e2e.migratedUrl` | `http://localhost:8000/crm/blacklist/search` |
| `e2e.jdbcUrl` / `e2e.jdbcUser` / `e2e.jdbcPassword` | `jdbc:postgresql://localhost:5433/crm` / `postgres` / `postgres` |
| `e2e.logsDir` | `../docker/logs` |
| `e2e.seedScript` | `../docker/postgres/init/02-blacklist-seed.sql` |
| `e2e.browser` | `chrome` (`chrome` \| `chromium`) |
| `e2e.headed` | `false` |

## Scénáře

| # | Typ chování portletu | Test | UI | DB | Audit |
|---|---|---|---|---|---|
| 1 | čtení (render) | vyhledání podle jména s diakritikou | 1 výsledek | beze změny | žádný |
| 2 | zápis (action + PRG) | změna stavu bez „platné do“ | hláška, stav Neaktivní | `INACTIVE`, `valid_to` = dnes | `blacklist.change` |
| 3 | validace bez vedlejšího efektu | prázdná poznámka | chybová hláška | beze změny | žádný |
| 4a | AJAX (resource) | odstranění identifikátoru | fragment bez identifikátoru, stránka se nepřenačte | řádek smazán | `blacklist.identifier.remove` |
| 4b | AJAX – byznys pravidlo | poslední identifikátor | hláška ve fragmentu | beze změny | žádný |
| 5 | vytvoření | nový záznam, AJAX podmínky podle důvodu | detail, hláška | nový záznam + identifikátor | `blacklist.create` |
| 6 | oprávnění (ACL) | čtenář | bez ovládacích prvků | beze změny | žádný |

Každý test navíc končí kontrolou hygieny logu – test spadne, i když UI prošlo, pokud aplikace zalogovala chybu.

## Když test spadne

Artefakty jsou v `e2e/target/e2e-artifacts/<cíl>/<třída.test>/`: `screenshot.png`, `trace.zip` (Playwright trace –
otevřít na https://trace.playwright.dev, soubor zůstává v prohlížeči) a `app.log` (log aplikace během testu).

## Struktura

```
src/test/java/com/example/crm/e2e/
  BlacklistCharacterizationIT.java   scénáře (failsafe, *IT)
  pages/                             page objects: SearchPage, ResultPage, DetailPage, CreatePage
  support/                           E2eConfig, AppTarget (Legacy/Migrated), DbProbe, LogProbe, E2eExtension, SsoLogin
  support/*Test.java                 unit testy podpory (surefire)
```

## Pravidla

- **Legacy = pravda.** Odchylku na `crm-web` opravit v `crm-web`, ne v testu. Chybu legacy (např. rozbité kódování)
  opravit v legacy nebo vědomě zdokumentovat – nikdy ji nekonzervovat testem.
- Záměrné odchylky (jiná vstupní URL, jiný log) patří jen do `AppTarget` s komentářem PROČ.
- Přenos na reálný projekt: mění se `E2eConfig`, případně implementace `DbProbe` / `LogProbe`; testy zůstávají.
  Postup a pasti: [`../poznamky/12-charakterizacni-e2e-testy.md`](../poznamky/12-charakterizacni-e2e-testy.md).
