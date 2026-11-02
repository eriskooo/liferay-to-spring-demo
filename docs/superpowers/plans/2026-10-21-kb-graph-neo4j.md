# Znalostná báza v grafe (Neo4j) – implementačný plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Učebné demo: štruktúra repa vygenerovaná z kódu + lekcie z `poznamky/` v Neo4j, prechádzané klikacím guide v Neo4j Browseri.

**Architecture:** Java 21 CLI (`kb-graph/extractor`) číta repo ako text/XML/YAML a zapíše `kb-graph/graph/structure.json`; ručne písané lekcie sú v `kb-graph/lessons/*.json`. Príkaz `load` zlúči oboje, overí kotvy, premaže Neo4j a nahrá graf. Uložené dotazy `kb-graph/queries/*.cypher` sa generujú do HTML guide, ktorý servíruje malý nginx vedľa Neo4j.

**Tech Stack:** Java 21, Maven 3.9, Jackson 2.18, SnakeYAML 2.3, Neo4j Java Driver 5.28, Neo4j 5.26 Community (Docker), JUnit 5.12 + AssertJ 3.27, Testcontainers 2.0 (Neo4j modul), nginx 1.27-alpine.

**Spec:** `docs/superpowers/specs/2026-10-21-kb-graph-neo4j-design.md`

## Global Constraints

- Nič v `input/` sa nečíta ani nemení; extraktor preskakuje `input/`, `target/`, `node_modules/`, `.git/`, `poznamky/LOCAL-*`, `dependency-reduced-pom.xml`.
- Java 21 (`maven.compiler.release` 21), Maven 3.9; samostatný Maven projekt (nie súčasť buildu iných appiek), vzor `e2e/pom.xml`.
- Neo4j Community v Dockeri, porty viazané na `127.0.0.1` (Browser 7474, Bolt 7687, guide 7475); auth `neo4j/kbdemo123`.
- Bežný stack (`docker/docker-compose.yml`, `docker/*.sh`) sa nemení; Neo4j má vlastný `kb-graph/docker-compose.yml` (projekt `kb-graph`).
- Každý `*.sh` má `*.ps1` dvojča.
- Identifikátory anglicky, komentáre a JavaDoc slovensky (vysvetľujú rozhodnutie, nie čo kód robí), dokumentácia (`README.md`, guide texty) česky.
- Testy `should_x_when_y` s `@DisplayName` po slovensky; balík `com.example.kb`.
- Neo4j sa plní **len** z JSON; databáza je jednorazová (každý `load` = `MATCH (n) DETACH DELETE n` + nahratie).
- Anonymizácia: žiadne údaje z `input/`; pred odovzdaním `bash poznamky/LOCAL-anonymizacia.sh` bez výstupu.
- **Necommitovať** – používateľ commituje sám (namiesto commit krokov: testy zelené).
- `structure.json` je deterministický (uzly zoradené podľa `label`,`id`; hrany podľa `type`,`from`,`to`; vlastnosti zoradené), 2-medzerový pretty print, LF, koncový `\n`.

## Review Focus

1. **Windows cesty** – extraktor beží v Git Bash/PowerShell; `File.id`, `Module.path`, `Note.id` musia byť s `/` a relatívne ku koreňu repa, inak sa `structure.json` líši medzi OS a `check` hlási falošný rozdiel → test v Task 5 (`should_useForwardSlashes_whenRunOnAnyOs`).
2. **Neexistujúca kotva v lekcii** (preklep, alebo kód sa zmenil) – `load` musí skončiť chybou so zoznamom všetkých zlých kotiev a **nesmie** pred tým zmazať DB → testy v Task 6 a Task 7.
3. **Neo4j nebeží / zlé heslo** – `load` má skončiť zrozumiteľnou hláškou „Neo4j nedostupné na … – spusti `kb-graph/kb.sh up`“ a exit kódom 2, nie stack tracom → test v Task 7.
4. **Prázdny výsledok dotazu v guide** (dotaz po zmene modelu nič nevráti) – učebnica by mlčky ukazovala prázdny graf → test v Task 8 (každý dotaz vráti ≥ 1 riadok).
5. **Opakovaný `load`** – druhé spustenie nesmie zdvojiť uzly → test v Task 7 (2× load = rovnaké počty).

---

## Súborová štruktúra

```
kb-graph/
  README.md                         učebnica (Task 9)
  docker-compose.yml                neo4j + guide nginx (Task 7, 8)
  kb.sh / kb.ps1                    up | extract | check | load | guide | down (Task 7, 8)
  guide/nginx.conf                  CORS pre guide (Task 8)
  guide/kb.html                     generovaný guide (Task 8, commitovaný)
  graph/structure.json              generovaná štruktúra (Task 5, commitovaná)
  lessons/topics.json, pitfalls.json, concepts.json   (Task 6)
  queries/01-…cypher … 11-…cypher   (Task 8)
  extractor/pom.xml
  extractor/src/main/java/com/example/kb/
    Main.java                       CLI
    graph/  NodeRef, Node, Edge, Graph, GraphBuilder, GraphJson
    extract/ Extractor, RepoFiles, PomExtractor, ComposeExtractor, NginxExtractor,
             PortletExtractor, EndpointExtractor, SqlExtractor, NoteExtractor, StructureExtractor
    lessons/ LessonReader, AnchorValidator
    load/   Neo4jLoader
    guide/  GuideGenerator
  extractor/src/test/java/com/example/kb/...   (zrkadlí main)
  extractor/src/test/resources/fixtures/...    malé vstupné súbory
```

