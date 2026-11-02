# Legacy aplikace „před“ (Liferay 6.2)

Příklady **blacklist nezákazníků** a **zrušení služby** jako portlety v reálném Liferay 6.2 – stejný stack
a mechanismy jako legacy CRM, napsané znovu a anonymizované. Podprojekt 2 je zmigruje na Spring Boot.

## Stack

| Věc | Verze |
|---|---|
| Portál | Liferay Portal 6.2 CE GA6 (Tomcat 7.0.62, HSQL) v Dockeru |
| Java | 8 (Zulu 8u252 – update musí být ≤ 255, viz `poznamky/10`) |
| Framework | Spring 4.3.6 Portlet MVC, JSP + JSTL, vlastní taglibs |
| Sdílená session | Redis 7.4 (Jedis 2.9), JSON |
| Data blacklistu | Postgres 16 (schéma `blacklist`, sdílené s novou aplikací), Spring JDBC – DAO po tabulkách jako originál, pool commons-dbcp2 |
| Log portletu | `log4j.xml` ve WAR → `docker/logs/liferay/customercare-portlet.log` (audit `AUDIT event=…`) |
| SSO portál | mock – Spring Boot 4.1.1 / Java 21 (`../sso-mock`) |

## Moduly (stejné členění jako legacy)

| Modul | Co obsahuje | V reálném legacy |
|---|---|---|
| `sso-portal-api` | API SSO portálu, service locator, HTTP klient (shaded JAR v `tomcat/lib/ext`) | `provided` API + JBoss modul |
| `frontend-base-customization` | session fasáda `SessionManagerCustom` + duální session, ACL, tagy `portal:*`/`common:*`, `DispatcherPortletCustom`, `BaseCrmController` | `FBC` |
| `customercare-portlets` | portlety `blacklist`, `dashboard`, `header`, in-memory backend, JSP, startup hook, hooknutý `status.jsp` | `CP`, `J` |
| `customercare-theme` | Velocity theme, vkládá portlety hlavičky | `customercare-theme` |

## Spuštění

Předpoklady: Docker Desktop (~3 GB RAM pro Liferay), JDK 21, Maven 3.9, Git Bash (smoke test).

```bash
bash docker/build-and-deploy.sh     # build + docker compose up + hot deploy (PowerShell: docker\build-and-deploy.ps1)
bash docker/smoke.sh                # end-to-end ověření, na konci "SMOKE OK"
```

První start trvá 1–3 minuty (stažení bundlu, vytvoření DB). Log: `docker compose -f docker/docker-compose.yml logs -f liferay`.
Zastavení: `docker compose -f docker/docker-compose.yml down` (data portálu zůstanou ve volume; `down -v` je smaže).

| Adresa | Co |
|---|---|
| http://localhost:8000 | **CRM** (nginx → Liferay + SSO mock), začni zde |
| http://localhost:8000/sso/login | SSO portál (mock) – přihlášení, změna zákazníka, odhlášení |
| http://localhost:5540 | RedisInsight – sdílená session v Redisu |
| http://localhost:8080 | přímo Liferay (admin `test@liferay.com` / `test`) |

Demo operátoři: **Správce** (`spravca`) – vše; **Čtenář** (`citatel`) – jen čtení blacklistu.
Zákazníci: `C-1001` Jan Vzorový (3 produkty), `C-1002` Firma Ukázka s.r.o. (2 produkty).

## Proklikání

1. Otevři **http://localhost:8000** → přesměruje na `/web/crm/blacklist`. Nejsi přihlášený: hlavička i portlet
   ukážou „Přihlásit“. Pod hlavičkou je ladicí řádek *Sdílená session: zdroj čtení = PORTLET, SSO_ID = -*.
2. Klikni na **Přihlásit** → stránka SSO mocku. Vyber *Správce* a zákazníka *C-1001* → **Přihlásit**.
   Vrátíš se do CRM: hlavička ukazuje operátora, zákazníka a v ladicím řádku tvoje `SSO_ID`.
3. **Blacklist – vyhledávání:** příjmení `vzork` → **Hledat**. Action fáze nastaví render parametr
   a zobrazí se výsledky (Petr Vzorka, Petr Vzorkový). Zkus i jméno `Věra` (diakritika) nebo prázdný formulář
   (chyba „Zadejte alespoň jedno kritérium.“ – validační chyba přežila action → render).
4. **Detail:** klikni na *Petr Vzorka*. Hlavička teď ukazuje *Naposledy otevřený záznam: Petr Vzorka* –
   to je hodnota ze session fasády (a v Redisu).
   - **Změna stavu a platnosti** → *Neaktivní* → **Uložit**: hláška v hlavičce (jednou), stav se změní.
   - **Přidat poznámku**: poznámka se jménem operátora; prázdná poznámka → chybová hláška.
   - **Odstranit** u identifikátoru: AJAX (resource fáze) překreslí jen seznam; poslední identifikátor
     odstranit nelze (hláška ve fragmentu).
