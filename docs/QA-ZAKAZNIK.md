# Migrace z Liferay 6.2 na Spring Boot: otázky a odpovědi

Otázky, které při úvaze o migraci obvykle zazní z pohledu provozovatele stávající aplikace na Liferay 6.2,
a odpovědi dodavatele migrace. Každá odpověď uvádí, čím je tvrzení doloženo v demu (repo, testy, měření).
Body, které demo neověřilo, jsou uvedeny věcně a shrnuty v části **Otevřené body** na konci.

Výchozí čísla z analýzy legacy aplikace: 13 portletů, 1 241 JSP pohledů, ~235 tis. řádků Javy v modulu portletů,
~16 000 použití vlastních tagů, backend ve stejném JVM ([`ANALYZA.md`](ANALYZA.md)).

---

## A. Proč a co se změní

### 1. Proč migrovat, když aplikace funguje?
Liferay 6.2 ani Spring 4.3 již nedostávají bezpečnostní opravy a aplikace je vázána na Javu 8. Každý další rok
zvyšuje riziko i cenu případného vynuceného zásahu. Cílem není nová aplikace, ale odstranění nepodporované
platformy při zachování funkcionality.
*Doloženo:* cílový stack Java 21 + Spring Boot 4.1.1 v demu běží vedle původní aplikace.

### 2. Proč ne upgrade na novější Liferay?
Upgrade na Liferay 7.x znamená přepsat portlety na nový modulový model (OSGi). Pracnost je srovnatelná s migrací,
závislost na portálu ale zůstává. Podklady uvádějí jako cíl odstranit závislost na Liferay, nikoli ho aktualizovat. Portál přitom
aplikaci nedodává žádnou funkci, kterou by bylo nutné zachovat: identitu vlastní SSO portál, obsah CMS se nepoužívá.

### 3. Změní se něco pro operátory?
Ne. Funkcionalita i technický přístup zůstávají 1:1: stejné obrazovky (server-side JSP), stejné texty, stejný
vzhled, stejná oprávnění. JSP stránky se přenášejí téměř beze změny. Mění se platforma, ne aplikace.
*Doloženo:* zmigrovaný blacklist používá původní JSP. Rozdíly jsou jen tam, kde se změnil kontrakt controlleru,
a jsou komentované.

### 4. Bude nutný velký přechod s odstávkou?
Ne. Stará a nová aplikace běží souběžně za jedním vstupním bodem (nginx). Oblasti (portlety) se přesouvají
po jedné. Uživatel přechod mezi starou a novou částí nepozná: má stejné přihlášení, stejnou session a stejná data.
*Doloženo:* odkaz „Blacklist“ v Liferay vede do nové aplikace, ostatní stránky zůstávají v Liferay.

### 5. Co když se zmigrovaná část v provozu neosvědčí?
Návrat zpět znamená odebrat jedno přesměrování v nginx. Nevyžaduje nasazení ani migraci dat, protože obě aplikace
pracují nad stejnou databází a stejnou session. Proto je každá oblast hotová, až když je takto vratná.

### 6. Budete zasahovat do stávající aplikace?
Ano, v malém a předem známém rozsahu. Úpravy jsou **přidávající**, nemění chování a nasazují se standardním
release procesem dřív, než se přepne první modul:

| Část | Úprava | Rozsah |
|---|---|---|
| stávající frontend | session fasáda zapisuje i do sdílené session (Redis), čtení podle přepínače | jedna implementace fasády (dnes jediný bod přístupu k session) |
| stávající frontend | kontrola vlastníka session při změně operátora v SSO | jedno místo v dispatcheru |
| stávající frontend | zneplatnění dat v session, když je změní nová aplikace | posluchač na sdílený signál |
| backend | nové API nad stávajícími službami, včetně transakčních operací | rozšíření; stávající služby a jejich použití se nemění |
| infrastruktura | nginx před aplikací | konfigurace, ne kód |

Stávající frontend volá backend dál stejně jako dnes, nová aplikace volá nové API. Úpravy ověříme stejnými
automatickými testy, které musí na stávající aplikaci zůstat zelené.
Variantu s velkým zásahem (upgrade backendu na nový stack na místě) jsme nezvolili:
stávající frontend by ho přestal načítat a migrace by se nedala provádět postupně.
*Doloženo:* stará aplikace v demu obsahuje sdílenou session, přepínač zdroje čtení i kontrolu vlastníka session.