---

### Task 1: Maven modul a grafový model

**Files:**
- Create: `kb-graph/extractor/pom.xml`
- Create: `kb-graph/extractor/src/main/java/com/example/kb/graph/{NodeRef,Node,Edge,Graph,GraphBuilder,GraphJson}.java`
- Test: `kb-graph/extractor/src/test/java/com/example/kb/graph/{GraphBuilderTest,GraphJsonTest}.java`

**Interfaces:**
- Produces:
  - `record NodeRef(String label, String id)`
  - `record Node(String label, String id, Map<String,Object> props)` + `NodeRef ref()`
  - `record Edge(String type, NodeRef from, NodeRef to)`
  - `record Graph(List<Node> nodes, List<Edge> edges)` + `Graph merge(Graph other)`, `long count(String label)`, `boolean contains(NodeRef ref)`
  - `class GraphBuilder`: `GraphBuilder node(String label, String id, Map<String,Object> props)` (opakované volanie s rovnakým `label`+`id` zlúči vlastnosti, neduplikuje), `GraphBuilder edge(String type, NodeRef from, NodeRef to)` (duplicitné hrany ignoruje), `Graph build()` (zoradené podľa Global Constraints).
  - `final class GraphJson`: `static String write(Graph g)`, `static Graph read(String json)`, `static Graph readFile(Path p)`, `static void writeFile(Graph g, Path p)`. Formát: `{"nodes":[{"label","id","props":{…}}],"edges":[{"type","from":{"label","id"},"to":{"label","id"}}]}`.

- [ ] **Step 1: pom.xml** – `groupId com.example.kb`, `artifactId kb-graph-extractor`, `version 0.1.0-SNAPSHOT`, packaging jar; závislosti: `jackson-databind 2.18.2`, `snakeyaml 2.3`, `neo4j-java-driver 5.28.4`, `slf4j-simple 2.0.16`; test: `junit-jupiter 5.12.2`, `assertj-core 3.27.3`, Testcontainers BOM `org.testcontainers:testcontainers-bom:2.0.2` (import) + `testcontainers-neo4j`, `testcontainers-junit-jupiter`. Pluginy ako `e2e/pom.xml` (compiler 3.13.0, surefire 3.5.2, `-Dfile.encoding=UTF-8`) + `maven-shade-plugin 3.6.0` s `Main-Class com.example.kb.Main`, výstup `target/kb-graph-extractor.jar` (`finalName`).

- [ ] **Step 2: Failing testy**

```java
@Test @DisplayName("Zlúči vlastnosti, keď sa rovnaký uzol pridá dvakrát")
void should_mergeProps_whenSameNodeAddedTwice() {
    Graph g = new GraphBuilder()
        .node("Service", "redis", Map.of("image", "redis:7.4-alpine"))
        .node("Service", "redis", Map.of("ports", "6379:6379")).build();
    assertThat(g.nodes()).hasSize(1);
    assertThat(g.nodes().get(0).props()).containsEntry("image", "redis:7.4-alpine").containsEntry("ports", "6379:6379");
}
@Test @DisplayName("Ignoruje duplicitnú hranu")
void should_ignoreDuplicateEdge_whenAddedTwice()  // 2× edge → edges().size()==1
@Test @DisplayName("Zoradí uzly podľa labelu a id bez ohľadu na poradie pridania")
void should_sortNodes_whenBuilt()  // pridaj Service b, Module z, Service a → [Module z, Service a, Service b]
@Test @DisplayName("Zápis a čítanie JSON vráti rovnaký graf a rovnaký text")
void should_roundTrip_whenWrittenAndRead()  // read(write(g)).equals(g) && write(read(write(g))).equals(write(g))
@Test @DisplayName("JSON končí novým riadkom a používa LF")
void should_endWithLf_whenWritten()  // write(g).endsWith("\n") && !contains("\r")
@Test @DisplayName("Prázdny graf sa zapíše ako prázdne polia")
void should_writeEmptyArrays_whenGraphEmpty()  // read(write(empty)) má 0 uzlov a 0 hrán
```

- [ ] **Step 3: Spusti** `mvn -q -B -f kb-graph/extractor/pom.xml test` → FAIL (triedy neexistujú).
- [ ] **Step 4: Implementuj** – `GraphBuilder` drží `LinkedHashMap<NodeRef, TreeMap<String,Object>>` a `LinkedHashSet<Edge>`; `GraphJson` cez Jackson `ObjectMapper` s `ORDER_MAP_ENTRIES_BY_KEYS`, `INDENT_OUTPUT`, `DefaultPrettyPrinter` s LF oddeľovačom.
- [ ] **Step 5: Spusti** testy → PASS.

---

### Task 2: PomExtractor

**Files:**
- Create: `extract/Extractor.java`, `extract/RepoFiles.java`, `extract/PomExtractor.java`
- Test: `extract/PomExtractorTest.java`, fixtures `src/test/resources/fixtures/poms/{parent/pom.xml, child/pom.xml, other/pom.xml}`

