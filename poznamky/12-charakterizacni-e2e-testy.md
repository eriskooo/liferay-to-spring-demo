# Charakterizační e2e testy jako záchranná síť migrace

Ukázka v demu: [`../e2e/`](../e2e/README.md), spuštění `bash docker/e2e.sh legacy|migrated|all`,
spec [`../docs/superpowers/specs/2026-10-16-e2e-charakterizacne-testy-design.md`](../docs/superpowers/specs/2026-10-16-e2e-charakterizacne-testy-design.md).

## 1. Proč

Legacy nemá specifikaci – jedinou pravdou je, jak se aplikace chová. Charakterizační test toto chování zachytí na
legacy a stejná sada pak slouží jako **akceptační kritérium** migrace: co je zelené na legacy, musí být zelené i na nové
aplikaci. Vyplatí se dvojnásob, pokud migrace mění i datovou vrstvu – v podkladech je DAO blacklistu čistý Spring JDBC
(`JdbcTemplate`/`RowMapper`, DAO po tabulkách), JPA/Hibernate má legacy backend jen v jiných částech; nová aplikace
v demu přechází na JPA a e2e sada dokazuje, že se tím chování nezměnilo.

## 2. Postup

1. Vybrat **typy chování portletu** a ke každému jeden vzorový test: čtení (render), zápis (action + PRG), validace bez
   vedlejšího efektu, AJAX (resource), vytvoření, oprávnění (ACL). Úplné pokrytí není cílem – cílem je vzor.
2. Napsat a odladit na **legacy**; třikrát za sebou zeleně (reset DB + značka v logu dělají testy nezávislé na pořadí).
3. Přepnout cíl na novou aplikaci → **očekávaně červená**.
4. Každou odchylku porovnat s legacy a **opravit v nové aplikaci**, ne v testu.
5. Záměrné odchylky (jiná vstupní URL, jiný log) jen v jednom místě (`AppTarget`) s komentářem PROČ.
6. Najde-li test **chybu legacy**, rozhodnout: opravit v legacy, nebo vědomě zdokumentovat. Chybu nikdy nekonzervovat testem.

## 3. Tři vrstvy kontroly

- **UI** – ID elementů (stejná v obou aplikacích díky tagům se stejným rozhraním), texty z legacy `messages.properties`;
  stránky otevírat klikáním (portlet URL nejsou stabilní).
- **DB** – před každým testem reset na seed (jeden SQL soubor pro init i reset), deterministická ID; „dnes“ brát z DB
  (`current_date`), ne z počítače, kde běží test.
- **Log** – audit kontrakt `AUDIT event=… k=v` (stejný řádek v obou aplikacích, bez osobních údajů) a hygiena
  (ERROR/FATAL/stacktrace od značky na začátku testu = test spadne, i když UI prošlo).

## 4. Co se mění při přenosu na reálný projekt

| Demo | Reálný projekt |
|---|---|
| `E2eConfig` – URL, JDBC, cesta k logům | URL testovacího prostředí, read/write uživatel testovací DB |
| `JdbcDbProbe` nad Postgresem, reset seed skriptem | Oracle; reset přes testovací data backendu (nebo transakční izolace na test) |
| `FileLogProbe` nad bind mountem | vzdálený log soubor (SSH/sdílený disk) nebo dotaz do Loki/ELK |
| Chrome lokálně | Chrome v CI (headless), artefakty (screenshot, trace, log) jako výstup buildu |

Testy a page objects zůstávají.

## 5. Pasti

- **Liferay při deployi podstrčí do WAR `log4j.properties` jen s konzolí** – log portletu v souboru neskončí. Řešení:
  vlastní `WEB-INF/classes/log4j.xml` (log4j 1.2 ho upřednostní) se souborovým appenderem.
- **Resource fáze (AJAX) v ISO-8859-1** – `page contentType` ve vloženém JSP fragmentu odpověď neovlivní, znaky mimo
  Latin-1 (ž, ť, č) přijdou jako `?`. Odhalil to e2e test (4b); opraveno v `DispatcherPortletCustom`
  (`setCharacterEncoding("UTF-8")` pro každý resource request). Na reálném projektu hledat stejnou chybu.
- **Strangler v nginx přesměruje legacy URL** – legacy testovat přímo na Liferay (jiný port), přihlášení přes SSO platí
  pro oba (cookie nerozlišuje port).
- **Sdílená DB ⇒ sériový běh** cílů i smoke skriptů; každý začíná resetem.
- **Sdílená session**: otevření detailu přepíše „naposledy otevřený záznam“ – testy/skripty, které ho ověřují, nesmí
  mezi tím otevírat jiný detail (krok přes obě aplikace ve smoke testu je proto na konci).
- **JDBC → JPA nad cizím schématem**: Hibernate nesmí generovat DDL (`ddl-auto=validate`), `IDENTITY` kvůli stejným ID
  z obou aplikací, entity mapovat na doménu v transakci (lazy kolekce v JSP, `open-in-view=false`), enumy jako `VARCHAR`.
  Na reálném projektu je přepis datové vrstvy samostatné rozhodnutí oproti převzetí backend JARů.
- **AJAX čekat na odpověď serveru**, ne `sleep`; zápis do logu přes bind mount může mírně zaostat – audit číst s krátkým
  čekáním (`awaitAuditEvents`).
- **Prostředí vývojáře**: lokální PostgreSQL na 5432 (Docker Postgres proto na 5433); `Browser.close()` s kanálem
  `chrome` na Windows občas visí – ukončovat přes `Playwright.close()`.
- **Rotace logů** – menší soubor než při značce = číst od začátku.

## 6. Odchylky nalezené v demu

| Test | Legacy | Nová aplikace před opravou | Oprava |
|---|---|---|---|
| 2 změna stavu | „Stav záznamu byl změněn.“ | „Záznam bol zmenený.“ | text v `crm-web` `messages.properties` |
| 3 prázdná poznámka | „Poznámka nesmí být prázdná.“ | `validation.required` („povinné pole“) | `NoteForm` → klíč `blacklist.detail.noteEmpty` |
| 4b poslední identifikátor | `mo?né odstráni?` (chyba legacy) | správně UTF-8 | oprava v legacy (`DispatcherPortletCustom`) |
