# Znalostná báza projektu v grafe (Neo4j) – návrh

Dátum: 2026-10-21 · Podprojekt 5 (učebné demo, nezávislé od migrácie)

## 1. Zámer a prečo

Používateľ sa chce naučiť, čo je Neo4j a ako sa s ním pracuje, na niečom, čo dobre pozná:
na znalostnej báze tohto repozitára. Inšpirácia je článok kolegu („Moving a project's
knowledge base into a graph…“, Medium). Jeho kľúčové myšlienky, ktoré demo prenáša:

- **Pravda je JSON v gite, Neo4j je len „lupa“.** Databáza sa plní z JSON a dá sa kedykoľvek zahodiť;
  nič sa do nej nepíše ručne (ďalší reload by to zmazal).
- **Štruktúrna vrstva sa generuje z kódu** (extraktor), preto nemôže zastarať.
- **Lekcie (poznatky) sú písané ručne** a ukotvené na konkrétny kus systému (modul, súbor, službu, tému),
  takže sa dajú vyhľadať podľa toho, na čom práve pracujem.
- **Uložené Cypher dotazy** odpovedajú na viacskokové otázky („čo všetko zasiahne…“), na ktoré grep nestačí.

Úspech = používateľ si spustí Neo4j, jedným príkazom naplní graf, v Neo4j Browseri si prejde
učebnicu dotazov, vidí výsledky ako graf a rozumie, čo je uzol, vzťah, vlastnosť, label a Cypher,
a prečo sa štruktúra generuje z kódu.

## 2. Ciele, ne-ciele, anti-ciele

Ciele:
- vizualizácia znalostnej bázy v Neo4j Browseri cez Cypher dotazy,
- generovanie štruktúrnej vrstvy z kódu repozitára,
- lekcie z `poznamky/05` (mapa konceptov) a `poznamky/07` (pasce) v grafe, ukotvené na štruktúru,
- česká učebnica (`kb-graph/README.md`) s vysvetlením pojmov a sadou dotazov.

Ne-ciele:
- symbolová vrstva (JavaParser, triedy/metódy/volania) – na pochopenie Neo4j zbytočne ťažká,
- pripojenie agenta cez MCP – môže prísť neskôr,
- zmena existujúcich aplikácií (legacy, crm-web, e2e) alebo bežného stacku,
- prenos celých `poznamky/` do grafu (len `05` a `07`; ostatné súbory sa ukotvia ako uzly `Note`).

Anti-ciele (zlyhanie, aj keby to technicky fungovalo):
- graf, ktorý treba ručne udržiavať popri kóde (štruktúra musí byť odvodená),
- údaje v Neo4j, ktoré nie sú v JSON (ručné úpravy v Browseri),
- čokoľvek z `input/` alebo neanonymizované údaje (graf sa commituje – platí pravidlo anonymizácie z `CLAUDE.md`),
- učebnica, ktorá je len zoznam dotazov bez vysvetlenia, čo ukazujú a prečo je to graf a nie SQL.

## 3. Obmedzenia

- Nič v `input/` sa nečíta ani nemení; extraktor číta len súbory mimo `input/` (git-trackované).
- Java 21, Maven 3.9 (ako zvyšok repa), Docker Desktop, Git Bash; každý `*.sh` má `*.ps1` dvojča (konvencia `docker/`).
- Neo4j Community v Dockeri, porty viazané na `127.0.0.1` (Browser 7474, Bolt 7687).
- Bežný stack (`docker/build-and-deploy.sh`) sa nemení; Neo4j je samostatný compose profil `kb`
  (alebo samostatný compose súbor v `kb-graph/` – voľba builderu, viď §8).
- Identifikátory v kóde anglicky, komentáre slovensky (konvencia repa), dokumentácia česky.

## 4. Architektúra