**Interfaces:**
- Consumes: `GraphBuilder` (Task 1).
- Produces:
  - `interface Extractor { void extract(Path repoRoot, GraphBuilder g) throws IOException; }`
  - `final class RepoFiles`: `static List<Path> find(Path root, Predicate<Path> match)` – rekurzívne, preskočí adresáre a súbory z Global Constraints, výsledok zoradený; `static String rel(Path root, Path file)` – relatívna cesta s `/`.
  - `PomExtractor implements Extractor` – uzly `Module {path, packaging, app}`, `Library {version?}`; hrany `DEPENDS_ON`, `CHILD_OF`.

Pravidlá: id modulu = `artifactId` projektu **po odstránení `<parent>` bloku** (pasca z článku); `packaging` default `jar`; `app` podľa prvého segmentu cesty: `legacy`→`legacy`, `crm-web`→`crm-web`, `sso-mock`→`sso-mock`, `e2e`→`e2e`, `kb-graph`→`kb-graph`. `<dependency>` na artifactId, ktorý je modulom v repe → `DEPENDS_ON` na `Module`; inak `Library` s id `groupId:artifactId` (verzia len ak je literál, nie `${…}`). `<parent>` s artifactId modulu v repe → `CHILD_OF`. Závislosti z `<dependencyManagement>` a `<plugins>` sa ignorujú. Parsovanie JDK DOM (bez namespace-aware).

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Id modulu je jeho artifactId, nie artifactId parenta")
void should_useOwnArtifactId_whenPomHasParent()
    // child/pom.xml má <parent><artifactId>fx-parent</artifactId> a vlastný <artifactId>fx-child</artifactId>
    // → existuje Module fx-child, Module fx-parent; CHILD_OF fx-child→fx-parent