## B. Technické řešení

### 7. Jak zajistíte, že nová aplikace dělá přesně totéž co stará?
Chování staré aplikace zachytíme **charakterizačními e2e testy**. Testy se píší a ověřují na legacy aplikaci,
potom musí beze změny projít na nové. Kontrolují UI, stav databáze i audit log. Rozdíly se opravují v nové aplikaci,
nikdy v testu.
*Doloženo:* 7 scénářů, po jednom na každý typ chování portletu. Na nové aplikaci odhalily 2 odchylky, na legacy
1 skutečnou chybu (kódování znaků v AJAX odpovědích). Sada je zelená na obou aplikacích (`docker/e2e.sh all`).

### 8. Budete přepisovat 1 241 JSP?
Ne. JSP používají vlastní tagy (`portal:*`, `common:*`). Ty v nové aplikaci reimplementujeme se **stejným rozhraním**,
ale bez Liferay. Stránky se tak přenášejí téměř beze změny. Ručně se upravují jen místa, kde se mění tok
mezi stránkami (viz otázka 19).

### 9. Jak bude fungovat přihlášení a SSO?
Beze změny. Identitu operátora i kontext zákazníka vlastní SSO portál a obě aplikace ji čtou ze stejné cookie.
Bezpečnostní model (session fasáda a vlastní ACL) se při migraci záměrně nemění. Případná změna je samostatné
architektonické rozhodnutí.
*Doloženo:* SSO mock, cookie `SSO_ID`, stejná identita v Liferay i v nové aplikaci.

### 10. Ztratí uživatel rozpracovaný stav při přechodu mezi starou a novou částí?
Ne. Session je sdílená v Redisu se stejným datovým kontraktem v obou aplikacích: hlášky, poslední záznam,
kritéria vyhledávání. Stará aplikace čte podle přepínače (feature flag), takže změnu zdroje lze zapnout i vrátit
za provozu.
*Doloženo:* `docker/smoke-migration.sh` ověřuje zápis v jedné aplikaci a čtení ve druhé.

### 11. Bude potřeba migrovat data nebo provozovat dvě databáze?
Ne. Obě aplikace pracují nad stejnou databází a stejnými tabulkami. Nová aplikace má moderní datovou vrstvu
(JPA/Hibernate), jejíž mapování se při startu ověřuje proti skutečnému schématu.
*Doloženo:* sdílená DB blacklistu (legacy přes JDBC, nová aplikace přes JPA), ověřeno e2e testy.

### 12. Musíte přepsat backendové služby a integrace (REST/SOAP, message broker)?
Rozsah migrace je frontend; backendové služby zůstávají. Jejich napojení je ale hlavní technické riziko. Backend
je dnes knihovna uvnitř frontendové aplikace se starým stackem (Java 8, `javax.*`), nová aplikace používá
Jakarta EE. Kontejnerizace oddělení provozně usnadní, sama ho ale nezajistí: backend potřebuje síťové rozhraní,
které dnes nemá.

Klíčové jsou transakce. Ve stávajícím kódu je otevírá frontend a v jedné transakci volá několik backendových
služeb (8 tříd). Přes síť transakce nejde, proto se tyto operace nepřenášejí do nového frontendu, ale zůstávají
na straně backendu jako **jedna operace API** s původní hranicí transakce. Atomičnost ověříme testem s vyvolanou
chybou uprostřed operace. Rozsah API a způsob napojení potvrdíme nad zdrojovými kódy backendu.

### 13. Zůstanou oprávnění operátorů stejná?
Ano. Zůstávají stejná privilegia a stejná pravidla. V nové aplikaci je oprávnění deklarováno přímo u stránky
nebo akce a kontroluje se centrálně při každém požadavku.
*Doloženo:* e2e scénář operátora s právem jen ke čtení (skryté akce, odmítnutý přístup).

### 14. Poběží nová aplikace na našem aplikačním serveru JBoss?
Ne, aplikační server nebude potřeba. Nová aplikace běží jako samostatný proces s vestavěným Tomcatem a nasazuje se
jako kontejner nebo systémová služba. Nasazení na JBoss by naráželo na to, že Spring Boot 4 vyžaduje Servlet 6.1
(Jakarta EE 11) a nepodporuje webový server Undertow, na kterém JBoss stojí. Cílový provoz tak nepřebírá žádnou
část stávající platformy.
*Doloženo:* nová aplikace běží v demu jako samostatný kontejner vedle Liferay.

