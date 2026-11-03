# Migrácia portletu blacklist na Spring Boot a súbeh so starou appkou (podprojekt 2) – implementačný plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zmigrovať portlet blacklist zo starej appky (Liferay 6.2, `legacy/`) do novej Spring Boot appky `crm-web/`, spustiť obe súčasne za nginx (strangler) a na zdieľanej identite (SSO) a session (Redis) ukázať, že prechod medzi nimi funguje.

**Architecture:** `crm-web` je Spring Boot 4.1.1 WAR s JSP (kontext `/crm`). Portletové fázy sú HTTP metódy (render = GET, action = POST + redirect, resource = AJAX endpoint), vlastné tagy `portal:*`/`common:*` majú rovnaké rozhranie ako legacy. Fasáda `CrmSession` číta identitu zo SSO mocku (REST) a zdieľané kľúče zapisuje do toho istého Redis hashu `crm:session:{SSO_ID}` v rovnakom JSON tvare ako stará appka. nginx presmeruje `/web/crm/blacklist` na `/crm/blacklist/search`, dashboard ostáva v Liferay.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `-validation`, `-data-redis`), Tomcat + Jasper (JSP), Jakarta JSTL, Jackson 3 (`JsonMapper`), Spring `RestClient`, JUnit 5, MockMvc, Testcontainers (`redis:7.4-alpine`), JDK `HttpServer` ako fake SSO; Docker Compose + nginx (z podprojektu 1).

**Spec:** [`docs/superpowers/specs/2026-10-12-crm-web-migration-design.md`](../specs/2026-10-12-crm-web-migration-design.md)

## Global Constraints

- `crm-web/`: Java **21**, Spring Boot **4.1.1** (`spring-boot-starter-parent`), balenie **war**, `groupId=com.example.crm`, `artifactId=crm-web`, `version=0.1.0-SNAPSHOT`, `finalName=crm-web`, koreňový balík `com.example.crm`.
- Príkazy sa spúšťajú z koreňa repa `C:\projekty\tmp\liferay-to-spring-demo`: `mvn -q -B -f crm-web/pom.xml …`, `mvn -q -B -f legacy/pom.xml …`.
- Kontext `server.servlet.context-path=/crm`; session cookie `CRM_SESSION` s `Path=/crm` (nekoliduje s `JSESSIONID` Liferay); konfigurácia `crm.sso.api-url` (default `http://localhost:8090/sso`), `crm.sso.login-url` (default `/sso/login`), `spring.data.redis.host` (default `localhost`).
- Zdieľané kľúče a ich JSON (kontrakt so starou appkou, presne): hash `crm:session:{SSO_ID}`, TTL 1800 s; `APP_MESSAGES` = `[{"level":"INFO|ERROR","key":"…"}]`; `APP_BLACKLIST_LAST_CRITERIA` = `{"firstName","lastName","identifierValue","state"}` (reťazce alebo null); `APP_LAST_VIEWED_RECORD` = `{"id":<long>,"fullName":"…"}`. Flag starej appky `crm:flags:session-read-source` nová appka len zobrazuje.
- `SSO_ID` platí len ak zodpovedá `[A-Za-z0-9-]{1,64}`, inak „neprihlásený“ bez HTTP volania.
- Seed dáta blacklistu, operátori (`spravca` „Správca“, `citatel` „Čitateľ“) a zákazníci (`C-1001` „Ján Vzorový“, `C-1002` „Firma Ukážka s.r.o.“) sú **rovnaké ako v starej appke a SSO mocku**.
- Git: krok „Checkpoint“ je `git status --short`; commit len na pokyn používateľa, **nikdy push**.
- **`input/` je read-only a NEČÍTA sa.** Žiadne názvy klienta, dodávateľa, ľudí, interných systémov, interné URL, heslá (`poznamky/00-konvencie.md`).
- Jazyk: identifikátory po anglicky; komentáre, texty UI a dokumentácia po **slovensky**; komentáre vysvetľujú legacy ekvivalent a prečo, nie čo kód robí.
- Každá JSP stránka `crm-web` začína `<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>` a nemá vlastnú `page` direktívu.
- Testy: JUnit 5; nové testy majú názvy `should_…_when…()` a `@DisplayName` po slovensky (testy prenesené zo starého plánu si názvy ponechajú). Integračné testy s Redisom potrebujú bežiaci Docker (Testcontainers).
- Testy `@SpringBootTest` dostanú automaticky testovaciu infraštruktúru (`com.example.crm.testsupport.TestInfrastructure`: identita zo session atribútu, zdieľaná session v pamäti); reálne SSO + Redis zapína property `crm.test.fake-infrastructure=false`.

## Review Focus

1. **JSON zo starej appky (Jackson 2, Java 8)** – `null` hodnoty a neznáme polia – musí nová appka prečítať bez výnimky. Test `should_readLegacyJson_when_nullsAndUnknownFields` v Tasku 2.
2. **Ukončená SSO session (odhlásenie v portáli)** pri existujúcej cookie `SSO_ID` – nová appka musí presmerovať na login, nie zobraziť stav z Redisu. Test `should_redirectToSsoLogin_when_portalSessionEnded` v Tasku 5.
3. **Výpadok Redisu** – stránka novej appky sa vykreslí (bez hlášok a posledného záznamu), action prejde. Test `should_keepWorking_when_storeFails` v Tasku 2 a smoke krok „Redis dole“ v Tasku 12.
4. **Action URL portletu po prepnutí** (`POST /web/crm/blacklist?p_p_lifecycle=1…` zo starej záložky) – nginx musí poslať 302 na `/crm/blacklist/search` (prehliadač potom GET), nie 405 ani slučku. Smoke krok „strangler POST“ v Tasku 12.
5. **Kolízia cookies** – návšteva novej appky nesmie zmeniť `JSESSIONID` Liferay (iná cookie, iná cesta). Test `should_useOwnSessionCookie_when_sessionCreated` v Tasku 10 a smoke krok „JSESSIONID nezmenený“ v Tasku 12.

---

## Štruktúra súborov

```
crm-web/pom.xml, Dockerfile, README.md
crm-web/src/main/java/com/example/crm/
  CrmWebApplication.java, common/ClockConfig.java, NotFoundException.java
  session/  OperatorUser, CustomerContext, UiMessage, SsoIdentity, SsoUnavailableException, SsoClient,
            IdentityResolver, SsoIdentityResolver, SharedSessionStore, RedisSharedSessionStore,
            LastSearchCriteria, LastViewedRecord, SharedSessionKeys, CrmSession, SharedCrmSession, CrmSessions
  acl/      Privilege, AclService, InMemoryAclService, RequiresPrivilege, AclDeniedException
  web/      WebConfig, WebRequests, CrmSessionArgumentResolver, GlobalExceptionHandler, HomeController
  web/portlet/ LegacyPortlet, PortletRequestAttributes, LegacyPortletInterceptor, LayoutModelInterceptor
  web/tag/  PortletUrlTag, ActionUrlTag, RenderUrlTag, ResourceUrlTag, UrlParamTag, MessageTag, MessageArgTag,
            AclTagSupport, AclFilterTag, AclPageFilterTag
  blacklist/ (doména, služba, formuláre, 3 controllery)
crm-web/src/main/resources/application.properties, messages.properties, static/css/crm.css, static/js/crm.js
crm-web/src/main/webapp/WEB-INF/tld/{portal,common}.tld, WEB-INF/jsp/layout/{taglibs,header,footer}.jspf,
  error.jsp, error/{403,403-fragment,404,sso}.jsp, blacklist/*.jsp, blacklist/fragments/*.jsp
crm-web/src/test/java/com/example/crm/... (testy), testsupport/{TestInfrastructure,InMemorySharedSessionStore,
  DemoIdentities,FakeSsoServer}.java, TestSessions.java, it/WebClient.java; com/example/testsupport/ProbeController.java
legacy/… (opravy z review), docker/{docker-compose.yml,nginx/default.conf,build-and-deploy.*,smoke.sh,smoke-migration.*}
docs/MIGRACIA.md, poznamky/*, README.md, CLAUDE.md
```

---

### Task 1: Kostra `crm-web` (Spring Boot 4.1.1 WAR, kontext `/crm`, texty, statické súbory)

**Files:**
- Create: `crm-web/pom.xml`
- Create: `crm-web/src/main/java/com/example/crm/CrmWebApplication.java`
- Create: `crm-web/src/main/java/com/example/crm/common/ClockConfig.java`, `NotFoundException.java`
- Create: `crm-web/src/main/resources/application.properties`, `messages.properties`
- Create: `crm-web/src/main/resources/static/css/crm.css`, `static/js/crm.js`
- Test: `crm-web/src/test/java/com/example/crm/ApplicationSmokeTest.java`

**Interfaces:**
- Produces: bean `java.time.Clock`; `com.example.crm.common.NotFoundException(String what)` (→ HTTP 404); všetky i18n kľúče v `messages.properties` (ďalšie tasky ich len používajú); statické súbory pod `/crm/css/crm.css`, `/crm/js/crm.js`.