@Test @DisplayName("Závislosť na module z repa je hrana na Module, nie Library")
void should_linkModule_whenDependencyIsRepoModule()   // fx-child závisí na fx-other → DEPENDS_ON Module fx-other
@Test @DisplayName("Externá závislosť je Library s groupId:artifactId")
void should_createLibrary_whenDependencyIsExternal()  // org.example:lib-a 1.0 → Library "org.example:lib-a" {version:"1.0"}
@Test @DisplayName("Verzia s premennou sa neukladá")
void should_omitVersion_whenVersionIsProperty()
@Test @DisplayName("Ignoruje dependencyManagement a pluginy")
void should_ignoreManagedAndPlugins_whenPresent()     // fx-parent má dependencyManagement s org.example:managed → žiadna Library "org.example:managed"
@Test @DisplayName("Preskočí target a dependency-reduced-pom.xml")
void should_skipBuildOutput_whenPresent()             // fixture other/target/pom.xml a other/dependency-reduced-pom.xml → nevznikne z nich modul
```

- [ ] **Step 2: Spusti** `mvn -q -B -f kb-graph/extractor/pom.xml test -Dtest=PomExtractorTest` → FAIL.
- [ ] **Step 3: Implementuj** podľa pravidiel vyššie (dva prechody: najprv zozbieraj artifactId všetkých modulov, potom hrany).
- [ ] **Step 4: Spusti** → PASS.

---

### Task 3: ComposeExtractor a NginxExtractor

**Files:**
- Create: `extract/ComposeExtractor.java`, `extract/NginxExtractor.java`
- Test: `extract/ComposeExtractorTest.java`, `extract/NginxExtractorTest.java`; fixtures `fixtures/docker/docker-compose.yml`, `fixtures/docker/nginx/default.conf` (skrátené kópie skutočných súborov: služby `redis`, `postgres`, `crm-web` (build `../crm-web`), `sso-mock` (profil `sso`, build context `..`), `liferay` (build `./liferay`), `nginx`; locations `/sso/` (cez `set $sso`), `/web/crm/blacklist` (return 302), `/crm/`, `= /`, `/`)

**Interfaces:**
- Consumes: `Extractor`, `RepoFiles` (Task 2). Číta `docker/docker-compose.yml` a `docker/nginx/default.conf` relatívne ku koreňu.
- Produces:
  - `Service {image?, build?, ports?, profile?}`; `DEPENDS_ON` Service→Service (zoznam aj mapa `depends_on`); `BUILT_FROM` Service→Module.
  - `Route {kind: proxy|redirect, exact?: true}` s id = cesta location (`/crm/`, `= /` → id `/`, prop `exact:true`; `location /` → id `/*`, aby sa odlíšili); `(Service nginx)-[:ROUTES]->(Route)`; `PROXIES_TO` Route→Service; `REDIRECTS_TO` Route→Route.

Pravidlá BUILT_FROM: `build` reťazec alebo `build.context` + `dockerfile` → adresár relatívne k `docker/`; ak je v ňom `pom.xml` (v repe), hrana na jeho modul. Výnimky v explicitnej mape s komentárom (legacy WAR-y sa do Liferay hot-deployujú, nie buildujú v image): `liferay` → `customercare-portlets`, `customercare-theme`. Hrana sa vytvorí len ak cieľový `Module` už existuje (StructureExtractor spúšťa PomExtractor prvý; inak hranu vynechaj).
Pravidlá nginx: `proxy_pass http://<host>:<port>` → Service `<host>`; `proxy_pass $var` → hodnota z `set $var http://<host>:…` v tom istom bloku; `return 30x <cieľ>` → Route s najdlhším prefixom, ktorý je prefixom cieľa (`/crm/blacklist/search` → `/crm/`), pri `= /` zhoda presná.

- [ ] **Step 1: Failing testy**

```java
// ComposeExtractorTest
@Test @DisplayName("Načíta služby s image a profilom")
void should_createServices_whenComposeParsed()      // Service redis {image:"redis:7.4-alpine"}, Service sso-mock {profile:"sso"}
@Test @DisplayName("depends_on ako mapa aj zoznam vytvorí hrany")
void should_createDependsOn_whenListOrMap()         // crm-web→postgres (mapa s condition), nginx→crm-web (zoznam)
@Test @DisplayName("Služba buildovaná z adresára s pom.xml je BUILT_FROM modul")
void should_linkModule_whenBuildDirHasPom()         // GraphBuilder s Module crm-web → BUILT_FROM crm-web→crm-web
@Test @DisplayName("Liferay je BUILT_FROM legacy WAR moduly podľa explicitnej mapy")
void should_linkLegacyWars_whenServiceIsLiferay()
@Test @DisplayName("Bez existujúceho modulu sa BUILT_FROM nevytvorí")
void should_skipBuiltFrom_whenModuleMissing()
// NginxExtractorTest
@Test @DisplayName("Každý location patrí nginx")
void should_linkRoutesToNginx_whenConfParsed()      // 6 hrán ROUTES z Service nginx
@Test @DisplayName("proxy_pass na host vytvorí PROXIES_TO")
void should_proxyToService_whenProxyPassHasHost()   // /crm/ → crm-web, /* → liferay
@Test @DisplayName("proxy_pass cez premennú sa vyrieši zo set")
void should_resolveVariable_whenProxyPassUsesSet()  // /sso/ → sso-mock
@Test @DisplayName("Presmerovanie ide na location s najdlhším prefixom")
void should_redirectToLongestPrefix_whenReturn302() // /web/crm/blacklist → /crm/; "/" (exact) → /web/crm/blacklist
```

- [ ] **Step 2: Spusti** `-Dtest='ComposeExtractorTest,NginxExtractorTest'` → FAIL.
- [ ] **Step 3: Implementuj** – compose cez SnakeYAML (`Map<String,Object>`); nginx: regex nad blokmi `location\s+(=\s*)?(\S+)\s*\{(.*?)\n\s*\}` (DOTALL), v bloku `proxy_pass`, `set`, `return`.
- [ ] **Step 4: Spusti** → PASS.

---

### Task 4: PortletExtractor, EndpointExtractor, SqlExtractor

**Files:**
- Create: `extract/PortletExtractor.java`, `extract/EndpointExtractor.java`, `extract/SqlExtractor.java`
- Test: `extract/{PortletExtractorTest,EndpointExtractorTest,SqlExtractorTest}.java`; fixtures `fixtures/portlet.xml` (2 portlety), `fixtures/controllers/{WithPrefixController.java, HomeController.java}`, `fixtures/application.properties` (`server.servlet.context-path=/crm`), `fixtures/schema.sql` (2× `CREATE TABLE`, 1× `CREATE SCHEMA`, 1× `CREATE ROLE`)

**Interfaces:**
- Consumes: `Extractor`, `RepoFiles`.
- Produces:
  - `PortletExtractor`: každý `**/WEB-INF/portlet.xml` mimo `target/` → `Portlet {class}` (id = `portlet-name`), `(Module)-[:DECLARES]->(Portlet)`; modul = najbližší nadradený adresár s `pom.xml` (artifactId).
  - `EndpointExtractor`: každý `*.java` v `crm-web/src/main/java` → pre `@GetMapping`/`@PostMapping` (jedna hodnota alebo `{…}` zoznam) uzol `Endpoint {controller, file}` s id `"<METHOD> <contextPath><classPrefix><path>"` (napr. `GET /crm/blacklist/search`, `GET /crm/` z `"/"`), `context-path` z `crm-web/src/main/resources/application.properties`; `(Module crm-web)-[:DECLARES]->(Endpoint)`, `(Endpoint)-[:DEFINED_IN]->(File)` s id = relatívna cesta.
  - `SqlExtractor`: `docker/postgres/init/01-*.sql` → `Table` s id `schema.table` (lowercase), `(Service postgres)-[:OWNS]->(Table)`.

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Portlet z portlet.xml patrí modulu s najbližším pom.xml")
void should_declarePortlet_whenPortletXmlFound()
@Test @DisplayName("Endpoint skladá context-path, prefix triedy a cestu metódy")
void should_composePath_whenClassHasRequestMapping()   // GET /crm/blacklist/search, POST /crm/blacklist/searchAction
@Test @DisplayName("Zoznam ciest v anotácii vytvorí viac endpointov")
void should_createEachPath_whenMappingHasArray()       // {"/", "/blacklist"} → GET /crm/ a GET /crm/blacklist
@Test @DisplayName("Endpoint odkazuje na súbor s relatívnou cestou")
void should_linkFile_whenEndpointCreated()
@Test @DisplayName("Tabuľky sa načítajú so schémou, schéma a rola nie")
void should_createTables_whenCreateTableFound()        // 2 Table, žiadny uzol pre SCHEMA/ROLE
```

- [ ] **Step 2: Spusti** → FAIL. **Step 3: Implementuj** (regex: `@RequestMapping\("([^"]*)"\)` pred `class`, `@(Get|Post)Mapping\((\{[^}]*\}|"[^"]*")\)`, `CREATE TABLE\s+([\w.]+)`). **Step 4: Spusti** → PASS.

---

### Task 5: NoteExtractor, StructureExtractor a CLI `extract`/`check`