5. **Nový záznam** (na stránce vyhledávání): změna *Důvodu* přes AJAX překreslí povolené *Podmínky*.
   Odešli neúplný formulář → chyby u polí; vyplněný → detail nového záznamu.
6. **Dashboard** (navigace nahoře): produkty zákazníka ze SSO. **Zrušit** u *Tarif Neomezený* →
   formulář (`page=tariffDeactivation`). Změna typu nebo data podání zavolá JSON resource a nastaví
   *Nejdříve:*. Příliš brzké datum → chyba; správné → potvrzení se žádostí.
   Legacy past `@SessionAttributes`: u *Tarif Neomezený* zvol *Zrušení* a datum „Ke dni“ dřívější než *Nejdříve*
   → **Odeslat** skončí chybou (formulář zůstane v session). Vrať se na produkty a otevři *Tarif Standard* – typ a data
   zůstala z předchozího formuláře. Po úspěšném odeslání se formulář ze session odstraní (`setComplete`), proto
   se past projeví jen po neúspěšném pokusu; migrace ji odstraní úplně.
7. **Změna zákazníka:** v hlavičce **Změnit zákazníka** → v SSO mocku vyber `C-1002` → dashboard ukáže jiné produkty.
   Zákazníka vlastní SSO portál, ne CRM.
8. **ACL:** **Odhlásit** → přihlas se jako *Čtenář*. Blacklist vidí, ale bez tlačítek změny a bez „Nový záznam“;
   dashboard ukáže *Nemáte oprávnění DEACTIVATION.*
9. **Chybová stránka portálu:** http://localhost:8000/web/crm/neexistuje → přepsaná `status.jsp` (hook).

## Sdílená session živě

Fasáda (`SessionManagerCustom`) zapisuje hlášky a vybrané atributy **do PortletSession i do Redisu**
(hash `crm:session:{SSO_ID}`, JSON, TTL 30 min). Feature flag `crm:flags:session-read-source` určuje, odkud čte.

- **V prohlížeči:** ladicí řádek pod hlavičkou ukazuje zdroj čtení a `SSO_ID`.
- **V terminálu:** `bash docker/show-session.sh` (PowerShell: `docker\show-session.ps1`) vypíše flag
  a všechny sdílené session s TTL a obsahem, např.
  `APP_LAST_VIEWED_RECORD  {"id":1,"fullName":"Petr Vzorka"}`. Spusť ho po každém kroku proklikání –
  uvidíš, jak přibývají a mizí hlášky (`APP_MESSAGES`) a mění se poslední záznam.
- **V GUI:** http://localhost:5540 → databáze *crm-shared-session* (zaregistruje ji `build-and-deploy`;
  pokud chybí: *Add database*, host `redis`, port `6379`) → klíče `crm:session:*`.
- **Přepnutí čtení na Redis** (bez restartu a deploye): `bash docker/session-flag.sh REDIS`
  (`docker\session-flag.ps1 REDIS`). Obnov stránku – ladicí řádek ukáže `REDIS`, hlavička čte poslední
  záznam z Redisu. Zpět: `session-flag.sh PORTLET`.
- **Výpadek Redisu:** `docker compose -f docker/docker-compose.yml stop redis` – CRM běží dál (zápis do Redisu
  se jen zaloguje, při flagu `REDIS` se čte ze staré session). `… start redis` ho vrátí.
- **Co se nesdílí:** formuláře v `@SessionAttributes` (kritéria vyhledávání, nový záznam, žádost o zrušení)
  zůstávají v `PortletSession` – sdílí se jen to, co jde přes fasádu a je v registru `SharedKeys`.

V podprojektu 2 bude za tímtéž nginx i Spring Boot aplikace (`/crm/`), která přečte tentýž hash.

## Souběh se zmigrovanou aplikací
Blacklist je zmigrovaný do `../crm-web` – na :8000 ho nginx přesměruje do nové aplikace, starý portlet je na
http://localhost:8080/web/crm/blacklist. Návod na proklikání souběhu: [`../crm-web/README.md`](../crm-web/README.md).

## Testy

```bash
mvn -q -B -f legacy/pom.xml verify     # unit + wiring testy (JUnit 5, portlet mocky Spring 4.3)
mvn -q -B -f sso-mock/pom.xml verify   # SSO mock (MockMvc)
bash docker/smoke.sh                   # běžící stack end-to-end
bash docker/e2e.sh legacy              # charakterizační e2e testy (Chrome, UI + DB + log), viz ../e2e/README.md
```

## Známá omezení (vědomě)

- Render fáze portletu neumí přesměrovat – bez SSO session portlet ukáže odkaz „Přihlásit“, ne redirect.
- Theme nedědí z Liferay `_styled`, admin dockbar je proto bez stylů.
- Pro Liferay je každý operátor host; identitu zná jen SSO mock (v legacy autologin přes SSO portál).
- HSQL (portál) a Postgres (blacklist) místo Oracle, Tomcat místo JBoss, `params="page=…"` místo vlastního dashboard dispatche.