- [ ] **Step 1: Vytvor `crm-web/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <groupId>com.example.crm</groupId>
    <artifactId>crm-web</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <!-- WAR: JSP v Spring Boot funguje len pri WAR balení (spustiteľný cez java -jar aj nasaditeľný do kontajnera). -->
    <packaging>war</packaging>
    <name>crm-web</name>
    <description>Zmigrovaný portlet blacklist (Spring MVC + JSP), beží vedľa starej Liferay appky</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <!-- RestClient.Builder pre SSO klienta (Boot 4 je rozdelený na moduly). -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-restclient</artifactId>
        </dependency>
        <!-- Zdieľaná session so starou appkou (Redis hash crm:session:{SSO_ID}). -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <!-- Jasper = JSP kompilátor pre vstavaný Tomcat; podklady migrácie požadujú zachovať JSP. -->
        <dependency>
            <groupId>org.apache.tomcat.embed</groupId>
            <artifactId>tomcat-embed-jasper</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.servlet.jsp.jstl</groupId>
            <artifactId>jakarta.servlet.jsp.jstl-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.glassfish.web</groupId>
            <artifactId>jakarta.servlet.jsp.jstl</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>crm-web</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

> Testcontainers 2.x premenoval moduly s prefixom `testcontainers-`; verziu riadi BOM Spring Boot. Ak Maven artefakt
> `org.testcontainers:testcontainers-junit-jupiter` nenájde, použi `org.testcontainers:junit-jupiter` (1.x názov) a zapíš ruling.

- [ ] **Step 2: Napíš padajúci smoke test**

`crm-web/src/test/java/com/example/crm/ApplicationSmokeTest.java`:

```java
package com.example.crm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("Statické súbory (náhrada theme) sú pod kontextom /crm")
    void should_serveStaticAssets_when_requestedUnderContextPath() throws Exception {
        assertThat(get("/crm/css/crm.css")).contains("--accent");
        assertThat(get("/crm/js/crm.js")).contains("X-Requested-With");
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
```

- [ ] **Step 3: Spusti test, musí zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=ApplicationSmokeTest`
Expected: FAIL – „Unable to find a @SpringBootConfiguration“ (aplikačná trieda ešte neexistuje).

- [ ] **Step 4: Aplikačná trieda, spoločné triedy a konfigurácia**

`crm-web/src/main/java/com/example/crm/CrmWebApplication.java`:

```java
package com.example.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Nová appka so zmigrovaným portletom blacklist. Dedí zo {@link SpringBootServletInitializer}, aby sa ten istý WAR
 * dal nasadiť aj do externého servlet kontajnera (podklady riešia nasadenie na aplikačný server).
 */
@SpringBootApplication
public class CrmWebApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(CrmWebApplication.class, args);
    }
}
```

`crm-web/src/main/java/com/example/crm/common/ClockConfig.java`:

```java
package com.example.crm.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Hodiny ako bean – služby s „dnešným dátumom“ sa tak dajú testovať s pevným časom. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
```

`crm-web/src/main/java/com/example/crm/common/NotFoundException.java`:

```java
package com.example.crm.common;

/** Záznam neexistuje alebo nepatrí aktuálnemu kontextu. Web vrstva z neho spraví HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super("Nenájdené: " + what);
    }
}
```

`crm-web/src/main/resources/application.properties`:

```properties
spring.application.name=crm-web

# Kontext /crm: nginx smeruje /crm/ sem, /web/ do Liferay – obe appky za jednou adresou (strangler).
server.servlet.context-path=/crm
# Vlastná session cookie s cestou /crm – za jednou adresou by JSESSIONID prepísal session Liferay (pasca č. 12).
server.servlet.session.cookie.name=CRM_SESSION
server.servlet.session.cookie.path=/crm
server.servlet.session.tracking-modes=cookie
server.error.whitelabel.enabled=false

# JSP views – rovnaké umiestnenie ako v legacy portlet WARe (/WEB-INF/jsp/...)
spring.mvc.view.prefix=/WEB-INF/jsp/
spring.mvc.view.suffix=.jsp

spring.messages.basename=messages
spring.messages.encoding=UTF-8
spring.messages.fallback-to-system-locale=false

# Identita zo SSO portálu (mock) a zdieľaná session v Redise – v Dockeri prepísané premennými prostredia.
crm.sso.api-url=http://localhost:8090/sso
crm.sso.login-url=/sso/login
spring.data.redis.host=localhost
spring.data.redis.port=6379
spring.data.redis.timeout=1s
```

`crm-web/src/main/resources/messages.properties` (všetky texty – ďalšie tasky ich len používajú):

```properties
app.name=CRM – nová appka (Spring Boot)
nav.blacklist=Blacklist ne-zákazníkov
header.customer=Zákazník
header.logout=Odhlásiť


common.any=— ľubovoľný —
common.search=Hľadať
common.detail=Detail
common.save=Uložiť
common.remove=Odstrániť
common.back=Späť
common.unlimited=neobmedzene

validation.required=Povinné pole.
validation.past=Dátum musí byť v minulosti.
validation.maxLength=Text je príliš dlhý.

acl.pageDenied=Na zobrazenie tejto časti nemáte privilégium {0}.
error.403.title=Prístup zamietnutý
error.403.text=Chýba privilégium:
error.404.title=Nenájdené
error.404.text=Požadovaný záznam neexistuje alebo nepatrí aktuálnemu zákazníkovi.
error.generic.title=Chyba

blacklist.search.title=Blacklist ne-zákazníkov – vyhľadávanie
blacklist.search.clear=Nové vyhľadávanie
blacklist.search.noCriteria=Zadajte aspoň jedno kritérium.
blacklist.search.noResult=Nenašiel sa žiadny záznam.
blacklist.result.title=Výsledky vyhľadávania
blacklist.result.backToSearch=Upraviť kritériá
blacklist.field.firstName=Meno
blacklist.field.lastName=Priezvisko
blacklist.field.birthDate=Dátum narodenia
blacklist.field.identifier=Identifikátor
blacklist.field.identifierType=Typ identifikátora
blacklist.field.state=Stav
blacklist.field.stateReason=Dôvod
blacklist.field.conditionType=Podmienka
blacklist.field.validFrom=Platné od
blacklist.field.validTo=Platné do
blacklist.state.ACTIVE=Aktívny
blacklist.state.INACTIVE=Neaktívny
blacklist.reason.FRAUD=Podvod
blacklist.reason.DEBT=Neplatenie
blacklist.reason.ABUSE=Zneužitie služieb
blacklist.condition.BLOCK_ALL=Zablokovať všetky objednávky
blacklist.condition.REQUIRE_DEPOSIT=Vyžadovať zálohu
blacklist.condition.MANUAL_APPROVAL=Manuálne schválenie
blacklist.identType.BIRTH_CODE=Rodné číslo
blacklist.identType.ID_CARD=Občiansky preukaz
blacklist.identType.PASSPORT=Pas
blacklist.identType.COMPANY_REG_NO=IČO
blacklist.detail.title=Detail záznamu
blacklist.detail.identifiers=Identifikátory
blacklist.detail.change=Zmena stavu a platnosti
blacklist.detail.deactivateHint=Deaktivácia = stav Neaktívny; ak nevyplníte „Platné do“, doplní sa dnešný dátum.
blacklist.detail.changed=Záznam bol zmenený.
blacklist.detail.notes=Poznámky
blacklist.detail.addNote=Pridať poznámku
blacklist.detail.noteAdded=Poznámka bola pridaná.
blacklist.detail.lastIdentifier=Posledný identifikátor nie je možné odstrániť.
blacklist.create.title=Nový záznam
blacklist.create.person=Osoba
blacklist.create.identifier=Identifikátor
blacklist.create.classification=Zaradenie
blacklist.create.created=Záznam bol vytvorený.
blacklist.create.periodInvalid=„Platné do“ nesmie byť pred „Platné od“.
blacklist.create.identifierInvalid=Identifikátor nemá správny formát pre zvolený typ.
blacklist.create.conditionNotAllowed=Táto podmienka nie je pre zvolený dôvod povolená.



# Hlavička novej appky (náhrada portletu header) a navigácia do Liferay
nav.dashboard=Dashboard (Liferay)
header.operator=Operátor
header.lastViewed=Naposledy otvorený záznam
header.changeCustomer=Zmeniť zákazníka
header.loginRequired=Na prácu s CRM sa prihláste cez SSO portál.
header.sessionDebug=Zdieľaná session
header.readSource=zdroj čítania
header.newApp=Redis (nová appka)
header.legacyFlag=flag starej appky
error.sso=SSO portál je nedostupný. Skúste to o chvíľu znova.

# Hlášky, ktoré môže do zdieľanej session zapísať stará appka (zobrazí ich hlavička novej appky)
dashboard.deactivation.submitted=Žiadosť o zrušenie bola prijatá.
blacklist.detail.noteEmpty=Poznámka nesmie byť prázdna.
```

`crm-web/src/main/resources/static/css/crm.css`:

```css
/* Neutrálny vzhľad dema – náhrada Liferay theme (bez brandingu klienta). */
:root { --fg:#1d2433; --muted:#5b6475; --accent:#1f5fbf; --bg:#f4f6fa; --panel:#fff; --line:#dde2ea; --error:#b3261e; --ok:#1e7d32; }
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; color: var(--fg); background: var(--bg); }
.app-header { display: flex; gap: 1.5rem; align-items: center; padding: .75rem 1.5rem; background: var(--accent); color: #fff; }
.app-header a { color: #fff; }
.app-header nav { display: flex; gap: 1rem; flex: 1; }
.app-header .who { font-size: .9rem; }
main { max-width: 960px; margin: 1.5rem auto; padding: 0 1rem; }
.panel { background: var(--panel); padding: 1rem 1.25rem; border-radius: 6px; border: 1px solid var(--line); margin-bottom: 1rem; }
label { display: block; margin: .5rem 0; }
input, select, textarea { font: inherit; padding: .3rem .4rem; }
fieldset { border: 1px solid var(--line); margin-bottom: 1rem; }
button, .button { font: inherit; padding: .4rem .9rem; border-radius: 4px; border: 1px solid var(--accent); background: #fff; color: var(--accent); cursor: pointer; text-decoration: none; }
button.primary { background: var(--accent); color: #fff; }
button.link { border: none; background: none; text-decoration: underline; padding: 0 .3rem; }
form.inline { display: inline; }
.actions { display: flex; gap: 1rem; align-items: center; margin-top: 1rem; }
.msg { padding: .6rem 1rem; border-radius: 4px; margin-bottom: 1rem; }
.msg-INFO { background: #e6f4ea; color: var(--ok); }
.msg-ERROR, .acl-denied { background: #fdecea; color: var(--error); padding: .6rem 1rem; }
.field-error { color: var(--error); font-size: .9rem; }
table.grid { width: 100%; border-collapse: collapse; background: var(--panel); }
table.grid th, table.grid td { text-align: left; padding: .5rem; border-bottom: 1px solid var(--line); }
.badge { font-size: .8rem; background: #e8eefc; color: var(--accent); padding: .1rem .4rem; border-radius: 3px; }
.hint { color: var(--muted); }
dl.panel { display: grid; grid-template-columns: 12rem 1fr; gap: .4rem 1rem; }
dl.panel dt { font-weight: 600; }
dl.panel dd { margin: 0; }
ul.plain { list-style: none; padding: 0; }
ul.plain li { padding: .4rem 0; border-bottom: 1px solid var(--line); }
/* Štruktúra ako theme starej appky (nad Bootstrapom 2 z Liferay „classic“). */
body.crm-new-app { background: #f4f6f8; }
.crm-wrapper { max-width: 1140px; margin: 0 auto; padding: 0 0 24px; background: #fff; box-shadow: 0 0 6px rgba(0, 0, 0, .08); }
.crm-brand { padding: 12px 16px; font-size: 18px; font-weight: bold; background: #33475b; color: #fff; }
.crm-nav ul { list-style: none; margin: 0; padding: 0 8px; display: flex; background: #dfe6ee; }
.crm-nav li a { display: block; padding: 10px 16px; color: #33475b; text-decoration: none; }
.crm-nav li.selected a { background: #fff; font-weight: bold; }
.crm-header { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: center; padding: 8px 16px; background: #eef2f6; border-bottom: 1px solid #c9d3dd; }
.crm-header .crm-message { flex-basis: 100%; margin: 0; }
.crm-header form { margin: 0; }
.crm-session-debug { flex-basis: 100%; font: 12px monospace; color: #555; }
#content { padding: 16px; }
```

`crm-web/src/main/resources/static/js/crm.js`:

```js
// Náhrada portletových AJAX volaní (resource fáza). Legacy JS volal resource URL portletu;
// tu volá bežný HTTP endpoint. Hlavička X-Requested-With hovorí serveru, že nechceme celú stránku
// (chyby vráti bez layoutu: 401 / 403).
const AJAX_HEADERS = { 'X-Requested-With': 'XMLHttpRequest' };

async function replaceWithFragment(url, options, targetSelector) {
  const response = await fetch(url, { ...options, headers: { ...AJAX_HEADERS, ...(options.headers || {}) } });
  if (response.status === 401) { window.location.reload(); return; }
  document.querySelector(targetSelector).outerHTML = await response.text();
}

// <button data-ajax-post="URL" data-ajax-target="#id"> – POST na resource URL, odpoveď (HTML fragment) nahradí cieľ.
document.addEventListener('click', (event) => {
  const button = event.target.closest('[data-ajax-post]');
  if (!button) return;
  event.preventDefault();
  replaceWithFragment(button.dataset.ajaxPost, { method: 'POST' }, button.dataset.ajaxTarget);
});

// <select data-ajax-refresh="URL" data-ajax-target="#id"> – pri zmene GET s hodnotou poľa, fragment nahradí cieľ.
document.addEventListener('change', (event) => {
  const field = event.target.closest('[data-ajax-refresh]');
  if (!field) return;
  const url = new URL(field.dataset.ajaxRefresh, window.location.href);
  url.searchParams.set(field.name, field.value);
  replaceWithFragment(url, { method: 'GET' }, field.dataset.ajaxTarget);
});

// <form data-earliest-date-url="URL"> – JSON endpoint vráti najskorší dátum „ku dňu“ podľa typu a dátumu podania.
document.addEventListener('change', async (event) => {
  const form = event.target.closest('form[data-earliest-date-url]');
  if (!form) return;
  const type = form.querySelector('input[name="type"]:checked');
  const notice = form.querySelector('input[name="noticeDate"]');
  if (!type || !notice.value) return;
  const url = new URL(form.dataset.earliestDateUrl, window.location.href);
  url.searchParams.set('type', type.value);
  url.searchParams.set('noticeDate', notice.value);
  const response = await fetch(url, { headers: { ...AJAX_HEADERS, Accept: 'application/json' } });
  if (!response.ok) return;
  const { earliestDate } = await response.json();
  const effective = form.querySelector('input[name="effectiveDate"]');
  effective.min = earliestDate;
  if (!effective.value || effective.value < earliestDate) effective.value = earliestDate;
});
```

- [ ] **Step 5: Spusti test, musí prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=ApplicationSmokeTest`
Expected: PASS.

- [ ] **Step 6: Checkpoint** – `git status --short` (nový `crm-web/`, `target/` ignorovaný)

---

### Task 2: Identita zo SSO a zdieľaná session v Redise (`CrmSession`)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/session/OperatorUser.java`, `CustomerContext.java`, `UiMessage.java`, `SsoIdentity.java`, `SsoUnavailableException.java`, `SsoClient.java`, `IdentityResolver.java`, `SsoIdentityResolver.java`, `SharedSessionStore.java`, `RedisSharedSessionStore.java`, `LastSearchCriteria.java`, `LastViewedRecord.java`, `SharedSessionKeys.java`, `CrmSession.java`, `SharedCrmSession.java`, `CrmSessions.java`
- Create (test helpers): `crm-web/src/test/java/com/example/crm/testsupport/InMemorySharedSessionStore.java`, `FakeSsoServer.java`
- Test: `crm-web/src/test/java/com/example/crm/session/SsoClientTest.java`, `SsoIdentityResolverTest.java`, `SharedCrmSessionTest.java`, `RedisSharedSessionStoreTest.java`

**Interfaces:**
- Produces:
  - `record OperatorUser(String login, String displayName)`, `record CustomerContext(String customerId, String displayName)`, `record UiMessage(Level level, String key)` + `enum Level { INFO, ERROR }` (všetky `Serializable`)
  - `record SsoIdentity(String ssoSessionId, OperatorUser operator, CustomerContext customer)`; `SsoIdentity.REQUEST_ATTRIBUTE`; `static Optional<SsoIdentity> current(HttpServletRequest)` (z atribútu requestu)
  - `SsoClient(RestClient.Builder, @Value crm.sso.api-url)` – `Optional<SsoIdentity> fetch(String ssoSessionId)`; 404 → empty; iné/timeout → `SsoUnavailableException`
  - `interface IdentityResolver { Optional<SsoIdentity> resolve(HttpServletRequest); String loginUrl(String returnPath); }`; `SsoIdentityResolver` (`@Component`) – cookie `SSO_ID`, validácia `[A-Za-z0-9-]{1,64}`, cache v `SsoIdentity.REQUEST_ATTRIBUTE`
  - `interface SharedSessionStore { Optional<String> getJson(String ssoId, String key); void putJson(String ssoId, String key, String json); void remove(String ssoId, String key); Optional<String> legacyReadSource(); }`; `RedisSharedSessionStore` (`@Component`, `StringRedisTemplate`)
  - `record LastSearchCriteria(String firstName, String lastName, String identifierValue, String state)` (`KEY = "BLACKLIST_LAST_CRITERIA"`), `record LastViewedRecord(long id, String fullName)` (`KEY = "LAST_VIEWED_RECORD"`)
  - `SharedSessionKeys(JsonMapper)` – `MESSAGES = "APP_MESSAGES"`, `PREFIX = "APP_"`, `Optional<JavaType> typeOf(String fullKey)`, `String toJson(Object)`, `Object fromJson(String json, JavaType)`
  - `interface CrmSession { Optional<OperatorUser> getOperator(); Optional<CustomerContext> getCustomer(); void setGlobalAttribute(String key, Serializable value); <T> Optional<T> getGlobalAttribute(String key, Class<T> type); void removeGlobalAttribute(String key); void pushInfo(String key); void pushError(String key); List<UiMessage> popMessages(); }`
  - `CrmSessions` (`@Component`) – `CrmSession of(HttpServletRequest)`, `IdentityResolver identities()`, `Optional<String> legacyReadSource()`
  - Test helpers: `InMemorySharedSessionStore` (verejné `Map<String, Map<String,String>> data()`, `failNext(boolean)`), `FakeSsoServer` (JDK `HttpServer`; `start()`, `baseUrl()`, `put(id, operatorLogin, operatorName, customerId, customerName)`, `remove(id)`, `stop()`)

- [ ] **Step 1: Test helpery**

`crm-web/src/test/java/com/example/crm/testsupport/InMemorySharedSessionStore.java`:

```java
package com.example.crm.testsupport;

import com.example.crm.session.SharedSessionStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Zdieľaná session v pamäti pre testy – rovnaký kontrakt ako Redis hash crm:session:{SSO_ID}. */
public class InMemorySharedSessionStore implements SharedSessionStore {

    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();
    private volatile String legacyFlag;
    private volatile boolean failing;

    public Map<String, Map<String, String>> data() {
        return data;
    }

    /** Simuluje výpadok Redisu – každé volanie hodí výnimku. */
    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void legacyFlag(String value) {
        this.legacyFlag = value;
    }

    @Override
    public Optional<String> getJson(String ssoId, String key) {
        check();
        return Optional.ofNullable(data.getOrDefault(ssoId, Map.of()).get(key));
    }

    @Override
    public void putJson(String ssoId, String key, String json) {
        check();
        data.computeIfAbsent(ssoId, id -> new ConcurrentHashMap<>()).put(key, json);
    }

    @Override
    public void remove(String ssoId, String key) {
        check();
        data.getOrDefault(ssoId, new ConcurrentHashMap<>()).remove(key);
    }

    @Override
    public Optional<String> legacyReadSource() {
        check();
        return Optional.ofNullable(legacyFlag);
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("Redis je nedostupný (test)");
        }
    }
}
```

`crm-web/src/test/java/com/example/crm/testsupport/FakeSsoServer.java`:

```java
package com.example.crm.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake SSO portálu s rovnakým REST kontraktom ako sso-mock: GET /sso/api/sessions/{id} → 200 JSON / 404. */
public class FakeSsoServer {

    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int forcedStatus;
    private volatile long delayMillis;
    private HttpServer server;

    public FakeSsoServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sso/api/sessions/", this::handle);
        server.start();
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/sso";
    }

    public void put(String id, String operatorLogin, String operatorName, String customerId, String customerName) {
        sessions.put(id, "{\"operator\":{\"login\":\"" + operatorLogin + "\",\"displayName\":\"" + operatorName + "\"},"
                + "\"customer\":{\"customerId\":\"" + customerId + "\",\"displayName\":\"" + customerName + "\"}}");
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    public void forceStatus(int status) {
        this.forcedStatus = status;
    }

    public void delay(long millis) {
        this.delayMillis = millis;
    }

    public int calls() {
        return calls.get();
    }

    public void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String path = exchange.getRequestURI().getPath();
        String json = sessions.get(path.substring(path.lastIndexOf('/') + 1));
        if (forcedStatus > 0 || json == null) {
            exchange.sendResponseHeaders(forcedStatus > 0 ? forcedStatus : 404, -1);
            exchange.close();
            return;
        }
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
```

- [ ] **Step 2: Napíš padajúce testy**

`crm-web/src/test/java/com/example/crm/session/SsoClientTest.java`:

```java
package com.example.crm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.crm.testsupport.FakeSsoServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SsoClientTest {

    private FakeSsoServer sso;
    private SsoClient client;

    @BeforeEach
    void start() throws Exception {
        sso = new FakeSsoServer().start();
        client = new SsoClient(RestClient.builder(), sso.baseUrl());
    }

    @AfterEach
    void stop() {
        sso.stop();
    }

    @Test
    @DisplayName("Platná SSO session vráti operátora a zákazníka (rovnaký JSON ako sso-mock)")
    void should_returnIdentity_when_portalKnowsSession() {
        sso.put("s-1", "spravca", "Správca", "C-1001", "Ján Vzorový");

        SsoIdentity identity = client.fetch("s-1").orElseThrow();

        assertThat(identity.ssoSessionId()).isEqualTo("s-1");
        assertThat(identity.operator()).isEqualTo(new OperatorUser("spravca", "Správca"));
        assertThat(identity.customer()).isEqualTo(new CustomerContext("C-1001", "Ján Vzorový"));
    }

    @Test
    @DisplayName("Neznáma session (404) je „neprihlásený“")
    void should_returnEmpty_when_portalReturns404() {
        assertThat(client.fetch("neznama")).isEmpty();
    }

    @Test
    @DisplayName("HTTP 500 aj timeout sú nedostupný portál")
    void should_throwSsoUnavailable_when_portalFailsOrIsSlow() {
        sso.forceStatus(500);
        assertThatThrownBy(() -> client.fetch("s-1")).isInstanceOf(SsoUnavailableException.class);

        sso.forceStatus(0);
        sso.put("s-1", "spravca", "Správca", "C-1001", "Ján Vzorový");
        sso.delay(3000);
        assertThatThrownBy(() -> client.fetch("s-1")).isInstanceOf(SsoUnavailableException.class);
    }
}
```

`crm-web/src/test/java/com/example/crm/session/SsoIdentityResolverTest.java`:

```java
package com.example.crm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.Cookie;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SsoIdentityResolverTest {

    private final SsoClient client = mock(SsoClient.class);
    private final SsoIdentityResolver resolver = new SsoIdentityResolver(client, "/sso/login");

    @Test
    @DisplayName("Identitu zo SSO volá raz za request, ďalej ju berie z atribútu requestu")
    void should_callSsoOnce_when_resolvedRepeatedly() {
        MockHttpServletRequest request = withCookie("s-1");
        SsoIdentity identity = new SsoIdentity("s-1", new OperatorUser("spravca", "Správca"), null);
        when(client.fetch("s-1")).thenReturn(Optional.of(identity));

        assertThat(resolver.resolve(request)).contains(identity);
        assertThat(resolver.resolve(request)).contains(identity);
        assertThat(SsoIdentity.current(request)).contains(identity);
        verify(client, times(1)).fetch("s-1");
    }

    @Test
    @DisplayName("Bez cookie alebo s poškodenou cookie je neprihlásený a SSO sa nevolá")
    void should_returnEmptyWithoutCall_when_cookieMissingOrMalformed() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isEmpty();
        assertThat(resolver.resolve(withCookie("../admin"))).isEmpty();
        assertThat(resolver.resolve(withCookie(""))).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("Ani prázdny výsledok sa v rámci requestu nepýta znova")
    void should_cacheEmptyResult_when_notLoggedIn() {
        MockHttpServletRequest request = withCookie("s-x");
        when(client.fetch("s-x")).thenReturn(Optional.empty());

        resolver.resolve(request);
        resolver.resolve(request);

        verify(client, times(1)).fetch("s-x");
    }

    @Test
    @DisplayName("Login URL vráti používateľa na pôvodnú cestu")
    void should_encodeReturnPath_when_buildingLoginUrl() {
        assertThat(resolver.loginUrl("/crm/blacklist/detail?detailId=1"))
                .isEqualTo("/sso/login?returnUrl=%2Fcrm%2Fblacklist%2Fdetail%3FdetailId%3D1");
    }

    private static MockHttpServletRequest withCookie(String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("SSO_ID", value));
        return request;
    }
}
```

`crm-web/src/test/java/com/example/crm/session/SharedCrmSessionTest.java`:

```java
package com.example.crm.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.testsupport.InMemorySharedSessionStore;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;

class SharedCrmSessionTest {

    private static final SsoIdentity ADMIN = new SsoIdentity("s-1", new OperatorUser("spravca", "Správca"),
            new CustomerContext("C-1001", "Ján Vzorový"));

    private final InMemorySharedSessionStore store = new InMemorySharedSessionStore();
    private final CrmSessions sessions = new CrmSessions(new FixedIdentity(Optional.of(ADMIN)), store,
            new SharedSessionKeys(JsonMapper.builder().build()));

    @Test
    @DisplayName("Identitu berie zo SSO (resolver), nie z HttpSession")
    void should_readIdentityFromResolver_when_asked() {
        CrmSession session = sessions.of(new MockHttpServletRequest());

        assertThat(session.getOperator()).contains(ADMIN.operator());
        assertThat(session.getCustomer()).contains(ADMIN.customer());
    }

    @Test
    @DisplayName("Hlášky idú do zdieľanej session a v ďalšom requeste sa zobrazia raz")
    void should_popMessagesOnce_when_nextRequestReadsThem() {
        sessions.of(new MockHttpServletRequest()).pushInfo("blacklist.detail.changed");

        assertThat(store.data().get("s-1")).containsEntry("APP_MESSAGES",
                "[{\"level\":\"INFO\",\"key\":\"blacklist.detail.changed\"}]");
        CrmSession next = sessions.of(new MockHttpServletRequest());
        assertThat(next.popMessages()).containsExactly(new UiMessage(UiMessage.Level.INFO, "blacklist.detail.changed"));
        assertThat(next.popMessages()).isEmpty();
    }

    @Test
    @DisplayName("Zdieľaný kľúč sa zapíše ako JSON v tvare, ktorý číta stará appka")
    void should_writeLegacyJson_when_sharedKeyStored() {
        sessions.of(new MockHttpServletRequest())
                .setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(1, "Peter Vzorka"));

        assertThat(store.data().get("s-1"))
                .containsEntry("APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Peter Vzorka\"}");
    }

    @Test
    @DisplayName("JSON zo starej appky s null a neznámymi poliami sa prečíta")
    void should_readLegacyJson_when_nullsAndUnknownFields() {
        store.putJson("s-1", "APP_BLACKLIST_LAST_CRITERIA",
                "{\"firstName\":\"\",\"lastName\":\"Vzork\",\"identifierValue\":\"\",\"state\":null,\"extra\":1}");

        Optional<LastSearchCriteria> criteria = sessions.of(new MockHttpServletRequest())
                .getGlobalAttribute(LastSearchCriteria.KEY, LastSearchCriteria.class);

        assertThat(criteria).contains(new LastSearchCriteria("", "Vzork", "", null));
    }

    @Test
    @DisplayName("Nezdieľaný kľúč ostáva v HttpSession s prefixom APP_")
    void should_keepNonSharedKeyInHttpSession_when_stored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession httpSession = new MockHttpSession();
        request.setSession(httpSession);

        sessions.of(request).setGlobalAttribute("LOCAL_ONLY", "x");

        assertThat(httpSession.getAttribute("APP_LOCAL_ONLY")).isEqualTo("x");
        assertThat(store.data()).isEmpty();
    }

    @Test
    @DisplayName("Výpadok Redisu request nezhodí: zápis sa zaloguje, čítanie je prázdne")
    void should_keepWorking_when_storeFails() {
        store.failing(true);
        CrmSession session = sessions.of(new MockHttpServletRequest());

        session.pushInfo("blacklist.detail.changed");
        session.setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(1, "Peter Vzorka"));

        assertThat(session.popMessages()).isEmpty();
        assertThat(session.getGlobalAttribute(LastViewedRecord.KEY, LastViewedRecord.class)).isEmpty();
        assertThat(sessions.legacyReadSource()).isEmpty();
    }

    @Test
    @DisplayName("Bez SSO identity sa do zdieľanej session nič nezapisuje")
    void should_skipSharedStore_when_notLoggedIn() {
        CrmSessions anonymous = new CrmSessions(new FixedIdentity(Optional.empty()), store,
                new SharedSessionKeys(JsonMapper.builder().build()));

        anonymous.of(new MockHttpServletRequest()).pushInfo("x");

        assertThat(store.data()).isEmpty();
    }

    /** Resolver s pevnou identitou (bez SSO). */
    record FixedIdentity(Optional<SsoIdentity> identity) implements IdentityResolver {

        @Override
        public Optional<SsoIdentity> resolve(jakarta.servlet.http.HttpServletRequest request) {
            return identity;
        }

        @Override
        public String loginUrl(String returnPath) {
            return "/sso/login";
        }
    }
}
```

`crm-web/src/test/java/com/example/crm/session/RedisSharedSessionStoreTest.java`:

```java
package com.example.crm.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/** Skutočný Redis (Testcontainers) – rovnaké príkazy, aké vidí stará appka cez Jedis. */
class RedisSharedSessionStoreTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisSharedSessionStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisSharedSessionStore(redis);
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("Zápis ide do hashu crm:session:{SSO_ID} s TTL 30 minút")
    void should_writeHashWithTtl_when_put() {
        store.putJson("s-1", "APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Peter Vzorka\"}");

        assertThat(redis.opsForHash().get("crm:session:s-1", "APP_LAST_VIEWED_RECORD"))
                .isEqualTo("{\"id\":1,\"fullName\":\"Peter Vzorka\"}");
        assertThat(redis.getExpire("crm:session:s-1")).isBetween(1700L, 1800L);
        assertThat(store.getJson("s-1", "APP_LAST_VIEWED_RECORD")).isPresent();
    }

    @Test
    @DisplayName("Odstránenie poľa a flag starej appky")
    void should_removeFieldAndReadLegacyFlag_when_asked() {
        store.putJson("s-2", "APP_MESSAGES", "[]");
        store.remove("s-2", "APP_MESSAGES");
        redis.opsForValue().set("crm:flags:session-read-source", "REDIS");

        assertThat(store.getJson("s-2", "APP_MESSAGES")).isEmpty();
        assertThat(store.legacyReadSource()).contains("REDIS");
    }
}
```

- [ ] **Step 3: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="SsoClientTest,SsoIdentityResolverTest,SharedCrmSessionTest,RedisSharedSessionStoreTest"`
Expected: FAIL – kompilácia: `cannot find symbol: class SsoClient` (a ďalšie).

- [ ] **Step 4: Typy identity a SSO klient**

`crm-web/src/main/java/com/example/crm/session/OperatorUser.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Operátor prihlásený v SSO portáli (legacy: PortalOperator zo sso-portal-api). */
public record OperatorUser(String login, String displayName) implements Serializable {
}
```

`crm-web/src/main/java/com/example/crm/session/CustomerContext.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Zákazník vybraný v SSO portáli – CRM ho nemení, len číta. */
public record CustomerContext(String customerId, String displayName) implements Serializable {
}
```

`crm-web/src/main/java/com/example/crm/session/UiMessage.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Hláška (i18n kľúč). JSON tvar {"level","key"} je rovnaký ako v starej appke – hláška môže prejsť medzi appkami. */
public record UiMessage(Level level, String key) implements Serializable {

    public enum Level { INFO, ERROR }
}
```

`crm-web/src/main/java/com/example/crm/session/SsoIdentity.java`:

```java
package com.example.crm.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Identita z portálovej session: ID session (kľúč zdieľanej session v Redise), operátor a zákazník. */
public record SsoIdentity(String ssoSessionId, OperatorUser operator, CustomerContext customer) {

    /** Atribút requestu s výsledkom rozpoznania (cache na jeden request; čítajú ho aj JSP tagy). */
    public static final String REQUEST_ATTRIBUTE = SsoIdentity.class.getName();

    /** Identita už rozpoznaná v tomto requeste (interceptorom alebo fasádou). */
    public static Optional<SsoIdentity> current(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof SsoIdentity identity
                ? Optional.of(identity) : Optional.empty();
    }
}
```

`crm-web/src/main/java/com/example/crm/session/SsoUnavailableException.java`:

```java
package com.example.crm.session;

/** SSO portál neodpovedá alebo odpovedá chybou – nie je to „neprihlásený“. */
public class SsoUnavailableException extends RuntimeException {

    public SsoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`crm-web/src/main/java/com/example/crm/session/SsoClient.java`:

```java
package com.example.crm.session;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * REST klient SSO portálu. Legacy na to mal „provided“ API JAR + service locator v classloaderi portálu;
 * nová appka volá ten istý endpoint priamo. Krátke timeouty – SSO sa pýta pri každom requeste.
 */
@Component
public class SsoClient {

    private final RestClient rest;

    public SsoClient(RestClient.Builder builder, @Value("${crm.sso.api-url}") String apiUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.rest = builder.baseUrl(apiUrl).requestFactory(factory).build();
    }

    /** Identita portálovej session, alebo prázdno, ak ju portál nepozná. */
    public Optional<SsoIdentity> fetch(String ssoSessionId) {
        try {
            SessionView view = rest.get().uri("/api/sessions/{id}", ssoSessionId).retrieve().body(SessionView.class);
            return Optional.ofNullable(view).map(v -> new SsoIdentity(ssoSessionId, v.operator(), v.customer()));
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw new SsoUnavailableException("SSO portál vrátil " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new SsoUnavailableException("SSO portál je nedostupný: " + e.getMessage(), e);
        }
    }

    /** Tvar odpovede sso-mocku. */
    record SessionView(OperatorUser operator, CustomerContext customer) {
    }
}
```

- [ ] **Step 5: Rozpoznanie identity**

`crm-web/src/main/java/com/example/crm/session/IdentityResolver.java`:

```java
package com.example.crm.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Kto je prihlásený – v produkcii SSO portál, v testoch náhrada (testsupport.TestInfrastructure). */
public interface IdentityResolver {

    /** Identita pre request (výsledok sa cachuje v {@link SsoIdentity#REQUEST_ATTRIBUTE}). */
    Optional<SsoIdentity> resolve(HttpServletRequest request);

    /** URL prihlásenia v SSO portáli s návratom na {@code returnPath}. */
    String loginUrl(String returnPath);
}
```

`crm-web/src/main/java/com/example/crm/session/SsoIdentityResolver.java`:

```java
package com.example.crm.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Identita podľa cookie {@code SSO_ID} – tú istú cookie číta aj stará appka, takže obe vidia toho istého operátora
 * a zákazníka. Neplatný tvar cookie = neprihlásený (hodnota ide do URL portálu aj do kľúča v Redise).
 */
@Component
public class SsoIdentityResolver implements IdentityResolver {

    static final String COOKIE = "SSO_ID";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Object NO_IDENTITY = new Object();

    private final SsoClient client;
    private final String loginUrl;

    public SsoIdentityResolver(SsoClient client, @Value("${crm.sso.login-url}") String loginUrl) {
        this.client = client;
        this.loginUrl = loginUrl;
    }

    @Override
    public Optional<SsoIdentity> resolve(HttpServletRequest request) {
        Object cached = request.getAttribute(SsoIdentity.REQUEST_ATTRIBUTE);
        if (cached == NO_IDENTITY) {
            return Optional.empty();
        }
        if (cached instanceof SsoIdentity identity) {
            return Optional.of(identity);
        }
        Optional<SsoIdentity> identity = ssoSessionId(request).flatMap(client::fetch);
        request.setAttribute(SsoIdentity.REQUEST_ATTRIBUTE, identity.isPresent() ? identity.get() : NO_IDENTITY);
        return identity;
    }

    @Override
    public String loginUrl(String returnPath) {
        return loginUrl + "?returnUrl=" + URLEncoder.encode(returnPath, StandardCharsets.UTF_8);
    }

    private static Optional<String> ssoSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && VALID_ID.matcher(v).matches())
                .findFirst();
    }
}
```

- [ ] **Step 6: Zdieľané úložisko a kľúče**

`crm-web/src/main/java/com/example/crm/session/SharedSessionStore.java`:

```java
package com.example.crm.session;

import java.util.Optional;

/** Zdieľaná session oboch appiek (Redis hash crm:session:{SSO_ID}); hodnoty sú JSON reťazce. */
public interface SharedSessionStore {

    Optional<String> getJson(String ssoId, String key);

    void putJson(String ssoId, String key, String json);

    void remove(String ssoId, String key);

    /** Flag zdroja čítania starej appky (crm:flags:session-read-source) – nová appka ho len zobrazuje. */
    Optional<String> legacyReadSource();
}
```

`crm-web/src/main/java/com/example/crm/session/RedisSharedSessionStore.java`:

```java
package com.example.crm.session;

import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis implementácia – rovnaké kľúče a TTL ako {@code RedisSessionStore} starej appky (Jedis, Java 8).
 * Kontrakt medzi appkami tvorí len názov hashu, názvy polí a JSON tvar hodnôt.
 */
@Component
public class RedisSharedSessionStore implements SharedSessionStore {

    static final String KEY_PREFIX = "crm:session:";
    static final String FLAG_KEY = "crm:flags:session-read-source";
    static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public RedisSharedSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<String> getJson(String ssoId, String key) {
        Object value = redis.opsForHash().get(KEY_PREFIX + ssoId, key);
        redis.expire(KEY_PREFIX + ssoId, TTL);
        return Optional.ofNullable((String) value);
    }

    @Override
    public void putJson(String ssoId, String key, String json) {
        redis.opsForHash().put(KEY_PREFIX + ssoId, key, json);
        redis.expire(KEY_PREFIX + ssoId, TTL);
    }

    @Override
    public void remove(String ssoId, String key) {
        redis.opsForHash().delete(KEY_PREFIX + ssoId, key);
    }

    @Override
    public Optional<String> legacyReadSource() {
        return Optional.ofNullable(redis.opsForValue().get(FLAG_KEY));
    }
}
```

`crm-web/src/main/java/com/example/crm/session/LastSearchCriteria.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Posledné kritériá vyhľadávania v blackliste – zdieľané so starou appkou (len reťazce). */
public record LastSearchCriteria(String firstName, String lastName, String identifierValue, String state)
        implements Serializable {

    /** Kľúč vo fasáde (v úložisku APP_BLACKLIST_LAST_CRITERIA). */
    public static final String KEY = "BLACKLIST_LAST_CRITERIA";
}
```

`crm-web/src/main/java/com/example/crm/session/LastViewedRecord.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Naposledy otvorený záznam blacklistu – hlavička starej aj novej appky ho ukazuje. */
public record LastViewedRecord(long id, String fullName) implements Serializable {

    /** Kľúč vo fasáde (v úložisku APP_LAST_VIEWED_RECORD). */
    public static final String KEY = "LAST_VIEWED_RECORD";
}
```

`crm-web/src/main/java/com/example/crm/session/SharedSessionKeys.java`:

```java
package com.example.crm.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Register zdieľaných kľúčov (kľúč → typ) a JSON prevod. Zdieľa sa len to, čo je tu – rovnaké kľúče ako
 * {@code SharedKeys} v starej appke. Neznáme polia sa ignorujú (stará appka môže pridať pole skôr).
 */
@Component
public class SharedSessionKeys {

    public static final String PREFIX = "APP_";
    public static final String MESSAGES = PREFIX + "MESSAGES";

    private final JsonMapper mapper;
    private final Map<String, JavaType> types;

    public SharedSessionKeys(JsonMapper mapper) {
        this.mapper = mapper.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.types = Map.of(
                MESSAGES, this.mapper.getTypeFactory().constructCollectionType(List.class, UiMessage.class),
                PREFIX + LastSearchCriteria.KEY, this.mapper.constructType(LastSearchCriteria.class),
                PREFIX + LastViewedRecord.KEY, this.mapper.constructType(LastViewedRecord.class));
    }

    /** Typ hodnoty zdieľaného kľúča (s prefixom), alebo prázdno, ak sa kľúč nezdieľa. */
    public Optional<JavaType> typeOf(String fullKey) {
        return Optional.ofNullable(types.get(fullKey));
    }

    public String toJson(Object value) {
        return mapper.writeValueAsString(value);
    }

    public Object fromJson(String json, JavaType type) {
        return mapper.readValue(json, type);
    }
}
```

- [ ] **Step 7: Fasáda**

`crm-web/src/main/java/com/example/crm/session/CrmSession.java`:

```java
package com.example.crm.session;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Session fasáda – kontrakt legacy {@code SessionManagerCustom}, nová implementácia. Identitu vlastní SSO portál
 * (setter nie je), zdieľané kľúče sú v Redise spolu so starou appkou, ostatné v HttpSession novej appky.
 */
public interface CrmSession {

    Optional<OperatorUser> getOperator();

    Optional<CustomerContext> getCustomer();

    void setGlobalAttribute(String key, Serializable value);

    <T> Optional<T> getGlobalAttribute(String key, Class<T> type);

    void removeGlobalAttribute(String key);

    void pushInfo(String messageKey);

    void pushError(String messageKey);

    /** Vráti a odstráni čakajúce hlášky – zobrazia sa práve raz (aj cez redirect, aj z druhej appky). */
    List<UiMessage> popMessages();
}
```

`crm-web/src/main/java/com/example/crm/session/SharedCrmSession.java`:

```java
package com.example.crm.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JavaType;

/**
 * Fasáda pre jeden request. Nová appka nemá „starú“ session, zdieľané kľúče preto číta vždy z Redisu
 * (flag starej appky ju netýka). Výpadok Redisu request nezhodí – zaloguje sa a fasáda sa správa ako prázdna.
 */
final class SharedCrmSession implements CrmSession {

    private static final Logger LOG = LoggerFactory.getLogger(SharedCrmSession.class);

    private final HttpServletRequest request;
    private final IdentityResolver identities;
    private final SharedSessionStore store;
    private final SharedSessionKeys keys;

    SharedCrmSession(HttpServletRequest request, IdentityResolver identities, SharedSessionStore store,
                     SharedSessionKeys keys) {
        this.request = request;
        this.identities = identities;
        this.store = store;
        this.keys = keys;
    }

    @Override
    public Optional<OperatorUser> getOperator() {
        return identities.resolve(request).map(SsoIdentity::operator);
    }

    @Override
    public Optional<CustomerContext> getCustomer() {
        return identities.resolve(request).map(SsoIdentity::customer);
    }

    @Override
    public void setGlobalAttribute(String key, Serializable value) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        if (keys.typeOf(fullKey).isPresent()) {
            writeShared(fullKey, value);
        } else {
            request.getSession(true).setAttribute(fullKey, value);
        }
    }

    @Override
    public <T> Optional<T> getGlobalAttribute(String key, Class<T> type) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        Object value = keys.typeOf(fullKey).isPresent() ? readShared(fullKey).orElse(null) : readLocal(fullKey);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public void removeGlobalAttribute(String key) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        if (keys.typeOf(fullKey).isPresent()) {
            ssoId().ifPresent(id -> guard(() -> store.remove(id, fullKey)));
        } else {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(fullKey);
            }
        }
    }

    @Override
    public void pushInfo(String messageKey) {
        push(new UiMessage(UiMessage.Level.INFO, messageKey));
    }

    @Override
    public void pushError(String messageKey) {
        push(new UiMessage(UiMessage.Level.ERROR, messageKey));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UiMessage> popMessages() {
        List<UiMessage> messages = (List<UiMessage>) readShared(SharedSessionKeys.MESSAGES).orElse(List.of());
        if (!messages.isEmpty()) {
            ssoId().ifPresent(id -> guard(() -> store.remove(id, SharedSessionKeys.MESSAGES)));
        }
        return List.copyOf(messages);
    }

    @SuppressWarnings("unchecked")
    private void push(UiMessage message) {
        List<UiMessage> messages =
                new ArrayList<>((List<UiMessage>) readShared(SharedSessionKeys.MESSAGES).orElse(List.of()));
        messages.add(message);
        // Vždy celý nový zoznam – rovnako ako stará appka (žiadna úprava „na mieste“).
        writeShared(SharedSessionKeys.MESSAGES, messages);
    }

    private Optional<Object> readShared(String fullKey) {
        Optional<String> id = ssoId();
        JavaType type = keys.typeOf(fullKey).orElseThrow();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        try {
            return store.getJson(id.get(), fullKey).map(json -> keys.fromJson(json, type));
        } catch (RuntimeException e) {
            LOG.error("Čítanie {} zo zdieľanej session zlyhalo", fullKey, e);
            return Optional.empty();
        }
    }

    private void writeShared(String fullKey, Object value) {
        ssoId().ifPresent(id -> guard(() -> store.putJson(id, fullKey, keys.toJson(value))));
    }

    private Object readLocal(String fullKey) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getAttribute(fullKey);
    }

    private Optional<String> ssoId() {
        return identities.resolve(request).map(SsoIdentity::ssoSessionId);
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.error("Zápis do zdieľanej session zlyhal", e);
        }
    }
}
```

`crm-web/src/main/java/com/example/crm/session/CrmSessions.java`:

```java
package com.example.crm.session;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Továreň fasády pre request (náhrada legacy singletonu, ktorý si request hľadal cez ThreadLocal). */
@Component
public class CrmSessions {

    private static final Logger LOG = LoggerFactory.getLogger(CrmSessions.class);

    private final IdentityResolver identities;
    private final SharedSessionStore store;
    private final SharedSessionKeys keys;

    public CrmSessions(IdentityResolver identities, SharedSessionStore store, SharedSessionKeys keys) {
        this.identities = identities;
        this.store = store;
        this.keys = keys;
    }

    public CrmSession of(HttpServletRequest request) {
        return new SharedCrmSession(request, identities, store, keys);
    }

    public IdentityResolver identities() {
        return identities;
    }

    /** Flag starej appky pre ladiaci riadok; pri výpadku Redisu prázdno. */
    public Optional<String> legacyReadSource() {
        try {
            return store.legacyReadSource();
        } catch (RuntimeException e) {
            LOG.warn("Flag starej appky sa nedá prečítať: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 8: Spusti testy, musia prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="SsoClientTest,SsoIdentityResolverTest,SharedCrmSessionTest,RedisSharedSessionStoreTest"`
Expected: PASS (3 + 4 + 7 + 2 = 16 testov; `RedisSharedSessionStoreTest` potrebuje bežiaci Docker).

> Ak Spring Boot 4.1.1 nepoužíva Jackson 3 (`tools.jackson.*`) ale Jackson 2, nahraď importy `tools.jackson.databind.*`
> za `com.fasterxml.jackson.databind.*`, `JsonMapper.rebuild()` za `copy()` a ošetri checked výnimky; zapíš ruling.

- [ ] **Step 9: Checkpoint** – `git status --short`

---

### Task 3: ACL privilégiá (náhrada vlastného ACL legacy aplikácie)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/acl/Privilege.java`
- Create: `crm-web/src/main/java/com/example/crm/acl/AclService.java`
- Create: `crm-web/src/main/java/com/example/crm/acl/InMemoryAclService.java`
- Create: `crm-web/src/main/java/com/example/crm/acl/RequiresPrivilege.java`
- Create: `crm-web/src/main/java/com/example/crm/acl/AclDeniedException.java`
- Test: `crm-web/src/test/java/com/example/crm/acl/InMemoryAclServiceTest.java`

**Interfaces:**
- Consumes: `OperatorUser` (Task 2)
- Produces:
  - `enum Privilege { BLACKLIST_VIEW, BLACKLIST_MODIFY, DEACTIVATION }`
  - `interface AclService { boolean hasPrivilege(OperatorUser, Privilege); Set<Privilege> privilegesOf(OperatorUser); }`
  - `@Service InMemoryAclService` s no-arg konštruktorom. Loginy: `citatel` → {BLACKLIST_VIEW}, `spravca` → všetky tri.
  - `@RequiresPrivilege(Privilege value)` (TYPE, METHOD, RUNTIME)
  - `AclDeniedException(Privilege)` s `getPrivilege()`

- [ ] **Step 1: Napíš padajúci test**

`crm-web/src/test/java/com/example/crm/acl/InMemoryAclServiceTest.java`:

```java
package com.example.crm.acl;

import com.example.crm.session.OperatorUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAclServiceTest {

    private final AclService acl = new InMemoryAclService();

    @Test
    void readerCanOnlyViewBlacklist() {
        OperatorUser reader = new OperatorUser("citatel", "Čitateľ");

        assertThat(acl.hasPrivilege(reader, Privilege.BLACKLIST_VIEW)).isTrue();
        assertThat(acl.hasPrivilege(reader, Privilege.BLACKLIST_MODIFY)).isFalse();
        assertThat(acl.hasPrivilege(reader, Privilege.DEACTIVATION)).isFalse();
    }

    @Test
    void adminHasAllPrivileges() {
        assertThat(acl.privilegesOf(new OperatorUser("spravca", "Správca")))
                .containsExactlyInAnyOrder(Privilege.values());
    }

    @Test
    void unknownOperatorHasNothing() {
        assertThat(acl.privilegesOf(new OperatorUser("niekto", "?"))).isEmpty();
    }

    @Test
    void deniedExceptionCarriesPrivilege() {
        assertThat(new AclDeniedException(Privilege.DEACTIVATION).getPrivilege()).isEqualTo(Privilege.DEACTIVATION);
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=InMemoryAclServiceTest`
Expected: FAIL – `cannot find symbol: class InMemoryAclService`.

- [ ] **Step 3: Implementuj**

`crm-web/src/main/java/com/example/crm/acl/Privilege.java`:

```java
package com.example.crm.acl;

/**
 * Privilégiá – názvy sa v JSP používajú ako reťazec v atribúte {@code privilegeType}, rovnako ako v legacy ACL tagoch.
 * Podklady nenavrhujú zmenu security, preto demo zachováva model „privilégium na operátora“.
 */
public enum Privilege {
    BLACKLIST_VIEW,
    BLACKLIST_MODIFY,
    DEACTIVATION
}
```

`crm-web/src/main/java/com/example/crm/acl/AclService.java`:

```java
package com.example.crm.acl;

import java.util.Set;

import com.example.crm.session.OperatorUser;

/** Port na ACL – v legacy vlastná ACL služba backendu; demo má in-memory implementáciu. */
public interface AclService {

    boolean hasPrivilege(OperatorUser operator, Privilege privilege);

    Set<Privilege> privilegesOf(OperatorUser operator);
}
```

`crm-web/src/main/java/com/example/crm/acl/InMemoryAclService.java`:

```java
package com.example.crm.acl;

import java.util.Map;
import java.util.Set;

import com.example.crm.session.OperatorUser;
import org.springframework.stereotype.Service;

/** Pevné privilégiá demo operátorov (loginy zodpovedajú SSO mocku). */
@Service
public class InMemoryAclService implements AclService {

    private final Map<String, Set<Privilege>> privilegesByLogin = Map.of(
            "citatel", Set.of(Privilege.BLACKLIST_VIEW),
            "spravca", Set.of(Privilege.BLACKLIST_VIEW, Privilege.BLACKLIST_MODIFY, Privilege.DEACTIVATION));

    @Override
    public boolean hasPrivilege(OperatorUser operator, Privilege privilege) {
        return privilegesOf(operator).contains(privilege);
    }

    @Override
    public Set<Privilege> privilegesOf(OperatorUser operator) {
        return privilegesByLogin.getOrDefault(operator.login(), Set.of());
    }
}
```

`crm-web/src/main/java/com/example/crm/acl/RequiresPrivilege.java`:

```java
package com.example.crm.acl;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Deklaratívna kontrola privilégia na controlleri alebo handler metóde (metóda má prednosť pred triedou).
 * Kontroluje ju {@code LegacyPortletInterceptor}. V legacy kontrolu robil dispatcher portletu nad ACL službou.
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPrivilege {

    Privilege value();
}
```

`crm-web/src/main/java/com/example/crm/acl/AclDeniedException.java`:

```java
package com.example.crm.acl;

/** Operátor nemá privilégium – web vrstva ju mapuje na HTTP 403. */
public class AclDeniedException extends RuntimeException {

    private final Privilege privilege;

    public AclDeniedException(Privilege privilege) {
        super("Chýba privilégium " + privilege);
        this.privilege = privilege;
    }

    public Privilege getPrivilege() {
        return privilege;
    }
}
```

- [ ] **Step 4: Spusti test, musí prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=InMemoryAclServiceTest`
Expected: PASS.

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 4: JSP tagy s rovnakým rozhraním ako legacy (`portal:*`, `common:*`)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/web/portlet/PortletRequestAttributes.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/PortletUrlTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/ActionUrlTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/RenderUrlTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/ResourceUrlTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/UrlParamTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/MessageTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/MessageArgTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/AclTagSupport.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/AclFilterTag.java`
- Create: `crm-web/src/main/java/com/example/crm/web/tag/AclPageFilterTag.java`
- Create: `crm-web/src/main/webapp/WEB-INF/tld/portal.tld`
- Create: `crm-web/src/main/webapp/WEB-INF/tld/common.tld`
- Test: `crm-web/src/test/java/com/example/crm/web/tag/TagTestSupport.java`
- Test: `crm-web/src/test/java/com/example/crm/web/tag/PortletUrlTagTest.java`
- Test: `crm-web/src/test/java/com/example/crm/web/tag/MessageTagTest.java`
- Test: `crm-web/src/test/java/com/example/crm/web/tag/AclTagsTest.java`

**Interfaces:**
- Consumes: `SsoIdentity.current`, `SsoIdentity.REQUEST_ATTRIBUTE`, `OperatorUser` (Task 2); `AclService`, `Privilege`, `InMemoryAclService` (Task 3)
- Produces:
  - `PortletRequestAttributes.BASE_PATH = "crm.portlet.basePath"` (request atribút, String bez context path, napr. `/blacklist`)
  - TLD URI `http://example.com/crm/tags/portal` (prefix `portal`): `actionURL`(var, name, escapeXml), `renderURL`(var, escapeXml, copyCurrentRenderParameters), `resourceURL`(var, id, escapeXml), `param`(name, value)
  - TLD URI `http://example.com/crm/tags/common` (prefix `common`): `message`(key, var, showMessageOnlyIfExists), `msgarg`(value), `aclFilter`(privilegeType, aclResource), `aclPageFilterTag`(privilegeType, wireframe)
  - Konvencia URL: actionURL → `{ctx}{base}/{param action | atribút name}`; renderURL → `{ctx}{base}/{param render | viewPage}`; resourceURL → `{ctx}{base}/resource/{id}`; ostatné parametre idú do query stringu (UTF-8 URL-encoded); `viewPage` sa pri actionURL zahodí. Segment musí zodpovedať `[A-Za-z0-9_-]+`, inak `JspException`.

- [ ] **Step 1: Napíš testovaciu podporu a padajúce testy**

`crm-web/src/test/java/com/example/crm/web/tag/TagTestSupport.java`:

```java
package com.example.crm.web.tag;

import java.util.Locale;

import com.example.crm.acl.InMemoryAclService;
import com.example.crm.session.OperatorUser;
import com.example.crm.session.SsoIdentity;
import com.example.crm.web.portlet.PortletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPageContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;

/** Minimálne prostredie JSP stránky pre unit testy tagov (bez servera a bez Jasperu). */
final class TagTestSupport {

    final MockServletContext servletContext = new MockServletContext();
    final StaticWebApplicationContext context = new StaticWebApplicationContext();
    final MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
    final MockHttpServletResponse response = new MockHttpServletResponse();
    final MockPageContext pageContext;

    TagTestSupport() {
        context.setServletContext(servletContext);
        context.registerSingleton("aclService", InMemoryAclService.class);
        context.getStaticMessageSource().addMessage("greeting", Locale.ENGLISH, "Ahoj {0}");
        context.getStaticMessageSource().addMessage("html", Locale.ENGLISH, "<b>tučné</b>");
        context.getStaticMessageSource().addMessage("acl.pageDenied", Locale.ENGLISH, "Chýba {0}");
        context.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, context);

        request.setContextPath("/app");
        request.setAttribute(PortletRequestAttributes.BASE_PATH, "/blacklist");
        response.setCharacterEncoding("UTF-8");
        pageContext = new MockPageContext(servletContext, request, response);
    }

    void loginAs(String login) {
        request.setAttribute(SsoIdentity.REQUEST_ATTRIBUTE, new SsoIdentity("test", new OperatorUser(login, login), null));
    }

    String output() throws Exception {
        pageContext.getOut().flush();
        return response.getContentAsString();
    }
}
```

`crm-web/src/test/java/com/example/crm/web/tag/PortletUrlTagTest.java`:

```java
package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortletUrlTagTest {

    private final TagTestSupport page = new TagTestSupport();

    @Test
    void actionUrlUsesActionParamAsPathAndDropsViewPage() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");

        render(tag, "viewPage", "search", "action", "searchAction", "x", "1");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/searchAction?x=1");
    }

    @Test
    void actionUrlFallsBackToNameAttribute() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");
        tag.setName("createAction");

        render(tag);

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/createAction");
    }

    @Test
    void renderUrlPrefersRenderOverViewPage() throws Exception {
        RenderUrlTag tag = new RenderUrlTag();
        tag.setVar("url");

        render(tag, "viewPage", "detail", "render", "detail", "detailId", "5");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/detail?detailId=5");
    }

    @Test
    void resourceUrlUsesIdUnderResourcePath() throws Exception {
        ResourceUrlTag tag = new ResourceUrlTag();
        tag.setVar("url");
        tag.setId("removeIdentAction");

        render(tag, "detailId", "1", "identId", "2");

        assertThat(page.pageContext.getAttribute("url"))
                .isEqualTo("/app/blacklist/resource/removeIdentAction?detailId=1&identId=2");
    }

    @Test
    void queryValuesAreUtf8Encoded() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");

        render(tag, "action", "a", "q", "Ján Novák&co");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/a?q=J%C3%A1n+Nov%C3%A1k%26co");
    }

    @Test
    void writesEscapedUrlWhenNoVar() throws Exception {
        render(new ActionUrlTag(), "action", "a", "p1", "1", "p2", "2");

        assertThat(page.output()).isEqualTo("/app/blacklist/a?p1=1&amp;p2=2");
    }

    @Test
    void missingSegmentFailsLoudly() {
        assertThatThrownBy(() -> render(new RenderUrlTag(), "detailId", "5"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("render");
    }

    @Test
    void unsafeSegmentIsRejected() {
        assertThatThrownBy(() -> render(new ActionUrlTag(), "action", "../admin"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("../admin");
    }

    @Test
    void missingBasePathFailsLoudly() {
        page.request.removeAttribute("crm.portlet.basePath");

        assertThatThrownBy(() -> render(new ActionUrlTag(), "action", "a"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("@LegacyPortlet");
    }

    @Test
    void paramOutsideUrlTagFails() {
        UrlParamTag param = new UrlParamTag();
        param.setPageContext(page.pageContext);
        param.setName("a");
        param.setValue("b");

        assertThatThrownBy(param::doStartTag).isInstanceOf(JspException.class);
    }

    @Test
    void reusedTagInstanceDoesNotLeakParameters() throws Exception {
        // Jasper tag handlery poolinguje a používa opakovane (napr. v c:forEach).
        RenderUrlTag tag = new RenderUrlTag();
        tag.setVar("url");
        render(tag, "render", "detail", "detailId", "1");
        render(tag, "render", "detail", "detailId", "2");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/detail?detailId=2");
    }

    private void render(PortletUrlTag tag, String... nameValuePairs) throws JspException {
        tag.setPageContext(page.pageContext);
        tag.doStartTag();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            UrlParamTag param = new UrlParamTag();
            param.setPageContext(page.pageContext);
            param.setParent(tag);
            param.setName(nameValuePairs[i]);
            param.setValue(nameValuePairs[i + 1]);
            param.doStartTag();
            param.doEndTag();
        }
        tag.doEndTag();
    }
}
```

`crm-web/src/test/java/com/example/crm/web/tag/MessageTagTest.java`:

```java
package com.example.crm.web.tag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageTagTest {

    private final TagTestSupport page = new TagTestSupport();

    @Test
    void resolvesKeyWithArgumentsFromSpringMessageSource() throws Exception {
        MessageTag tag = tag("greeting");
        tag.doStartTag();
        MessageArgTag arg = new MessageArgTag();
        arg.setPageContext(page.pageContext);
        arg.setParent(tag);
        arg.setValue("svet");
        arg.doStartTag();
        tag.doEndTag();

        assertThat(page.output()).isEqualTo("Ahoj svet");
    }

    @Test
    void escapesHtmlInMessage() throws Exception {
        MessageTag tag = tag("html");
        tag.doStartTag();
        tag.doEndTag();

        assertThat(page.output()).isEqualTo("&lt;b&gt;tučné&lt;/b&gt;");
    }

    @Test
    void missingKeyIsVisibleInsteadOfSilent() throws Exception {
        MessageTag tag = tag("neexistuje");
        tag.doStartTag();
        tag.doEndTag();

        assertThat(page.output()).isEqualTo("??neexistuje??");
    }

    @Test
    void showMessageOnlyIfExistsSuppressesMissingKey() throws Exception {
        MessageTag tag = tag("neexistuje");
        tag.setShowMessageOnlyIfExists(true);
        tag.doStartTag();
        tag.doEndTag();

        assertThat(page.output()).isEmpty();
    }

    @Test
    void varStoresUnescapedText() throws Exception {
        MessageTag tag = tag("html");
        tag.setVar("text");
        tag.doStartTag();
        tag.doEndTag();

        assertThat(page.pageContext.getAttribute("text")).isEqualTo("<b>tučné</b>");
    }

    private MessageTag tag(String key) {
        MessageTag tag = new MessageTag();
        tag.setPageContext(page.pageContext);
        tag.setKey(key);
        return tag;
    }
}
```

`crm-web/src/test/java/com/example/crm/web/tag/AclTagsTest.java`:

```java
package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AclTagsTest {

    private final TagTestSupport page = new TagTestSupport();

    @Test
    void aclFilterShowsBodyOnlyWithPrivilege() throws Exception {
        page.loginAs("spravca");
        assertThat(filter("BLACKLIST_MODIFY").doStartTag()).isEqualTo(Tag.EVAL_BODY_INCLUDE);

        page.loginAs("citatel");
        assertThat(filter("BLACKLIST_MODIFY").doStartTag()).isEqualTo(Tag.SKIP_BODY);
    }

    @Test
    void aclFilterHidesBodyWithoutOperator() throws Exception {
        assertThat(filter("BLACKLIST_VIEW").doStartTag()).isEqualTo(Tag.SKIP_BODY);
    }

    @Test
    void unknownPrivilegeFailsLoudly() {
        assertThatThrownBy(() -> filter("NEEXISTUJE").doStartTag())
                .isInstanceOf(JspException.class)
                .hasMessageContaining("NEEXISTUJE");
    }

    @Test
    void pageFilterStopsPageWithoutPrivilege() throws Exception {
        page.loginAs("citatel");
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(page.pageContext);
        tag.setPrivilegeType("BLACKLIST_MODIFY");
        tag.setWireframe("4.15.2");

        assertThat(tag.doEndTag()).isEqualTo(Tag.SKIP_PAGE);
        assertThat(page.output()).contains("Chýba BLACKLIST_MODIFY");
    }

    @Test
    void pageFilterContinuesWithPrivilege() throws Exception {
        page.loginAs("spravca");
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(page.pageContext);
        tag.setPrivilegeType("BLACKLIST_MODIFY");

        assertThat(tag.doEndTag()).isEqualTo(Tag.EVAL_PAGE);
    }

    private AclFilterTag filter(String privilege) {
        AclFilterTag tag = new AclFilterTag();
        tag.setPageContext(page.pageContext);
        tag.setPrivilegeType(privilege);
        return tag;
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="PortletUrlTagTest,MessageTagTest,AclTagsTest"`
Expected: FAIL – kompilácia: `cannot find symbol` (`ActionUrlTag`, `PortletRequestAttributes`, …).

- [ ] **Step 3: Implementuj URL tagy**

`crm-web/src/main/java/com/example/crm/web/portlet/PortletRequestAttributes.java`:

```java
package com.example.crm.web.portlet;

/** Názvy request atribútov, ktorými web vrstva odovzdáva JSP tagom kontext „portletu“. */
public final class PortletRequestAttributes {

    /**
     * Base path aktuálneho „portletu“ bez context path, napr. {@code /blacklist} alebo {@code /dashboard/C-1001}.
     * Nastavuje ho {@code LegacyPortletInterceptor} z anotácie {@code @LegacyPortlet}; čítajú ho URL tagy.
     */
    public static final String BASE_PATH = "crm.portlet.basePath";

    private PortletRequestAttributes() {
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/PortletUrlTag.java`:

```java
package com.example.crm.web.tag;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;

import com.example.crm.web.portlet.PortletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;
import org.springframework.web.util.HtmlUtils;

/**
 * Spoločný základ {@code <portal:actionURL|renderURL|resourceURL>} – ROVNAKÉ ROZHRANIE ako legacy tagy,
 * nová implementácia.
 *
 * <p>Legacy tag si od portletového kontajnera vypýtal {@code LiferayPortletURL} (URL zakódované do stránky portálu
 * s ID portletu a fázou). Tu sa z tých istých parametrov poskladá obyčajná servletová URL podľa jednoduchej
 * konvencie: {@code {contextPath}{basePath}{segment}?ostatné=parametre}. JSP sa preto nemenia.
 * Toto je jediné miesto, kde sa „portletové“ URL prekladajú – v reálnom projekte by tu bola tabuľka routovania
 * (deeplinker z podkladov), nie konvencia.
 */
public abstract class PortletUrlTag extends TagSupport {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private String var;
    private boolean escapeXml = true;

    public void setVar(String var) {
        this.var = var;
    }

    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    void addParameter(String name, String value) {
        parameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value == null ? "" : value);
    }

    @Override
    public int doStartTag() {
        // Jasper inštancie tagov opakovane používa – parametre z predchádzajúceho použitia sa musia zahodiť.
        parameters.clear();
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
        String basePath = (String) request.getAttribute(PortletRequestAttributes.BASE_PATH);
        if (basePath == null) {
            throw new JspException(tagName() + ": chýba base path portletu (request atribút "
                    + PortletRequestAttributes.BASE_PATH + "). Má controller anotáciu @LegacyPortlet?");
        }
        Map<String, List<String>> remaining = new LinkedHashMap<>(parameters);
        String url = request.getContextPath() + basePath + resolvePath(remaining) + toQueryString(remaining);
        try {
            if (var != null) {
                pageContext.setAttribute(var, url);
            } else {
                pageContext.getOut().write(escapeXml ? HtmlUtils.htmlEscape(url, "UTF-8") : url);
            }
        } catch (IOException e) {
            throw new JspException(e);
        }
        return EVAL_PAGE;
    }

    /** Vráti časť cesty za base path (napr. {@code /searchAction}) a odoberie z mapy parametre, ktoré spotreboval. */
    protected abstract String resolvePath(Map<String, List<String>> parameters) throws JspException;

    protected abstract String tagName();

    protected static String take(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.remove(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    protected String segment(String value, String what) throws JspException {
        if (value == null || value.isBlank()) {
            throw new JspException(tagName() + ": chýba " + what);
        }
        if (!SAFE_SEGMENT.matcher(value).matches()) {
            throw new JspException(tagName() + ": nepovolený segment URL '" + value + "'");
        }
        return "/" + value;
    }

    private static String toQueryString(Map<String, List<String>> parameters) {
        StringJoiner query = new StringJoiner("&", "?", "");
        query.setEmptyValue("");
        parameters.forEach((name, values) -> values.forEach(value -> query.add(encode(name) + "=" + encode(value))));
        return query.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void release() {
        super.release();
        parameters.clear();
        var = null;
        escapeXml = true;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/ActionUrlTag.java`:

```java
package com.example.crm.web.tag;

import java.util.List;
import java.util.Map;

import jakarta.servlet.jsp.JspException;

/** {@code <portal:actionURL>} – URL pre POST (portletová action fáza). Segment = parameter {@code action}. */
public class ActionUrlTag extends PortletUrlTag {

    private String name;

    public void setName(String name) {
        this.name = name;
    }

    @Override
    protected String resolvePath(Map<String, List<String>> parameters) throws JspException {
        String action = take(parameters, "action");
        // V portlete 'viewPage' vyberal controller v rámci portletu; po migrácii ho určuje už samotná cesta.
        parameters.remove("viewPage");
        return segment(action != null ? action : name, "parameter 'action' (alebo atribút name)");
    }

    @Override
    protected String tagName() {
        return "<portal:actionURL>";
    }

    @Override
    public void release() {
        super.release();
        name = null;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/RenderUrlTag.java`:

```java
package com.example.crm.web.tag;

import java.util.List;
import java.util.Map;

import jakarta.servlet.jsp.JspException;

/** {@code <portal:renderURL>} – URL pre GET (portletová render fáza). Segment = {@code render}, inak {@code viewPage}. */
public class RenderUrlTag extends PortletUrlTag {

    /**
     * Prijíma sa kvôli kompatibilite s legacy JSP, ale ignoruje sa: kopírovanie render parametrov bolo potrebné,
     * lebo stav stránky portálu žil v URL všetkých portletov. Po migrácii má každá stránka vlastnú URL.
     */
    public void setCopyCurrentRenderParameters(boolean ignored) {
        // zámerne prázdne – viď Javadoc
    }

    @Override
    protected String resolvePath(Map<String, List<String>> parameters) throws JspException {
        String render = take(parameters, "render");
        String viewPage = take(parameters, "viewPage");
        return segment(render != null ? render : viewPage, "parameter 'render' alebo 'viewPage'");
    }

    @Override
    protected String tagName() {
        return "<portal:renderURL>";
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/ResourceUrlTag.java`:

```java
package com.example.crm.web.tag;

import java.util.List;
import java.util.Map;

import jakarta.servlet.jsp.JspException;

/** {@code <portal:resourceURL id="...">} – URL pre AJAX (portletová resource fáza) pod {@code {base}/resource/{id}}. */
public class ResourceUrlTag extends PortletUrlTag {

    private String id;

    @Override
    public void setId(String id) {
        this.id = id;
    }

    @Override
    protected String resolvePath(Map<String, List<String>> parameters) throws JspException {
        return "/resource" + segment(id, "atribút id");
    }

    @Override
    protected String tagName() {
        return "<portal:resourceURL>";
    }

    @Override
    public void release() {
        super.release();
        id = null;
    }
}
```

> Pozn.: `TagSupport` už má `setId(String)`. `ResourceUrlTag` ho prepisuje, aby si hodnotu uložil do vlastného poľa.

`crm-web/src/main/java/com/example/crm/web/tag/UrlParamTag.java`:

```java
package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

/**
 * {@code <portal:param name value>} – pridá parameter rodičovskému URL tagu.
 * Legacy atribúty {@code codebook} a {@code var} demo nepodporuje (TLD ich nemá → JSP s nimi zlyhá pri kompilácii, nie potichu).
 */
public class UrlParamTag extends TagSupport {

    private String name;
    private String value;

    public void setName(String name) {
        this.name = name;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public int doStartTag() throws JspException {
        PortletUrlTag parent = (PortletUrlTag) findAncestorWithClass(this, PortletUrlTag.class);
        if (parent == null) {
            throw new JspException("<portal:param> musí byť vnorený v <portal:actionURL|renderURL|resourceURL>");
        }
        parent.addParameter(name, value);
        return SKIP_BODY;
    }
}
```

- [ ] **Step 4: Implementuj i18n a ACL tagy**

`crm-web/src/main/java/com/example/crm/web/tag/MessageTag.java`:

```java
package com.example.crm.web.tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <common:message key [var] [showMessageOnlyIfExists]>} s {@code <common:msgarg>} – rovnaké rozhranie ako legacy.
 *
 * <p>Legacy implementácia brala preklady z DB/CMS cez vlastný MessageSource. Predchádzajúci PoC tag nahradil
 * implementáciou, ktorá vypisovala kľúč – preklady tak potichu zmizli. Tu ide preklad cez Spring {@link MessageSource}
 * a chýbajúci kľúč je VIDITEĽNÝ ({@code ??kľúč??} + WARN v logu).
 */
public class MessageTag extends TagSupport {

    private static final Logger log = LoggerFactory.getLogger(MessageTag.class);

    private final List<Object> arguments = new ArrayList<>();
    private String key;
    private String var;
    private boolean showMessageOnlyIfExists;

    public void setKey(String key) {
        this.key = key;
    }

    public void setVar(String var) {
        this.var = var;
    }

    public void setShowMessageOnlyIfExists(boolean showMessageOnlyIfExists) {
        this.showMessageOnlyIfExists = showMessageOnlyIfExists;
    }

    void addArgument(Object value) {
        arguments.add(value);
    }

    @Override
    public int doStartTag() {
        arguments.clear();
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
        WebApplicationContext context =
                RequestContextUtils.findWebApplicationContext(request, pageContext.getServletContext());
        if (context == null) {
            throw new JspException("<common:message>: nenašiel sa Spring WebApplicationContext");
        }
        String text = resolve(context, RequestContextUtils.getLocale(request));
        try {
            if (var != null) {
                pageContext.setAttribute(var, text);
            } else {
                pageContext.getOut().write(HtmlUtils.htmlEscape(text, "UTF-8"));
            }
        } catch (IOException e) {
            throw new JspException(e);
        }
        return EVAL_PAGE;
    }

    private String resolve(MessageSource messageSource, Locale locale) {
        try {
            return messageSource.getMessage(key, arguments.toArray(), locale);
        } catch (NoSuchMessageException e) {
            log.warn("Chýba i18n kľúč '{}'", key);
            return showMessageOnlyIfExists ? "" : "??" + key + "??";
        }
    }

    @Override
    public void release() {
        super.release();
        arguments.clear();
        key = null;
        var = null;
        showMessageOnlyIfExists = false;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/MessageArgTag.java`:

```java
package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

/** {@code <common:msgarg value>} – argument pre rodičovský {@code <common:message>}. */
public class MessageArgTag extends TagSupport {

    private Object value;

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public int doStartTag() throws JspException {
        MessageTag parent = (MessageTag) findAncestorWithClass(this, MessageTag.class);
        if (parent == null) {
            throw new JspException("<common:msgarg> musí byť vnorený v <common:message>");
        }
        parent.addArgument(value);
        return SKIP_BODY;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/AclTagSupport.java`:

```java
package com.example.crm.web.tag;

import java.util.Optional;

import com.example.crm.acl.AclService;
import com.example.crm.acl.Privilege;
import com.example.crm.session.OperatorUser;
import com.example.crm.session.SsoIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.PageContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.support.RequestContextUtils;

/** Spoločná logika ACL tagov: aktuálny operátor zo session fasády + {@link AclService} zo Spring kontextu. */
final class AclTagSupport {

    private AclTagSupport() {
    }

    static boolean currentOperatorHas(PageContext pageContext, String privilegeType) throws JspException {
        Privilege privilege = parse(privilegeType);
        HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
        // Identitu rozpoznal interceptor (SSO) a nechal ju v requeste – tag sa SSO znova nepýta.
        Optional<OperatorUser> operator = SsoIdentity.current(request).map(SsoIdentity::operator);
        return operator.isPresent() && context(pageContext).getBean(AclService.class).hasPrivilege(operator.get(), privilege);
    }

    static String message(PageContext pageContext, String key, String argument) throws JspException {
        HttpServletRequest request = (HttpServletRequest) pageContext.getRequest();
        return context(pageContext).getMessage(key, new Object[] {argument}, "Chýba privilégium " + argument,
                RequestContextUtils.getLocale(request));
    }

    private static Privilege parse(String privilegeType) throws JspException {
        try {
            return Privilege.valueOf(privilegeType);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JspException("Neznáme privilégium: " + privilegeType);
        }
    }

    private static WebApplicationContext context(PageContext pageContext) throws JspException {
        WebApplicationContext context = RequestContextUtils.findWebApplicationContext(
                (HttpServletRequest) pageContext.getRequest(), pageContext.getServletContext());
        if (context == null) {
            throw new JspException("ACL tag: nenašiel sa Spring WebApplicationContext");
        }
        return context;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/AclFilterTag.java`:

```java
package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;

/** {@code <common:aclFilter privilegeType="...">telo</common:aclFilter>} – telo len pre operátora s privilégiom. */
public class AclFilterTag extends TagSupport {

    private String privilegeType;

    public void setPrivilegeType(String privilegeType) {
        this.privilegeType = privilegeType;
    }

    /** Legacy atribút pre jemnejšie ACL na zdroj – demo ho prijme a ignoruje. */
    public void setAclResource(String ignored) {
        // zámerne prázdne
    }

    @Override
    public int doStartTag() throws JspException {
        return AclTagSupport.currentOperatorHas(pageContext, privilegeType) ? EVAL_BODY_INCLUDE : SKIP_BODY;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/tag/AclPageFilterTag.java`:

```java
package com.example.crm.web.tag;

import java.io.IOException;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.tagext.TagSupport;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <common:aclPageFilterTag privilegeType="..." wireframe="..."/>} – bez privilégia zastaví vykresľovanie
 * zvyšku stránky (rovnako ako legacy). Je to druhá línia obrany; prvá je {@code @RequiresPrivilege} na controlleri.
 */
public class AclPageFilterTag extends TagSupport {

    private String privilegeType;

    public void setPrivilegeType(String privilegeType) {
        this.privilegeType = privilegeType;
    }

    /** V legacy odkaz na číslo obrazovky v UI návrhu – pre beh nemá význam, prijíma sa kvôli kompatibilite JSP. */
    public void setWireframe(String ignored) {
        // zámerne prázdne
    }

    @Override
    public int doEndTag() throws JspException {
        if (AclTagSupport.currentOperatorHas(pageContext, privilegeType)) {
            return EVAL_PAGE;
        }
        try {
            pageContext.getOut().write("<p class=\"acl-denied\">"
                    + HtmlUtils.htmlEscape(AclTagSupport.message(pageContext, "acl.pageDenied", privilegeType), "UTF-8") + "</p>");
        } catch (IOException e) {
            throw new JspException(e);
        }
        return SKIP_PAGE;
    }
}
```

- [ ] **Step 5: Vytvor TLD súbory**

`crm-web/src/main/webapp/WEB-INF/tld/portal.tld`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Rovnaké názvy tagov a atribútov ako legacy portal.tld; implementácia bez Liferay/Portlet API. -->
<taglib xmlns="https://jakarta.ee/xml/ns/jakartaee"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-jsptaglibrary_3_0.xsd"
        version="3.0">
    <tlib-version>1.0</tlib-version>
    <short-name>portal</short-name>
    <uri>http://example.com/crm/tags/portal</uri>

    <tag>
        <name>actionURL</name>
        <tag-class>com.example.crm.web.tag.ActionUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>var</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>name</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue><type>boolean</type></attribute>
    </tag>
    <tag>
        <name>renderURL</name>
        <tag-class>com.example.crm.web.tag.RenderUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>var</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue><type>boolean</type></attribute>
        <attribute><name>copyCurrentRenderParameters</name><required>false</required><rtexprvalue>true</rtexprvalue><type>boolean</type></attribute>
    </tag>
    <tag>
        <name>resourceURL</name>
        <tag-class>com.example.crm.web.tag.ResourceUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>var</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>id</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue><type>boolean</type></attribute>
    </tag>
    <tag>
        <name>param</name>
        <tag-class>com.example.crm.web.tag.UrlParamTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>name</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>value</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
</taglib>
```

`crm-web/src/main/webapp/WEB-INF/tld/common.tld`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Rovnaké názvy tagov a atribútov ako legacy common.tld (len podmnožina, ktorú demo potrebuje). -->
<taglib xmlns="https://jakarta.ee/xml/ns/jakartaee"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-jsptaglibrary_3_0.xsd"
        version="3.0">
    <tlib-version>1.0</tlib-version>
    <short-name>common</short-name>
    <uri>http://example.com/crm/tags/common</uri>

    <tag>
        <name>message</name>
        <tag-class>com.example.crm.web.tag.MessageTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>key</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>var</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>showMessageOnlyIfExists</name><required>false</required><rtexprvalue>true</rtexprvalue><type>boolean</type></attribute>
    </tag>
    <tag>
        <name>msgarg</name>
        <tag-class>com.example.crm.web.tag.MessageArgTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>value</name><required>true</required><rtexprvalue>true</rtexprvalue><type>java.lang.Object</type></attribute>
    </tag>
    <tag>
        <name>aclFilter</name>
        <tag-class>com.example.crm.web.tag.AclFilterTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>privilegeType</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>aclResource</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>aclPageFilterTag</name>
        <tag-class>com.example.crm.web.tag.AclPageFilterTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>privilegeType</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>wireframe</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
</taglib>
```

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="PortletUrlTagTest,MessageTagTest,AclTagsTest"`
Expected: PASS (21 testov). Ak `writesEscapedUrlWhenNoVar` vráti prázdny reťazec, `TagTestSupport.output()` nevolá `flush()`. Over, že tam je.

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 5: Web infraštruktúra – „portlet“ anotácia, SSO prihlásenie, layout s hlavičkou, chybové stránky

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/web/portlet/LegacyPortlet.java`, `LegacyPortletInterceptor.java`, `LayoutModelInterceptor.java`
- Create: `crm-web/src/main/java/com/example/crm/web/WebRequests.java`, `CrmSessionArgumentResolver.java`, `WebConfig.java`, `GlobalExceptionHandler.java`, `HomeController.java`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/layout/taglibs.jspf`, `header.jspf`, `footer.jspf`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/error.jsp`, `error/403.jsp`, `error/403-fragment.jsp`, `error/404.jsp`, `error/sso.jsp`
- Create (test support): `crm-web/src/test/java/com/example/crm/testsupport/TestInfrastructure.java`, `DemoIdentities.java`; `crm-web/src/test/java/com/example/crm/TestSessions.java`; `crm-web/src/test/java/com/example/crm/it/WebClient.java`; `crm-web/src/test/java/com/example/testsupport/ProbeController.java`
- Test: `crm-web/src/test/java/com/example/crm/web/WebInfrastructureTest.java`
- Test: `crm-web/src/test/java/com/example/crm/it/LayoutRenderingTest.java`

**Interfaces:**
- Consumes: Task 2 (`CrmSessions`, `CrmSession`, `IdentityResolver`, `SsoIdentity`, `SsoUnavailableException`, `LastViewedRecord`, `UiMessage`, `OperatorUser`, `CustomerContext`, `SharedSessionStore`, `SharedSessionKeys`), Task 3 (`AclService`, `RequiresPrivilege`, `AclDeniedException`, `Privilege`), Task 4 (`PortletRequestAttributes.BASE_PATH`, TLD URI), Task 1 (`NotFoundException`, `InMemorySharedSessionStore` z Tasku 2)
- Produces:
  - `@LegacyPortlet(String value)` na triede controllera (base path bez context path, napr. `/blacklist`)
  - Controller metódy môžu mať parameter `CrmSession` (dodá `CrmSessionArgumentResolver` cez `CrmSessions`)
  - Model každej ne-AJAX, ne-redirect stránky: `layoutOperator` (OperatorUser|null), `layoutCustomer` (CustomerContext|null), `layoutMessages` (List<UiMessage>), `layoutLastViewed` (LastViewedRecord|null), `layoutLegacyFlag` (String, „PORTLET“ ak chýba), `layoutSsoId` (String|null)
  - Správanie: `@LegacyPortlet` handler bez identity → 302 na `IdentityResolver.loginUrl(requestURI[?query])`, AJAX → 401; chýba privilégium → 403 view `error/403` (AJAX `error/403-fragment`); `NotFoundException` → 404 `error/404`; `SsoUnavailableException` → 503 `error/sso`
  - `GET /` a `GET /blacklist` → redirect `/blacklist/search`
  - `WebRequests.isAjax(HttpServletRequest)` = hlavička `X-Requested-With: XMLHttpRequest`
  - Testy: `TestSessions.admin()`, `reader()`, `loggedIn(OperatorUser, CustomerContext)` → `MockHttpSession` s atribútom `TestInfrastructure.TEST_IDENTITY`; `TestSessions.popMessages(MockHttpSession)`; `TestInfrastructure.STORE` (`InMemorySharedSessionStore`); `DemoIdentities.ADMIN`/`READER`/`PERSON`/`COMPANY`; `WebClient(int port)` (`get`, `post`, `assertNoMissingMessages`, `addCookie(name, value)`)
  - JSP konvencia: stránka = `taglibs.jspf` → `<c:set var="titleKey" .../>` → `header.jspf` → obsah → `footer.jspf`

- [ ] **Step 1: Testovacia infraštruktúra a pomôcky**

`crm-web/src/test/java/com/example/crm/testsupport/DemoIdentities.java`:

```java
package com.example.crm.testsupport;

import com.example.crm.session.CustomerContext;
import com.example.crm.session.OperatorUser;

/** Rovnakí operátori a zákazníci ako v SSO mocku (a ACL). */
public final class DemoIdentities {

    public static final OperatorUser ADMIN = new OperatorUser("spravca", "Správca");
    public static final OperatorUser READER = new OperatorUser("citatel", "Čitateľ");
    public static final CustomerContext PERSON = new CustomerContext("C-1001", "Ján Vzorový");
    public static final CustomerContext COMPANY = new CustomerContext("C-1002", "Firma Ukážka s.r.o.");

    private DemoIdentities() {
    }
}
```

`crm-web/src/test/java/com/example/crm/testsupport/TestInfrastructure.java`:

```java
package com.example.crm.testsupport;

import com.example.crm.session.IdentityResolver;
import com.example.crm.session.SharedSessionStore;
import com.example.crm.session.SsoIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Testovacia náhrada SSO a Redisu pre všetky {@code @SpringBootTest} (je v skenovanom balíku com.example.crm).
 * Identita = atribút session nastavený cez {@code TestSessions}; zdieľaná session = mapa v pamäti.
 * Integračný test so skutočným SSO a Redisom ju vypne: {@code crm.test.fake-infrastructure=false}.
 */
@Configuration
@ConditionalOnProperty(name = "crm.test.fake-infrastructure", havingValue = "true", matchIfMissing = true)
public class TestInfrastructure {

    public static final String TEST_IDENTITY = "TEST_IDENTITY";
    public static final InMemorySharedSessionStore STORE = new InMemorySharedSessionStore();

    @Bean
    @Primary
    public IdentityResolver testIdentityResolver() {
        return new SessionAttributeIdentityResolver();
    }

    @Bean
    @Primary
    public SharedSessionStore testSharedSessionStore() {
        return STORE;
    }

    /** Identita z atribútu HttpSession (MockMvc {@code .session(TestSessions.admin())}). */
    public static class SessionAttributeIdentityResolver implements IdentityResolver {

        @Override
        public Optional<SsoIdentity> resolve(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            Optional<SsoIdentity> identity = session != null && session.getAttribute(TEST_IDENTITY) instanceof SsoIdentity i
                    ? Optional.of(i) : Optional.empty();
            identity.ifPresent(i -> request.setAttribute(SsoIdentity.REQUEST_ATTRIBUTE, i));
            return identity;
        }

        @Override
        public String loginUrl(String returnPath) {
            return "/sso/login?returnUrl=" + java.net.URLEncoder.encode(returnPath, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
```

`crm-web/src/test/java/com/example/crm/TestSessions.java`:

```java
package com.example.crm;

import com.example.crm.session.CrmSessions;
import com.example.crm.session.CustomerContext;
import com.example.crm.session.OperatorUser;
import com.example.crm.session.SharedSessionKeys;
import com.example.crm.session.SsoIdentity;
import com.example.crm.session.UiMessage;
import com.example.crm.testsupport.DemoIdentities;
import com.example.crm.testsupport.TestInfrastructure;
import java.util.List;
import java.util.UUID;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import tools.jackson.databind.json.JsonMapper;

/** HTTP session „po SSO prihlásení“ pre MockMvc testy (identita podstrčená, zdieľaná session v pamäti). */
public final class TestSessions {

    private TestSessions() {
    }

    public static MockHttpSession loggedIn(OperatorUser operator, CustomerContext customer) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(TestInfrastructure.TEST_IDENTITY,
                new SsoIdentity("test-" + UUID.randomUUID(), operator, customer));
        return session;
    }

    public static MockHttpSession admin() {
        return loggedIn(DemoIdentities.ADMIN, DemoIdentities.PERSON);
    }

    public static MockHttpSession reader() {
        return loggedIn(DemoIdentities.READER, DemoIdentities.PERSON);
    }

    /** Hlášky čakajúce v zdieľanej session operátora (spotrebuje ich). */
    public static List<UiMessage> popMessages(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        CrmSessions sessions = new CrmSessions(new TestInfrastructure.SessionAttributeIdentityResolver(),
                TestInfrastructure.STORE, new SharedSessionKeys(JsonMapper.builder().build()));
        return sessions.of(request).popMessages();
    }
}
```

`crm-web/src/test/java/com/example/testsupport/ProbeController.java`:

```java
package com.example.testsupport;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.CrmSession;
import com.example.crm.web.portlet.LegacyPortlet;
import com.example.crm.web.portlet.PortletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Testovací „portlet“ na overenie interceptorov bez závislosti na skutočných obrazovkách. */
@Controller
@LegacyPortlet("/probe")
@RequestMapping("/probe")
public class ProbeController {

    @GetMapping("/open")
    @ResponseBody
    public String open(HttpServletRequest request) {
        return (String) request.getAttribute(PortletRequestAttributes.BASE_PATH);
    }

    @GetMapping("/modify")
    @ResponseBody
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String modify() {
        return "ok";
    }

    @PostMapping("/notify")
    public String notifyAndRedirect(CrmSession session) {
        session.pushInfo("blacklist.detail.changed");
        return "redirect:/probe/page";
    }

    @GetMapping("/page")
    public String page() {
        return "error/404";
    }
}
```

`crm-web/src/test/java/com/example/crm/it/WebClient.java`:

```java
package com.example.crm.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/** Prehliadač „bez JavaScriptu“ pre testy s reálnym Tomcatom: cookies + nasledovanie redirectov v rámci appky. */
public final class WebClient {

    public static final String[] AJAX = {"X-Requested-With", "XMLHttpRequest"};

    private final CookieManager cookies = new CookieManager();
    private final HttpClient http;
    private final String baseUrl;

    public WebClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Cookie pre celý host (napr. SSO_ID, ktorú by nastavil SSO portál). */
    public WebClient addCookie(String name, String value) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setPath("/");
        // Verzia 0 (Netscape) ako v prehliadači – verzia 1 by hodnotu poslala v úvodzovkách ("it-admin").
        cookie.setVersion(0);
        cookies.getCookieStore().add(URI.create(baseUrl), cookie);
        return this;
    }

    public CookieManager cookies() {
        return cookies;
    }

    public HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpResponse<String> post(String path, Map<String, String> form, String... headers) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Každý chýbajúci i18n kľúč sa vykreslí ako ??kľúč?? – stránka nesmie obsahovať ani jeden. */
    public static void assertNoMissingMessages(String body) {
        assertThat(body).doesNotContain("??");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 2: Napíš padajúce testy**

`crm-web/src/test/java/com/example/crm/web/WebInfrastructureTest.java`:

```java
package com.example.crm.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.crm.TestSessions;
import com.example.crm.acl.Privilege;
import com.example.crm.session.LastViewedRecord;
import com.example.crm.session.UiMessage;
import com.example.crm.testsupport.TestInfrastructure;
import com.example.testsupport.ProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProbeController.class)
class WebInfrastructureTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("Bez SSO identity presmeruje na SSO login s návratom na pôvodnú cestu")
    void should_redirectToSsoLogin_when_portalSessionEnded() throws Exception {
        mvc.perform(get("/probe/open").queryParam("x", "1"))
                .andExpect(redirectedUrl("/sso/login?returnUrl=%2Fprobe%2Fopen%3Fx%3D1"));
    }

    @Test
    @DisplayName("AJAX bez identity dostane 401 bez presmerovania")
    void should_return401_when_ajaxWithoutIdentity() throws Exception {
        mvc.perform(get("/probe/open").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Interceptor zverejní base path portletu pre URL tagy")
    void should_exposeBasePath_when_loggedIn() throws Exception {
        mvc.perform(get("/probe/open").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(content().string("/probe"));
    }

    @Test
    @DisplayName("Chýbajúce privilégium vykreslí 403 (AJAX bez layoutu)")
    void should_render403_when_privilegeMissing() throws Exception {
        mvc.perform(get("/probe/modify").session(TestSessions.reader()))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403.jsp"))
                .andExpect(model().attribute("privilege", Privilege.BLACKLIST_MODIFY));
        mvc.perform(get("/probe/modify").session(TestSessions.reader()).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403-fragment.jsp"));
        mvc.perform(get("/probe/modify").session(TestSessions.admin())).andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("Hláška z POST sa po redirecte zobrazí v layoute raz")
    void should_showMessageOnce_when_redirected() throws Exception {
        MockHttpSession session = TestSessions.admin();

        mvc.perform(post("/probe/notify").session(session)).andExpect(redirectedUrl("/probe/page"));
        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutMessages",
                        contains(new UiMessage(UiMessage.Level.INFO, "blacklist.detail.changed"))));
        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutMessages", empty()));
    }

    @Test
    @DisplayName("Layout dostane posledný záznam zo zdieľanej session a ladiace údaje")
    void should_exposeSharedSessionInLayout_when_pageRendered() throws Exception {
        MockHttpSession session = TestSessions.admin();
        String ssoId = ((com.example.crm.session.SsoIdentity) session.getAttribute(TestInfrastructure.TEST_IDENTITY))
                .ssoSessionId();
        TestInfrastructure.STORE.putJson(ssoId, "APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Peter Vzorka\"}");

        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutLastViewed", new LastViewedRecord(1, "Peter Vzorka")))
                .andExpect(model().attribute("layoutSsoId", ssoId))
                .andExpect(model().attribute("layoutLegacyFlag", "PORTLET"));
    }

    @Test
    @DisplayName("Koreň a /blacklist presmerujú na vyhľadávanie")
    void should_redirectToSearch_when_homeOpened() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/blacklist/search"));
        mvc.perform(get("/blacklist")).andExpect(redirectedUrl("/blacklist/search"));
    }
}
```

`crm-web/src/test/java/com/example/crm/it/LayoutRenderingTest.java`:

```java
package com.example.crm.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Reálny Tomcat + Jasper: chybová stránka s layoutom sa skompiluje a texty sú preložené. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LayoutRenderingTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("Neznáma cesta vykreslí vlastnú chybovú stránku s layoutom a navigáciou do Liferay")
    void should_renderCustomErrorPage_when_pathUnknown() throws Exception {
        HttpResponse<String> response = new WebClient(port).get("/crm/neexistuje", "Accept", "text/html");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Nenájdené").contains("/web/crm/dashboard").contains("/crm/css/crm.css");
        WebClient.assertNoMissingMessages(response.body());
    }
}
```

- [ ] **Step 3: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="WebInfrastructureTest,LayoutRenderingTest"`
Expected: FAIL – kompilácia: `cannot find symbol` (`LegacyPortlet`, …).

- [ ] **Step 4: Anotácia, interceptory, konfigurácia**

`crm-web/src/main/java/com/example/crm/web/portlet/LegacyPortlet.java`:

```java
package com.example.crm.web.portlet;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Označuje controller, ktorý nahrádza (časť) legacy portletu. V legacy mal portlet záznam v {@code portlet.xml}
 * a vlastný Spring kontext; tu je to skupina controllerov so spoločným base path – z neho skladajú URL tagy
 * {@code portal:*URL} a interceptor podľa neho vyžaduje prihláseného operátora.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LegacyPortlet {

    /** Base path bez context path, napr. {@code /blacklist}. */
    String value();
}
```

`crm-web/src/main/java/com/example/crm/web/WebRequests.java`:

```java
package com.example.crm.web;

import jakarta.servlet.http.HttpServletRequest;

/** Pomocné rozhodnutia o requeste, ktoré potrebuje viac miest web vrstvy. */
public final class WebRequests {

    private WebRequests() {
    }

    /** AJAX volanie (náhrada portletovej resource fázy) – chyby sa vracajú bez layoutu a bez redirectu. */
    public static boolean isAjax(HttpServletRequest request) {
        return "XMLHttpRequest".equals(request.getHeader("X-Requested-With"));
    }
}
```

`crm-web/src/main/java/com/example/crm/web/portlet/LegacyPortletInterceptor.java`:

```java
package com.example.crm.web.portlet;

import com.example.crm.acl.AclDeniedException;
import com.example.crm.acl.AclService;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.IdentityResolver;
import com.example.crm.session.SsoIdentity;
import com.example.crm.web.WebRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Robí to, čo v legacy {@code DispatcherPortletCustom}: (1) bez identity zo SSO nepustí ďalej – stránka ide na SSO
 * login (portlet v render fáze presmerovať nevedel, servlet áno), AJAX dostane 401; (2) zverejní base path
 * „portletu“ pre URL tagy; (3) skontroluje ACL privilégium z {@link RequiresPrivilege}.
 */
public class LegacyPortletInterceptor implements HandlerInterceptor {

    private final IdentityResolver identities;
    private final AclService aclService;

    public LegacyPortletInterceptor(IdentityResolver identities, AclService aclService) {
        this.identities = identities;
        this.aclService = aclService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        LegacyPortlet portlet = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), LegacyPortlet.class);
        if (portlet == null) {
            return true;
        }
        Optional<SsoIdentity> identity = identities.resolve(request);
        if (identity.isEmpty()) {
            if (WebRequests.isAjax(request)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                String query = request.getQueryString();
                response.sendRedirect(identities.loginUrl(request.getRequestURI() + (query == null ? "" : "?" + query)));
            }
            return false;
        }
        request.setAttribute(PortletRequestAttributes.BASE_PATH, portlet.value());
        RequiresPrivilege required = requiredPrivilege(method);
        if (required != null && !aclService.hasPrivilege(identity.get().operator(), required.value())) {
            throw new AclDeniedException(required.value());
        }
        return true;
    }

    private static RequiresPrivilege requiredPrivilege(HandlerMethod method) {
        RequiresPrivilege onMethod = method.getMethodAnnotation(RequiresPrivilege.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresPrivilege.class);
    }
}
```

`crm-web/src/main/java/com/example/crm/web/portlet/LayoutModelInterceptor.java`:

```java
package com.example.crm.web.portlet;

import com.example.crm.session.CrmSession;
import com.example.crm.session.CrmSessions;
import com.example.crm.session.LastViewedRecord;
import com.example.crm.session.SsoIdentity;
import com.example.crm.web.WebRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Dáta pre hlavičku – v Liferay ich vykresľoval portlet {@code header} vložený z theme. Hlášky sa vyberajú len pri
 * skutočnom vykreslení stránky (nie pri redirecte ani AJAX fragmente), inak by sa stratili skôr, než ich uvidíš.
 */
public class LayoutModelInterceptor implements HandlerInterceptor {

    private final CrmSessions sessions;

    public LayoutModelInterceptor(CrmSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        if (modelAndView == null || WebRequests.isAjax(request) || isRedirect(modelAndView)) {
            return;
        }
        CrmSession session = sessions.of(request);
        modelAndView.addObject("layoutOperator", session.getOperator().orElse(null));
        modelAndView.addObject("layoutCustomer", session.getCustomer().orElse(null));
        modelAndView.addObject("layoutMessages", session.popMessages());
        modelAndView.addObject("layoutLastViewed",
                session.getGlobalAttribute(LastViewedRecord.KEY, LastViewedRecord.class).orElse(null));
        modelAndView.addObject("layoutLegacyFlag", sessions.legacyReadSource().orElse("PORTLET"));
        modelAndView.addObject("layoutSsoId", SsoIdentity.current(request).map(SsoIdentity::ssoSessionId).orElse(null));
    }

    private static boolean isRedirect(ModelAndView modelAndView) {
        String viewName = modelAndView.getViewName();
        return (viewName != null && viewName.startsWith("redirect:")) || modelAndView.getView() instanceof RedirectView;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/CrmSessionArgumentResolver.java`:

```java
package com.example.crm.web;

import com.example.crm.session.CrmSession;
import com.example.crm.session.CrmSessions;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** {@code CrmSession session} ako parameter handler metódy – legacy volal {@code getSessionManager()} z base controllera. */
public class CrmSessionArgumentResolver implements HandlerMethodArgumentResolver {

    private final CrmSessions sessions;

    public CrmSessionArgumentResolver(CrmSessions sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CrmSession.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return sessions.of(webRequest.getNativeRequest(HttpServletRequest.class));
    }
}
```

`crm-web/src/main/java/com/example/crm/web/WebConfig.java`:

```java
package com.example.crm.web;

import com.example.crm.acl.AclService;
import com.example.crm.session.CrmSessions;
import com.example.crm.web.portlet.LayoutModelInterceptor;
import com.example.crm.web.portlet.LegacyPortletInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Náhrada per-portlet Spring kontextov – jedna konfigurácia; poradie: prihlásenie + ACL, potom layout. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CrmSessions sessions;
    private final AclService aclService;

    public WebConfig(CrmSessions sessions, AclService aclService) {
        this.sessions = sessions;
        this.aclService = aclService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LegacyPortletInterceptor(sessions.identities(), aclService));
        registry.addInterceptor(new LayoutModelInterceptor(sessions));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CrmSessionArgumentResolver(sessions));
    }
}
```

`crm-web/src/main/java/com/example/crm/web/GlobalExceptionHandler.java`:

```java
package com.example.crm.web;

import com.example.crm.acl.AclDeniedException;
import com.example.crm.common.NotFoundException;
import com.example.crm.session.SsoUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/** Náhrada legacy {@code SimpleMappingExceptionResolverCustom} a hooked chybových JSP portálu. */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AclDeniedException.class)
    public ModelAndView aclDenied(AclDeniedException exception, HttpServletRequest request) {
        ModelAndView modelAndView = new ModelAndView(WebRequests.isAjax(request) ? "error/403-fragment" : "error/403");
        modelAndView.addObject("privilege", exception.getPrivilege());
        modelAndView.setStatus(HttpStatus.FORBIDDEN);
        return modelAndView;
    }

    @ExceptionHandler(NotFoundException.class)
    public ModelAndView notFound(NotFoundException exception) {
        ModelAndView modelAndView = new ModelAndView("error/404");
        modelAndView.addObject("detail", exception.getMessage());
        modelAndView.setStatus(HttpStatus.NOT_FOUND);
        return modelAndView;
    }

    @ExceptionHandler(SsoUnavailableException.class)
    public ModelAndView ssoUnavailable() {
        ModelAndView modelAndView = new ModelAndView("error/sso");
        modelAndView.setStatus(HttpStatus.SERVICE_UNAVAILABLE);
        return modelAndView;
    }
}
```

`crm-web/src/main/java/com/example/crm/web/HomeController.java`:

```java
package com.example.crm.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Vstupné body: koreň appky a „stránka“ blacklistu (cieľ presmerovania z nginx) vedú na vyhľadávanie. */
@Controller
public class HomeController {

    @GetMapping({"/", "/blacklist"})
    public String home() {
        return "redirect:/blacklist/search";
    }
}
```

- [ ] **Step 5: Layout a chybové stránky**

`crm-web/src/main/webapp/WEB-INF/jsp/layout/taglibs.jspf`:

```jsp
<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="portal" uri="http://example.com/crm/tags/portal" %>
<%@ taglib prefix="common" uri="http://example.com/crm/tags/common" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/layout/header.jspf`:

```jsp
<%-- Náhrada Liferay theme + portletu „header“. Očakáva premennú titleKey. Vzhľad: rovnaké CSS ako stará appka
     (theme „classic“ z Liferay cez spoločný nginx), potom vlastné crm.css. --%>
<!DOCTYPE html>
<html lang="sk">
<head>
    <meta charset="UTF-8">
    <title><common:message key="${titleKey}"/> &ndash; <common:message key="app.name"/></title>
    <link rel="stylesheet" href="/html/themes/classic/css/aui.css?themeId=classic&amp;minifierType=css">
    <link rel="stylesheet" href="/html/themes/classic/css/main.css?themeId=classic&amp;minifierType=css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/crm.css">
    <script src="${pageContext.request.contextPath}/js/crm.js" defer></script>
</head>
<body class="crm-new-app">
<div class="container-fluid crm-wrapper">
<div class="crm-brand"><common:message key="app.name"/></div>
<nav class="crm-nav">
    <ul>
        <li class="selected"><a href="${pageContext.request.contextPath}/blacklist/search"><common:message key="nav.blacklist"/></a></li>
        <li><a href="/web/crm/dashboard"><common:message key="nav.dashboard"/></a></li>
    </ul>
</nav>
<div class="crm-header">
    <c:choose>
        <c:when test="${empty layoutOperator}">
            <span><common:message key="header.loginRequired"/></span>
        </c:when>
        <c:otherwise>
            <span id="headerOperator"><common:message key="header.operator"/>: <c:out value="${layoutOperator.displayName}"/></span>
            <span id="headerCustomer"><common:message key="header.customer"/>: <c:out value="${layoutCustomer.displayName}"/> (<c:out value="${layoutCustomer.customerId}"/>)</span>
            <c:if test="${not empty layoutLastViewed}">
                <span id="headerLastViewed"><common:message key="header.lastViewed"/>: <c:out value="${layoutLastViewed.fullName}"/></span>
            </c:if>
            <a id="changeCustomer" href="/sso/login?returnUrl=${pageContext.request.contextPath}/blacklist/search"><common:message key="header.changeCustomer"/></a>
            <form id="logoutForm" method="post" action="/sso/logout" class="inline">
                <button type="submit" class="btn"><common:message key="header.logout"/></button>
            </form>
        </c:otherwise>
    </c:choose>
    <%-- Len pre demo zdieľanej session: nová appka číta zdieľané kľúče vždy z Redisu; flag patrí starej appke. --%>
    <div id="sessionDebug" class="crm-session-debug">
        <common:message key="header.sessionDebug"/>: <common:message key="header.readSource"/> = <common:message key="header.newApp"/>,
        <common:message key="header.legacyFlag"/> = <c:out value="${layoutLegacyFlag}"/>,
        SSO_ID = <c:out value="${empty layoutSsoId ? '-' : layoutSsoId}"/>
    </div>
    <c:forEach var="message" items="${layoutMessages}">
        <div class="msg msg-${message.level} crm-message"><common:message key="${message.key}"/></div>
    </c:forEach>
</div>
<main id="content">
```

`crm-web/src/main/webapp/WEB-INF/jsp/layout/footer.jspf`:

```jsp
</main>
</div>
</body>
</html>
```

`crm-web/src/main/webapp/WEB-INF/jsp/error.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.generic.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.generic.title"/> <c:out value="${status}"/></h1>
<p><c:out value="${error}"/></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/error/403.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.403.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.403.title"/></h1>
<p class="crm-error-403"><common:message key="error.403.text"/> <code><c:out value="${privilege}"/></code></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/error/403-fragment.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<p class="field-error"><common:message key="error.403.text"/> <c:out value="${privilege}"/></p>
```

`crm-web/src/main/webapp/WEB-INF/jsp/error/404.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.404.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.404.title"/></h1>
<p><common:message key="error.404.text"/></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/error/sso.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.generic.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<p class="msg msg-ERROR"><common:message key="error.sso"/></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="WebInfrastructureTest,LayoutRenderingTest"`
Expected: PASS (7 + 1 = 8 testov).
Ak `should_renderCustomErrorPage_when_pathUnknown` dostane telo bez „Nenájdené“, Spring Boot nenašiel view `error` – over `server.error.whitelabel.enabled=false` a `WEB-INF/jsp/error.jsp`.

- [ ] **Step 7: Spusti všetky doterajšie testy**

Run: `mvn -q -B -f crm-web/pom.xml test`
Expected: PASS (Tasky 1–5).

- [ ] **Step 8: Checkpoint** – `git status --short`

---

### Task 6: Blacklist – doména a in-memory služba (backend port)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistState.java`, `StateReason.java`, `ConditionType.java`, `IdentifierType.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/Identifier.java`, `Note.java`, `BlacklistRecord.java`, `NewBlacklistRecord.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistSearchCriteria.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistRuleException.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistService.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/InMemoryBlacklistService.java`
- Test: `crm-web/src/test/java/com/example/crm/blacklist/InMemoryBlacklistServiceTest.java`

**Interfaces:**
- Consumes: `NotFoundException` (Task 1), bean `Clock` (Task 1)
- Produces:
  - `enum BlacklistState { ACTIVE, INACTIVE }`, `enum StateReason { FRAUD, DEBT, ABUSE }`, `enum ConditionType { BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL }`
  - `enum IdentifierType { BIRTH_CODE, ID_CARD, PASSPORT, COMPANY_REG_NO; boolean isValid(String) }`
  - `record Identifier(long id, IdentifierType type, String value)`, `record Note(String text, String author, LocalDateTime createdAt)`
  - `class BlacklistRecord`: gettery `getId()`, `getFirstName()`, `getLastName()`, `getFullName()`, `getBirthDate()`, `getState()`, `getStateReason()`, `getConditionType()`, `getValidFrom()`, `getValidTo()`, `getIdentifiers()`, `getNotes()`
  - `record NewBlacklistRecord(String firstName, String lastName, LocalDate birthDate, StateReason stateReason, ConditionType conditionType, LocalDate validFrom, LocalDate validTo, IdentifierType identifierType, String identifierValue)`
  - `class BlacklistSearchCriteria implements Serializable`: get/set `firstName`, `lastName`, `identifierValue`, `state`; `boolean isAnyCriterionFilled()` (@AssertTrue)
  - `BlacklistRuleException(String messageKey)` s `getMessageKey()`
  - `interface BlacklistService`: `List<BlacklistRecord> search(BlacklistSearchCriteria)`, `Optional<BlacklistRecord> findById(long)`, `BlacklistRecord create(NewBlacklistRecord)`, `void change(long id, BlacklistState, LocalDate validTo)`, `void addNote(long id, String text, String author)`, `List<Identifier> removeIdentifier(long recordId, long identifierId)`, `List<ConditionType> allowedConditionTypes(StateReason)` (null → všetky)
  - `@Service InMemoryBlacklistService(Clock)` s 8 záznamami

Seed dáta (ID sú pevné). **Rozdelenie záznamov testom** zabráni, aby si testy zdieľajúce Spring kontext navzájom menili dáta:

| ID | Meno | Stav / dôvod / podmienka | Platnosť | Identifikátory (ID) | Kto ho mení v testoch |
|---|---|---|---|---|---|
| 1 | Peter Vzorka, 1980-05-12 | ACTIVE / FRAUD / BLOCK_ALL | 2024-01-10 → – | (1) BIRTH_CODE 800512/1234, (2) ID_CARD AB123456 | Task 8: AJAX odstránenie ident. 2 |
| 2 | Mária Príkladová, 1975-11-03 | ACTIVE / DEBT / REQUIRE_DEPOSIT | 2023-06-01 → 2027-06-01 | (3) BIRTH_CODE 755103/2345 | nikto (len čítanie) |
| 3 | Ján Testovací, 1990-02-20 | INACTIVE / DEBT / MANUAL_APPROVAL | 2022-03-15 → 2024-03-15 | (4) ID_CARD CD987654 | nikto |
| 4 | Eva Ukážková, 1988-07-30 | ACTIVE / ABUSE / MANUAL_APPROVAL | 2025-02-01 → – | (5) PASSPORT P1234567 | nikto (pokus o odstránenie posledného ident. zlyhá) |
| 5 | Tomáš Fiktívny, 1969-12-24 | ACTIVE / FRAUD / BLOCK_ALL | 2021-09-09 → – | (6) BIRTH_CODE 691224/3456, (7) PASSPORT X7654321 | Task 8: zmena stavu; Task 12: AJAX odstránenie ident. 7 |
| 6 | Lucia Demová, 1995-04-18 | INACTIVE / ABUSE / BLOCK_ALL | 2020-01-01 → 2021-01-01 | (8) ID_CARD EF555444 | Task 8: poznámka |
| 7 | Karol Modelový, 1983-08-08 | ACTIVE / DEBT / REQUIRE_DEPOSIT | 2025-10-01 → – | (9) COMPANY_REG_NO 12345678 | Task 12: deaktivácia |
| 8 | Peter Vzorkový, 1979-01-01 | ACTIVE / DEBT / MANUAL_APPROVAL | 2024-05-05 → – | (10) BIRTH_CODE 790101/4567 | nikto |

Záznam 1 má poznámku („Opakované pokusy o objednávku na cudzie doklady.“, autor „Systém“, 2024-01-10T09:00).

- [ ] **Step 1: Napíš padajúci test**

`crm-web/src/test/java/com/example/crm/blacklist/InMemoryBlacklistServiceTest.java`:

```java
package com.example.crm.blacklist;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.crm.common.NotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);

    @Test
    void seedHasEightRecords() {
        assertThat(service.search(new BlacklistSearchCriteria())).hasSize(8);
    }

    @Test
    void lastNameSearchIsCaseInsensitiveContains() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    void firstNameSearchHandlesDiacritics() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("mária"))))).containsExactly(2L);
    }

    @Test
    void identifierSearchIsExact() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("AB123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    void stateSearch() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    void deactivationWithoutValidToUsesToday() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).orElseThrow();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void changeOfUnknownRecordIsNotFound() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lastIdentifierCannotBeRemoved() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
    }

    @Test
    void identifierOfAnotherRecordIsNotFound() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeIdentifierReturnsRemaining() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::id).containsExactly(1L);
    }

    @Test
    void allowedConditionTypesDependOnReason() {
        assertThat(service.allowedConditionTypes(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(service.allowedConditionTypes(null)).containsExactly(ConditionType.values());
    }

    @Test
    void createAssignsIdsAndStartsActive() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getIdentifiers()).extracting(Identifier::id).containsExactly(11L);
    }

    @Test
    void noteUsesClockAndAuthor() {
        service.addNote(6, "  Overené telefonicky.  ", "Správca");

        assertThat(service.findById(6).orElseThrow().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správca", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    void identifierFormats() {
        assertThat(IdentifierType.BIRTH_CODE.isValid("800512/1234")).isTrue();
        assertThat(IdentifierType.BIRTH_CODE.isValid("abc")).isFalse();
        assertThat(IdentifierType.ID_CARD.isValid("AB123456")).isTrue();
        assertThat(IdentifierType.PASSPORT.isValid("P1234567")).isTrue();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid("1234567")).isFalse();
    }

    @Test
    void emptyCriteriaAreNotFilled() {
        BlacklistSearchCriteria blank = criteria(c -> c.setLastName("   "));
        assertThat(blank.isAnyCriterionFilled()).isFalse();
        assertThat(criteria(c -> c.setState(BlacklistState.ACTIVE)).isAnyCriterionFilled()).isTrue();
    }

    private static BlacklistSearchCriteria criteria(java.util.function.Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).toList();
    }
}
```

> Seed má 10 identifikátorov, takže nový záznam dostane identifikátor s ID 11 (test `createAssignsIdsAndStartsActive`).

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=InMemoryBlacklistServiceTest`
Expected: FAIL – kompilácia: `cannot find symbol: class InMemoryBlacklistService`.

- [ ] **Step 3: Implementuj doménu**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistState.java`:

```java
package com.example.crm.blacklist;

public enum BlacklistState { ACTIVE, INACTIVE }
```

`crm-web/src/main/java/com/example/crm/blacklist/StateReason.java`:

```java
package com.example.crm.blacklist;

/** Dôvod zaradenia na blacklist (zjednodušené, vymyslené hodnoty). */
public enum StateReason { FRAUD, DEBT, ABUSE }
```

`crm-web/src/main/java/com/example/crm/blacklist/ConditionType.java`:

```java
package com.example.crm.blacklist;

/** Podmienka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType { BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL }
```

`crm-web/src/main/java/com/example/crm/blacklist/IdentifierType.java`:

```java
package com.example.crm.blacklist;

import java.util.regex.Pattern;

/** Typ identifikátora osoby a jeho formát (zjednodušené vzory pre demo). */
public enum IdentifierType {
    BIRTH_CODE("\\d{6}/?\\d{3,4}"),
    ID_CARD("[A-Z]{2}\\d{6}"),
    PASSPORT("[A-Z]\\d{7}"),
    COMPANY_REG_NO("\\d{8}");

    private final Pattern format;

    IdentifierType(String regex) {
        this.format = Pattern.compile(regex);
    }

    public boolean isValid(String value) {
        return value != null && format.matcher(value).matches();
    }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/Identifier.java`:

```java
package com.example.crm.blacklist;

public record Identifier(long id, IdentifierType type, String value) {
}
```

`crm-web/src/main/java/com/example/crm/blacklist/Note.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDateTime;

public record Note(String text, String author, LocalDateTime createdAt) {
}
```

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistRecord.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Záznam blacklistu ne-zákazníka (osoba, ktorá nie je zákazníkom, ale má obmedzené objednávky).
 * Meniteľné časti mení len služba – gettery používajú JSP cez EL.
 */
public class BlacklistRecord {

    private final long id;
    private final String firstName;
    private final String lastName;
    private final LocalDate birthDate;
    private final StateReason stateReason;
    private final ConditionType conditionType;
    private final LocalDate validFrom;
    private final List<Identifier> identifiers = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private BlacklistState state;
    private LocalDate validTo;

    BlacklistRecord(long id, String firstName, String lastName, LocalDate birthDate, BlacklistState state,
                    StateReason stateReason, ConditionType conditionType, LocalDate validFrom, LocalDate validTo) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.state = state;
        this.stateReason = stateReason;
        this.conditionType = conditionType;
        this.validFrom = validFrom;
        this.validTo = validTo;
    }

    public long getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getFullName() { return firstName + " " + lastName; }
    public LocalDate getBirthDate() { return birthDate; }
    public BlacklistState getState() { return state; }
    public StateReason getStateReason() { return stateReason; }
    public ConditionType getConditionType() { return conditionType; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public List<Identifier> getIdentifiers() { return Collections.unmodifiableList(identifiers); }
    public List<Note> getNotes() { return Collections.unmodifiableList(notes); }

    void changeState(BlacklistState newState, LocalDate newValidTo) {
        this.state = newState;
        this.validTo = newValidTo;
    }

    void addIdentifier(Identifier identifier) {
        identifiers.add(identifier);
    }

    void removeIdentifier(Identifier identifier) {
        identifiers.remove(identifier);
    }

    void addNote(Note note) {
        notes.add(note);
    }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/NewBlacklistRecord.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;

/** Príkaz na vytvorenie záznamu – backend port nezávisí od webového formulára. */
public record NewBlacklistRecord(String firstName, String lastName, LocalDate birthDate, StateReason stateReason,
                                 ConditionType conditionType, LocalDate validFrom, LocalDate validTo,
                                 IdentifierType identifierType, String identifierValue) {
}
```

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistSearchCriteria.java`:

```java
package com.example.crm.blacklist;

import java.io.Serializable;

import jakarta.validation.constraints.AssertTrue;

/**
 * Kritériá vyhľadávania. Rovnako ako v legacy slúžia zároveň ako formulár (držaný v session cez
 * {@code @SessionAttributes}) aj ako dotaz pre backend – preto {@link Serializable}.
 */
public class BlacklistSearchCriteria implements Serializable {

    private String firstName;
    private String lastName;
    private String identifierValue;
    private BlacklistState state;

    /** Náhrada legacy vlastného search validatora: aspoň jedno kritérium (medzery sa nerátajú). */
    @AssertTrue(message = "{blacklist.search.noCriteria}")
    public boolean isAnyCriterionFilled() {
        return hasText(firstName) || hasText(lastName) || hasText(identifierValue) || state != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String identifierValue) { this.identifierValue = identifierValue; }
    public BlacklistState getState() { return state; }
    public void setState(BlacklistState state) { this.state = state; }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistRuleException.java`:

```java
package com.example.crm.blacklist;

/** Porušené business pravidlo – nesie i18n kľúč hlášky pre používateľa. */
public class BlacklistRuleException extends RuntimeException {

    private final String messageKey;

    public BlacklistRuleException(String messageKey) {
        super(messageKey);
        this.messageKey = messageKey;
    }

    public String getMessageKey() {
        return messageKey;
    }
}
```

- [ ] **Step 4: Implementuj port a in-memory službu**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistService.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port na backend blacklistu. V legacy je za ním databázová služba backendu v tom istom JVM;
 * demo má in-memory implementáciu. Reálna migrácia sem pripojí adaptér na existujúci backend – controllery sa nemenia.
 */
public interface BlacklistService {

    List<BlacklistRecord> search(BlacklistSearchCriteria criteria);

    Optional<BlacklistRecord> findById(long id);

    BlacklistRecord create(NewBlacklistRecord data);

    /** Zmena stavu a platnosti. Deaktivácia bez „platné do“ doplní dnešný dátum. */
    void change(long id, BlacklistState state, LocalDate validTo);

    void addNote(long id, String text, String author);

    /** Vráti zostávajúce identifikátory; posledný sa odstrániť nedá ({@link BlacklistRuleException}). */
    List<Identifier> removeIdentifier(long recordId, long identifierId);

    /** Povolené podmienky pre dôvod; {@code null} = všetky. */
    List<ConditionType> allowedConditionTypes(StateReason reason);
}
```

`crm-web/src/main/java/com/example/crm/blacklist/InMemoryBlacklistService.java`:

```java
package com.example.crm.blacklist;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.example.crm.common.NotFoundException;
import org.springframework.stereotype.Service;

import static com.example.crm.blacklist.BlacklistState.ACTIVE;
import static com.example.crm.blacklist.BlacklistState.INACTIVE;
import static com.example.crm.blacklist.ConditionType.BLOCK_ALL;
import static com.example.crm.blacklist.ConditionType.MANUAL_APPROVAL;
import static com.example.crm.blacklist.ConditionType.REQUIRE_DEPOSIT;
import static com.example.crm.blacklist.IdentifierType.BIRTH_CODE;
import static com.example.crm.blacklist.IdentifierType.COMPANY_REG_NO;
import static com.example.crm.blacklist.IdentifierType.ID_CARD;
import static com.example.crm.blacklist.IdentifierType.PASSPORT;
import static com.example.crm.blacklist.StateReason.ABUSE;
import static com.example.crm.blacklist.StateReason.DEBT;
import static com.example.crm.blacklist.StateReason.FRAUD;

/**
 * In-memory náhrada backendu (vymyslené dáta). Metódy sú {@code synchronized} – jednoduchosť pred výkonom;
 * reálny backend rieši konzistenciu transakciou v DB.
 */
@Service
public class InMemoryBlacklistService implements BlacklistService {

    private final Clock clock;
    private final Map<Long, BlacklistRecord> records = new LinkedHashMap<>();
    private long nextRecordId = 1;
    private long nextIdentifierId = 1;

    public InMemoryBlacklistService(Clock clock) {
        this.clock = clock;
        seed();
    }

    @Override
    public synchronized List<BlacklistRecord> search(BlacklistSearchCriteria criteria) {
        return records.values().stream().filter(record -> matches(record, criteria)).toList();
    }

    @Override
    public synchronized Optional<BlacklistRecord> findById(long id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized BlacklistRecord create(NewBlacklistRecord data) {
        BlacklistRecord record = add(data.firstName(), data.lastName(), data.birthDate(), ACTIVE, data.stateReason(),
                data.conditionType(), data.validFrom(), data.validTo());
        record.addIdentifier(new Identifier(nextIdentifierId++, data.identifierType(), data.identifierValue()));
        return record;
    }

    @Override
    public synchronized void change(long id, BlacklistState state, LocalDate validTo) {
        LocalDate effectiveValidTo = (state == INACTIVE && validTo == null) ? LocalDate.now(clock) : validTo;
        require(id).changeState(state, effectiveValidTo);
    }

    @Override
    public synchronized void addNote(long id, String text, String author) {
        require(id).addNote(new Note(text.trim(), author, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES)));
    }

    @Override
    public synchronized List<Identifier> removeIdentifier(long recordId, long identifierId) {
        BlacklistRecord record = require(recordId);
        Identifier identifier = record.getIdentifiers().stream()
                .filter(i -> i.id() == identifierId)
                .findFirst()
                // Identifikátor iného záznamu = podvrhnuté ID → 404, nie zmena cudzích dát.
                .orElseThrow(() -> new NotFoundException("identifikátor " + identifierId + " záznamu " + recordId));
        if (record.getIdentifiers().size() == 1) {
            throw new BlacklistRuleException("blacklist.detail.lastIdentifier");
        }
        record.removeIdentifier(identifier);
        return List.copyOf(record.getIdentifiers());
    }

    @Override
    public List<ConditionType> allowedConditionTypes(StateReason reason) {
        if (reason == null) {
            return List.of(ConditionType.values());
        }
        return switch (reason) {
            case FRAUD -> List.of(BLOCK_ALL);
            case DEBT -> List.of(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            case ABUSE -> List.of(MANUAL_APPROVAL, BLOCK_ALL);
        };
    }

    private BlacklistRecord require(long id) {
        return findById(id).orElseThrow(() -> new NotFoundException("záznam blacklistu " + id));
    }

    private static boolean matches(BlacklistRecord record, BlacklistSearchCriteria criteria) {
        String identifier = criteria.getIdentifierValue();
        return containsIgnoreCase(record.getFirstName(), criteria.getFirstName())
                && containsIgnoreCase(record.getLastName(), criteria.getLastName())
                && (identifier == null || identifier.isBlank() || record.getIdentifiers().stream()
                        .anyMatch(i -> i.value().equalsIgnoreCase(identifier.trim())))
                && (criteria.getState() == null || criteria.getState() == record.getState());
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return query == null || query.isBlank()
                || value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private BlacklistRecord add(String firstName, String lastName, LocalDate birthDate, BlacklistState state,
                                StateReason reason, ConditionType condition, LocalDate validFrom, LocalDate validTo) {
        BlacklistRecord record = new BlacklistRecord(nextRecordId++, firstName, lastName, birthDate, state, reason,
                condition, validFrom, validTo);
        records.put(record.getId(), record);
        return record;
    }

    private void identifier(BlacklistRecord record, IdentifierType type, String value) {
        record.addIdentifier(new Identifier(nextIdentifierId++, type, value));
    }

    private void seed() {
        BlacklistRecord r1 = add("Peter", "Vzorka", LocalDate.of(1980, 5, 12), ACTIVE, FRAUD, BLOCK_ALL,
                LocalDate.of(2024, 1, 10), null);
        identifier(r1, BIRTH_CODE, "800512/1234");
        identifier(r1, ID_CARD, "AB123456");
        r1.addNote(new Note("Opakované pokusy o objednávku na cudzie doklady.", "Systém",
                LocalDateTime.of(2024, 1, 10, 9, 0)));

        BlacklistRecord r2 = add("Mária", "Príkladová", LocalDate.of(1975, 11, 3), ACTIVE, DEBT, REQUIRE_DEPOSIT,
                LocalDate.of(2023, 6, 1), LocalDate.of(2027, 6, 1));
        identifier(r2, BIRTH_CODE, "755103/2345");

        BlacklistRecord r3 = add("Ján", "Testovací", LocalDate.of(1990, 2, 20), INACTIVE, DEBT, MANUAL_APPROVAL,
                LocalDate.of(2022, 3, 15), LocalDate.of(2024, 3, 15));
        identifier(r3, ID_CARD, "CD987654");

        BlacklistRecord r4 = add("Eva", "Ukážková", LocalDate.of(1988, 7, 30), ACTIVE, ABUSE, MANUAL_APPROVAL,
                LocalDate.of(2025, 2, 1), null);
        identifier(r4, PASSPORT, "P1234567");

        BlacklistRecord r5 = add("Tomáš", "Fiktívny", LocalDate.of(1969, 12, 24), ACTIVE, FRAUD, BLOCK_ALL,
                LocalDate.of(2021, 9, 9), null);
        identifier(r5, BIRTH_CODE, "691224/3456");
        identifier(r5, PASSPORT, "X7654321");

        BlacklistRecord r6 = add("Lucia", "Demová", LocalDate.of(1995, 4, 18), INACTIVE, ABUSE, BLOCK_ALL,
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        identifier(r6, ID_CARD, "EF555444");

        BlacklistRecord r7 = add("Karol", "Modelový", LocalDate.of(1983, 8, 8), ACTIVE, DEBT, REQUIRE_DEPOSIT,
                LocalDate.of(2025, 10, 1), null);
        identifier(r7, COMPANY_REG_NO, "12345678");

        BlacklistRecord r8 = add("Peter", "Vzorkový", LocalDate.of(1979, 1, 1), ACTIVE, DEBT, MANUAL_APPROVAL,
                LocalDate.of(2024, 5, 5), null);
        identifier(r8, BIRTH_CODE, "790101/4567");
    }
}
```

- [ ] **Step 5: Spusti test, musí prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=InMemoryBlacklistServiceTest`
Expected: PASS (15 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 7: Blacklist – vyhľadávanie a výsledky (render + action s PRG, `@SessionAttributes`)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistSearchController.java`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/searchresult.jsp`
- Test: `crm-web/src/test/java/com/example/crm/blacklist/BlacklistSearchWebTest.java`

**Interfaces:**
- Consumes: `BlacklistService`, `BlacklistSearchCriteria`, `BlacklistState` (Task 6); `@LegacyPortlet`, `CrmSession`, `TestSessions` (Task 5); `@RequiresPrivilege` (Task 3)
- Produces: routy `GET /blacklist/search`, `POST /blacklist/searchAction`, `GET /blacklist/searchresult`, `GET /blacklist/newSearch`; session atribút `searchCriteria`; view `blacklist/search`, `blacklist/searchresult` (model `results`)

- [ ] **Step 1: Napíš padajúci test**

`crm-web/src/test/java/com/example/crm/blacklist/BlacklistSearchWebTest.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.TestSessions;
import com.example.crm.session.UiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
class BlacklistSearchWebTest {

    @Autowired
    MockMvc mvc;

    @Test
    @org.junit.jupiter.api.DisplayName("Kritériá vyhľadávania sa zapíšu do zdieľanej session (JSON pre starú appku)")
    void should_shareLastCriteria_when_searchSucceeds() throws Exception {
        MockHttpSession session = TestSessions.reader();
        String ssoId = ((com.example.crm.session.SsoIdentity) session.getAttribute(
                com.example.crm.testsupport.TestInfrastructure.TEST_IDENTITY)).ssoSessionId();

        mvc.perform(post("/blacklist/searchAction").session(session).param("lastName", "vzork"));

        assertThat(com.example.crm.testsupport.TestInfrastructure.STORE.data().get(ssoId))
                .containsEntry("APP_BLACKLIST_LAST_CRITERIA",
                        "{\"firstName\":null,\"lastName\":\"vzork\",\"identifierValue\":null,\"state\":null}");
    }

    @Test
    void searchPageHasEmptyCriteria() throws Exception {
        mvc.perform(get("/blacklist/search").session(TestSessions.reader()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/search"))
                .andExpect(model().attributeExists("searchCriteria", "states"));
    }

    @Test
    void blankCriteriaAreValidationError() throws Exception {
        mvc.perform(post("/blacklist/searchAction").session(TestSessions.reader())
                        .param("firstName", "  ").param("lastName", "").param("identifierValue", "").param("state", ""))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/search"))
                .andExpect(model().attributeHasFieldErrors("searchCriteria", "anyCriterionFilled"));
    }

    @Test
    void searchRedirectsToResultsAndKeepsCriteriaInSession() throws Exception {
        MockHttpSession session = TestSessions.reader();

        mvc.perform(post("/blacklist/searchAction").session(session).param("lastName", "vzork"))
                .andExpect(redirectedUrl("/blacklist/searchresult"));
        mvc.perform(get("/blacklist/searchresult").session(session))
                .andExpect(view().name("blacklist/searchresult"))
                .andExpect(model().attribute("results", hasSize(2)));
    }

    @Test
    void noResultPushesInfoAndReturnsToSearch() throws Exception {
        MockHttpSession session = TestSessions.reader();

        mvc.perform(post("/blacklist/searchAction").session(session).param("lastName", "Neexistujúci"))
                .andExpect(redirectedUrl("/blacklist/search"));

        assertThat(TestSessions.popMessages(session))
                .containsExactly(new UiMessage(UiMessage.Level.INFO, "blacklist.search.noResult"));
    }

    @Test
    void newSearchClearsCriteria() throws Exception {
        MockHttpSession session = TestSessions.reader();
        mvc.perform(post("/blacklist/searchAction").session(session).param("lastName", "vzork"));
        assertThat(session.getAttribute("searchCriteria")).isNotNull();

        mvc.perform(get("/blacklist/newSearch").session(session)).andExpect(redirectedUrl("/blacklist/search"));

        assertThat(session.getAttribute("searchCriteria")).isNull();
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=BlacklistSearchWebTest`
Expected: FAIL – `searchPageHasEmptyCriteria` dostane 404 (controller neexistuje). Ostatné testy zlyhajú na redirect/404.

- [ ] **Step 3: Implementuj controller**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistSearchController.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.CrmSession;
import com.example.crm.session.LastSearchCriteria;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

/**
 * Vyhľadávanie v blackliste. Legacy: render {@code search}/{@code searchresult} + action {@code searchAction}
 * v jednom portletovom controlleri s {@code @SessionAttributes}. Tu: render = GET, action = POST + redirect (PRG).
 */
@Controller
@LegacyPortlet("/blacklist")
@RequestMapping("/blacklist")
@SessionAttributes(BlacklistSearchController.SEARCH_MODEL)
@RequiresPrivilege(Privilege.BLACKLIST_VIEW)
public class BlacklistSearchController {

    static final String SEARCH_MODEL = "searchCriteria";

    private final BlacklistService service;

    public BlacklistSearchController(BlacklistService service) {
        this.service = service;
    }

    /** Volá sa len vtedy, keď kritériá ešte nie sú v session (vlastnosť {@code @SessionAttributes}). */
    @ModelAttribute(SEARCH_MODEL)
    public BlacklistSearchCriteria searchCriteria() {
        return new BlacklistSearchCriteria();
    }

    @ModelAttribute("states")
    public BlacklistState[] states() {
        return BlacklistState.values();
    }

    @GetMapping("/search")
    public String search() {
        return "blacklist/search";
    }

    @PostMapping("/searchAction")
    public String searchAction(@Valid @ModelAttribute(SEARCH_MODEL) BlacklistSearchCriteria criteria,
                               BindingResult result, CrmSession session) {
        if (result.hasErrors()) {
            // Validačná chyba sa vykreslí hneď (bez redirectu), aby zostali chyby pri poliach – rovnako ako render po action v portlete.
            return "blacklist/search";
        }
        // Zdieľaná session: posledné kritériá vidí aj stará appka (a ladiaci skript show-session).
        session.setGlobalAttribute(LastSearchCriteria.KEY, new LastSearchCriteria(criteria.getFirstName(),
                criteria.getLastName(), criteria.getIdentifierValue(),
                criteria.getState() == null ? null : criteria.getState().name()));
        if (service.search(criteria).isEmpty()) {
            session.pushInfo("blacklist.search.noResult");
            return "redirect:/blacklist/search";
        }
        // PRG: legacy nastavil render parameter; tu redirect, takže F5 na výsledkoch neodošle formulár znova.
        return "redirect:/blacklist/searchresult";
    }

    @GetMapping("/searchresult")
    public String searchResult(@ModelAttribute(SEARCH_MODEL) BlacklistSearchCriteria criteria, Model model) {
        // Hľadá sa znova (rovnako ako legacy render fáza) – výsledky sa nedržia v session.
        model.addAttribute("results", service.search(criteria));
        return "blacklist/searchresult";
    }

    /** Legacy akcia „vyčistiť polia“ bola action fáza; je to len navigácia, preto GET. */
    @GetMapping("/newSearch")
    public String newSearch(SessionStatus status) {
        status.setComplete();
        return "redirect:/blacklist/search";
    }
}
```

- [ ] **Step 4: Vytvor JSP**

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.search.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_VIEW" wireframe="blacklist-search"/>
<h1><common:message key="blacklist.search.title"/></h1>

<%-- Rovnaký zápis ako v legacy JSP: URL vzniká z parametrov, tag ju preloží na /blacklist/searchAction. --%>
<portal:actionURL var="searchUrl">
    <portal:param name="action" value="searchAction"/>
</portal:actionURL>

<form:form modelAttribute="searchCriteria" action="${searchUrl}" method="post" cssClass="panel">
    <form:errors path="anyCriterionFilled" cssClass="field-error" element="p"/>
    <label><common:message key="blacklist.field.firstName"/> <form:input path="firstName"/></label>
    <label><common:message key="blacklist.field.lastName"/> <form:input path="lastName"/></label>
    <label><common:message key="blacklist.field.identifier"/> <form:input path="identifierValue"/></label>
    <label><common:message key="blacklist.field.state"/>
        <form:select path="state">
            <form:option value=""><common:message key="common.any"/></form:option>
            <c:forEach var="state" items="${states}">
                <form:option value="${state}"><common:message key="blacklist.state.${state}"/></form:option>
            </c:forEach>
        </form:select>
    </label>
    <div class="actions">
        <button type="submit" class="primary btn btn-primary"><common:message key="common.search"/></button>
        <portal:renderURL var="newSearchUrl"><portal:param name="render" value="newSearch"/></portal:renderURL>
        <a href="${newSearchUrl}"><common:message key="blacklist.search.clear"/></a>
        <common:aclFilter privilegeType="BLACKLIST_MODIFY">
            <portal:renderURL var="createUrl"><portal:param name="render" value="create"/></portal:renderURL>
            <a href="${createUrl}" class="button"><common:message key="blacklist.create.title"/></a>
        </common:aclFilter>
    </div>
</form:form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/searchresult.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.result.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_VIEW" wireframe="blacklist-result"/>
<h1><common:message key="blacklist.result.title"/></h1>
<table class="grid table table-striped table-bordered">
    <thead>
    <tr>
        <th><common:message key="blacklist.field.lastName"/>, <common:message key="blacklist.field.firstName"/></th>
        <th><common:message key="blacklist.field.birthDate"/></th>
        <th><common:message key="blacklist.detail.identifiers"/></th>
        <th><common:message key="blacklist.field.state"/></th>
        <th><common:message key="blacklist.field.stateReason"/></th>
        <th></th>
    </tr>
    </thead>
    <tbody>
    <c:forEach var="record" items="${results}">
        <tr>
            <td><c:out value="${record.fullName}"/></td>
            <td>${record.birthDate}</td>
            <td><c:forEach var="identifier" items="${record.identifiers}" varStatus="loop"><c:out value="${identifier.value}"/><c:if test="${not loop.last}">, </c:if></c:forEach></td>
            <td><common:message key="blacklist.state.${record.state}"/></td>
            <td><common:message key="blacklist.reason.${record.stateReason}"/></td>
            <td>
                <portal:renderURL var="detailUrl">
                    <portal:param name="render" value="detail"/>
                    <portal:param name="detailId" value="${record.id}"/>
                </portal:renderURL>
                <a href="${detailUrl}"><common:message key="common.detail"/></a>
            </td>
        </tr>
    </c:forEach>
    </tbody>
</table>
<portal:renderURL var="backUrl"><portal:param name="render" value="search"/></portal:renderURL>
<p><a href="${backUrl}"><common:message key="blacklist.result.backToSearch"/></a></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

- [ ] **Step 5: Spusti test, musí prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=BlacklistSearchWebTest`
Expected: PASS (6 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 8: Blacklist – detail, zmena stavu, poznámka, AJAX odstránenie identifikátora

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistChangeForm.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/NoteForm.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistDetailController.java`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers.jsp`
- Test: `crm-web/src/test/java/com/example/crm/blacklist/BlacklistDetailWebTest.java`

**Interfaces:**
- Consumes: Task 6 (`BlacklistService`, `BlacklistRecord`, `BlacklistRuleException`, …), Task 5 (`@LegacyPortlet`, `CrmSession`, `TestSessions`)
- Produces: `GET /blacklist/detail?detailId=` (VIEW) → view `blacklist/detail` s modelom `record`, `identifiers`, `detailId`, `states`, `changeForm`, `noteForm`; `POST /blacklist/changeAction?detailId=` (MODIFY); `POST /blacklist/addNoteAction?detailId=` (MODIFY); `POST /blacklist/resource/removeIdentAction?detailId=&identId=` (MODIFY) → view `blacklist/fragments/identifiers` (model `identifiers`, `detailId`, voliteľne `errorKey`)

- [ ] **Step 1: Napíš padajúci test**

`crm-web/src/test/java/com/example/crm/blacklist/BlacklistDetailWebTest.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Mení len záznamy 1 (ident. 2), 5 a 6 – viď tabuľka v Tasku 6. */
@SpringBootTest
@AutoConfigureMockMvc
class BlacklistDetailWebTest {

    private static final String AJAX = "X-Requested-With";

    @Autowired
    MockMvc mvc;

    @Test
    @org.junit.jupiter.api.DisplayName("Detail zapíše posledný záznam do zdieľanej session")
    void should_shareLastViewed_when_detailOpened() throws Exception {
        org.springframework.mock.web.MockHttpSession session = TestSessions.reader();
        String ssoId = ((com.example.crm.session.SsoIdentity) session.getAttribute(
                com.example.crm.testsupport.TestInfrastructure.TEST_IDENTITY)).ssoSessionId();

        mvc.perform(get("/blacklist/detail").param("detailId", "2").session(session)).andExpect(status().isOk());

        assertThat(com.example.crm.testsupport.TestInfrastructure.STORE.data().get(ssoId))
                .containsEntry("APP_LAST_VIEWED_RECORD", "{\"id\":2,\"fullName\":\"Mária Príkladová\"}");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Odstránenie identifikátora cez GET je 405 (mutácia len cez POST)")
    void should_reject405_when_removeIdentifierViaGet() throws Exception {
        mvc.perform(get("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "2")
                        .session(TestSessions.admin()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Autowired
    BlacklistService service;

    @Test
    void detailShowsRecordAndForms() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "2").session(TestSessions.reader()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/detail"))
                .andExpect(model().attributeExists("record", "identifiers", "changeForm", "noteForm", "states"))
                .andExpect(model().attribute("detailId", 2L));
    }

    @Test
    void unknownDetailIs404() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "999").session(TestSessions.reader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericDetailIdIs400() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "abc").session(TestSessions.reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readerCannotChange() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").param("state", "INACTIVE")
                        .session(TestSessions.reader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDeactivatesWithRedirectBackToDetail() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").param("state", "INACTIVE")
                        .param("validTo", "").session(TestSessions.admin()))
                .andExpect(redirectedUrl("/blacklist/detail?detailId=5"));

        assertThat(service.findById(5).orElseThrow().getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(service.findById(5).orElseThrow().getValidTo()).isNotNull();
    }

    @Test
    void changeWithoutStateRerendersDetailWithError() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/detail"))
                .andExpect(model().attributeHasFieldErrors("changeForm", "state"));
    }

    @Test
    void addNoteStoresAuthorFromSession() throws Exception {
        mvc.perform(post("/blacklist/addNoteAction").param("detailId", "6").param("text", "Overené telefonicky.")
                        .session(TestSessions.admin()))
                .andExpect(redirectedUrl("/blacklist/detail?detailId=6"));

        assertThat(service.findById(6).orElseThrow().getNotes())
                .anySatisfy(note -> {
                    assertThat(note.text()).isEqualTo("Overené telefonicky.");
                    assertThat(note.author()).isEqualTo("Správca");
                });
    }

    @Test
    void blankNoteIsRejected() throws Exception {
        mvc.perform(post("/blacklist/addNoteAction").param("detailId", "6").param("text", "   ")
                        .session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("noteForm", "text"));
    }

    @Test
    void ajaxRemoveIdentifierReturnsFragment() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "2")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/fragments/identifiers"))
                .andExpect(model().attribute("identifiers", hasSize(1)));
    }

    @Test
    void removingLastIdentifierShowsErrorInFragment() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "4").param("identId", "5")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorKey", "blacklist.detail.lastIdentifier"))
                .andExpect(model().attribute("identifiers", hasSize(1)));
    }

    @Test
    void removeForeignIdentifierIs404() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "3")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isNotFound());
        assertThat(service.findById(2).orElseThrow().getIdentifiers()).hasSize(1);
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=BlacklistDetailWebTest`
Expected: FAIL – kompilácia: `cannot find symbol` (`BlacklistChangeForm`), resp. 404 na routách.

- [ ] **Step 3: Implementuj formuláre a controller**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistChangeForm.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

/** Formulár zmeny stavu/platnosti. Bean Validation nahrádza legacy vlastnú triedu Validator. */
public class BlacklistChangeForm {

    @NotNull(message = "{validation.required}")
    private BlacklistState state;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validTo;

    public static BlacklistChangeForm from(BlacklistRecord record) {
        BlacklistChangeForm form = new BlacklistChangeForm();
        form.state = record.getState();
        form.validTo = record.getValidTo();
        return form;
    }

    public BlacklistState getState() { return state; }
    public void setState(BlacklistState state) { this.state = state; }
    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/NoteForm.java`:

```java
package com.example.crm.blacklist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class NoteForm {

    @NotBlank(message = "{validation.required}")
    @Size(max = 500, message = "{validation.maxLength}")
    private String text;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistDetailController.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import com.example.crm.session.LastViewedRecord;
import com.example.crm.session.OperatorUser;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Detail záznamu. Legacy: render {@code detail}, action {@code changeAction}/{@code addNoteAction} a resource
 * {@code removeIdentAction} (AJAX, vracal HTML fragment cez ModelAndView) – všetko v jednom portletovom controlleri.
 */
@Controller
@LegacyPortlet("/blacklist")
@RequestMapping("/blacklist")
public class BlacklistDetailController {

    private final BlacklistService service;

    public BlacklistDetailController(BlacklistService service) {
        this.service = service;
    }

    @GetMapping("/detail")
    @RequiresPrivilege(Privilege.BLACKLIST_VIEW)
    public String detail(@RequestParam long detailId, Model model, CrmSession session) {
        String view = renderDetail(detailId, model);
        BlacklistRecord record = (BlacklistRecord) model.getAttribute("record");
        // Zdieľaná session: hlavička starej appky (dashboard v Liferay) ukáže, s kým operátor naposledy pracoval.
        session.setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(record.getId(), record.getFullName()));
        return view;
    }

    @PostMapping("/changeAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String change(@RequestParam long detailId, @Valid @ModelAttribute("changeForm") BlacklistChangeForm form,
                         BindingResult result, Model model, CrmSession session) {
        if (result.hasErrors()) {
            return renderDetail(detailId, model);
        }
        service.change(detailId, form.getState(), form.getValidTo());
        session.pushInfo("blacklist.detail.changed");
        return "redirect:/blacklist/detail?detailId=" + detailId;
    }

    @PostMapping("/addNoteAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String addNote(@RequestParam long detailId, @Valid @ModelAttribute("noteForm") NoteForm form,
                          BindingResult result, Model model, CrmSession session) {
        if (result.hasErrors()) {
            return renderDetail(detailId, model);
        }
        String author = session.getOperator().map(OperatorUser::displayName).orElseThrow();
        service.addNote(detailId, form.getText(), author);
        session.pushInfo("blacklist.detail.noteAdded");
        return "redirect:/blacklist/detail?detailId=" + detailId;
    }

    /** Resource fáza → AJAX endpoint. Vracia HTML fragment rovnako ako legacy – JS na stránke sa nemení. */
    @PostMapping("/resource/removeIdentAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String removeIdentifier(@RequestParam long detailId, @RequestParam long identId, Model model) {
        try {
            model.addAttribute("identifiers", service.removeIdentifier(detailId, identId));
        } catch (BlacklistRuleException e) {
            model.addAttribute("identifiers", load(detailId).getIdentifiers());
            model.addAttribute("errorKey", e.getMessageKey());
        }
        model.addAttribute("detailId", detailId);
        return "blacklist/fragments/identifiers";
    }

    private String renderDetail(long detailId, Model model) {
        BlacklistRecord record = load(detailId);
        model.addAttribute("record", record);
        model.addAttribute("identifiers", record.getIdentifiers());
        model.addAttribute("detailId", detailId);
        model.addAttribute("states", BlacklistState.values());
        // Pri validačnej chybe už model obsahuje odoslaný formulár s chybami – nesmie sa prepísať.
        if (!model.containsAttribute("changeForm")) {
            model.addAttribute("changeForm", BlacklistChangeForm.from(record));
        }
        if (!model.containsAttribute("noteForm")) {
            model.addAttribute("noteForm", new NoteForm());
        }
        return "blacklist/detail";
    }

    private BlacklistRecord load(long detailId) {
        return service.findById(detailId).orElseThrow(() -> new NotFoundException("záznam blacklistu " + detailId));
    }
}
```

- [ ] **Step 4: Vytvor JSP**

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.detail.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_VIEW" wireframe="blacklist-detail"/>
<h1><common:message key="blacklist.detail.title"/>: <c:out value="${record.fullName}"/></h1>

<dl class="panel">
    <dt><common:message key="blacklist.field.birthDate"/></dt><dd>${record.birthDate}</dd>
    <dt><common:message key="blacklist.field.state"/></dt><dd><common:message key="blacklist.state.${record.state}"/></dd>
    <dt><common:message key="blacklist.field.stateReason"/></dt><dd><common:message key="blacklist.reason.${record.stateReason}"/></dd>
    <dt><common:message key="blacklist.field.conditionType"/></dt><dd><common:message key="blacklist.condition.${record.conditionType}"/></dd>
    <dt><common:message key="blacklist.field.validFrom"/></dt><dd>${record.validFrom}</dd>
    <dt><common:message key="blacklist.field.validTo"/></dt>
    <dd><c:choose><c:when test="${empty record.validTo}"><common:message key="common.unlimited"/></c:when><c:otherwise>${record.validTo}</c:otherwise></c:choose></dd>
</dl>

<h2><common:message key="blacklist.detail.identifiers"/></h2>
<%-- Ten istý fragment vracia AJAX endpoint – stránka aj resource fáza zdieľajú jeden JSP. --%>
<jsp:include page="/WEB-INF/jsp/blacklist/fragments/identifiers.jsp"/>

<common:aclFilter privilegeType="BLACKLIST_MODIFY">
    <h2><common:message key="blacklist.detail.change"/></h2>
    <portal:actionURL var="changeUrl">
        <portal:param name="action" value="changeAction"/>
        <portal:param name="detailId" value="${record.id}"/>
    </portal:actionURL>
    <form:form modelAttribute="changeForm" action="${changeUrl}" method="post" cssClass="panel">
        <label><common:message key="blacklist.field.state"/>
            <form:select path="state">
                <c:forEach var="state" items="${states}">
                    <form:option value="${state}"><common:message key="blacklist.state.${state}"/></form:option>
                </c:forEach>
            </form:select>
        </label>
        <form:errors path="state" cssClass="field-error"/>
        <label><common:message key="blacklist.field.validTo"/> <form:input path="validTo" type="date"/></label>
        <form:errors path="validTo" cssClass="field-error"/>
        <p class="hint"><common:message key="blacklist.detail.deactivateHint"/></p>
        <button type="submit" class="primary btn btn-primary"><common:message key="common.save"/></button>
    </form:form>
</common:aclFilter>

<h2><common:message key="blacklist.detail.notes"/></h2>
<ul class="plain">
    <c:forEach var="note" items="${record.notes}">
        <li><strong><c:out value="${note.author}"/></strong> (${note.createdAt}): <c:out value="${note.text}"/></li>
    </c:forEach>
</ul>
<common:aclFilter privilegeType="BLACKLIST_MODIFY">
    <portal:actionURL var="noteUrl">
        <portal:param name="action" value="addNoteAction"/>
        <portal:param name="detailId" value="${record.id}"/>
    </portal:actionURL>
    <form:form modelAttribute="noteForm" action="${noteUrl}" method="post" cssClass="panel">
        <form:textarea path="text" rows="3" cols="60"/>
        <form:errors path="text" cssClass="field-error"/>
        <div class="actions"><button type="submit" class="btn"><common:message key="blacklist.detail.addNote"/></button></div>
    </form:form>
</common:aclFilter>

<portal:renderURL var="resultUrl"><portal:param name="render" value="searchresult"/></portal:renderURL>
<p><a href="${resultUrl}"><common:message key="common.back"/></a></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<div id="identifiers">
    <c:if test="${not empty errorKey}"><p class="field-error"><common:message key="${errorKey}"/></p></c:if>
    <ul class="plain">
        <c:forEach var="identifier" items="${identifiers}">
            <li>
                <common:message key="blacklist.identType.${identifier.type}"/>: <c:out value="${identifier.value}"/>
                <common:aclFilter privilegeType="BLACKLIST_MODIFY">
                    <portal:resourceURL var="removeUrl" id="removeIdentAction">
                        <portal:param name="detailId" value="${detailId}"/>
                        <portal:param name="identId" value="${identifier.id}"/>
                    </portal:resourceURL>
                    <button type="button" class="link" data-ajax-post="${removeUrl}" data-ajax-target="#identifiers"><common:message key="common.remove"/></button>
                </common:aclFilter>
            </li>
        </c:forEach>
    </ul>
</div>
```

- [ ] **Step 5: Spusti test, musí prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=BlacklistDetailWebTest`
Expected: PASS (13 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 9: Blacklist – nový záznam (validácia, `@ModelAttribute` na úrovni triedy, AJAX refresh podmienok)

**Files:**
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistCreateForm.java`
- Create: `crm-web/src/main/java/com/example/crm/blacklist/BlacklistCreateController.java`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`
- Create: `crm-web/src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp`
- Test: `crm-web/src/test/java/com/example/crm/blacklist/BlacklistCreateFormValidationTest.java`
- Test: `crm-web/src/test/java/com/example/crm/blacklist/BlacklistCreateWebTest.java`

**Interfaces:**
- Consumes: Task 6 (`BlacklistService.create`, `allowedConditionTypes`, enumy, `NewBlacklistRecord`), Task 5
- Produces: `GET /blacklist/create` (MODIFY) → view `blacklist/create`, model `createForm` (validFrom = dnes), `conditionTypes`, `stateReasons`, `identifierTypes`; `GET /blacklist/resource/refreshCreateConditionTypes?stateReason=` → view `blacklist/fragments/conditionTypes`; `POST /blacklist/createAction` → redirect `/blacklist/detail?detailId={nové}`; `BlacklistCreateForm` s property `periodValid`, `identifierValid` (@AssertTrue) a `toCommand(): NewBlacklistRecord`

- [ ] **Step 1: Napíš padajúce testy**

`crm-web/src/test/java/com/example/crm/blacklist/BlacklistCreateFormValidationTest.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistCreateFormValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validFormHasNoViolations() {
        assertThat(violations(validForm())).isEmpty();
    }

    @Test
    void emptyFormReportsRequiredFields() {
        assertThat(violations(new BlacklistCreateForm())).contains(
                "firstName", "lastName", "birthDate", "stateReason", "conditionType", "validFrom",
                "identifierType", "identifierValue");
    }

    @Test
    void validToBeforeValidFromIsInvalidPeriod() {
        BlacklistCreateForm form = validForm();
        form.setValidTo(form.getValidFrom().minusDays(1));

        assertThat(violations(form)).containsExactly("periodValid");
    }

    @Test
    void identifierMustMatchTypeFormat() {
        BlacklistCreateForm form = validForm();
        form.setIdentifierType(IdentifierType.BIRTH_CODE);
        form.setIdentifierValue("abc");

        assertThat(violations(form)).containsExactly("identifierValid");
    }

    @Test
    void birthDateMustBeInPast() {
        BlacklistCreateForm form = validForm();
        form.setBirthDate(LocalDate.now().plusDays(1));

        assertThat(violations(form)).containsExactly("birthDate");
    }

    private Set<String> violations(BlacklistCreateForm form) {
        return validator.validate(form).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    static BlacklistCreateForm validForm() {
        BlacklistCreateForm form = new BlacklistCreateForm();
        form.setFirstName("Nový");
        form.setLastName("Záznam");
        form.setBirthDate(LocalDate.of(1990, 1, 1));
        form.setStateReason(StateReason.DEBT);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        form.setValidFrom(LocalDate.of(2026, 1, 1));
        form.setIdentifierType(IdentifierType.COMPANY_REG_NO);
        form.setIdentifierValue("87654321");
        return form;
    }
}
```

`crm-web/src/test/java/com/example/crm/blacklist/BlacklistCreateWebTest.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;

import com.example.crm.TestSessions;
import com.example.crm.session.UiMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
class BlacklistCreateWebTest {

    @Autowired
    MockMvc mvc;

    @Test
    void createFormIsPrefilledByClassLevelModelAttribute() throws Exception {
        mvc.perform(get("/blacklist/create").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/create"))
                .andExpect(model().attribute("createForm", hasProperty("validFrom", is(LocalDate.now()))))
                .andExpect(model().attribute("conditionTypes", contains(ConditionType.values())));
    }

    @Test
    void readerCannotOpenCreate() throws Exception {
        mvc.perform(get("/blacklist/create").session(TestSessions.reader())).andExpect(status().isForbidden());
    }

    @Test
    void conditionTypesFragmentDependsOnReason() throws Exception {
        mvc.perform(get("/blacklist/resource/refreshCreateConditionTypes").param("stateReason", "FRAUD")
                        .header("X-Requested-With", "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/fragments/conditionTypes"))
                .andExpect(model().attribute("conditionTypes", contains(ConditionType.BLOCK_ALL)));
    }

    @Test
    void invalidFormIsRerenderedWithErrors() throws Exception {
        mvc.perform(post("/blacklist/createAction").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/create"))
                .andExpect(model().attributeHasFieldErrors("createForm",
                        "firstName", "lastName", "birthDate", "stateReason", "conditionType",
                        "identifierType", "identifierValue"));
    }

    @Test
    void conditionNotAllowedForReasonIsFieldError() throws Exception {
        mvc.perform(valid(post("/blacklist/createAction"), "FRAUD", "REQUIRE_DEPOSIT").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrorCode("createForm", "conditionType",
                        "blacklist.create.conditionNotAllowed"));
    }

    @Test
    void validFormCreatesRecordAndRedirectsToDetail() throws Exception {
        MockHttpSession session = TestSessions.admin();

        mvc.perform(valid(post("/blacklist/createAction")).session(session))
                .andExpect(redirectedUrlPattern("/blacklist/detail?detailId=*"));

        assertThat(TestSessions.popMessages(session))
                .containsExactly(new UiMessage(UiMessage.Level.INFO, "blacklist.create.created"));
    }

    private static MockHttpServletRequestBuilder valid(MockHttpServletRequestBuilder request) {
        return valid(request, "DEBT", "REQUIRE_DEPOSIT");
    }

    /** Každý parameter len raz – MockMvc param() hodnoty pridáva, neprepisuje. */
    private static MockHttpServletRequestBuilder valid(MockHttpServletRequestBuilder request, String reason,
                                                       String condition) {
        return request.param("firstName", "Nový").param("lastName", "Záznam").param("birthDate", "1990-01-01")
                .param("stateReason", reason).param("conditionType", condition)
                .param("validFrom", "2026-01-01").param("validTo", "")
                .param("identifierType", "COMPANY_REG_NO").param("identifierValue", "87654321");
    }
}
```


- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="BlacklistCreateFormValidationTest,BlacklistCreateWebTest"`
Expected: FAIL – kompilácia: `cannot find symbol: class BlacklistCreateForm`.

- [ ] **Step 3: Implementuj formulár a controller**

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistCreateForm.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Formulár nového záznamu. Legacy mal pre formulár samostatnú triedu {@code Validator} (Spring Validator API);
 * tu sú pravidlá priamo na formulári (Jakarta Bean Validation). Krížové pravidlá sú {@code @AssertTrue} metódy –
 * chyba sa naviaže na „property“ {@code periodValid}/{@code identifierValid} a JSP ju zobrazí cez {@code form:errors}.
 */
public class BlacklistCreateForm {

    @NotBlank(message = "{validation.required}")
    private String firstName;

    @NotBlank(message = "{validation.required}")
    private String lastName;

    @NotNull(message = "{validation.required}")
    @Past(message = "{validation.past}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate birthDate;

    @NotNull(message = "{validation.required}")
    private StateReason stateReason;

    @NotNull(message = "{validation.required}")
    private ConditionType conditionType;

    @NotNull(message = "{validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validTo;

    @NotNull(message = "{validation.required}")
    private IdentifierType identifierType;

    @NotBlank(message = "{validation.required}")
    private String identifierValue;

    @AssertTrue(message = "{blacklist.create.periodInvalid}")
    public boolean isPeriodValid() {
        return validFrom == null || validTo == null || !validTo.isBefore(validFrom);
    }

    @AssertTrue(message = "{blacklist.create.identifierInvalid}")
    public boolean isIdentifierValid() {
        return identifierType == null || identifierValue == null || identifierValue.isBlank()
                || identifierType.isValid(identifierValue.trim());
    }

    public NewBlacklistRecord toCommand() {
        return new NewBlacklistRecord(firstName.trim(), lastName.trim(), birthDate, stateReason, conditionType,
                validFrom, validTo, identifierType, identifierValue.trim());
    }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
    public StateReason getStateReason() { return stateReason; }
    public void setStateReason(StateReason stateReason) { this.stateReason = stateReason; }
    public ConditionType getConditionType() { return conditionType; }
    public void setConditionType(ConditionType conditionType) { this.conditionType = conditionType; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }
    public IdentifierType getIdentifierType() { return identifierType; }
    public void setIdentifierType(IdentifierType identifierType) { this.identifierType = identifierType; }
    public String getIdentifierValue() { return identifierValue; }
    public void setIdentifierValue(String identifierValue) { this.identifierValue = identifierValue; }
}
```

`crm-web/src/main/java/com/example/crm/blacklist/BlacklistCreateController.java`:

```java
package com.example.crm.blacklist;

import java.time.Clock;
import java.time.LocalDate;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.CrmSession;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Nový záznam. Legacy: render {@code create}, action {@code createAction}, resource {@code refreshCreateConditionTypes}. */
@Controller
@LegacyPortlet("/blacklist")
@RequestMapping("/blacklist")
@RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
public class BlacklistCreateController {

    static final String CREATE_MODEL = "createForm";

    private final BlacklistService service;
    private final Clock clock;

    public BlacklistCreateController(BlacklistService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /**
     * {@code @ModelAttribute} metóda na úrovni triedy sa volá pred KAŽDÝM handlerom tohto controllera:
     * GET dostane predvyplnený formulár, POST naň naviaže odoslané hodnoty. Presne takto to mal legacy portlet.
     * Keď ju pri migrácii niekto „zabudne“ presunúť, {@code <form:form modelAttribute="createForm">} spadne
     * s „Neither BindingResult nor plain target object…“ – to je chyba z logu predchádzajúceho PoC.
     */
    @ModelAttribute(CREATE_MODEL)
    public BlacklistCreateForm createForm() {
        BlacklistCreateForm form = new BlacklistCreateForm();
        form.setValidFrom(LocalDate.now(clock));
        return form;
    }

    @ModelAttribute("stateReasons")
    public StateReason[] stateReasons() {
        return StateReason.values();
    }

    @ModelAttribute("identifierTypes")
    public IdentifierType[] identifierTypes() {
        return IdentifierType.values();
    }

    @GetMapping("/create")
    public String createPage(@ModelAttribute(CREATE_MODEL) BlacklistCreateForm form, Model model) {
        model.addAttribute("conditionTypes", service.allowedConditionTypes(form.getStateReason()));
        return "blacklist/create";
    }

    /** Resource fáza → AJAX: po zmene dôvodu vráti HTML fragment so zoznamom povolených podmienok. */
    @GetMapping("/resource/refreshCreateConditionTypes")
    public String refreshConditionTypes(@RequestParam(required = false) StateReason stateReason, Model model) {
        model.addAttribute("conditionTypes", service.allowedConditionTypes(stateReason));
        return "blacklist/fragments/conditionTypes";
    }

    @PostMapping("/createAction")
    public String create(@Valid @ModelAttribute(CREATE_MODEL) BlacklistCreateForm form, BindingResult result,
                         Model model, CrmSession session) {
        if (form.getStateReason() != null && form.getConditionType() != null
                && !service.allowedConditionTypes(form.getStateReason()).contains(form.getConditionType())) {
            // Pravidlo závisí od backendu (povolené kombinácie) – preto nie je v Bean Validation, ale tu.
            result.rejectValue("conditionType", "blacklist.create.conditionNotAllowed");
        }
        if (result.hasErrors()) {
            model.addAttribute("conditionTypes", service.allowedConditionTypes(form.getStateReason()));
            return "blacklist/create";
        }
        BlacklistRecord created = service.create(form.toCommand());
        session.pushInfo("blacklist.create.created");
        return "redirect:/blacklist/detail?detailId=" + created.getId();
    }
}
```

- [ ] **Step 4: Vytvor JSP**

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.create.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_MODIFY" wireframe="blacklist-create"/>
<h1><common:message key="blacklist.create.title"/></h1>

<portal:actionURL var="createUrl"><portal:param name="action" value="createAction"/></portal:actionURL>
<portal:resourceURL var="conditionTypesUrl" id="refreshCreateConditionTypes"/>

<form:form modelAttribute="createForm" action="${createUrl}" method="post" cssClass="panel">
    <fieldset>
        <legend><common:message key="blacklist.create.person"/></legend>
        <label><common:message key="blacklist.field.firstName"/> <form:input path="firstName"/></label>
        <form:errors path="firstName" cssClass="field-error"/>
        <label><common:message key="blacklist.field.lastName"/> <form:input path="lastName"/></label>
        <form:errors path="lastName" cssClass="field-error"/>
        <label><common:message key="blacklist.field.birthDate"/> <form:input path="birthDate" type="date"/></label>
        <form:errors path="birthDate" cssClass="field-error"/>
    </fieldset>

    <fieldset>
        <legend><common:message key="blacklist.create.identifier"/></legend>
        <label><common:message key="blacklist.field.identifierType"/>
            <form:select path="identifierType">
                <form:option value="">&mdash;</form:option>
                <c:forEach var="type" items="${identifierTypes}">
                    <form:option value="${type}"><common:message key="blacklist.identType.${type}"/></form:option>
                </c:forEach>
            </form:select>
        </label>
        <form:errors path="identifierType" cssClass="field-error"/>
        <label><common:message key="blacklist.field.identifier"/> <form:input path="identifierValue"/></label>
        <form:errors path="identifierValue" cssClass="field-error"/>
        <form:errors path="identifierValid" cssClass="field-error"/>
    </fieldset>

    <fieldset>
        <legend><common:message key="blacklist.create.classification"/></legend>
        <label><common:message key="blacklist.field.stateReason"/>
            <%-- data-ajax-refresh: náhrada legacy JS, ktorý volal resource URL portletu --%>
            <form:select path="stateReason" data-ajax-refresh="${conditionTypesUrl}" data-ajax-target="#conditionTypes">
                <form:option value="">&mdash;</form:option>
                <c:forEach var="reason" items="${stateReasons}">
                    <form:option value="${reason}"><common:message key="blacklist.reason.${reason}"/></form:option>
                </c:forEach>
            </form:select>
        </label>
        <form:errors path="stateReason" cssClass="field-error"/>
        <label><common:message key="blacklist.field.conditionType"/>
            <jsp:include page="/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp"/>
        </label>
        <form:errors path="conditionType" cssClass="field-error"/>
        <label><common:message key="blacklist.field.validFrom"/> <form:input path="validFrom" type="date"/></label>
        <form:errors path="validFrom" cssClass="field-error"/>
        <label><common:message key="blacklist.field.validTo"/> <form:input path="validTo" type="date"/></label>
        <form:errors path="validTo" cssClass="field-error"/>
        <form:errors path="periodValid" cssClass="field-error"/>
    </fieldset>

    <div class="actions">
        <button type="submit" class="primary btn btn-primary"><common:message key="common.save"/></button>
        <portal:renderURL var="backUrl"><portal:param name="render" value="search"/></portal:renderURL>
        <a href="${backUrl}"><common:message key="common.back"/></a>
    </div>
</form:form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`crm-web/src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<%-- Obyčajný <select> (nie form:select): fragment sa vracia aj samostatne cez AJAX, mimo <form:form>. --%>
<select name="conditionType" id="conditionTypes">
    <option value="">&mdash;</option>
    <c:forEach var="condition" items="${conditionTypes}">
        <option value="${condition}" ${condition == createForm.conditionType ? 'selected' : ''}><common:message key="blacklist.condition.${condition}"/></option>
    </c:forEach>
</select>
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest="BlacklistCreateFormValidationTest,BlacklistCreateWebTest"`
Expected: PASS (11 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 10: Integračný test súbehu – skutočný Tomcat, Redis (Testcontainers) a fake SSO

**Files:**
- Test: `crm-web/src/test/java/com/example/crm/it/SharedSessionFlowTest.java`

**Interfaces:**
- Consumes: celá appka (Tasky 1–9), `FakeSsoServer`, `WebClient` (Tasky 2, 5); property `crm.test.fake-infrastructure=false` vypne `TestInfrastructure`
- Produces: overený kontrakt so starou appkou – názvy kľúčov a JSON v Redise, hlášky prečítané z hashu, vlastná session cookie `CRM_SESSION` s `Path=/crm`

- [ ] **Step 1: Napíš test**

`crm-web/src/test/java/com/example/crm/it/SharedSessionFlowTest.java`:

```java
package com.example.crm.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.testsupport.FakeSsoServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.HttpCookie;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

/**
 * Nová appka so skutočným Redisom a SSO kontraktom: overuje presne to, na čom stojí súbeh so starou appkou –
 * kľúče a JSON v hashi crm:session:{SSO_ID}, hlášky naprieč appkami a vlastnú session cookie.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "crm.test.fake-infrastructure=false")
class SharedSessionFlowTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final FakeSsoServer SSO = new FakeSsoServer();

    static {
        REDIS.start();
        try {
            SSO.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SSO.put("it-admin", "spravca", "Správca", "C-1001", "Ján Vzorový");
        SSO.put("it-reader", "citatel", "Čitateľ", "C-1001", "Ján Vzorový");
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("crm.sso.api-url", SSO::baseUrl);
    }

    @AfterAll
    static void stop() {
        SSO.stop();
        REDIS.stop();
    }

    @LocalServerPort
    int port;

    @Autowired
    StringRedisTemplate redis;

    @Test
    @DisplayName("Bez SSO cookie presmeruje na SSO login portálu")
    void should_redirectToSsoLogin_when_noSsoCookie() throws Exception {
        HttpResponse<String> response = new WebClient(port).get("/crm/blacklist/search");

        assertThat(response.uri().getPath()).isEqualTo("/sso/login");
        assertThat(response.uri().getQuery()).isEqualTo("returnUrl=/crm/blacklist/search");
    }

    @Test
    @DisplayName("Detail zapíše posledný záznam do Redisu v JSON tvare, ktorý číta stará appka")
    void should_shareLastViewedWithLegacy_when_detailOpened() throws Exception {
        WebClient client = new WebClient(port).addCookie("SSO_ID", "it-admin");

        HttpResponse<String> detail = client.get("/crm/blacklist/detail?detailId=1");

        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detail.body()).contains("Peter Vzorka").contains("Správca").contains("SSO_ID = it-admin");
        assertThat(redis.opsForHash().get("crm:session:it-admin", "APP_LAST_VIEWED_RECORD"))
                .isEqualTo("{\"id\":1,\"fullName\":\"Peter Vzorka\"}");
        assertThat(redis.getExpire("crm:session:it-admin")).isPositive();
        WebClient.assertNoMissingMessages(detail.body());
    }

    @Test
    @DisplayName("Vyhľadávanie s diakritikou: výsledok, kritériá v Redise, vlastná session cookie /crm")
    void should_searchAndShareCriteria_when_diacriticsPosted() throws Exception {
        WebClient client = new WebClient(port).addCookie("SSO_ID", "it-admin");

        HttpResponse<String> result = client.post("/crm/blacklist/searchAction", Map.of("firstName", "Mária"));

        assertThat(result.body()).contains("Mária Príkladová");
        assertThat((String) redis.opsForHash().get("crm:session:it-admin", "APP_BLACKLIST_LAST_CRITERIA"))
                .contains("\"firstName\":\"Mária\"");
    }

    @Test
    @DisplayName("Session cookie novej appky je CRM_SESSION s Path=/crm, JSESSIONID nevzniká")
    void should_useOwnSessionCookie_when_sessionCreated() throws Exception {
        WebClient client = new WebClient(port).addCookie("SSO_ID", "it-admin");

        client.post("/crm/blacklist/searchAction", Map.of("lastName", "Vzork"));

        assertThat(client.cookies().getCookieStore().getCookies())
                .extracting(HttpCookie::getName).contains("CRM_SESSION").doesNotContain("JSESSIONID");
        assertThat(client.cookies().getCookieStore().getCookies())
                .filteredOn(c -> c.getName().equals("CRM_SESSION")).extracting(HttpCookie::getPath).containsExactly("/crm");
    }

    @Test
    @DisplayName("Hláška zapísaná starou appkou do Redisu sa zobrazí raz a z hashu zmizne")
    void should_showLegacyMessageOnce_when_pageRendered() throws Exception {
        redis.opsForHash().put("crm:session:it-admin", "APP_MESSAGES",
                "[{\"level\":\"INFO\",\"key\":\"dashboard.deactivation.submitted\"}]");
        WebClient client = new WebClient(port).addCookie("SSO_ID", "it-admin");

        assertThat(client.get("/crm/blacklist/search").body()).contains("Žiadosť o zrušenie bola prijatá.");
        assertThat(redis.opsForHash().hasKey("crm:session:it-admin", "APP_MESSAGES")).isFalse();
        assertThat(client.get("/crm/blacklist/search").body()).doesNotContain("Žiadosť o zrušenie bola prijatá.");
    }

    @Test
    @DisplayName("Zmena stavu: hláška po PRG raz; čitateľ dostane 403; AJAX bez SSO 401; GET na mutáciu 405")
    void should_enforceAclAndMethods_when_modifying() throws Exception {
        WebClient admin = new WebClient(port).addCookie("SSO_ID", "it-admin");
        HttpResponse<String> changed = admin.post("/crm/blacklist/changeAction?detailId=7",
                Map.of("state", "INACTIVE", "validTo", ""));
        assertThat(changed.body()).contains("Záznam bol zmenený.");

        WebClient reader = new WebClient(port).addCookie("SSO_ID", "it-reader");
        assertThat(reader.post("/crm/blacklist/changeAction?detailId=7", Map.of("state", "ACTIVE", "validTo", ""))
                .statusCode()).isEqualTo(403);

        assertThat(new WebClient(port).get("/crm/blacklist/resource/refreshCreateConditionTypes?stateReason=DEBT",
                WebClient.AJAX).statusCode()).isEqualTo(401);
        assertThat(admin.get("/crm/blacklist/resource/removeIdentAction?detailId=1&identId=2").statusCode())
                .isEqualTo(405);
    }
}
```

- [ ] **Step 2: Spusti test**

Run: `mvn -q -B -f crm-web/pom.xml test -Dtest=SharedSessionFlowTest`
Expected: PASS (6 testov; potrebuje bežiaci Docker). Ak niektorý zlyhá, je to chyba v kóde Taskov 1–9 – oprav ju (systematic-debugging), nie test; kontrakt JSON je daný starou appkou.

- [ ] **Step 3: Celá sada**

Run: `mvn -q -B -f crm-web/pom.xml verify`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Checkpoint** – `git status --short`

---

### Task 11: Opravy starej appky z review (vlastník session, POST-only resource, README, smoke na :8080)

**Files:**
- Modify: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/session/PortletSessionStore.java`
- Modify: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/web/DispatcherPortletCustom.java`
- Modify: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/PortletSessionStoreTest.java`
- Modify: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/web/DispatcherPortletCustomTest.java`
- Modify: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/BlacklistDetailController.java`
- Modify: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistDetailControllerTest.java`
- Modify: `legacy/README.md` (krok 6), `docker/smoke.sh` (starý blacklist cez :8080)

**Interfaces:**
- Consumes: `PortletSessionStore`, `RequestAwareManager.getSsoSessionId()`, `FakeRequestAwareManager` (podprojekt 1)
- Produces:
  - `PortletSessionStore.OWNER = "APP_SSO_OWNER"`; `public void ensureOwner()` – ak sa `SSO_ID` requestu líši od uloženého vlastníka, zmaže v `APPLICATION_SCOPE` všetky `APP_*` a portlet-scope atribúty (`javax.portlet.p.*`, t. j. formuláre v `@SessionAttributes`) a uloží nového vlastníka
  - `DispatcherPortletCustom` volá `ensureSessionOwner()` (protected, bean `PortletSessionStore`) na začiatku každej fázy
  - `BlacklistDetailController.removeIdentifier(long detailId, long identId, ResourceRequest request, ResourceResponse response)` – iná metóda než POST → HTTP 405, `null` view

- [ ] **Step 1: Napíš padajúce testy (vlastník session)**

Do `PortletSessionStoreTest` pridaj na koniec triedy (pred poslednú `}`):

```java
    @Test
    @DisplayName("Iný operátor (iné SSO_ID) v tej istej Liferay session nevidí stav predchádzajúceho")
    void should_clearPreviousOperatorState_when_ssoIdChanges() {
        MockPortletSession session = new MockPortletSession();
        session.setAttribute(PortletSessionStore.OWNER, "s-a", PortletSession.APPLICATION_SCOPE);
        session.setAttribute("APP_LAST_VIEWED_RECORD", "A", PortletSession.APPLICATION_SCOPE);
        session.setAttribute("javax.portlet.p.blacklist_WAR_customercareportlet?searchCriteria", "form",
                PortletSession.APPLICATION_SCOPE);
        session.setAttribute("OTHER", "keep", PortletSession.APPLICATION_SCOPE);

        new PortletSessionStore(new FakeRequestAwareManager(session).withSsoSessionId("s-b")).ensureOwner();

        assertThat(session.getAttribute("APP_LAST_VIEWED_RECORD", PortletSession.APPLICATION_SCOPE)).isNull();
        assertThat(session.getAttribute("javax.portlet.p.blacklist_WAR_customercareportlet?searchCriteria",
                PortletSession.APPLICATION_SCOPE)).isNull();
        assertThat(session.getAttribute("OTHER", PortletSession.APPLICATION_SCOPE)).isEqualTo("keep");
        assertThat(session.getAttribute(PortletSessionStore.OWNER, PortletSession.APPLICATION_SCOPE)).isEqualTo("s-b");
    }

    @Test
    @DisplayName("Prvý prístup len zapamätá vlastníka; bez SSO_ID sa nič nemení")
    void should_rememberOwnerWithoutClearing_when_firstAccess() {
        MockPortletSession session = new MockPortletSession();
        session.setAttribute("APP_X", "1", PortletSession.APPLICATION_SCOPE);

        new PortletSessionStore(new FakeRequestAwareManager(session)).ensureOwner();
        assertThat(session.getAttribute(PortletSessionStore.OWNER, PortletSession.APPLICATION_SCOPE)).isNull();

        new PortletSessionStore(new FakeRequestAwareManager(session).withSsoSessionId("s-a")).ensureOwner();
        assertThat(session.getAttribute("APP_X", PortletSession.APPLICATION_SCOPE)).isEqualTo("1");
        assertThat(session.getAttribute(PortletSessionStore.OWNER, PortletSession.APPLICATION_SCOPE)).isEqualTo("s-a");
    }
```

V `DispatcherPortletCustomTest.TestablePortlet` pridaj (vedľa `include`) prázdne prekrytie, aby test nepotreboval Spring kontext,
a do triedy testov test, že sa volá:

```java
        int ownerChecks;

        @Override
        protected void ensureSessionOwner() {
            ownerChecks++;
        }
```

```java
    @Test
    @DisplayName("Pred každou fázou sa overí vlastník session (zmena operátora v SSO)")
    void should_checkSessionOwner_when_anyPhaseStarts() throws Exception {
        when(guard.decide(anyBoolean(), any())).thenReturn(AccessDecision.LOGIN_REQUIRED);

        portlet.doRenderService(new MockRenderRequest(), new MockRenderResponse());
        portlet.doActionService(new MockActionRequest(), new MockActionResponse());
        portlet.doResourceService(new MockResourceRequest(), new MockResourceResponse());

        assertThat(portlet.ownerChecks).isEqualTo(3);
    }
```

> `when(guard.loginUrl())` netreba – pri `LOGIN_REQUIRED` v action fáze sa volá `sendRedirect(null)`; ak `MockActionResponse`
> null odmietne, pridaj `when(guard.loginUrl()).thenReturn("/sso/login");`.

- [ ] **Step 2: Napíš padajúci test (resource len cez POST)**

V `BlacklistDetailControllerTest` nahraď tri volania `controller.removeIdentifier(a, b)` volaniami s requestom:
`controller.removeIdentifier(1, 2, post(), new MockResourceResponse())`, `controller.removeIdentifier(4, 5, post(), new MockResourceResponse())`,
`controller.removeIdentifier(1, 3, post(), new MockResourceResponse())`, pridaj importy
`org.springframework.mock.web.portlet.MockResourceRequest`, `org.springframework.mock.web.portlet.MockResourceResponse`,
`javax.portlet.ResourceResponse`, pomocnú metódu a test:

```java
    private static MockResourceRequest post() {
        MockResourceRequest request = new MockResourceRequest();
        request.setMethod("POST");
        return request;
    }

    @Test
    @DisplayName("Mutácia v resource fáze len cez POST – GET (napr. odkaz z cudzej stránky) je 405 bez zmeny")
    void should_reject405_when_removeIdentifierViaGet() {
        MockResourceRequest get = new MockResourceRequest();
        get.setMethod("GET");
        MockResourceResponse response = new MockResourceResponse();

        assertThat(controller.removeIdentifier(1, 2, get, response)).isNull();
        assertThat(response.getProperty(ResourceResponse.HTTP_STATUS_CODE)).isEqualTo("405");
        assertThat(service.findById(1).get().getIdentifiers()).hasSize(2);
    }
```

- [ ] **Step 3: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia (`OWNER`, `ensureOwner`, `ensureSessionOwner`, nová signatúra `removeIdentifier`).

- [ ] **Step 4: Implementuj**

V `PortletSessionStore` pridaj konštantu a metódu:

```java
    /** SSO_ID operátora, ktorému patrí obsah tejto PortletSession. */
    public static final String OWNER = PREFIX + "SSO_OWNER";
    private static final String PORTLET_SCOPE_PREFIX = "javax.portlet.p.";

    /**
     * Liferay session prežije odhlásenie/prihlásenie iného operátora v SSO (JSESSIONID sa nemení). Bez tejto kontroly
     * by operátor B videl stav A (hlavička, formuláre v @SessionAttributes) – pri flagu PORTLET, nie pri REDIS.
     */
    public void ensureOwner() {
        String current = requests.getSsoSessionId();
        PortletSession session = requests.getPortletRequest().getPortletSession(false);
        if (current == null || session == null) {
            return;
        }
        Object owner = session.getAttribute(OWNER, PortletSession.APPLICATION_SCOPE);
        if (owner != null && !owner.equals(current)) {
            List<String> stale = new ArrayList<>();
            Enumeration<String> names = session.getAttributeNames(PortletSession.APPLICATION_SCOPE);
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name.startsWith(PREFIX) || name.startsWith(PORTLET_SCOPE_PREFIX)) {
                    stale.add(name);
                }
            }
            for (String name : stale) {
                session.removeAttribute(name, PortletSession.APPLICATION_SCOPE);
            }
        }
        session.setAttribute(OWNER, current, PortletSession.APPLICATION_SCOPE);
    }
```

V `DispatcherPortletCustom` pridaj metódu a volanie `ensureSessionOwner();` ako prvý riadok `doRenderService`,
`doActionService` a `doResourceService`:

```java
    /** Zmena operátora v SSO vyčistí stav predchádzajúceho (viď PortletSessionStore#ensureOwner). */
    protected void ensureSessionOwner() {
        getPortletApplicationContext().getBean(PortletSessionStore.class).ensureOwner();
    }
```

(import `com.example.crm.frontend.base.session.PortletSessionStore`).

V `BlacklistDetailController` zmeň resource metódu:

```java
    /** AJAX: odstránenie identifikátora, vráti HTML fragment zoznamu. Len POST – resource URL nemá p_auth token. */
    @ResourceMapping("removeIdentAction")
    public ModelAndView removeIdentifier(@RequestParam("detailId") long detailId, @RequestParam("identId") long identId,
                                         ResourceRequest request, ResourceResponse response) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setProperty(ResourceResponse.HTTP_STATUS_CODE, "405");
            return null;
        }
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        ModelAndView view = new ModelAndView("blacklist/fragments/identifiers");
        view.addObject("detailId", detailId);
        try {
            view.addObject("identifiers", blacklistService.removeIdentifier(detailId, identId));
        } catch (BlacklistRuleException e) {
            view.addObject("identifiers", require(detailId).getIdentifiers());
            view.addObject("errorKey", e.getMessageKey());
        }
        return view;
    }
```

(importy `javax.portlet.ResourceRequest`, `javax.portlet.ResourceResponse`).

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml verify`
Expected: BUILD SUCCESS (151 + 2 + 1 + 1 = 155 testov).

- [ ] **Step 6: README krok 6 a smoke test starej appky**

V `legacy/README.md` nahraď vetu začínajúcu „Legacy pasca: otvor potom zrušenie *Tarif Štandard*…“ týmto:

```markdown
   Legacy pasca `@SessionAttributes`: pri *Tarif Neobmedzený* zvoľ *Zrušenie* a dátum „Ku dňu“ skorší než *Najskôr*
   → **Odoslať** skončí chybou (formulár ostane v session). Vráť sa na produkty a otvor *Tarif Štandard* – typ a dátumy
   ostali z predchádzajúceho formulára. Po úspešnom odoslaní sa formulár zo session odstráni (`setComplete`), preto
   sa pasca ukáže len po neúspešnom pokuse; migrácia ju odstráni úplne.
```

V `docker/smoke.sh`: blacklist starej appky je po prepnutí (Task 12) na :8000 presmerovaný – testuj ho priamo v Liferay.
Za riadok `B="http://localhost:8000"` pridaj `L="http://localhost:8080"   # Liferay priamo (starý blacklist mimo nginx)`
a nahraď všetky výskyty `/web/crm/blacklist` v príkazoch `fetch` a v čakacej slučke za `$L/web/crm/blacklist`
(`fetch` cez `abs()` absolútnu URL nemení; cookie `SSO_ID` z :8000 prehliadač aj curl pošlú aj na :8080, cookies
nerozlišujú port). Riadok čakania zmeň na `code="$(curl -s -o /dev/null -w '%{http_code}' "$L/web/crm/blacklist")"`.

- [ ] **Step 7: Checkpoint** – `git status --short`

---

### Task 12: Docker – `crm-web` v compose, strangler v nginx, smoke test súbehu, overenie spustením

**Files:**
- Create: `crm-web/Dockerfile`
- Modify: `docker/docker-compose.yml`, `docker/nginx/default.conf`, `docker/build-and-deploy.sh`, `docker/build-and-deploy.ps1`
- Create: `docker/smoke-migration.sh`, `docker/smoke-migration.ps1`

**Interfaces:**
- Consumes: `crm-web/target/crm-web.war` (Tasky 1–10), stack z podprojektu 1, opravy z Tasku 11
- Produces:
  - nginx `:8000`: `/web/crm/blacklist*` → 302 `/crm/blacklist/search`; `/crm/` → `crm-web:8080`; ostatné ako predtým
  - služba `crm-web` (aj priamo na `http://localhost:8081/crm`), env `CRM_SSO_APIURL=http://sso-mock:8090/sso`, `SPRING_DATA_REDIS_HOST=redis`
  - `docker/smoke-migration.sh` – exit kód = počet zlyhaní, na konci `MIGRATION SMOKE OK`

- [ ] **Step 1: Dockerfile a compose**

`crm-web/Dockerfile`:

```dockerfile
# WAR zbuildí docker/build-and-deploy (mvn -f crm-web/pom.xml verify) – image ho len zabalí.
FROM eclipse-temurin:21-jre
COPY target/crm-web.war /app/crm-web.war
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/crm-web.war"]
```

V `docker/docker-compose.yml` pridaj službu (za `sso-mock`):

```yaml
  # Nová appka so zmigrovaným blacklistom (podprojekt 2). Za nginx pod /crm/, priamo na :8081/crm.
  crm-web:
    build: ../crm-web
    environment:
      CRM_SSO_APIURL: http://sso-mock:8090/sso
      SPRING_DATA_REDIS_HOST: redis
    ports: ["8081:8080"]
    depends_on: [redis, sso-mock]
```

a do `nginx.depends_on` pridaj `crm-web`: `depends_on: [liferay, sso-mock, crm-web]`.

- [ ] **Step 2: Strangler v nginx**

V `docker/nginx/default.conf` nahraď riadok s komentárom `# Podprojekt 2: …` týmto:

```nginx
    # Strangler: zmigrovaný portlet blacklist obsluhuje nová appka. Aj staré URL portletu (render aj action URL
    # s p_p_id…) skončia na vyhľadávaní v novej appke – POST sa po 302 zmení na GET, nič sa neodošle dvakrát.
    location /web/crm/blacklist {
        return 302 /crm/blacklist/search;
    }

    # Nová appka (Spring Boot), kontext /crm.
    location /crm/ {
        proxy_pass http://crm-web:8080;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
```

- [ ] **Step 3: Build skripty**

V `docker/build-and-deploy.sh` za riadok `mvn -q -B -f "$ROOT/sso-mock/pom.xml" verify` pridaj:

```bash
mvn -q -B -f "$ROOT/crm-web/pom.xml" verify
```

V `docker/build-and-deploy.ps1` za kontrolu buildu sso-mock pridaj:

```powershell
mvn -q -B -f "$root\crm-web\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of crm-web failed' }
```

- [ ] **Step 4: Smoke test súbehu**

`docker/smoke-migration.sh`:

```bash
#!/usr/bin/env bash
# Súbeh starej (Liferay) a novej (Spring Boot) appky cez nginx (http://localhost:8000): strangler, SSO identita
# v oboch appkách, zdieľaná session v Redise a vplyv flagu starej appky (PORTLET vs REDIS).
set -u
cd "$(dirname "$0")"
B="http://localhost:8000"
WORK="$(mktemp -d)"
JAR="$WORK/cookies.txt"
FAILS=0

ok()   { echo "OK    $1"; }
fail() { echo "FAIL  $1"; FAILS=$((FAILS + 1)); }
expect()     { if grep -qF -- "$2" "$3"; then ok "$1"; else fail "$1 (chýba: $2)"; fi; }
expect_not() { if grep -qF -- "$2" "$3"; then fail "$1 (nemá tam byť: $2)"; else ok "$1"; fi; }
fetch() { curl -s -L -b "$JAR" -c "$JAR" -o "$2" "$B$1"; }
post()  { local path="$1" out="$2"; shift 2; curl -s -L -b "$JAR" -c "$JAR" -o "$out" "$@" "$B$path"; }
rc()    { docker compose exec -T redis redis-cli "$@" | tr -d '\r'; }
cookie() { grep -E "[[:space:]]$1[[:space:]]" "$JAR" | awk '{print $7}' | head -1; }
login() {
    rm -f "$JAR"
    curl -s -c "$JAR" -b "$JAR" -o /dev/null -X POST "$B/sso/login" \
        -d "operator=$1" -d "customer=C-1001" -d "returnUrl=/crm/blacklist/search"
}

echo "Čakám na Liferay a crm-web..."
for _ in $(seq 1 90); do
    a="$(curl -s -o /dev/null -w '%{http_code}' "$B/web/crm/dashboard")"
    b="$(curl -s -o /dev/null -w '%{http_code}' "$B/crm/blacklist/search")"
    [ "$a" = "200" ] && [ "$b" = "302" ] && curl -s "$B/web/crm/dashboard" | grep -q 'id="sessionDebug"' && break
    sleep 5
done

echo "== 1. Strangler v nginx"
loc="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$B/web/crm/blacklist")"
if [ "$loc" = "302 $B/crm/blacklist/search" ]; then ok "GET starej stránky -> nová appka"; else fail "GET starej stránky: $loc"; fi
loc="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -X POST \
    "$B/web/crm/blacklist?p_p_id=blacklist_WAR_customercareportlet&p_p_lifecycle=1")"
if [ "$loc" = "302 $B/crm/blacklist/search" ]; then ok "strangler POST (stará action URL) -> 302"; else fail "strangler POST: $loc"; fi

echo "== 2. Bez SSO nová appka presmeruje na SSO login"
loc="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$B/crm/blacklist/search")"
case "$loc" in "302 $B/sso/login?returnUrl=%2Fcrm%2Fblacklist%2Fsearch") ok "redirect na SSO login" ;; *) fail "redirect na SSO login: $loc" ;; esac

echo "== 3. Prihlásenie (správca) – identita v oboch appkách, cookies sa nebijú"
login spravca
SSO_ID="$(cookie SSO_ID)"
fetch /web/crm/dashboard "$WORK/dash0.html"
JSESSION_BEFORE="$(cookie JSESSIONID)"
fetch /crm/blacklist/search "$WORK/search.html"
expect "nová appka: operátor zo SSO" 'id="headerOperator"' "$WORK/search.html"
expect "nová appka: Správca" 'Správca' "$WORK/search.html"
expect "nová appka: ladiaci riadok so SSO_ID" "SSO_ID = $SSO_ID" "$WORK/search.html"
expect "stará appka (dashboard): operátor zo SSO" 'id="headerOperator"' "$WORK/dash0.html"

echo "== 4. Blacklist v novej appke"
# Všetky polia formulára ako prehliadač – kritériá sú v @SessionAttributes a nevyplnené pole by ostalo z minulého hľadania.
post /crm/blacklist/searchAction "$WORK/result1.html" -d "firstName=M%C3%A1ria" -d lastName= -d identifierValue= -d state=
expect "diakritika: Mária nájdená" 'Mária Príkladová' "$WORK/result1.html"
post /crm/blacklist/searchAction "$WORK/result2.html" -d firstName= -d lastName=Vzork -d identifierValue= -d state=
expect "výsledok: Peter Vzorka" 'Peter Vzorka' "$WORK/result2.html"
post "/crm/blacklist/changeAction?detailId=7" "$WORK/changed.html" -d state=INACTIVE -d validTo=
expect "zmena stavu: hláška po PRG" 'Záznam bol zmenený.' "$WORK/changed.html"
fetch "/crm/blacklist/detail?detailId=1" "$WORK/detail.html"
expect "detail: AB123456" 'AB123456' "$WORK/detail.html"
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'X-Requested-With: XMLHttpRequest' \
    "$B/crm/blacklist/resource/refreshCreateConditionTypes?stateReason=DEBT")"
if [ "$code" = "401" ]; then ok "AJAX bez SSO -> 401"; else fail "AJAX bez SSO -> $code"; fi
JSESSION_AFTER="$(cookie JSESSIONID)"
if [ -n "$JSESSION_BEFORE" ] && [ "$JSESSION_BEFORE" = "$JSESSION_AFTER" ]; then ok "JSESSIONID Liferay nezmenený"; else fail "JSESSIONID: '$JSESSION_BEFORE' -> '$JSESSION_AFTER'"; fi
if [ -n "$(cookie CRM_SESSION)" ]; then ok "nová appka má vlastnú cookie CRM_SESSION"; else fail "CRM_SESSION chýba"; fi

echo "== 5. Zdieľaná session v Redise"
rc HGETALL "crm:session:$SSO_ID" > "$WORK/redis.txt"
expect "Redis: posledný záznam z novej appky" '{"id":1,"fullName":"Peter Vzorka"}' "$WORK/redis.txt"
expect "Redis: posledné kritériá z novej appky" '"lastName":"Vzork"' "$WORK/redis.txt"

echo "== 6. Flag PORTLET: stará appka číta svoju PortletSession – zápis novej appky nevidí"
rc SET crm:flags:session-read-source PORTLET > /dev/null
fetch /web/crm/dashboard "$WORK/dash-portlet.html"
expect_not "dashboard bez posledného záznamu" 'id="headerLastViewed"' "$WORK/dash-portlet.html"

echo "== 7. Flag REDIS: stará appka číta zo zdieľanej session"
rc SET crm:flags:session-read-source REDIS > /dev/null
fetch /web/crm/dashboard "$WORK/dash-redis.html"
expect "dashboard (Liferay) ukáže záznam otvorený v novej appke" 'Naposledy otvorený záznam: Peter Vzorka' "$WORK/dash-redis.html"
fetch /crm/blacklist/search "$WORK/search-redis.html"
expect "nová appka zobrazí flag starej appky" 'flag starej appky = REDIS' "$WORK/search-redis.html"

echo "== 8. Zmena zákazníka v SSO platí v oboch appkách"
curl -s -b "$JAR" -c "$JAR" -o /dev/null -X POST "$B/sso/customer" -d customer=C-1002 -d returnUrl=/crm/blacklist/search
fetch /crm/blacklist/search "$WORK/search-c2.html"
expect "nová appka: zákazník C-1002" 'C-1002' "$WORK/search-c2.html"
fetch /web/crm/dashboard "$WORK/dash-c2.html"
expect "dashboard: produkty C-1002" 'Firemný tarif' "$WORK/dash-c2.html"

echo "== 9. Čitateľ: zmena v novej appke je zakázaná"
login citatel
code="$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" -c "$JAR" -X POST \
    "$B/crm/blacklist/changeAction?detailId=7" -d state=ACTIVE -d validTo=)"
if [ "$code" = "403" ]; then ok "čitateľ -> 403"; else fail "čitateľ -> $code"; fi

echo "== 10. Výpadok Redisu nezhodí novú appku"
docker compose stop redis > /dev/null 2>&1
fetch /crm/blacklist/search "$WORK/no-redis.html"
expect "nová appka beží bez Redisu" 'id="headerOperator"' "$WORK/no-redis.html"
docker compose start redis > /dev/null 2>&1

rc SET crm:flags:session-read-source PORTLET > /dev/null
rm -rf "$WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "MIGRATION SMOKE OK"; else echo "MIGRATION SMOKE: $FAILS zlyhaní"; fi
exit "$FAILS"
```

`docker/smoke-migration.ps1`:

```powershell
# The migration smoke test is a bash script (curl + grep); this wrapper runs it with Git Bash.
$bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw 'Smoke test needs Git Bash (C:\Program Files\Git\bin\bash.exe)' }
& $bash "$PSScriptRoot/smoke-migration.sh"
exit $LASTEXITCODE
```

- [ ] **Step 5: Spusti celý stack**

Run (na pozadí, niekoľko minút): `bash docker/build-and-deploy.sh`
Expected: `Hotovo…`; `docker compose -f docker/docker-compose.yml ps` ukáže 6 bežiacich služieb (vrátane `crm-web`).

- [ ] **Step 6: Smoke testy oboch appiek**

Run: `bash docker/smoke.sh && bash docker/smoke-migration.sh`
Expected: `SMOKE OK` (stará appka priamo na :8080) a `MIGRATION SMOKE OK`.

Najpravdepodobnejšie príčiny zlyhaní:

| Príznak | Príčina | Riešenie |
|---|---|---|
| krok 3 „nová appka: Správca“ chýba, stránka je SSO login | `crm-web` nevidí SSO (`CRM_SSO_APIURL`) | `docker compose logs crm-web`; over názov premennej (relaxed binding `crm.sso.api-url`) |
| krok 4 „JSESSIONID Liferay zmenený“ | session cookie novej appky bez cesty `/crm` | over `server.servlet.session.cookie.*` v `application.properties` |
| krok 7 dashboard bez záznamu aj pri REDIS | stará appka v PortletSession nemá vlastníka / flag sa necachuje správne | `bash docker/show-session.sh`; log Liferay (`ReadSourceFlag`) |
| stránky novej appky bez štýlov | CSS z `/html/themes/classic/…` ide cez nginx do Liferay | otvor appku cez :8000, nie :8081 |

- [ ] **Step 7: Checkpoint** – `git status --short`

---

### Task 13: Dokumentácia – README novej appky (preklikanie súbehu), MIGRACIA.md, poznámky, stav

**Files:**
- Create: `crm-web/README.md`, `docs/MIGRACIA.md`
- Modify: `poznamky/07-pasce.md`, `poznamky/09-session-liferay-vs-spring.md`, `poznamky/06-playbook-migracie-portletu.md`, `poznamky/README.md`
- Modify: `README.md`, `CLAUDE.md`, `legacy/README.md` (odkaz na súbeh)

**Interfaces:**
- Consumes: výsledky Taskov 1–12 (skutočné správanie stacku, zistené pasce)
- Produces: dokumentácia; žiadny kód

- [ ] **Step 1: `crm-web/README.md`**

~~~markdown
# Nová appka „po“ (Spring Boot) – zmigrovaný blacklist

Portlet **blacklist ne-zákazníkov** zo starej appky (`../legacy`, Liferay 6.2) prepísaný na Spring Boot 4.1.1 + JSP.
Beží **súčasne** so starou appkou za jedným nginx: blacklist obsluhuje nová appka, dashboard ostáva v Liferay.
Obe appky zdieľajú identitu (SSO mock, cookie `SSO_ID`) a session (Redis hash `crm:session:{SSO_ID}`).

## Spustenie

Predpoklady: Docker Desktop, JDK 21, Maven 3.9, Git Bash.

```bash
bash docker/build-and-deploy.sh      # build legacy + sso-mock + crm-web, docker compose up, deploy do Liferay
bash docker/smoke.sh                 # stará appka (blacklist priamo na :8080)
bash docker/smoke-migration.sh       # súbeh – na konci "MIGRATION SMOKE OK"
```

| Adresa | Čo |
|---|---|
| http://localhost:8000 | **vstup** – nginx: `/web/…` Liferay, `/crm/…` nová appka, `/sso/…` SSO mock |
| http://localhost:8000/crm/blacklist/search | blacklist v novej appke (sem vedie aj starý odkaz „Blacklist“) |
| http://localhost:8000/web/crm/dashboard | dashboard v starej appke |
| http://localhost:8080/web/crm/blacklist | **starý** blacklist priamo v Liferay – na porovnanie „pred/po“ |
| http://localhost:5540 | RedisInsight – zdieľaná session |

## Preklikanie súbehu

1. Otvor **http://localhost:8000** → **Prihlásiť** → SSO mock: *Správca*, zákazník *C-1001*.
2. Navigácia **Blacklist** v Liferay (alebo http://localhost:8000/web/crm/blacklist) → nginx presmeruje na
   `/crm/blacklist/search`. Hlavička vyzerá rovnako, ale je to Spring Boot – ladiaci riadok ukazuje
   *zdroj čítania = Redis (nová appka), flag starej appky = PORTLET, SSO_ID = …*.
3. Vyhľadaj priezvisko `vzork` → otvor **Peter Vzorka**. Hlavička novej appky ukáže *Naposledy otvorený záznam*.
   V termináli: `bash docker/show-session.sh` → v hashi je `APP_LAST_VIEWED_RECORD {"id":1,"fullName":"Peter Vzorka"}`
   zapísaný **novou** appkou.
4. Klikni **Dashboard** (navigácia novej appky vedie do Liferay). Pri flagu **PORTLET** hlavička starej appky posledný
   záznam **neukáže** – číta svoju PortletSession, kam nová appka nezapisuje.
5. `bash docker/session-flag.sh REDIS` a obnov dashboard → hlavička Liferay ukáže
   **„Naposledy otvorený záznam: Peter Vzorka“** z novej appky. Ladiaci riadok novej appky ukáže *flag starej appky = REDIS*.
   Poučenie: flag sa prepína na `REDIS` najneskôr s nasadením prvej zmigrovanej stránky.
6. **Zmeniť zákazníka** (v ľubovoľnej appke) → SSO mock → *C-1002*: obe appky ukážu nového zákazníka
   (dashboard iné produkty). Zákazníka vlastní SSO portál.
7. V novej appke zmeň stav záznamu → hláška sa zobrazí raz (prešla cez Redis). Odstránenie identifikátora (AJAX)
   ide len cez POST.
8. **Odhlásiť** → prihlás sa ako *Čitateľ* → v novej appke nevidí zmeny ani „Nový záznam“, priamy POST dostane 403.
9. **Pred/po:** otvor http://localhost:8080/web/crm/blacklist (starý portlet) a porovnaj URL, správanie a kód –
   `docs/MIGRACIA.md`.
10. `docker compose -f docker/docker-compose.yml stop redis` → nová appka beží ďalej (bez hlášok a posledného
    záznamu), `… start redis` ho vráti.

## Čo je kde

| Koncept | Súbor |
|---|---|
| fasáda session (kontrakt legacy, nová implementácia) | `src/main/java/com/example/crm/session/CrmSession.java`, `SharedCrmSession.java` |
| identita zo SSO | `session/SsoClient.java`, `SsoIdentityResolver.java` |
| zdieľaná session (Redis, JSON kontrakt) | `session/RedisSharedSessionStore.java`, `SharedSessionKeys.java` |
| „portlet“ = skupina controllerov | `web/portlet/LegacyPortlet.java`, `LegacyPortletInterceptor.java` |
| hlavička (náhrada theme + portletu header) | `webapp/WEB-INF/jsp/layout/header.jspf`, `web/portlet/LayoutModelInterceptor.java` |
| tagy s rovnakým rozhraním | `web/tag/*`, `webapp/WEB-INF/tld/*.tld` |
| blacklist | `blacklist/*Controller.java`, `webapp/WEB-INF/jsp/blacklist/*.jsp` |

## Testy

```bash
mvn -q -B -f crm-web/pom.xml verify    # unit, MockMvc, Tomcat + Jasper, Redis cez Testcontainers (potrebuje Docker)
```

## Vedomé obmedzenia

- Každá appka má vlastné in-memory dáta blacklistu (rovnaký seed). Legacy backend beží v JVM Liferay, nová appka ho
  zdieľať nemôže – zmena v novej appke sa v starom blackliste na :8080 neprejaví. Zdieľa sa len session.
- Stránky novej appky berú CSS z Liferay (`/html/themes/classic/…`) cez nginx – mimo :8000 sú len so základným štýlom.
~~~

- [ ] **Step 2: `docs/MIGRACIA.md`**

~~~markdown
# Migrácia portletu blacklist: pred → po

„Pred“ je skutočná stará appka v [`legacy/`](../legacy) (Liferay 6.2, Java 8, Spring Portlet MVC), „po“ je
[`crm-web/`](../crm-web) (Spring Boot 4.1.1, Java 21). Obe bežia súčasne (`docker/`), takže každý bod sa dá
porovnať aj v prehliadači: starý blacklist na :8080, nový na :8000. Skratky viď `poznamky/00-konvencie.md`.

## 1. Portlet → skupina controllerov
- **Pred:** `portlet.xml` + `blacklist-portlet.xml` (vlastný Spring kontext) + `DispatcherPortletCustom`;
  controller sa vyberá render parametrom `viewPage`, metóda parametrom `action`/`render`
  (`legacy/customercare-portlets/.../blacklist/BlacklistSearchController.java`, `BlacklistDetailController.java`).
- **Po:** `@LegacyPortlet("/blacklist")` + `@RequestMapping("/blacklist")`, jeden `DispatcherServlet`
  (`crm-web/.../blacklist/BlacklistSearchController.java`, `web/portlet/LegacyPortletInterceptor.java`).
- **Prečo:** bez portálu niet portletov ani ich kontextov; URL je adresa stránky, nie parametre portletu.

## 2. Fázy portletu → HTTP
| Legacy | Nové | Prečo |
|---|---|---|
| render (`@RenderMapping`) | `GET` | čistá adresa, bookmark, F5 bez opakovania |
| action (`@ActionMapping`, render parameter) | `POST` + `redirect:` (PRG) | action nemala výstup; v servlete to robí redirect |
| resource (`@ResourceMapping`, `ModelAndView` fragment) | AJAX endpoint `/resource/…` (fragment / JSON) | JS sa nemení, mení sa len URL |
| validačné chyby cez „implicit model“ v session | chyby sa vykreslia priamo (bez redirectu) | servlet MVC implicit model nemá |

## 3. URL tagy
- **Pred:** `portal:actionURL` pretypuje na `LiferayPortletURL` (`legacy/frontend-base-customization/.../tag/PortletUrlTagSupport.java`).
- **Po:** ten istý tag a atribúty, URL = `{ctx}{base}/{action|render|viewPage}` (`crm-web/.../web/tag/PortletUrlTag.java`).
- **Prečo:** ~7 000 použití v JSP sa nemení; skrytá Liferay závislosť zmizne na jednom mieste.

## 4. Session fasáda a identita
- **Pred:** singleton `SessionManagerCustom` – request z `RequestContextHolder`, identita zo SSO cez API JAR
  a service locator, ostatné v `PortletSession` + Redis (duálna session, flag)
  (`legacy/frontend-base-customization/.../session/`).
- **Po:** `CrmSession` ako parameter controllera, identita zo SSO cez REST, zdieľané kľúče len v Redise
  v **rovnakom JSON** (`crm-web/.../session/`).
- **Prečo:** controllery sa nemenia (rovnaký kontrakt); stará a nová appka si odovzdávajú stav cez Redis.
  Pri súbehu musí mať stará appka flag `REDIS`, inak nevidí zápisy novej (`docker/smoke-migration.sh`, kroky 6–7).

## 5. Formuláre a validácia
- **Pred:** vlastné `Validator` triedy volané v controlleri (`SearchCriteriaValidator`, `BlacklistCreateValidator`).
- **Po:** Bean Validation na formulári (`@NotBlank`, `@NotNull`, `@Past`, krížové pravidlá ako `@AssertTrue` metódy) + `@Valid`;
  povolenú podmienku pre dôvod overuje controller cez službu (`crm-web/.../blacklist/BlacklistCreateController.java`).
- **Prečo:** menej kódu, pravidlá pri poliach formulára; business pravidlo (povolená podmienka) ostáva v službe.

## 6. ACL
- **Pred:** privilégium portletu v `portlet.xml` + `checkPrivilege` v `BaseCrmController` + tagy `aclFilter`.
- **Po:** `@RequiresPrivilege` na metóde/triede, kontrola v interceptore; tagy rovnaké.
- **Prečo:** security sa podľa podkladov nemení – len miesto, kde sa kontroluje.

## 7. Kompozícia stránky
- **Pred:** theme (Velocity) + runtime portlet `header` (operátor, zákazník, hlášky).
- **Po:** `layout/header.jspf` + `LayoutModelInterceptor`.
- **Prečo:** bez portálu stránku neskladá portál; hlavička je fragment šablóny.

## 8. Chybové stavy
- **Pred:** `SimpleMappingExceptionResolverCustom` + hooked `status.jsp`; render bez SSO vykreslí len odkaz.
- **Po:** `GlobalExceptionHandler` (403/404/503) + `error.jsp`; bez SSO redirect na login (servlet to vie).

## 9. Nasadenie počas migrácie (strangler)
- nginx presmeruje `/web/crm/blacklist*` na `/crm/blacklist/search`; dashboard ostáva v Liferay.
- Nová appka má vlastnú session cookie (`CRM_SESSION`, `Path=/crm`) – inak prepíše `JSESSIONID` Liferay.
- Návrat späť (rollback) = odstrániť presmerovanie v nginx; dáta sa nemigrujú, session je zdieľaná.

## 10. Čo demo zámerne nerieši
- **Backend:** stará appka má backend v JVM Liferay; nová má vlastnú in-memory kópiu. Reálne by obe volali ten istý
  backend (REST adaptér alebo spoločná DB) – to je samostatné rozhodnutie (riziko č. 2 v `docs/ANALYZA.md`).
- Dashboard, Spring Security, cluster.

## Mapovacia tabuľka

| Liferay / portlet | Spring Boot (demo) |
|---|---|
| `portlet.xml`, `<portlet>-portlet.xml`, `DispatcherPortlet` | `@LegacyPortlet` + controllery, jeden `DispatcherServlet` |
| `@RenderMapping` / `@ActionMapping` / `@ResourceMapping` | `@GetMapping` / `@PostMapping` + redirect / AJAX endpoint |
| render parametre (`viewPage`, `action`) | cesta URL |
| `LiferayPortletURL` v tagoch | ten istý tag, servletová URL |
| `PortletSession` (portlet/application scope) | `HttpSession` (lokálne) + Redis (zdieľané kľúče) |
| `SessionManagerCustom` + SSO API JAR | `CrmSession` + SSO REST |
| vlastné `Validator` | Bean Validation |
| theme + portlet hlavičky | `header.jspf` + interceptor |
| exception resolver + hooked JSP | `@ControllerAdvice` + `error.jsp` |
| startup hook (site, stránky) | odpadá – stránky sú URL |
~~~

- [ ] **Step 3: Poznámky**

Na koniec `poznamky/07-pasce.md` pridaj (čísla podľa posledného bodu súboru, pokračuj od 19):

```markdown
19. **Flag zdroja čítania pri súbehu.** Kým stará appka číta `PORTLET`, nevidí nič, čo zapíše nová appka (tá starú
    session nemá). Flag treba prepnúť na `REDIS` najneskôr s nasadením prvej zmigrovanej stránky.
20. **JSON kontrakt medzi Java 8 a Java 21.** Zdieľané hodnoty sú len jednoduché DTO v JSON; nová appka musí ignorovať
    neznáme polia a znášať `null` (Jackson 2 vs. Jackson 3 sa líšia predvoľbami).
21. **Session cookie novej appky.** Za jedným hostom musí mať iný názov a cestu (`CRM_SESSION`, `/crm`) – inak prepíše
    `JSESSIONID` portálu.
22. **Liferay session prežije zmenu operátora v SSO.** Bez kontroly vlastníka (`SSO_ID`) vidí operátor B stav A.
```

Na koniec `poznamky/09-session-liferay-vs-spring.md` pridaj:

```markdown
## 8. Súbeh v praxi (demo)

Stará appka (`legacy/`) a nová (`crm-web/`) bežia za jedným nginx a zdieľajú hash `crm:session:{SSO_ID}`:
- nová appka zapisuje zdieľané kľúče len do Redisu, stará do PortletSession aj Redisu;
- stará appka číta podľa flagu – pri `PORTLET` nevidí zápisy novej, pri `REDIS` áno;
- identitu obe berú zo SSO (cookie `SSO_ID`), zákazníka mení len SSO portál.
Preklikanie: `crm-web/README.md`; automaticky: `docker/smoke-migration.sh`.
```

Na koniec `poznamky/06-playbook-migracie-portletu.md` pridaj:

```markdown
## Ukážka v deme
Celý postup na jednom portlete (blacklist) aj so súbehom starej a novej appky: `docs/MIGRACIA.md`
(pred → po po súboroch), `crm-web/README.md` (preklikanie), `docker/smoke-migration.sh` (overenie).
```

V `poznamky/README.md` nič nepribúda (súbory existujú); v riadku `06-…` doplň na koniec popisu „, ukážka v deme“.

- [ ] **Step 4: `README.md`, `CLAUDE.md`, `legacy/README.md`**

V `README.md` v tabuľke „Stav“ nahraď riadok `2b. …` týmto:

```markdown
| 2b. Zmigrovaná appka „po“ (Spring Boot) | [`crm-web/`](crm-web/README.md), [`docs/MIGRACIA.md`](docs/MIGRACIA.md) – [spec](docs/superpowers/specs/2026-10-12-crm-web-migration-design.md), [plán](docs/superpowers/plans/2026-10-12-crm-web-migration.md) | hotovo – blacklist beží v Spring Boot vedľa Liferay |
```

a do bloku „Štruktúra“ pridaj riadok `crm-web/     nová appka (Spring Boot 4.1.1) so zmigrovaným blacklistom – beží súčasne so starou`.

V `CLAUDE.md` v „Current status“ nahraď bod `2. **Spring Boot "after" app** – next: …` týmto:

```markdown
  2. **Spring Boot "after" app — done** (`crm-web/`; spec + plan `2026-10-12-crm-web-migration*`): blacklist migrated,
     dashboard stays in Liferay; nginx strangler `/web/crm/blacklist` → `/crm/blacklist/search`; shared SSO identity and
     Redis session (same JSON as legacy); legacy flag must be `REDIS` for cross-app state. Verify: `bash docker/smoke-migration.sh`.
```

a do sekcie „Build & run“ pridaj riadok:
`- New app: \`mvn -q -B -f crm-web/pom.xml verify\` (needs Docker for Testcontainers); stack smoke: \`bash docker/smoke-migration.sh\` → \`MIGRATION SMOKE OK\`.`
Sekciu „"After" app architecture and conventions (planned …)“ premenuj na „"After" app architecture and conventions“ a v jej
prvom bode nahraď „Single Maven WAR module at the repo root“ za „`crm-web/` Maven WAR module, context path `/crm`“.

Do `legacy/README.md` za sekciu „Zdieľaná session naživo“ pridaj:

```markdown
## Súbeh so zmigrovanou appkou
Blacklist je zmigrovaný do `../crm-web` – na :8000 ho nginx presmeruje do novej appky, starý portlet je na
http://localhost:8080/web/crm/blacklist. Návod na preklikanie súbehu: [`../crm-web/README.md`](../crm-web/README.md).
```

- [ ] **Step 5: Záverečné overenie**

Run: `mvn -q -B -f legacy/pom.xml verify && mvn -q -B -f sso-mock/pom.xml verify && mvn -q -B -f crm-web/pom.xml verify && bash docker/smoke.sh && bash docker/smoke-migration.sh`
Expected: všetko zelené, `SMOKE OK` a `MIGRATION SMOKE OK`.

- [ ] **Step 6: Anonymizačná kontrola** – `bash poznamky/LOCAL-anonymizacia.sh; echo "exit=$?"` → žiadny výstup, `exit=1`
  (ak skript chýba, uveď to v odovzdaní).

- [ ] **Step 7: Checkpoint** – `git status --short`