**Files:**
- Create: `extract/NoteExtractor.java`, `extract/StructureExtractor.java`, `Main.java`
- Create (vygenerovaný): `kb-graph/graph/structure.json`
- Test: `extract/NoteExtractorTest.java`, `extract/StructureExtractorTest.java` (nad skutočným repom), `MainTest.java`

**Interfaces:**
- Consumes: všetky extraktory (Task 2–4), `GraphJson`.
- Produces:
  - `NoteExtractor`: `poznamky/*.md` (bez `LOCAL-*`) → `Note {title}` (id = názov súboru, title = prvý `# ` nadpis); markdown odkazy `](NN-….md…)` → `(Note)-[:LINKS_TO]->(Note)` (bez self-loop, kotva `#…` sa odreže).
  - `StructureExtractor`: `static Graph extract(Path repoRoot)` – poradie Pom, Compose, Nginx, Portlet, Endpoint, Sql, Note; potom kontrola prahov `MIN_COUNTS = Map.of("Module",5,"Service",6,"Route",5,"Portlet",3,"Endpoint",3,"Table",1,"Note",10)` → `IllegalStateException("Extraktor našiel len N uzlov <label> (minimum M) – graf sa nezapíše")`.
  - `Main`: `java -jar kb-graph-extractor.jar <cmd> [--repo <path>]` (default `--repo` = aktuálny adresár); `extract` zapíše `kb-graph/graph/structure.json`; `check` porovná s commitnutým súborom, pri rozdiele vypíše „structure.json je zastaraný – spusti kb-graph/kb.sh extract“ a vráti exit 1; neznámy príkaz → usage + exit 64. `Main.run(String[] args, PrintStream out) : int` (testovateľné bez `System.exit`).

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Odkaz medzi poznámkami vytvorí LINKS_TO bez kotvy")
void should_linkNotes_whenMarkdownLinkFound()          // "[x](07-pasce.md#3)" → LINKS_TO a.md→07-pasce.md
@Test @DisplayName("Lokálne súbory LOCAL-* sa ignorujú")
void should_skipLocalFiles_whenPresent()
@Test @DisplayName("Skutočné repo prekročí všetky prahy")
void should_passThresholds_whenRunOnRepo()             // repo = Path.of("../..").toAbsolutePath().normalize(); Module≥5 … Note≥10
@Test @DisplayName("Skutočné repo obsahuje kľúčové uzly a hrany")
void should_containKeyFacts_whenRunOnRepo()
    // Service redis, Route /crm/, PROXIES_TO /crm/→crm-web, DEPENDS_ON crm-web→postgres, OWNS postgres→blacklist.blacklist_record,
    // Portlet blacklist, Endpoint "GET /crm/blacklist/search", Module customercare-portlets CHILD_OF legacy-parent,
    // Note 07-pasce.md; žiadny uzol, ktorého id/path obsahuje "input/"
