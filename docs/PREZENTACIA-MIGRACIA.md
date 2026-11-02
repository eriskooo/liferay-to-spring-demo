---
marp: true
theme: default
paginate: true
title: Migrace CRM z Liferay 6.2 na Spring Boot
---

<!-- Slajdy ve formátu Marp (VS Code „Marp for VS Code“ → Export PDF/PPTX); čitelné i jako obyčejný Markdown. -->

# Migrace CRM z Liferay 6.2 na Spring Boot

### Postup, ověření a plán

Pilotní migrace modulu blacklist ze stávající portletové aplikace do Spring Boot.
Stejná funkcionalita, stejné obrazovky, souběžný provoz obou aplikací.

---

# Shrnutí

1. **Bez dopadu na uživatele:** funkcionalita, obrazovky i oprávnění zůstávají 1:1; mění se platforma, ne aplikace.
2. **Bez odstávky:** stará a nová aplikace běží souběžně; moduly se přesouvají po jednom a každý krok je vratný.
3. **Ověřitelný výsledek:** chování staré aplikace je zachyceno automatickými testy, které musí projít i na nové.

---

# Výchozí stav

| | |
|---|---|
| Platforma | Liferay 6.2, Java 8, Spring 4.3 – bez bezpečnostních oprav |
| Rozsah | 13 portletů, 1 241 JSP stránek, ~235 tis. řádků Javy |
| Vlastní tagy v JSP | ~16 000 použití |
| Identita | externí SSO portál |
| Backend | ve stejném JVM jako frontend |

Portál aplikaci nedodává funkci, kterou je nutné zachovat: identitu vlastní SSO portál, CMS se nepoužívá.

---

# Cíl a zásady

| Zůstává beze změny | Mění se |
|---|---|
| obrazovky a texty (server-side JSP) | Liferay → Spring Boot 4.1, Java 8 → 21 |
| přihlášení přes SSO | portletové fáze → standardní HTTP požadavky |
| oprávnění operátorů (vlastní ACL) | vlastní tagy: stejné rozhraní, nová implementace |
| databáze a tabulky | datová vrstva JDBC → JPA |
| formát audit logu | Liferay theme → statické soubory aplikace |

Bezpečnostní model se při migraci záměrně nemění.

---

# Postup migrace

Postupná náhrada (vzor *strangler*):

1. Nová aplikace běží souběžně s Liferay za jedním vstupním bodem (nginx).
2. Obě aplikace používají společné SSO, společnou relaci v Redisu a společnou databázi.
3. nginx směruje zmigrované stránky do nové aplikace, ostatní do Liferay.
4. Návrat zpět = odebrání jednoho přesměrování; bez nasazení a bez migrace dat.
5. Liferay se odstaví po přesunu posledního modulu.

Vývoj stávající aplikace během migrace pokračuje.

---

# Co obě aplikace sdílejí

| Oblast | Řešení | Ověření |
|---|---|---|
| Identita | SSO portál, společná cookie | smoke test, e2e |
| Relace | Redis, stejný datový kontrakt; přepínač zdroje čtení ve staré aplikaci | smoke test migrace |
| Data | jedna databáze, stejné tabulky | e2e (kontrola DB) |
| Audit | stejný formát záznamu, bez osobních údajů | e2e (kontrola logu) |

Uživatel přechod mezi starou a novou částí nerozpozná.

---

# Úpravy stávající aplikace

Malé, předem známé a **přidávající**: nemění chování, nasazují se před přepnutím prvního modulu.

| Část | Úprava |
|---|---|
| Stávající frontend | session fasáda zapisuje i do sdílené session, čtení podle přepínače |
| Stávající frontend | kontrola vlastníka session při změně operátora |
| Stávající frontend | zneplatnění dat v session po změně v nové aplikaci |
| Backend | nové API nad stávajícími službami včetně transakčních operací; stávající použití beze změny |
| Infrastruktura | nginx před aplikací |

Úpravy ověří tytéž automatické testy jako novou aplikaci.
Variantu upgradu backendu na nový stack na místě jsme nezvolili: vylučovala by postupnou migraci.

---

# Ověření shodného chování

Charakterizační e2e testy: zapisují se a ověřují na staré aplikaci, beze změny musí projít na nové.
Kontrolují uživatelské rozhraní, stav databáze a audit log.

| Pilot (blacklist) | |
|---|---|
| Scénáře | 7 – po jednom na každý typ chování (čtení, zápis, validace, AJAX, byznys pravidlo, vytvoření, oprávnění) |
| Odchylky nové aplikace | 2 (texty hlášek) – opraveny |
| Chyby nalezené ve staré aplikaci | 1 (kódování znaků v AJAX odpovědích) – opravena |
| Výsledek | sada zelená na obou aplikacích |