```
repo (POM, compose, nginx, portlet.xml, controllery, SQL)
   │  extractor (Java 21)                     kb-graph/lessons/*.json (ručne)
   ▼                                              │
kb-graph/graph/structure.json (generované, commitované)
   └──────────────┬───────────────────────────────┘
                  ▼  loader (premaže DB, nahrá oboje)
            Neo4j (Docker, localhost) ──► Neo4j Browser + uložené dotazy
```

Adresár `kb-graph/`:
- `extractor/` – Maven modul (Java 21): extraktor + loader (Neo4j Java driver), testy JUnit 5.
- `graph/structure.json` – výstup extraktora, commitovaný (čitateľný v diffe), deterministicky zoradený.
- `lessons/` – ručne písané lekcie (JSON).
- `queries/` – uložené Cypher dotazy (`*.cypher`, jeden dotaz na súbor s komentárom).
- `kb.sh` / `kb.ps1` – príkazy `up | extract | load | check | down` (`up` naštartuje Neo4j a čaká na Bolt; `load` = extract + nahratie).
- `README.md` – učebnica (česky).

## 5. Model grafu

### 5.1 Štruktúrna vrstva (generovaná)

| Label | Zdroj | Kľúč `id` | Vlastnosti (minimum) |
|---|---|---|---|
| `Module` | každý `pom.xml` mimo `input/` a `target/` (bez `dependency-reduced-pom.xml`) | `artifactId` | `path`, `packaging`, `app` (legacy/crm-web/sso-mock/e2e) |
| `Library` | externé `<dependency>` (groupId:artifactId) | `groupId:artifactId` | `version` ak je uvedená |
| `Service` | služby v `docker/docker-compose.yml` | názov služby | `image` alebo `build`, `ports`, `profile` |
| `Route` | `location` bloky v `docker/nginx/default.conf` | cesta | `kind` (proxy/redirect) |
| `Portlet` | `legacy/.../WEB-INF/portlet.xml` | `portlet-name` | `class` |
| `Endpoint` | `@GetMapping`/`@PostMapping` (+ `@RequestMapping` triedy) v `crm-web/src/main/java` | `METHOD path` | `controller`, `file` |
| `Table` | `CREATE TABLE` v `docker/postgres/init/01-*.sql` | `schema.table` | – |
| `File` | súbory, na ktoré sa odkazuje (controller, conf…) | relatívna cesta | – |

Vzťahy:
- `(Module)-[:DEPENDS_ON]->(Module|Library)` – z `<dependency>`; medzi modulmi len ak artifactId existuje v repe
  (pozor: `<parent>` blok odstrániť pred čítaním súradníc – pasca z článku),
- `(Module)-[:CHILD_OF]->(Module)` – z `<parent>`, ak je v repe,
- `(Service)-[:DEPENDS_ON]->(Service)` – z `depends_on`,
- `(Service)-[:BUILT_FROM]->(Module)` – z `build` kontextu / Dockerfile (ak sa dá jednoznačne určiť, inak mapa v extraktore s komentárom),
- `(Service {id:'nginx'})-[:ROUTES]->(Route)` – každý `location` patrí nginx (aby dotaz 3 vedel ísť „cez nginx“),
- `(Route)-[:PROXIES_TO]->(Service)` – z `proxy_pass` (host = názov služby; ak je cieľ premenná, napr. `$sso`,
  vyrieši sa z `set`/`resolver` v tom istom súbore alebo explicitnou mapou s komentárom),
  `(Route)-[:REDIRECTS_TO]->(Route)` pre `return 30x` – cieľ = `location` s najdlhším zhodným prefixom,
- `(Module)-[:DECLARES]->(Portlet|Endpoint)`,
- `(Endpoint)-[:DEFINED_IN]->(File)`,
- `(Service)-[:OWNS]->(Table)` pre `postgres`.

Extraktor číta súbory ako text/XML/YAML (nie kompilátor). **Zlyhá nahlas**: ak nájde menej než
minimálny očakávaný počet uzlov daného typu (prahy v kóde, napr. ≥ 5 modulov, ≥ 6 služieb, ≥ 3 portlety,
≥ 3 endpointy, ≥ 1 tabuľka), skončí chybou namiesto zápisu „prázdneho“ grafu.