@Test @DisplayName("Cesty používajú lomky bez ohľadu na OS")
void should_useForwardSlashes_whenRunOnAnyOs()         // žiadny File.id / Module.path neobsahuje '\\' a nezačína '/'
@Test @DisplayName("Dva behy dajú identický JSON")
void should_beDeterministic_whenRunTwice()
@Test @DisplayName("Malý graf neprejde prahom")
void should_fail_whenBelowThreshold()                  // repo = fixtures adresár s 1 pom.xml → IllegalStateException s "minimum"
@Test @DisplayName("check vráti 1, keď sa structure.json líši")
void should_returnOne_whenStructureStale()            // temp kópia mini-repa so zmeneným structure.json
@Test @DisplayName("Neznámy príkaz vráti 64 a vypíše použitie")
void should_return64_whenUnknownCommand()
```

- [ ] **Step 2: Spusti** → FAIL. **Step 3: Implementuj.** Pre `should_fail_whenBelowThreshold`/`check` test daj `StructureExtractor` package-private variant s vlastnými prahmi (`extract(Path, Map<String,Integer>)`).
- [ ] **Step 4: Spusti** `mvn -q -B -f kb-graph/extractor/pom.xml test` → PASS.
- [ ] **Step 5: Vygeneruj** `mvn -q -B -f kb-graph/extractor/pom.xml package -DskipTests && java -jar kb-graph/extractor/target/kb-graph-extractor.jar extract` → súbor `kb-graph/graph/structure.json` existuje; prehliadni ho (moduly 9, služby 8, portlety 3, endpointy 13, tabuľky 3) a že neobsahuje nič z `input/`.

---

### Task 6: Lekcie (JSON) a overenie kotiev

**Files:**
- Create: `kb-graph/lessons/topics.json`, `kb-graph/lessons/pitfalls.json`, `kb-graph/lessons/concepts.json`
- Create: `lessons/LessonReader.java`, `lessons/AnchorValidator.java`
- Test: `lessons/{LessonReaderTest,AnchorValidatorTest,RealLessonsTest}.java`

**Interfaces:**
- Consumes: `Graph`, `GraphBuilder`, `NodeRef`, `GraphJson`, `StructureExtractor` (len v `RealLessonsTest`).
- Produces:
  - `LessonReader`: `static Graph read(Path lessonsDir)` – uzly `Topic {title}`, `Lesson {title, text, source}`, `Concept {name, description, side}`; hrany `ABOUT`, `ANCHORED_TO`, `REPLACED_BY`, `SEEN_IN`, `DESCRIBED_IN`.
  - `AnchorValidator`: `static List<String> missing(Graph combined)` – pre každú hranu, ktorej `to` alebo `from` uzol v grafe nie je, vráti text `"<from.id> -[<type>]-> <label>:<id>"`; prázdny zoznam = OK. (Spec chce „jeden dávkový dotaz“; overenie v pamäti nad zlúčeným grafom je ekvivalentné a beží **pred** zmazaním DB – rozhodnutie plánu.)
- Formát súborov:
  - `topics.json`: `[{"id":"session","title":"Session a stav"}, …]` – témy: `session`, `redis`, `cookies`, `url-routing`, `security`, `jsp-tags`, `mvc-phases`, `transactions`, `liferay-runtime`, `db`, `testing`, `java-platform`, `tooling`, `target-runtime`.
  - `pitfalls.json`: `[{"id":"pitfall-01","title":"…","text":"…","topics":["mvc-phases"],"anchors":[{"label":"Endpoint","id":"POST /crm/blacklist/createAction"}],"note":"07-pasce.md"}]` – všetky pasce z `poznamky/07-pasce.md` (k 2026-10-21 ich je 32; číslo = poradie), `text` 1–2 vety česky vlastnými slovami, `source` = `poznamky/07-pasce.md#NN` (loader doplní). Kotvy len na existujúce štruktúrne uzly; príklady: 12/21 → `Service nginx`, `Route /crm/`; 19 → `Service redis`; 22 → `Service sso-mock`; 26 → `Route /web/crm/blacklist`; 11/14/16/18 → `Service liferay`, `Module customercare-portlets`; 24 → `Module crm-web`; 27 → `Table blacklist.blacklist_record`; 28 → `Portlet dashboard`; 29 → `Module frontend-base-customization`; 30 → `Module crm-web`, `Service liferay`; 31 → `Module customercare-portlets`, `Service redis`; 32 → `Service postgres` (téma `tooling`). Pasca bez obdoby v demu (napr. 10) má len `topics` a `note`.
  - `concepts.json`: `[{"id":"render-mapping","name":"@RenderMapping","description":"…","side":"legacy","replacedBy":["get-mapping"],"seenIn":[{"label":"Portlet","id":"blacklist"}],"note":"05-mapa-konceptov.md"}, {"id":"get-mapping","name":"@GetMapping","side":"target","seenIn":[{"label":"Endpoint","id":"GET /crm/blacklist/search"}]}]` – každý riadok tabuľky `poznamky/05` = legacy koncept + jeho náhrady; aspoň 3 legacy koncepty bez `seenIn` (pre dotaz 9, napr. Service Builder, Liferay CMS, Friendly URL mapper).

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Lekcia s témou a kotvou vytvorí ABOUT, ANCHORED_TO a DESCRIBED_IN")
void should_createLessonEdges_whenPitfallRead()       // fixture lessons dir
@Test @DisplayName("Koncept s náhradou vytvorí REPLACED_BY")
void should_createReplacedBy_whenConceptHasReplacement()
@Test @DisplayName("Neexistujúca kotva sa nahlási s presným popisom")
void should_reportMissing_whenAnchorUnknown()         // kotva Service:neexistuje → ["pitfall-01 -[ANCHORED_TO]-> Service:neexistuje"]
@Test @DisplayName("Všetky chýbajúce kotvy sa nahlásia naraz")
void should_reportAll_whenSeveralMissing()            // 2 zlé kotvy → size 2
@Test @DisplayName("Prázdny adresár lekcií vráti prázdny graf")
void should_returnEmpty_whenNoLessonFiles()
@Test @DisplayName("Skutočné lekcie sedia na skutočnú štruktúru")
void should_haveNoMissingAnchors_whenRealLessonsLoaded() // missing(structure.merge(lessons)).isEmpty(); count("Lesson") == počet riadkov v poznamky/07-pasce.md zodpovedajúcich ^\d+\. (dnes 32); každá Lesson má ≥1 ABOUT
```

- [ ] **Step 2: Spusti** → FAIL. **Step 3: Implementuj** reader + validator, potom napíš tri JSON súbory (čítaj `poznamky/05` a `07`; nič z `input/`). Uzly `Note` pre `DESCRIBED_IN` pochádzajú zo štruktúry (NoteExtractor).
- [ ] **Step 4: Spusti** → PASS.

---

### Task 7: Neo4j v Dockeri, loader a `kb.sh`

**Files:**
- Create: `kb-graph/docker-compose.yml`, `kb-graph/kb.sh`, `kb-graph/kb.ps1`
- Create: `load/Neo4jLoader.java`; Modify: `Main.java` (príkaz `load`)
- Test: `load/Neo4jLoaderIT.java` → v surefire (pomenuj `Neo4jLoaderTest`, Testcontainers `neo4j:5.26-community`)

**Interfaces:**
- Consumes: `StructureExtractor.extract`, `LessonReader.read`, `AnchorValidator.missing`, `Graph`.
- Produces:
  - `Neo4jLoader(Driver driver)`: `LoadResult load(Graph g)` – v jednej transakcii `MATCH (n) DETACH DELETE n`, potom pre každý label `UNWIND $rows AS r CREATE (n:<Label> {id:r.id}) SET n += r.props`, pre každý typ hrany `UNWIND $rows AS r MATCH (a:<FromLabel> {id:r.from}),(b:<ToLabel> {id:r.to}) CREATE (a)-[:<TYPE>]->(b)` (zoskupené podľa trojice); po commite `CREATE INDEX … IF NOT EXISTS FOR (n:<Label>) ON (n.id)` pre každý label. `record LoadResult(long nodes, long relationships)` z `MATCH (n) RETURN count(n)` a `MATCH ()-[r]->() RETURN count(r)`.
  - `Main load [--repo] [--uri bolt://localhost:7687] [--user neo4j] [--password kbdemo123]`: extract → zapíše structure.json → read lessons → `missing` neprázdny ⇒ vypíše „Neplatné kotvy v lekciách:“ + zoznam, exit 1, **DB sa nedotkne** → load → vypíše „Nahraté: N uzlov, M vzťahov“. Nedostupné Neo4j (`ServiceUnavailableException`, `AuthenticationException`) ⇒ „Neo4j nedostupné na <uri> – spusti kb-graph/kb.sh up“, exit 2.
- `docker-compose.yml`: `name: kb-graph`; služba `neo4j` image `neo4j:5.26-community`, `NEO4J_AUTH=neo4j/kbdemo123`, porty `127.0.0.1:7474:7474`, `127.0.0.1:7687:7687`, healthcheck `wget -qO- http://localhost:7474 || exit 1` (interval 3s, retries 40), bez volume (jednorazová DB).
- `kb.sh` (bash, `set -euo pipefail`, cd do koreňa repa): `up` = `docker compose -f kb-graph/docker-compose.yml up -d --wait` + vypíše `http://localhost:7474`; `extract|check|load` = `mvn -q -B -f kb-graph/extractor/pom.xml package -DskipTests` (ak jar neexistuje alebo je starší ako `src/`) + `java -jar … <cmd> --repo .`; `down` = `docker compose … down`. Žiadne `docker compose exec` s absolútnou cestou v argumente (Git Bash ju prepíše – `poznamky/07` bod 32); nič také nie je potrebné. `kb.ps1` – rovnaké príkazy v PowerShell 5.1.

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Nahratie vytvorí uzly s labelom a vzťahy")
void should_createNodesAndRelationships_whenLoaded()   // malý graf 3 uzly/2 hrany → LoadResult(3,2); MATCH (:Service {id:'redis'}) count 1
@Test @DisplayName("Opakované nahratie nezdvojí graf")
void should_notDuplicate_whenLoadedTwice()            // 2× load → LoadResult(3,2)
@Test @DisplayName("Nahratie zmaže uzly, ktoré v JSON nie sú")
void should_removeStaleNodes_whenGraphShrinks()        // load(g3) potom load(g2) → 2 uzly
@Test @DisplayName("Neplatná kotva zastaví load a DB ostane nezmenená")
void should_keepDatabase_whenAnchorsInvalid()          // Main.run("load", --repo temp mini-repo s chybnou kotvou) → 1; počet uzlov v DB pred == po
@Test @DisplayName("Nedostupné Neo4j vráti 2 so zrozumiteľnou hláškou")
void should_return2_whenNeo4jUnavailable()             // --uri bolt://localhost:1 → 2, výstup obsahuje "kb.sh up"
```

- [ ] **Step 2: Spusti** `mvn -q -B -f kb-graph/extractor/pom.xml test -Dtest=Neo4jLoaderTest` → FAIL. **Step 3: Implementuj.** **Step 4: Spusti** → PASS.
- [ ] **Step 5: Ručne** `bash kb-graph/kb.sh up && bash kb-graph/kb.sh load` → „Nahraté: … uzlov, … vzťahov“; v Browseri `MATCH (n) RETURN labels(n)[0] AS l, count(*) ORDER BY l` vráti všetky labely zo spec §5.

---

### Task 8: Uložené dotazy a klikací guide

**Files:**
- Create: `kb-graph/queries/01-uzol-a-susedia.cypher` … `11-sql-vs-cypher.cypher`
- Create: `guide/GuideGenerator.java`; Modify: `Main.java` (príkaz `guide`), `kb.sh`/`kb.ps1` (`guide`; `load` na konci spustí aj `guide`)
- Create: `kb-graph/guide/nginx.conf`, vygenerovaný `kb-graph/guide/kb.html`; Modify: `kb-graph/docker-compose.yml`
- Test: `guide/GuideGeneratorTest.java`, `QueriesTest.java` (Testcontainers, nahrá skutočný graf)

**Interfaces:**
- Consumes: `Neo4jLoader`, `StructureExtractor`, `LessonReader`.
- Produces:
  - Formát `.cypher`: hlavička z riadkov `// title: …`, `// teaches: …` (Cypher pojem), `// ` ďalšie riadky = text slajdu (česky, čo uvidíš v grafe); zvyšok = jeden dotaz. Súbor 11 obsahuje Cypher z dotazu 4 a v komentári ekvivalentné rekurzívne SQL CTE (zobrazí sa ako nespúšťateľný blok).
  - `GuideGenerator`: `static String render(List<Path> cypherFiles)` – HTML Neo4j Browser guide:
    ```html
    <article class="guide"><carousel class="deck container-fluid">
      <slide class="row-fluid"><div class="col-sm-12">
        <h3>{title}</h3><p>{text}</p><p><em>Učí: {teaches}</em></p>
        <pre class="pre-scrollable code runnable">{query}</pre>
      </div></slide> …
    </carousel></article>
    ```
    úvodný slajd (čo je guide, ako klikať) + jeden slajd na súbor, HTML-escapované; `Main guide` zapíše `kb-graph/guide/kb.html`.
  - compose: služba `guide` (`nginx:1.27-alpine`, `127.0.0.1:7475:80`, mount `./guide:/usr/share/nginx/html:ro` a `./guide/nginx.conf:/etc/nginx/conf.d/default.conf:ro`); `nginx.conf` pridá `Access-Control-Allow-Origin *`. Neo4j env: `NEO4J_browser_remote__content__hostname__allowlist=http://localhost:7475`, `NEO4J_browser_post__connect__cmd=play http://localhost:7475/kb.html`.
- Dotazy (spec §6), id uzlov podľa Task 2–6: 01 `{id:'redis'}` susedia; 02 `(:Service)-[:DEPENDS_ON]->(:Service)`; 03 `shortestPath((:Route {id:'/crm/'})<-[:ROUTES]-(:Service {id:'nginx'})…` – cesta nginx→Route `/crm/`→crm-web→postgres→`Table blacklist.blacklist_record`; 04 `(s:Service)-[:DEPENDS_ON*]->(:Service {id:'redis'})`; 05 `(m:Module)-[:DEPENDS_ON*]->(:Module {id:'sso-portal-api'})`; 06 lekcie témy `session` + kotvy; 07 lekcie ukotvené na `Module crm-web` alebo jeho `Endpoint`/`File`; 08 `REPLACED_BY` + `OPTIONAL MATCH SEEN_IN`; 09 legacy koncepty `WHERE NOT (c)-[:SEEN_IN]->()`; 10 `Note` podľa počtu prichádzajúcich `DESCRIBED_IN`/`LINKS_TO`; 11 dotaz 04 + SQL v texte.

- [ ] **Step 1: Failing testy**

```java
@Test @DisplayName("Guide obsahuje slajd pre každý dotaz")
void should_containAllQueries_whenRendered()          // render(queries/*) obsahuje každý dotaz (escapovaný) a 12 <slide
@Test @DisplayName("Špeciálne znaky v dotaze sa escapujú")
void should_escapeHtml_whenQueryHasArrows()           // "->" a "<-" → "-&gt;" a "&lt;-"
@Test @DisplayName("Commitnutý kb.html zodpovedá dotazom")
void should_matchCommittedGuide_whenRegenerated()     // render(...) equals Files.readString(kb-graph/guide/kb.html)
@Test @DisplayName("Každý uložený dotaz beží a vráti aspoň jeden riadok")
void should_returnRows_whenEachQueryRuns()            // @ParameterizedTest nad queries/*.cypher na skutočnom grafe
```

- [ ] **Step 2: Spusti** → FAIL. **Step 3: Implementuj** generátor, napíš 11 dotazov s českými textami, vygeneruj `kb.html`. **Step 4: Spusti** celé `mvn -q -B -f kb-graph/extractor/pom.xml verify` → PASS.
- [ ] **Step 5: Ručne** `bash kb-graph/kb.sh down && bash kb-graph/kb.sh up && bash kb-graph/kb.sh load`, otvor `http://localhost:7474`, prihlás `neo4j/kbdemo123` → guide sa otvorí sám; klik na dotaz 3 a 4 vykreslí graf (nie prázdny). Ak sa guide neotvorí (formát/allowlist), oprav podľa chyby v Browseri a zaznač do README.

---

### Task 9: Učebnica a prepojenie dokumentácie

**Files:**
- Create: `kb-graph/README.md`
- Modify: `poznamky/README.md` (riadok do tabuľky), `README.md` (krátka sekcia), `CLAUDE.md` (podprojekt 5 v Status, príkazy v Build & run, `kb-graph/` v Output layout)

**Interfaces:**
- Consumes: všetko vyššie (príkazy `kb.sh`, dotazy 01–11, model grafu).

- [ ] **Step 1: Napíš `kb-graph/README.md` (česky)** – sekcie podľa spec §6 posledný odstavec: grafová DB a kedy dáva zmysel; pojmy (uzol, label, vzťah, typ, smer, vlastnosť, cesta) na príklade z tohto repa; základ Cypher (`MATCH`, `WHERE`, `RETURN`, `*`, `OPTIONAL MATCH`, `count`); spustenie (`kb.sh up/load/check/down`, `.ps1`); prechádzka dotazmi 01–11 (1–2 vety ku každému, odkaz na guide); model grafu (tabuľka labelov a vzťahov, rozlíšené generované vs. ručné); princíp „JSON v gite, DB jednorazová“ a „štruktúra z kódu“ (prečo `check` patrí do CI); ako pridať lekciu a čo spraví neplatná kotva (ukážka hlášky); obmedzenia (bez symbolovej vrstvy, bez MCP); odkaz na článok kolegu (bez mena firmy).
- [ ] **Step 2: Prepoj** – `poznamky/README.md`: riadok `| [../kb-graph/README.md](../kb-graph/README.md) | znalostní báze v grafu (Neo4j): model, dotazy, jak přidat poznatek |`; root `README.md`: 3–5 viet + príkazy; `CLAUDE.md`: podprojekt 5 + `bash kb-graph/kb.sh up|load|check|down`, testy `mvn -q -B -f kb-graph/extractor/pom.xml verify` (needs Docker).
- [ ] **Step 3: Over** `mvn -q -B -f kb-graph/extractor/pom.xml verify` → PASS; `bash kb-graph/kb.sh check` → exit 0; `bash poznamky/LOCAL-anonymizacia.sh` → bez výstupu.