### 15. Co to znamená pro provoz a monitoring?
Aplikace se nasazuje jako kontejner nebo samostatný proces, zapisuje logy do souborů a audit log má stejný formát
(`AUDIT event=…`) jako stará aplikace, bez osobních údajů. Existující vyhodnocování auditu tak funguje beze změny.
Během souběhu běží vedle sebe Liferay, nová aplikace, nginx a Redis.

### 16. Bude nová aplikace pomalejší?
Výkon staré a nové aplikace jsme v demu zatím neporovnávali. Nová aplikace odstraňuje vrstvu portálu (skládání
stránky z portletů), proto nepředpokládáme zhoršení. Tvrdit to ale budeme až po měření: nástroj pro zátěžový test je
součástí dema, scénář pro starou aplikaci by se doplnil. Měření by dávalo smysl na pilotní oblasti proti oběma aplikacím.

## C. Řízení, odhad, rizika

### 17. Jak odhadujete pracnost a cenu?
Jednotkou práce je oblast (portlet), ne soubor. Oblasti se rozdělí do tříd složitosti podle počtu stránek,
AJAX volání, napojených služeb a vlastní navigace (S: blacklist; M: vyúčtování; XL: dashboard, objednávky).
Pilotní oblast se změří a slouží jako kalibrační bod. Odhad ostatních oblastí se z něj odvodí a po každé
dokončené oblasti přepočítá. Pevné číslo před pilotem by nebylo podložené.

### 18. Jak budeme sledovat postup?
Postup měříme funkčními scénáři, ne počtem přepsaných souborů. Oblast je hotová, když (a) běží v provozu v nové
aplikaci, (b) její e2e scénáře jsou zelené na staré i nové aplikaci a (c) lze ji vrátit jedním přesměrováním.
Každý krok plánu má výstup, který lze předvést.

### 19. Kde vidíte největší rizika?
1. Napojení backendu, který dnes běží ve stejném procesu, včetně transakcí obepínajících několik backendových
   volání (otázka 12).
2. Vlastní navigace uvnitř velkých oblastí: dashboard a objednávky vybírají stránku parametrem, ne adresou
   (~970 JSP). Musí se převést na běžné URL; v plánu je to samostatný krok s vlastními e2e scénáři.
3. Skryté závislosti na Liferay ve vlastních tazích (~16 000 použití). Řeší je tagy se stejným rozhraním.
4. Rozdíly mezi portletovými fázemi a běžným HTTP (stav formulářů v session, přesměrování po odeslání).
   Známé pasti jsou zdokumentované a pokryté testy.

### 20. Předchozí pokus o automatický přepis neuspěl. Proč by teď měl uspět?
Předchozí PoC měřil úspěch počtem převedených JSP. Stránky se vykreslily, funkce ale potichu chyběly (v logu PoC
je např. chyba bindování formuláře). Nyní je akceptačním kritériem chování ověřené e2e testy na staré aplikaci.
AI nástroje používáme jako pomoc při přenosu kódu, ne jako náhradu ověření.

### 21. Co by řešení předpokládalo?
- zdrojové kódy backendových modulů a interní Maven repozitář (napojení backendu, otázka 12),
- testovací prostředí s napojením na SSO portál a testovací data,
- scénáře jednotlivých oblastí, nejlépe společně s lidmi, kteří je denně používají,
- provoz nové aplikace jako samostatného procesu (kontejner nebo služba),
- pilotní oblast; v demu je to blacklist jako malá, ale úplná,
- přidávající úpravy stávající aplikace a backendu přes její běžný release proces (otázka 6).

---

## Otevřené body (věcně)

| Bod | Stav | Kdy se rozhodne |
|---|---|---|
| Napojení backendu (`javax` → `jakarta`, oddělení za rozhraní) | analýza čeká na zdrojové kódy backendu | před pilotem |
| Transakce nad více backendovými voláními | hranice ve frontendu zmapovány (8 tříd); propagace a JTA/XA v backendu neověřeny | před pilotem |
| Výkon proti staré aplikaci | neměřeno | pilot (zátěžový test) |
| Deeplinky a odkazy „zpět“, překlady z CMS | v plánu jako průřezová témata (krok 7) | po druhé oblasti |
| Odhad celého projektu | metodika připravena, kalibrace pilotem | po pilotu |