### 5.2 Vrstva lekcií (ručne písaná)

| Label | Zdroj | Kľúč | Vlastnosti |
|---|---|---|---|
| `Topic` | `lessons/topics.json` | slug (napr. `session`, `redis`, `cookies`, `url`, `security`, `jsp-tags`, `transactions`, `liferay-runtime`) | `title` |
| `Lesson` | `lessons/pitfalls.json` – všetky pasce z `poznamky/07` (k 2026-10-21 je ich 32; test overí, že počet lekcií = počet číslovaných bodov v `07`) | `pitfall-NN` | `title`, `text` (stručne, česky), `source` = `poznamky/07-pasce.md#NN` |
| `Concept` | `lessons/concepts.json` – riadky z `poznamky/05` | slug | `name`, `description`, `side` = `legacy`/`target` |
| `Note` | súbory `poznamky/*.md` | názov súboru | `title` |

Vzťahy:
- `(Lesson)-[:ABOUT]->(Topic)`,
- `(Lesson)-[:ANCHORED_TO]->(Module|Service|Route|Portlet|Endpoint|Table|File)` – ukotvenie na štruktúru,
- `(Concept {side:'legacy'})-[:REPLACED_BY]->(Concept {side:'target'})`,
- `(Concept)-[:SEEN_IN]->(Portlet|Endpoint|Module)` – kde sa koncept v demu vyskytuje (ak vôbec),
- `(Lesson|Concept)-[:DESCRIBED_IN]->(Note)`,
- `(Note)-[:LINKS_TO]->(Note)` – generované z markdown odkazov medzi `poznamky/*.md` (súčasť extraktora).

Kotvy v lekciách sa odkazujú na `id` štruktúrnych uzlov s labelom (`{"label":"Service","id":"redis"}`).
**Loader overí každú kotvu** jedným dávkovým dotazom a zlyhá so zoznamom neexistujúcich kotiev
(lekcia ukotvená na niečo, čo už v kóde nie je = zastaraná lekcia – presne efekt z článku).

## 6. Uložené dotazy (učebnica)

Každý dotaz v `queries/NN-nazov.cypher` s komentárom: otázka, čo uvidíš v grafe, aký Cypher pojem učí.
Minimálna sada (postupne od jednoduchých):

1. Všetko o jednom uzle – `MATCH (n {id:'redis'})-[r]-(m) RETURN n,r,m` (uzol, vzťah, smer).
2. Topológia runtime – služby a ich `DEPENDS_ON` (labely, filtrovanie podľa labelu).
3. Cesta požiadavky – od `Route '/crm'` cez `nginx` po `Table` (`shortestPath` / premenlivá dĺžka cesty).
4. „Čo padne, keď spadne Redis?“ – `(s)-[:DEPENDS_ON*]->(:Service {id:'redis'})` (viacskokové `*`).
5. Čo treba prebuildovať pri zmene modulu – `DEPENDS_ON*` medzi modulmi (obdoba dotazu z článku).
6. Pasce k téme `session` a na čo sú ukotvené (spojenie dvoch vrstiev).
7. Pasce, ktoré ma čakajú, keď mením `crm-web` (lekcie ukotvené na modul alebo jeho endpointy/súbory).
8. Legacy koncept → náhrada a kde ho vidno v demu (`REPLACED_BY`, `SEEN_IN`).
9. Legacy koncepty bez výskytu v demu (`WHERE NOT (c)-[:SEEN_IN]->()` – negácia vzoru).
10. Najprepojenejšie poznámky / pasce (agregácia `count`, `ORDER BY`).
11. Porovnanie s SQL – jeden dotaz z bodu 4 prepísaný ako rekurzívne SQL CTE v texte učebnice (len ilustrácia).