Rozdíly se opravují v nové aplikaci, nikdy v testu.

---

# Plán krok po kroku

| # | Krok | Stav |
|---|---|---|
| 0 | Analýza stávající aplikace a podkladů | hotovo |
| 1 | Spustitelná výchozí verze se stejnými mechanismy | hotovo |
| 2 | Infrastruktura souběhu: nginx, SSO, sdílená relace | hotovo |
| 3 | Pilotní migrace modulu blacklist | hotovo |
| 4 | Automatické testy shodného chování | hotovo |
| 5 | Sdílená databáze, nová datová vrstva (JDBC → JPA) | hotovo (součást kroku 4) |
| 6 | Druhý modul: dashboard (vlastní navigace, AJAX) | další |
| 7 | Průřezová témata: odkazy, překlady, kompozice stránky | plán |
| 8 | Přepnutí všech stránek a odstavení Liferay | plán |

Každý krok má výstup, který lze předvést, a test, který ho drží funkční.

---

# Odhad a řízení

**Jednotka práce je modul, ne soubor.** Pracnost určují: počet stránek, AJAX volání, napojené služby,
vlastní navigace a stav formulářů v relaci.

1. Moduly se rozdělí do tříd složitosti (S: blacklist; M: vyúčtování; XL: dashboard, objednávky).
2. Pilot se změří a slouží jako kalibrační bod.
3. Odhad ostatních modulů se z pilotu odvodí a po každém dokončeném modulu přepočítá.

**Modul je hotový,** když běží v nové aplikaci, jeho testy jsou zelené na obou aplikacích
a lze ho vrátit jedním přesměrováním.

---

# Rizika a opatření

| Riziko | Opatření |
|---|---|
| Backend ve stejném JVM se starým stackem (`javax` vs. `jakarta`) | oddělení za rozhraní; varianta po analýze backendu |
| Transakce otevřené frontendem nad více backendovými voláními (8 tříd) | operace zůstává u backendu jako jedno volání API s původní transakcí; test atomičnosti |
| Vlastní navigace ve velkých modulech (~970 JSP) | převod na standardní adresy; samostatný krok s vlastními testy |
| Skryté závislosti na Liferay ve vlastních tazích | tagy se stejným rozhraním bez Liferay |
| Rozdíly portletových fází a HTTP | zdokumentované vzory, pokryté testy |
| Funkce, které se vykreslí, ale nefungují | akceptace podle testů chování, ne podle počtu převedených stránek |

Předchozí PoC měřil počet převedených JSP; stránky se vykreslily, část funkcí chyběla.

---

# Otevřené otázky

Následující oblasti pilot zatím neověřil:

- **Backend:** zdrojové kódy backendových modulů nejsou k dispozici; způsob napojení se určí před pilotem.
- **Transakce:** hranice ve frontendu jsou zmapovány; propagace v backendu a případné distribuované transakce
  (databáze + message broker) je třeba ověřit v jeho kódu.
- **Výkon:** srovnání staré a nové aplikace dosud neměřeno; nástroj pro zátěžový test je k dispozici, scénář pro starou aplikaci je třeba doplnit.
- **Odkazy a překlady:** generování interních odkazů a překlady z externího CMS jsou v plánu (krok 7).
- **Celkový odhad:** metodika je připravena, čísla vzniknou kalibrací pilotem.

---

# Co by řešení předpokládalo

- zdrojové kódy backendu a interní Maven repozitář (napojení backendu)
- testovací prostředí napojené na SSO portál, testovací data
- scénáře jednotlivých modulů, nejlépe společně s lidmi, kteří je denně používají
- provoz nové aplikace jako samostatného procesu (kontejner nebo služba)
- úpravy stávající aplikace a backendu přes její standardní release proces

---

# Možný další krok

### Pilotní migrace jednoho modulu v reálném prostředí

1. Výběr pilotního modulu a napojení na skutečné SSO a backend.
2. Testy shodného chování napsané na stávající aplikaci.
3. Migrace modulu a souběžný provoz s Liferay.
4. Měření pracnosti a výkonu, kalibrace odhadu celého projektu.
5. Rozhodnutí o dalším postupu na základě výsledků.

Pilot je vratný bez zásahu do stávající aplikace.

---

# Podklady

- [`docs/ANALYZA.md`](ANALYZA.md) – analýza stávající aplikace
- [`docs/MIGRACIA.md`](MIGRACIA.md) – mapování před → po
- [`docs/QA-ZAKAZNIK.md`](QA-ZAKAZNIK.md) – otázky a odpovědi
- `bash docker/e2e.sh all` – reprodukce testů shodného chování