**Klikací guide v Browseri (rozhodnutie používateľa):** prechádzka dotazmi 1–11 ako vlastný Neo4j Browser guide
(`:play http://localhost:<port>/kb.html`) – každý slajd = krátke vysvetlenie (česky) + dotaz, ktorý sa klikom vloží
do editora. Guide sa **generuje** z `queries/*.cypher` (komentár = text slajdu), aby sa nerozišiel so súbormi;
servíruje ho malý statický kontejner (napr. nginx) v rovnakom compose ako Neo4j, Neo4j má nastavené
`browser.remote_content_hostname_allowlist` (a prípadne `browser.post_connect_cmd`, aby sa guide otvoril sám).
Test overí, že guide obsahuje všetky dotazy z `queries/`.

Učebnica `kb-graph/README.md` (česky): čo je grafová DB a kedy dáva zmysel, pojmy (uzol, label, vzťah,
typ, vlastnosť, smer, cesta), základ Cypher (`MATCH`, `WHERE`, `RETURN`, `*`, `OPTIONAL MATCH`, agregácie),
spustenie (`kb.sh up/load`), prechádzka dotazmi 1–11 s popisom, čo ukazujú, model grafu (obrázok/tabuľka),
princíp „JSON v gite, DB jednorazová“ a „štruktúra z kódu“, ako pridať lekciu, ako sa prejaví zastaraná kotva,
odkaz na článok. Prepojenie: riadok v `poznamky/README.md` a sekcia v root `README.md` (krátko).

## 7. Testovanie a overenie

- JUnit 5 testy extraktora po častiach (POM parser vrátane pasce `<parent>`, compose, nginx, portlet.xml,
  endpointy, SQL, markdown odkazy) na malých fixtures v `src/test/resources` + jeden test nad skutočným repom
  (prahy). Konvencia názvov `should_x_when_y` s `@DisplayName` po slovensky.
- Test loadera s Testcontainers Neo4j: nahratie, idempotentnosť (2× load = rovnaké počty), chyba pri neplatnej kotve.
- Test, že každý `queries/*.cypher` sa vykoná bez chyby a vráti neprázdny výsledok (Testcontainers).
- Determinizmus: dva behy extraktora → identický `structure.json`.
- Anonymizácia: `bash poznamky/LOCAL-anonymizacia.sh` bez výstupu.

## 8. Rozhodnutia autora návrhu (nie používateľa) po kontrole

- **Čerstvosť `structure.json`:** žiadny test nepadá, keď sa commitnutý JSON líši od kódu (inak by zmena v iných
  podprojektoch shodila `kb-graph` build – porušilo by to „žiadna zmena ostatných aplikácií“). `kb.sh load` vždy
  najprv extrahuje, takže Neo4j je aktuálny; `kb.sh check` vypíše, či sa commitnutý JSON líši (exit 1), a README
  vysvetlí, prečo to v reálnom projekte patrí do CI.
- `Module.app` má aj hodnotu `kb-graph` (modul extraktora) – graf popisuje aj sám seba.
- Riadok tabuľky `poznamky/05` = dvojica `Concept` (legacy, target) spojená `REPLACED_BY`; viac náhrad v jednej bunke
  = viac `target` uzlov.
- Pasca, ktorá nemá protějšok v demu (týka sa len pôvodného projektu), sa ukotví len na `Topic` a `Note`.

## 9. Ponechané na builderovi

- compose profil `kb` v `docker/docker-compose.yml` vs. samostatný `kb-graph/docker-compose.yml`
  (odporúčanie: samostatný – nemení bežný stack),
- verzia Neo4j 5.x Community a Java drivera, autentifikácia (napr. `neo4j/kbdemo123`, len localhost),
- presný formát JSON (odporúčanie: `{"nodes":[{"label","id","props"}],"edges":[{"type","from","to"}]}`, kde `from`/`to` = `{label,id}`),
- spôsob čítania YAML (SnakeYAML) a XML (JDK DOM),
- presné rozdelenie pascí do tém a ich kotvy (na základe obsahu `poznamky/07`),
- voliteľne `:style` súbor (GraSS) pre farby labelov v Browseri, ak je jednoduchý.
