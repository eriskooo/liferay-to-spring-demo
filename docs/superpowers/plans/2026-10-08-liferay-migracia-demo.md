# Demo migrácie portletov z Liferay na Spring Boot – implementačný plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Postaviť spustiteľné Spring Boot demo, ktoré ukazuje migráciu dvoch legacy Liferay portletov (blacklist ne-zákazníkov, zrušenie služby) na Spring MVC + JSP, s testami a dokumentáciou „pred → po“.

**Architecture:** Jeden Maven modul (WAR) so Spring Boot 4.1.1. Portletové fázy sa mapujú na Spring MVC: render = GET, action = POST + redirect (PRG), resource = AJAX endpoint (HTML fragment alebo JSON). Vlastné JSP tagy `portal:*` a `common:*` majú rovnaké rozhranie ako v legacy, ale sú implementované bez Liferay. Session fasáda a ACL zachovávajú legacy kontrakt. Backend služby sú porty (interface) s in-memory implementáciou.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `-validation`, `-webmvc-test`), Tomcat 11 + Jasper (JSP), Jakarta JSTL, JUnit 5, MockMvc, `java.net.http.HttpClient` pre testy s reálnym serverom.

**Spec:** [`docs/superpowers/specs/2026-10-08-liferay-migracia-demo-design.md`](../specs/2026-10-08-liferay-migracia-demo-design.md)

## Global Constraints

- Java **21**, Spring Boot **4.1.1** (`spring-boot-starter-parent`), balenie **war**, `groupId=com.example.crm`, `artifactId=crm-web-demo`, `version=0.1.0-SNAPSHOT`, koreňový balík `com.example.crm`.
- Maven projekt je v **koreni repa** (`pom.xml`, `src/`). Všetky príkazy `mvn` sa spúšťajú z `C:\projekty\tmp\liferay-to-spring-demo`.
- **NIKDY nespúšťaj `git commit` ani `git push`.** Commituje používateľ. Krok „Checkpoint“ je len `git status --short` na kontrolu.
- **`input/` je read-only a NEČÍTA sa** pri implementácii. Všetok kód a ukážky „pred“ sú v tomto pláne napísané nanovo. Žiadne názvy klienta, dodávateľa, ľudí, interných systémov, interné URL, heslá ani dáta z logov (pravidlá: `poznamky/00-konvencie.md`).
- Jazyk: identifikátory v kóde po anglicky; komentáre, texty UI (`messages.properties`) a dokumentácia po **slovensky**.
- Komentáre v kóde vysvetľujú **prečo** (migračné rozhodnutie, legacy ekvivalent), nie čo kód robí.
- JSP sú v `src/main/webapp/WEB-INF/jsp/`, TLD v `src/main/webapp/WEB-INF/tld/`, statické súbory v `src/main/resources/static/`.
- Každá JSP stránka začína `<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>` a nemá vlastnú `page` direktívu.
- Testy zdieľajú in-memory stav v rámci jedného Spring kontextu. Test, ktorý mení dáta, používa **vlastný záznam** podľa tabuľky v Tasku 6.

## Review Focus

1. **Diakritika vo vstupe** (POST `firstName=Mária`) musí nájsť záznam a stránka ju musí zobraziť správne. Test s reálnym Tomcatom je v Tasku 12 (`searchWithDiacriticsFindsRecord`).
2. **AJAX po vypršaní session** musí dostať HTTP 401, nie presmerovanie na login. Inak by JS vložil prihlasovaciu stránku do `<div>`. Test je v Tasku 5 (`ajaxWithoutLoginGets401`) a v Tasku 12 (`ajaxWithoutSessionGets401`).
3. **Podvrhnuté ID**: identifikátor iného záznamu pri AJAX odstraňovaní a produkt iného zákazníka musia skončiť HTTP 404, nie zmenou dát. Testy sú v Tasku 8 (`removeForeignIdentifierIs404`) a v Tasku 11 (`foreignProductIs404`).
4. **Nečíselné ID v URL** (`detailId=abc`) musí vrátiť HTTP 400, nie 500. Test je v Tasku 8 (`nonNumericDetailIdIs400`).
5. **HTML vo vstupe** (meno `<b>Zlý</b>`) musí byť na stránke escapované. Test s reálnym Tomcatom je v Tasku 12 (`createEscapesHtmlInput`).

---

## Štruktúra súborov

```
pom.xml
src/main/java/com/example/crm/
  CrmDemoApplication.java
  common/ClockConfig.java, NotFoundException.java
  session/CrmSession.java, HttpSessionCrmSession.java, OperatorUser.java, CustomerContext.java, UiMessage.java
  acl/Privilege.java, AclService.java, InMemoryAclService.java, RequiresPrivilege.java, AclDeniedException.java
  web/WebConfig.java, WebRequests.java, CrmSessionArgumentResolver.java, GlobalExceptionHandler.java
  web/portlet/LegacyPortlet.java, PortletRequestAttributes.java, LegacyPortletInterceptor.java,
             CustomerContextInterceptor.java, LayoutModelInterceptor.java
  web/tag/PortletUrlTag.java, ActionUrlTag.java, RenderUrlTag.java, ResourceUrlTag.java, UrlParamTag.java,
          MessageTag.java, MessageArgTag.java, AclTagSupport.java, AclFilterTag.java, AclPageFilterTag.java
  login/DemoDirectory.java, FakeLoginController.java
  blacklist/(doména, služba, formuláre, 3 controllery)
  deactivation/(doména, pravidlá, služby, formulár, 2 controllery)
src/main/resources/application.properties, messages.properties, static/css/crm.css, static/js/crm.js
src/main/webapp/WEB-INF/tld/portal.tld, common.tld
src/main/webapp/WEB-INF/jsp/layout/{taglibs,header,footer}.jspf, login.jsp, error.jsp, error/{403,403-fragment,404}.jsp,
  blacklist/{search,searchresult,detail,create}.jsp, blacklist/fragments/{identifiers,conditionTypes}.jsp,
  dashboard/products.jsp, dashboard/deactivation/{form,done}.jsp
src/test/java/com/example/crm/... (testy pri každom tasku), com/example/testsupport/ProbeController.java
README.md, docs/MIGRACIA.md, poznamky/08-ponaucenia-z-implementacie.md
```

---

### Task 1: Kostra projektu (Spring Boot 4.1.1 WAR + statické súbory + texty)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/crm/CrmDemoApplication.java`
- Create: `src/main/java/com/example/crm/common/ClockConfig.java`
- Create: `src/main/java/com/example/crm/common/NotFoundException.java`
- Create: `src/main/resources/application.properties`
- Create: `src/main/resources/messages.properties`
- Create: `src/main/resources/static/css/crm.css`
- Create: `src/main/resources/static/js/crm.js`
- Test: `src/test/java/com/example/crm/ApplicationSmokeTest.java`

**Interfaces:**
- Produces: bean `java.time.Clock` (systémové hodiny); `com.example.crm.common.NotFoundException(String what)` (RuntimeException → neskôr HTTP 404); všetky i18n kľúče v `messages.properties` (ďalšie tasky ich len používajú, nepridávajú).

- [ ] **Step 1: Vytvor `pom.xml`**

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
    <artifactId>crm-web-demo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <!-- WAR: JSP v Spring Boot funguje len pri WAR balení (spustiteľný cez java -jar aj nasaditeľný do kontajnera). -->
    <packaging>war</packaging>
    <name>crm-web-demo</name>
    <description>Demo migrácie Liferay portletov na Spring Boot (Spring MVC + JSP)</description>

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
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Napíš padajúci smoke test**

`src/test/java/com/example/crm/ApplicationSmokeTest.java`:

```java
package com.example.crm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationSmokeTest {

    @LocalServerPort
    int port;

    @Test
    void servesStaticAssetsThatReplaceLiferayTheme() throws Exception {
        assertThat(get("/css/crm.css")).contains("--accent");
        assertThat(get("/js/crm.js")).contains("X-Requested-With");
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

Run: `mvn -q -B test -Dtest=ApplicationSmokeTest`
Expected: FAIL. Kompilácia hlási, že nevie nájsť `@SpringBootConfiguration` (aplikačná trieda ešte neexistuje).

- [ ] **Step 4: Vytvor aplikačnú triedu, konfiguráciu a spoločné triedy**

`src/main/java/com/example/crm/CrmDemoApplication.java`:

```java
package com.example.crm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Vstupný bod dema.
 *
 * <p>Dedí zo {@link SpringBootServletInitializer}, aby sa ten istý WAR dal nasadiť aj do externého
 * servlet kontajnera (podklady riešia otázku nasadenia na aplikačný server) – nielen spustiť cez {@code java -jar}.
 */
@SpringBootApplication
public class CrmDemoApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(CrmDemoApplication.class, args);
    }
}
```

`src/main/java/com/example/crm/common/ClockConfig.java`:

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

`src/main/java/com/example/crm/common/NotFoundException.java`:

```java
package com.example.crm.common;

/**
 * Záznam neexistuje alebo nepatrí aktuálnemu kontextu (zákazníkovi). Mapuje sa na HTTP 404.
 *
 * <p>Je v spoločnom balíku zámerne: hádžu ju backend porty aj controllery a web vrstva ju prekladá na odpoveď.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super("Nenájdené: " + what);
    }
}
```

`src/main/resources/application.properties`:

```properties
spring.application.name=crm-web-demo

# JSP views – rovnaké umiestnenie ako v legacy portlet WARe (/WEB-INF/jsp/...)
spring.mvc.view.prefix=/WEB-INF/jsp/
spring.mvc.view.suffix=.jsp

# i18n – náhrada prekladov z DB/CMS jedným súborom (preklady z DB/CMS sú mimo rozsahu dema)
spring.messages.basename=messages
spring.messages.encoding=UTF-8
spring.messages.fallback-to-system-locale=false

server.servlet.session.cookie.name=CRMDEMO_SESSION
# Session len cez cookie – bez ;jsessionid v URL (inak by ho Tomcat pridával do redirectov a odkazov pri prvom requeste)
server.servlet.session.tracking-modes=cookie
# Vlastná chybová stránka WEB-INF/jsp/error.jsp namiesto Whitelabel (náhrada legacy hook-u s chybovými JSP)
server.error.whitelabel.enabled=false
```

`src/main/resources/messages.properties` (všetky texty dema – ďalšie tasky ich len používajú):

```properties
app.name=CRM demo (bez Liferay)
nav.blacklist=Blacklist ne-zákazníkov
nav.products=Produkty zákazníka
header.customer=Zákazník
header.logout=Odhlásiť

login.title=Prihlásenie (fake login)
login.hint=Toto nie je autentifikácia. Je to ekvivalent legacy „fake login“ portletu: vyberte operátora a zákazníka, s ktorým budete pracovať.
login.operator=Operátor
login.customer=Zákazník
login.submit=Pokračovať
login.welcome=Prihlásenie prebehlo, zákaznícky kontext je nastavený.

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

dashboard.products.title=Produkty zákazníka
dashboard.products.hint=Kliknite na tarif alebo produkt, ktorý chcete zrušiť.
dashboard.productType.TARIFF=Tarif
dashboard.productType.PRODUCT=Produkt

deactivation.title=Zrušenie služby
deactivation.product=Názov služby
deactivation.description=Ruší sa
deactivation.noticeDate=Dátum podania
deactivation.effectiveDate=Ku dňu
deactivation.type=Typ žiadosti
deactivation.type.TRANSFER_TO_CUSTOMER=Previesť na iného zákazníka
deactivation.type.CUSTOMER_DEACTIVATION=Žiadam o zrušenie služby
deactivation.type.CUSTOMER_POSTPRE=Žiadam o prevod na predplatenú kartu
deactivation.submit=Dokončiť
deactivation.noticeInFuture=Dátum podania nesmie byť v budúcnosti.
deactivation.effectiveTooEarly=Najskorší možný dátum pre zvolený typ je {0}.
deactivation.submitted=Žiadosť o zrušenie služby bola odoslaná.
deactivation.done.title=Žiadosť odoslaná
deactivation.done.operator=Zadal
deactivation.backToProducts=Späť na produkty
```

`src/main/resources/static/css/crm.css`:

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
```

`src/main/resources/static/js/crm.js`:

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

Run: `mvn -q -B test -Dtest=ApplicationSmokeTest`
Expected: PASS (BUILD SUCCESS, žiadny výstup chýb).

- [ ] **Step 6: Checkpoint (bez commitu)**

Run: `git status --short`
Expected: nové `pom.xml`, `src/`; `target/` sa nezobrazuje (je v `.gitignore`).

---

### Task 2: Session fasáda (`CrmSession`) nad `HttpSession`

**Files:**
- Create: `src/main/java/com/example/crm/session/OperatorUser.java`
- Create: `src/main/java/com/example/crm/session/CustomerContext.java`
- Create: `src/main/java/com/example/crm/session/UiMessage.java`
- Create: `src/main/java/com/example/crm/session/CrmSession.java`
- Create: `src/main/java/com/example/crm/session/HttpSessionCrmSession.java`
- Test: `src/test/java/com/example/crm/session/HttpSessionCrmSessionTest.java`

**Interfaces:**
- Produces:
  - `record OperatorUser(String login, String displayName)`
  - `record CustomerContext(String customerId, String displayName)`
  - `record UiMessage(UiMessage.Level level, String key)`, `enum Level { INFO, ERROR }`
  - `interface CrmSession`: `setOperator(OperatorUser)`, `Optional<OperatorUser> getOperator()`, `setCustomer(CustomerContext)`, `Optional<CustomerContext> getCustomer()`, `setGlobalAttribute(String, Serializable)`, `<T> Optional<T> getGlobalAttribute(String, Class<T>)`, `removeGlobalAttribute(String)`, `pushInfo(String)`, `pushError(String)`, `List<UiMessage> popMessages()`, `invalidate()`
  - `HttpSessionCrmSession.of(HttpServletRequest): CrmSession`; konštanty `PREFIX="APP_"`, `OPERATOR="APP_OPERATOR"`, `CUSTOMER="APP_CUSTOMER"`, `MESSAGES="APP_MESSAGES"` (package-private)

- [ ] **Step 1: Napíš padajúci test**

`src/test/java/com/example/crm/session/HttpSessionCrmSessionTest.java`:

```java
package com.example.crm.session;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSessionCrmSessionTest {

    @Test
    void storesOperatorAndCustomerUnderPrefixedKeys() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CrmSession session = HttpSessionCrmSession.of(request);

        session.setOperator(new OperatorUser("spravca", "Správca"));
        session.setCustomer(new CustomerContext("C-1001", "Ján Vzorový"));

        assertThat(request.getSession().getAttribute("APP_OPERATOR")).isEqualTo(new OperatorUser("spravca", "Správca"));
        assertThat(session.getOperator()).contains(new OperatorUser("spravca", "Správca"));
        assertThat(session.getCustomer()).map(CustomerContext::customerId).contains("C-1001");
    }

    @Test
    void readingDoesNotCreateHttpSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(HttpSessionCrmSession.of(request).getOperator()).isEmpty();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void messagesSurviveRedirectAndArePoppedOnce() {
        MockHttpSession httpSession = new MockHttpSession();
        CrmSession actionRequest = HttpSessionCrmSession.of(requestWith(httpSession));
        actionRequest.pushInfo("blacklist.detail.changed");
        actionRequest.pushError("blacklist.detail.lastIdentifier");

        CrmSession renderRequest = HttpSessionCrmSession.of(requestWith(httpSession));

        assertThat(renderRequest.popMessages()).containsExactly(
                new UiMessage(UiMessage.Level.INFO, "blacklist.detail.changed"),
                new UiMessage(UiMessage.Level.ERROR, "blacklist.detail.lastIdentifier"));
        assertThat(renderRequest.popMessages()).isEmpty();
    }

    @Test
    void globalAttributesArePrefixedAndTypeChecked() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CrmSession session = HttpSessionCrmSession.of(request);

        session.setGlobalAttribute("LAST_SEARCH", "Vzorka");

        assertThat(request.getSession().getAttribute("APP_LAST_SEARCH")).isEqualTo("Vzorka");
        assertThat(session.getGlobalAttribute("LAST_SEARCH", String.class)).contains("Vzorka");
        assertThat(session.getGlobalAttribute("LAST_SEARCH", Integer.class)).isEmpty();

        session.removeGlobalAttribute("LAST_SEARCH");
        assertThat(session.getGlobalAttribute("LAST_SEARCH", String.class)).isEmpty();
    }

    @Test
    void invalidateEndsHttpSession() {
        MockHttpSession httpSession = new MockHttpSession();
        HttpSessionCrmSession.of(requestWith(httpSession)).invalidate();

        assertThat(httpSession.isInvalid()).isTrue();
    }

    private static MockHttpServletRequest requestWith(MockHttpSession httpSession) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(httpSession);
        return request;
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B test -Dtest=HttpSessionCrmSessionTest`
Expected: FAIL – kompilácia: `cannot find symbol: class HttpSessionCrmSession`.

- [ ] **Step 3: Implementuj**

`src/main/java/com/example/crm/session/OperatorUser.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Interný používateľ (operátor). V legacy ho do session zapisovalo SSO portál cez fasádu. */
public record OperatorUser(String login, String displayName) implements Serializable {
}
```

`src/main/java/com/example/crm/session/CustomerContext.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Zákazník, s ktorým operátor práve pracuje („zákaznícky kontext“ v legacy session). */
public record CustomerContext(String customerId, String displayName) implements Serializable {
}
```

`src/main/java/com/example/crm/session/UiMessage.java`:

```java
package com.example.crm.session;

import java.io.Serializable;

/** Hláška pre používateľa (i18n kľúč) – legacy pushInfo/pushError zobrazované layoutom portálu. */
public record UiMessage(Level level, String key) implements Serializable {

    public enum Level { INFO, ERROR }
}
```

`src/main/java/com/example/crm/session/CrmSession.java`:

```java
package com.example.crm.session;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;

/**
 * Session fasáda – zachovaný KONTRAKT legacy session managera, nová implementácia.
 *
 * <p>Podklady migrácie: stovky controllerov pracujú so session len cez fasádu, preto sa jej rozhranie
 * nemení a vymení sa iba implementácia (preč od PortletSession a session SSO portálu).
 * Demo má zmenšený rozsah: identita operátora, zákaznícky kontext, všeobecné atribúty a hlášky.
 */
public interface CrmSession {

    void setOperator(OperatorUser operator);

    Optional<OperatorUser> getOperator();

    void setCustomer(CustomerContext customer);

    Optional<CustomerContext> getCustomer();

    void setGlobalAttribute(String key, Serializable value);

    <T> Optional<T> getGlobalAttribute(String key, Class<T> type);

    void removeGlobalAttribute(String key);

    void pushInfo(String messageKey);

    void pushError(String messageKey);

    /** Vráti a odstráni čakajúce hlášky – zobrazia sa práve raz (aj cez redirect). */
    List<UiMessage> popMessages();

    void invalidate();
}
```

`src/main/java/com/example/crm/session/HttpSessionCrmSession.java`:

```java
package com.example.crm.session;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Implementácia fasády nad {@link HttpSession} aktuálneho requestu.
 *
 * <p>Kľúče majú prefix {@value #PREFIX} – podklady navrhujú pevné prefixy na oddelenie „aplikačných“
 * kľúčov od portálových. V produkcii by tu bol Spring Session + Redis (zdieľanie medzi uzlami clustra)
 * a feature flag „zdroj čítania“ (legacy vs. nová session); to je mimo rozsahu dema.
 */
public final class HttpSessionCrmSession implements CrmSession {

    static final String PREFIX = "APP_";
    static final String OPERATOR = PREFIX + "OPERATOR";
    static final String CUSTOMER = PREFIX + "CUSTOMER";
    static final String MESSAGES = PREFIX + "MESSAGES";

    private final HttpServletRequest request;

    private HttpSessionCrmSession(HttpServletRequest request) {
        this.request = request;
    }

    public static CrmSession of(HttpServletRequest request) {
        return new HttpSessionCrmSession(request);
    }

    @Override
    public void setOperator(OperatorUser operator) {
        session().setAttribute(OPERATOR, operator);
    }

    @Override
    public Optional<OperatorUser> getOperator() {
        return Optional.ofNullable((OperatorUser) read(OPERATOR));
    }

    @Override
    public void setCustomer(CustomerContext customer) {
        session().setAttribute(CUSTOMER, customer);
    }

    @Override
    public Optional<CustomerContext> getCustomer() {
        return Optional.ofNullable((CustomerContext) read(CUSTOMER));
    }

    @Override
    public void setGlobalAttribute(String key, Serializable value) {
        session().setAttribute(PREFIX + key, value);
    }

    @Override
    public <T> Optional<T> getGlobalAttribute(String key, Class<T> type) {
        Object value = read(PREFIX + key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public void removeGlobalAttribute(String key) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.removeAttribute(PREFIX + key);
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
    public List<UiMessage> popMessages() {
        HttpSession existing = request.getSession(false);
        if (existing == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<UiMessage> messages = (List<UiMessage>) existing.getAttribute(MESSAGES);
        existing.removeAttribute(MESSAGES);
        return messages == null ? List.of() : List.copyOf(messages);
    }

    @Override
    public void invalidate() {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
    }

    private void push(UiMessage message) {
        @SuppressWarnings("unchecked")
        List<UiMessage> current = (List<UiMessage>) session().getAttribute(MESSAGES);
        ArrayList<UiMessage> messages = current == null ? new ArrayList<>() : new ArrayList<>(current);
        messages.add(message);
        // Atribút sa vždy znova nastaví (nie len upraví zoznam) – replikovaná session (Redis) inak zmenu nezaznamená.
        session().setAttribute(MESSAGES, messages);
    }

    private HttpSession session() {
        return request.getSession(true);
    }

    private Object read(String key) {
        HttpSession existing = request.getSession(false);
        return existing == null ? null : existing.getAttribute(key);
    }
}
```

- [ ] **Step 4: Spusti test, musí prejsť**

Run: `mvn -q -B test -Dtest=HttpSessionCrmSessionTest`
Expected: PASS.

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 3: ACL privilégiá (náhrada vlastného ACL legacy aplikácie)

**Files:**
- Create: `src/main/java/com/example/crm/acl/Privilege.java`
- Create: `src/main/java/com/example/crm/acl/AclService.java`
- Create: `src/main/java/com/example/crm/acl/InMemoryAclService.java`
- Create: `src/main/java/com/example/crm/acl/RequiresPrivilege.java`
- Create: `src/main/java/com/example/crm/acl/AclDeniedException.java`
- Test: `src/test/java/com/example/crm/acl/InMemoryAclServiceTest.java`

**Interfaces:**
- Consumes: `OperatorUser` (Task 2)
- Produces:
  - `enum Privilege { BLACKLIST_VIEW, BLACKLIST_MODIFY, DEACTIVATION }`
  - `interface AclService { boolean hasPrivilege(OperatorUser, Privilege); Set<Privilege> privilegesOf(OperatorUser); }`
  - `@Service InMemoryAclService` s no-arg konštruktorom. Loginy: `citatel` → {BLACKLIST_VIEW}, `spravca` → všetky tri.
  - `@RequiresPrivilege(Privilege value)` (TYPE, METHOD, RUNTIME)
  - `AclDeniedException(Privilege)` s `getPrivilege()`

- [ ] **Step 1: Napíš padajúci test**

`src/test/java/com/example/crm/acl/InMemoryAclServiceTest.java`:

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

Run: `mvn -q -B test -Dtest=InMemoryAclServiceTest`
Expected: FAIL – `cannot find symbol: class InMemoryAclService`.

- [ ] **Step 3: Implementuj**

`src/main/java/com/example/crm/acl/Privilege.java`:

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

`src/main/java/com/example/crm/acl/AclService.java`:

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

`src/main/java/com/example/crm/acl/InMemoryAclService.java`:

```java
package com.example.crm.acl;

import java.util.Map;
import java.util.Set;

import com.example.crm.session.OperatorUser;
import org.springframework.stereotype.Service;

/** Pevné privilégiá demo operátorov (loginy zodpovedajú {@code DemoDirectory}). */
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

`src/main/java/com/example/crm/acl/RequiresPrivilege.java`:

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

`src/main/java/com/example/crm/acl/AclDeniedException.java`:

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

Run: `mvn -q -B test -Dtest=InMemoryAclServiceTest`
Expected: PASS.

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 4: JSP tagy s rovnakým rozhraním ako legacy (`portal:*`, `common:*`)

**Files:**
- Create: `src/main/java/com/example/crm/web/portlet/PortletRequestAttributes.java`
- Create: `src/main/java/com/example/crm/web/tag/PortletUrlTag.java`
- Create: `src/main/java/com/example/crm/web/tag/ActionUrlTag.java`
- Create: `src/main/java/com/example/crm/web/tag/RenderUrlTag.java`
- Create: `src/main/java/com/example/crm/web/tag/ResourceUrlTag.java`
- Create: `src/main/java/com/example/crm/web/tag/UrlParamTag.java`
- Create: `src/main/java/com/example/crm/web/tag/MessageTag.java`
- Create: `src/main/java/com/example/crm/web/tag/MessageArgTag.java`
- Create: `src/main/java/com/example/crm/web/tag/AclTagSupport.java`
- Create: `src/main/java/com/example/crm/web/tag/AclFilterTag.java`
- Create: `src/main/java/com/example/crm/web/tag/AclPageFilterTag.java`
- Create: `src/main/webapp/WEB-INF/tld/portal.tld`
- Create: `src/main/webapp/WEB-INF/tld/common.tld`
- Test: `src/test/java/com/example/crm/web/tag/TagTestSupport.java`
- Test: `src/test/java/com/example/crm/web/tag/PortletUrlTagTest.java`
- Test: `src/test/java/com/example/crm/web/tag/MessageTagTest.java`
- Test: `src/test/java/com/example/crm/web/tag/AclTagsTest.java`

**Interfaces:**
- Consumes: `HttpSessionCrmSession.of`, `OperatorUser` (Task 2); `AclService`, `Privilege`, `InMemoryAclService` (Task 3)
- Produces:
  - `PortletRequestAttributes.BASE_PATH = "crm.portlet.basePath"` (request atribút, String bez context path, napr. `/blacklist`)
  - TLD URI `http://example.com/crm/tags/portal` (prefix `portal`): `actionURL`(var, name, escapeXml), `renderURL`(var, escapeXml, copyCurrentRenderParameters), `resourceURL`(var, id, escapeXml), `param`(name, value)
  - TLD URI `http://example.com/crm/tags/common` (prefix `common`): `message`(key, var, showMessageOnlyIfExists), `msgarg`(value), `aclFilter`(privilegeType, aclResource), `aclPageFilterTag`(privilegeType, wireframe)
  - Konvencia URL: actionURL → `{ctx}{base}/{param action | atribút name}`; renderURL → `{ctx}{base}/{param render | viewPage}`; resourceURL → `{ctx}{base}/resource/{id}`; ostatné parametre idú do query stringu (UTF-8 URL-encoded); `viewPage` sa pri actionURL zahodí. Segment musí zodpovedať `[A-Za-z0-9_-]+`, inak `JspException`.

- [ ] **Step 1: Napíš testovaciu podporu a padajúce testy**

`src/test/java/com/example/crm/web/tag/TagTestSupport.java`:

```java
package com.example.crm.web.tag;

import java.util.Locale;

import com.example.crm.acl.InMemoryAclService;
import com.example.crm.session.HttpSessionCrmSession;
import com.example.crm.session.OperatorUser;
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
        HttpSessionCrmSession.of(request).setOperator(new OperatorUser(login, login));
    }

    String output() throws Exception {
        pageContext.getOut().flush();
        return response.getContentAsString();
    }
}
```

`src/test/java/com/example/crm/web/tag/PortletUrlTagTest.java`:

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

`src/test/java/com/example/crm/web/tag/MessageTagTest.java`:

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

`src/test/java/com/example/crm/web/tag/AclTagsTest.java`:

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

Run: `mvn -q -B test -Dtest="PortletUrlTagTest,MessageTagTest,AclTagsTest"`
Expected: FAIL – kompilácia: `cannot find symbol` (`ActionUrlTag`, `PortletRequestAttributes`, …).

- [ ] **Step 3: Implementuj URL tagy**

`src/main/java/com/example/crm/web/portlet/PortletRequestAttributes.java`:

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

`src/main/java/com/example/crm/web/tag/PortletUrlTag.java`:

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
                pageContext.getOut().write(escapeXml ? HtmlUtils.htmlEscape(url) : url);
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

`src/main/java/com/example/crm/web/tag/ActionUrlTag.java`:

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

`src/main/java/com/example/crm/web/tag/RenderUrlTag.java`:

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

`src/main/java/com/example/crm/web/tag/ResourceUrlTag.java`:

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

`src/main/java/com/example/crm/web/tag/UrlParamTag.java`:

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

`src/main/java/com/example/crm/web/tag/MessageTag.java`:

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
                pageContext.getOut().write(HtmlUtils.htmlEscape(text));
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

`src/main/java/com/example/crm/web/tag/MessageArgTag.java`:

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

`src/main/java/com/example/crm/web/tag/AclTagSupport.java`:

```java
package com.example.crm.web.tag;

import java.util.Optional;

import com.example.crm.acl.AclService;
import com.example.crm.acl.Privilege;
import com.example.crm.session.HttpSessionCrmSession;
import com.example.crm.session.OperatorUser;
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
        Optional<OperatorUser> operator = HttpSessionCrmSession.of(request).getOperator();
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

`src/main/java/com/example/crm/web/tag/AclFilterTag.java`:

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

`src/main/java/com/example/crm/web/tag/AclPageFilterTag.java`:

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
                    + HtmlUtils.htmlEscape(AclTagSupport.message(pageContext, "acl.pageDenied", privilegeType)) + "</p>");
        } catch (IOException e) {
            throw new JspException(e);
        }
        return SKIP_PAGE;
    }
}
```

- [ ] **Step 5: Vytvor TLD súbory**

`src/main/webapp/WEB-INF/tld/portal.tld`:

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

`src/main/webapp/WEB-INF/tld/common.tld`:

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

Run: `mvn -q -B test -Dtest="PortletUrlTagTest,MessageTagTest,AclTagsTest"`
Expected: PASS (21 testov). Ak `writesEscapedUrlWhenNoVar` vráti prázdny reťazec, `TagTestSupport.output()` nevolá `flush()`. Over, že tam je.

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 5: Web infraštruktúra – „portlet“ anotácia, interceptory, fake login, layout, chybové stránky

**Files:**
- Create: `src/main/java/com/example/crm/web/portlet/LegacyPortlet.java`
- Create: `src/main/java/com/example/crm/web/portlet/LegacyPortletInterceptor.java`
- Create: `src/main/java/com/example/crm/web/portlet/CustomerContextInterceptor.java`
- Create: `src/main/java/com/example/crm/web/portlet/LayoutModelInterceptor.java`
- Create: `src/main/java/com/example/crm/web/WebRequests.java`
- Create: `src/main/java/com/example/crm/web/CrmSessionArgumentResolver.java`
- Create: `src/main/java/com/example/crm/web/WebConfig.java`
- Create: `src/main/java/com/example/crm/web/GlobalExceptionHandler.java`
- Create: `src/main/java/com/example/crm/login/DemoDirectory.java`
- Create: `src/main/java/com/example/crm/login/FakeLoginController.java`
- Create: `src/main/webapp/WEB-INF/jsp/layout/taglibs.jspf`, `header.jspf`, `footer.jspf`
- Create: `src/main/webapp/WEB-INF/jsp/login.jsp`, `error.jsp`, `error/403.jsp`, `error/403-fragment.jsp`, `error/404.jsp`
- Test: `src/test/java/com/example/testsupport/ProbeController.java`
- Test: `src/test/java/com/example/crm/TestSessions.java`
- Test: `src/test/java/com/example/crm/it/WebClient.java`
- Test: `src/test/java/com/example/crm/web/WebInfrastructureTest.java`
- Test: `src/test/java/com/example/crm/it/LoginPageRenderingTest.java`

**Interfaces:**
- Consumes: Tasks 1–4 (`CrmSession`, `HttpSessionCrmSession`, `AclService`, `RequiresPrivilege`, `AclDeniedException`, `NotFoundException`, `PortletRequestAttributes.BASE_PATH`, TLD URI)
- Produces:
  - `@LegacyPortlet(String value)` na triede controllera; value = base path, môže obsahovať `{premenné}` z URL
  - Controller metódy môžu mať parameter `CrmSession` (dodá `CrmSessionArgumentResolver`)
  - Model každej ne-AJAX, ne-redirect stránky obsahuje `layoutOperator` (OperatorUser|null), `layoutCustomer` (CustomerContext|null), `layoutMessages` (List<UiMessage>)
  - Správanie: `@LegacyPortlet` handler bez operátora → 302 `/login`, AJAX → 401; `{customerId}` ≠ zákazník v session → 302 `/dashboard/{vlastný}/products`; chýba privilégium → 403 view `error/403` (AJAX `error/403-fragment`); `NotFoundException` → 404 view `error/404`
  - `WebRequests.isAjax(HttpServletRequest)` = hlavička `X-Requested-With: XMLHttpRequest`
  - `DemoDirectory.READER` = `OperatorUser("citatel","Čitateľ (len prezeranie)")`, `ADMIN` = `OperatorUser("spravca","Správca (všetky práva)")`, `PERSON` = `CustomerContext("C-1001","Ján Vzorový")`, `COMPANY` = `CustomerContext("C-1002","Príklad s.r.o.")`
  - Testy: `TestSessions.admin()`, `TestSessions.reader()`, `TestSessions.loggedIn(OperatorUser, CustomerContext)` → `MockHttpSession`; `WebClient(int port)` s `get(path, headers...)`, `post(path, Map form, headers...)`, `static assertNoMissingMessages(String body)`
  - JSP konvencia: stránka = `taglibs.jspf` → `<c:set var="titleKey" .../>` → `header.jspf` → obsah → `footer.jspf`

- [ ] **Step 1: Napíš testovacie pomôcky a padajúce testy**

`src/test/java/com/example/testsupport/ProbeController.java` (mimo `com.example.crm`, aby ho component scan nenašiel – testy ho importujú explicitne):

```java
package com.example.testsupport;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.web.portlet.LegacyPortlet;
import com.example.crm.web.portlet.PortletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** Testovací „portlet“ na overenie interceptorov bez závislosti na skutočných obrazovkách. */
@Controller
@LegacyPortlet("/probe/{customerId}")
@RequestMapping("/probe/{customerId}")
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
}
```

`src/test/java/com/example/crm/TestSessions.java`:

```java
package com.example.crm;

import com.example.crm.login.DemoDirectory;
import com.example.crm.session.CrmSession;
import com.example.crm.session.CustomerContext;
import com.example.crm.session.HttpSessionCrmSession;
import com.example.crm.session.OperatorUser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

/** HTTP session „po fake logine“ pre MockMvc testy. */
public final class TestSessions {

    private TestSessions() {
    }

    public static MockHttpSession loggedIn(OperatorUser operator, CustomerContext customer) {
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        CrmSession crm = HttpSessionCrmSession.of(request);
        crm.setOperator(operator);
        crm.setCustomer(customer);
        return session;
    }

    public static MockHttpSession admin() {
        return loggedIn(DemoDirectory.ADMIN, DemoDirectory.PERSON);
    }

    public static MockHttpSession reader() {
        return loggedIn(DemoDirectory.READER, DemoDirectory.PERSON);
    }

    /** Hlášky čakajúce v session (bez ich spotrebovania nie je čo testovať – preto pop). */
    public static java.util.List<com.example.crm.session.UiMessage> popMessages(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return HttpSessionCrmSession.of(request).popMessages();
    }
}
```

`src/test/java/com/example/crm/it/WebClient.java`:

```java
package com.example.crm.it;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Prehliadač „bez JavaScriptu“ pre testy s reálnym Tomcatom: cookies (session) + nasledovanie redirectov. */
public final class WebClient {

    public static final String[] AJAX = {"X-Requested-With", "XMLHttpRequest"};

    private final HttpClient http;
    private final String baseUrl;

    public WebClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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

`src/test/java/com/example/crm/web/WebInfrastructureTest.java`:

```java
package com.example.crm.web;

import com.example.crm.TestSessions;
import com.example.crm.session.UiMessage;
import com.example.testsupport.ProbeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProbeController.class)
class WebInfrastructureTest {

    @Autowired
    MockMvc mvc;

    @Test
    void portletWithoutLoginRedirectsToLogin() throws Exception {
        mvc.perform(get("/probe/C-1001/open")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void ajaxWithoutLoginGets401() throws Exception {
        mvc.perform(get("/probe/C-1001/open").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void basePathExpandsUriVariables() throws Exception {
        mvc.perform(get("/probe/C-1001/open").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(content().string("/probe/C-1001"));
    }

    @Test
    void foreignCustomerRedirectsToOwnDashboard() throws Exception {
        mvc.perform(get("/probe/C-9999/open").session(TestSessions.admin()))
                .andExpect(redirectedUrl("/dashboard/C-1001/products"));
    }

    @Test
    void missingPrivilegeRenders403Page() throws Exception {
        mvc.perform(get("/probe/C-1001/modify").session(TestSessions.reader()))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403.jsp"))
                .andExpect(model().attribute("privilege", com.example.crm.acl.Privilege.BLACKLIST_MODIFY));
    }

    @Test
    void ajaxMissingPrivilegeGets403WithoutLayout() throws Exception {
        mvc.perform(get("/probe/C-1001/modify").session(TestSessions.reader())
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403-fragment.jsp"));
    }

    @Test
    void privilegedOperatorPasses() throws Exception {
        mvc.perform(get("/probe/C-1001/modify").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void loginPageListsDemoOperatorsAndCustomers() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attributeExists("operators", "customers"));
    }

    @Test
    void loginStoresIdentityAndRedirectsToBlacklist() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/login").session(session).param("operator", "spravca").param("customer", "C-1001"))
                .andExpect(redirectedUrl("/blacklist/search"));

        assertThat(session.getAttribute("APP_OPERATOR")).isNotNull();
        assertThat(session.getAttribute("APP_CUSTOMER")).isNotNull();
    }

    @Test
    void unknownOperatorIs404() throws Exception {
        mvc.perform(post("/login").param("operator", "neexistuje").param("customer", "C-1001"))
                .andExpect(status().isNotFound());
    }

    @Test
    void homeRedirectsByLoginState() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/").session(TestSessions.admin())).andExpect(redirectedUrl("/blacklist/search"));
    }

    @Test
    void layoutMessagesAreShownOnceAfterRedirect() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).param("operator", "spravca").param("customer", "C-1001"));

        mvc.perform(get("/login").session(session))
                .andExpect(model().attribute("layoutMessages",
                        contains(new UiMessage(UiMessage.Level.INFO, "login.welcome"))));
        mvc.perform(get("/login").session(session))
                .andExpect(model().attribute("layoutMessages", empty()));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        MockHttpSession session = TestSessions.admin();

        mvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/login"));

        assertThat(session.isInvalid()).isTrue();
    }
}
```

`src/test/java/com/example/crm/it/LoginPageRenderingTest.java`:

```java
package com.example.crm.it;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** Reálny Tomcat + Jasper: JSP sa skompiluje, TLD sa nájdu a {@code common:message} číta messages.properties. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginPageRenderingTest {

    @LocalServerPort
    int port;

    @Test
    void loginPageRendersWithLayoutAndTranslations() throws Exception {
        HttpResponse<String> response = new WebClient(port).get("/login");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("Prihlásenie (fake login)")
                .contains("Správca (všetky práva)")
                .contains("/css/crm.css");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void unknownPathRendersCustomErrorPage() throws Exception {
        HttpResponse<String> response = new WebClient(port).get("/neexistuje");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Chyba");
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B test -Dtest="WebInfrastructureTest,LoginPageRenderingTest"`
Expected: FAIL – kompilácia: `cannot find symbol` (`LegacyPortlet`, `DemoDirectory`, …).

- [ ] **Step 3: Implementuj anotáciu, interceptory a konfiguráciu**

`src/main/java/com/example/crm/web/portlet/LegacyPortlet.java`:

```java
package com.example.crm.web.portlet;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Označuje controller, ktorý nahrádza (časť) legacy portletu.
 *
 * <p>V legacy mal každý portlet záznam v {@code portlet.xml} a vlastný Spring kontext; portál k nemu vyrábal URL.
 * Tu je „portlet“ len skupina controllerov so spoločným base path. Z neho skladajú URL tagy {@code portal:*URL}
 * a interceptor podľa neho vyžaduje prihláseného operátora.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LegacyPortlet {

    /** Base path bez context path, môže obsahovať premenné z URL, napr. {@code /dashboard/{customerId}}. */
    String value();
}
```

`src/main/java/com/example/crm/web/WebRequests.java`:

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

`src/main/java/com/example/crm/web/portlet/LegacyPortletInterceptor.java`:

```java
package com.example.crm.web.portlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.example.crm.acl.AclDeniedException;
import com.example.crm.acl.AclService;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.HttpSessionCrmSession;
import com.example.crm.session.OperatorUser;
import com.example.crm.web.WebRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Robí to, čo v legacy robil portál a vlastný DispatcherPortlet pred zavolaním controllera:
 * (1) bez prihláseného operátora nepustí ďalej, (2) zverejní base path „portletu“ pre URL tagy,
 * (3) skontroluje ACL privilégium z {@link RequiresPrivilege}.
 */
public class LegacyPortletInterceptor implements HandlerInterceptor {

    private final AclService aclService;

    public LegacyPortletInterceptor(AclService aclService) {
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
        Optional<OperatorUser> operator = HttpSessionCrmSession.of(request).getOperator();
        if (operator.isEmpty()) {
            if (WebRequests.isAjax(request)) {
                // AJAX nesmie dostať redirect na login – JS by prihlasovaciu stránku vložil do fragmentu.
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return false;
        }
        request.setAttribute(PortletRequestAttributes.BASE_PATH, expand(portlet.value(), request));
        RequiresPrivilege required = requiredPrivilege(method);
        if (required != null && !aclService.hasPrivilege(operator.get(), required.value())) {
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

    @SuppressWarnings("unchecked")
    private static String expand(String template, HttpServletRequest request) {
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return UriComponentsBuilder.fromPath(template)
                .buildAndExpand(variables == null ? Map.of() : variables)
                .toUriString();
    }
}
```

`src/main/java/com/example/crm/web/portlet/CustomerContextInterceptor.java`:

```java
package com.example.crm.web.portlet;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import com.example.crm.session.CustomerContext;
import com.example.crm.session.HttpSessionCrmSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Náhrada legacy interceptora, ktorý vyžadoval kontext zákazníka v session.
 *
 * <p>V legacy bol zákazník súčasťou friendly URL dashboardu aj session. Tu: ak URL obsahuje {@code {customerId}},
 * musí sa zhodovať so zákazníkom v session – inak redirect na dashboard „vlastného“ zákazníka
 * (operátor nemôže prepísaním URL pracovať s cudzím zákazníkom).
 */
public class CustomerContextInterceptor implements HandlerInterceptor {

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Map<String, String> variables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String requested = variables == null ? null : variables.get("customerId");
        if (requested == null) {
            return true;
        }
        Optional<CustomerContext> current = HttpSessionCrmSession.of(request).getCustomer();
        if (current.isPresent() && current.get().customerId().equals(requested)) {
            return true;
        }
        response.sendRedirect(request.getContextPath()
                + current.map(c -> "/dashboard/" + c.customerId() + "/products").orElse("/login"));
        return false;
    }
}
```

`src/main/java/com/example/crm/web/portlet/LayoutModelInterceptor.java`:

```java
package com.example.crm.web.portlet;

import com.example.crm.session.CrmSession;
import com.example.crm.session.HttpSessionCrmSession;
import com.example.crm.web.WebRequests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Dodá layoutu (hlavička stránky) operátora, zákazníka a hlášky.
 *
 * <p>V legacy to robili iné portlety na stránke portálu (hlavička, kontext zákazníka, info hlášky) a theme.
 * Hlášky sa vyberajú len pri skutočnom vykreslení stránky – nie pri redirecte ani AJAX fragmente,
 * inak by sa stratili skôr, než ich používateľ uvidí.
 */
public class LayoutModelInterceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        if (modelAndView == null || WebRequests.isAjax(request) || isRedirect(modelAndView)) {
            return;
        }
        CrmSession session = HttpSessionCrmSession.of(request);
        modelAndView.addObject("layoutOperator", session.getOperator().orElse(null));
        modelAndView.addObject("layoutCustomer", session.getCustomer().orElse(null));
        modelAndView.addObject("layoutMessages", session.popMessages());
    }

    private static boolean isRedirect(ModelAndView modelAndView) {
        String viewName = modelAndView.getViewName();
        return (viewName != null && viewName.startsWith("redirect:")) || modelAndView.getView() instanceof RedirectView;
    }
}
```

`src/main/java/com/example/crm/web/CrmSessionArgumentResolver.java`:

```java
package com.example.crm.web;

import com.example.crm.session.CrmSession;
import com.example.crm.session.HttpSessionCrmSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Umožní písať {@code CrmSession session} ako parameter handler metódy.
 * Legacy controllery volali {@code getSessionManager()} zo spoločného base controllera; tu nie je potrebná dedičnosť.
 */
public class CrmSessionArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return CrmSession.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return HttpSessionCrmSession.of(webRequest.getNativeRequest(HttpServletRequest.class));
    }
}
```

`src/main/java/com/example/crm/web/WebConfig.java`:

```java
package com.example.crm.web;

import java.util.List;

import com.example.crm.acl.AclService;
import com.example.crm.web.portlet.CustomerContextInterceptor;
import com.example.crm.web.portlet.LayoutModelInterceptor;
import com.example.crm.web.portlet.LegacyPortletInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Náhrada per-portlet Spring kontextov ({@code <portlet>-portlet.xml}) – jedna konfigurácia pre celú aplikáciu.
 * Poradie interceptorov je dôležité: najprv prihlásenie + ACL, potom kontext zákazníka, nakoniec dáta pre layout.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AclService aclService;

    public WebConfig(AclService aclService) {
        this.aclService = aclService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LegacyPortletInterceptor(aclService));
        registry.addInterceptor(new CustomerContextInterceptor());
        registry.addInterceptor(new LayoutModelInterceptor());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CrmSessionArgumentResolver());
    }
}
```

`src/main/java/com/example/crm/web/GlobalExceptionHandler.java`:

```java
package com.example.crm.web;

import com.example.crm.acl.AclDeniedException;
import com.example.crm.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/** Náhrada legacy exception resolvera v portlet kontexte a hook-u s chybovými JSP portálu. */
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
}
```

- [ ] **Step 4: Implementuj fake login**

`src/main/java/com/example/crm/login/DemoDirectory.java`:

```java
package com.example.crm.login;

import java.util.List;
import java.util.Optional;

import com.example.crm.session.CustomerContext;
import com.example.crm.session.OperatorUser;
import org.springframework.stereotype.Component;

/** Vymyslení operátori a zákazníci dema. Loginy zodpovedajú {@code InMemoryAclService}. */
@Component
public class DemoDirectory {

    public static final OperatorUser READER = new OperatorUser("citatel", "Čitateľ (len prezeranie)");
    public static final OperatorUser ADMIN = new OperatorUser("spravca", "Správca (všetky práva)");
    public static final CustomerContext PERSON = new CustomerContext("C-1001", "Ján Vzorový");
    public static final CustomerContext COMPANY = new CustomerContext("C-1002", "Príklad s.r.o.");

    public List<OperatorUser> operators() {
        return List.of(ADMIN, READER);
    }

    public List<CustomerContext> customers() {
        return List.of(PERSON, COMPANY);
    }

    public Optional<OperatorUser> findOperator(String login) {
        return operators().stream().filter(o -> o.login().equals(login)).findFirst();
    }

    public Optional<CustomerContext> findCustomer(String customerId) {
        return customers().stream().filter(c -> c.customerId().equals(customerId)).findFirst();
    }
}
```

`src/main/java/com/example/crm/login/FakeLoginController.java`:

```java
package com.example.crm.login;

import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Ekvivalent legacy „fake login“ portletu – NIE autentifikácia.
 *
 * <p>V legacy identitu dodával SSO portál. Podklady migrácie security nemenia (len zachovávajú kontrakt session
 * fasády), preto demo len zapíše vybraného operátora a zákazníka do {@link CrmSession}.
 */
@Controller
public class FakeLoginController {

    private final DemoDirectory directory;

    public FakeLoginController(DemoDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/")
    public String home(CrmSession session) {
        return session.getOperator().isPresent() ? "redirect:/blacklist/search" : "redirect:/login";
    }

    @GetMapping("/login")
    public String loginForm(Model model) {
        model.addAttribute("operators", directory.operators());
        model.addAttribute("customers", directory.customers());
        return "login";
    }

    @PostMapping("/login")
    public String login(@RequestParam String operator, @RequestParam String customer, CrmSession session) {
        session.setOperator(directory.findOperator(operator)
                .orElseThrow(() -> new NotFoundException("operátor " + operator)));
        session.setCustomer(directory.findCustomer(customer)
                .orElseThrow(() -> new NotFoundException("zákazník " + customer)));
        session.pushInfo("login.welcome");
        return "redirect:/blacklist/search";
    }

    @PostMapping("/logout")
    public String logout(CrmSession session) {
        session.invalidate();
        return "redirect:/login";
    }
}
```

- [ ] **Step 5: Vytvor layout a JSP stránky**

`src/main/webapp/WEB-INF/jsp/layout/taglibs.jspf`:

```jsp
<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="form" uri="http://www.springframework.org/tags/form" %>
<%@ taglib prefix="portal" uri="http://example.com/crm/tags/portal" %>
<%@ taglib prefix="common" uri="http://example.com/crm/tags/common" %>
```

`src/main/webapp/WEB-INF/jsp/layout/header.jspf`:

```jsp
<%-- Náhrada Liferay theme + portletov hlavičky a kontextu zákazníka. Očakáva premennú titleKey. --%>
<!DOCTYPE html>
<html lang="sk">
<head>
    <meta charset="UTF-8">
    <title><common:message key="${titleKey}"/> – <common:message key="app.name"/></title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/crm.css">
    <script src="${pageContext.request.contextPath}/js/crm.js" defer></script>
</head>
<body>
<header class="app-header">
    <strong><common:message key="app.name"/></strong>
    <c:if test="${not empty layoutOperator}">
        <nav>
            <a href="${pageContext.request.contextPath}/blacklist/search"><common:message key="nav.blacklist"/></a>
            <common:aclFilter privilegeType="DEACTIVATION">
                <a href="${pageContext.request.contextPath}/dashboard/${layoutCustomer.customerId}/products"><common:message key="nav.products"/></a>
            </common:aclFilter>
        </nav>
        <span class="who"><c:out value="${layoutOperator.displayName}"/> · <common:message key="header.customer"/>: <c:out value="${layoutCustomer.displayName}"/></span>
        <form method="post" action="${pageContext.request.contextPath}/logout" class="inline">
            <button type="submit"><common:message key="header.logout"/></button>
        </form>
    </c:if>
</header>
<main>
<c:forEach var="message" items="${layoutMessages}">
    <div class="msg msg-${message.level}"><common:message key="${message.key}"/></div>
</c:forEach>
```

`src/main/webapp/WEB-INF/jsp/layout/footer.jspf`:

```jsp
</main>
</body>
</html>
```

`src/main/webapp/WEB-INF/jsp/login.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="login.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="login.title"/></h1>
<p class="hint"><common:message key="login.hint"/></p>
<form method="post" action="${pageContext.request.contextPath}/login" class="panel">
    <label><common:message key="login.operator"/>
        <select name="operator">
            <c:forEach var="operator" items="${operators}">
                <option value="${operator.login}"><c:out value="${operator.displayName}"/></option>
            </c:forEach>
        </select>
    </label>
    <label><common:message key="login.customer"/>
        <select name="customer">
            <c:forEach var="customer" items="${customers}">
                <option value="${customer.customerId}"><c:out value="${customer.displayName}"/></option>
            </c:forEach>
        </select>
    </label>
    <button type="submit" class="primary"><common:message key="login.submit"/></button>
</form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/error.jsp` (chybová stránka Spring Boot pre neošetrené chyby, napr. 400/404/500):

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.generic.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.generic.title"/> <c:out value="${status}"/></h1>
<p><c:out value="${error}"/></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/error/403.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.403.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.403.title"/></h1>
<p><common:message key="error.403.text"/> <code><c:out value="${privilege}"/></code></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/error/403-fragment.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<p class="field-error"><common:message key="error.403.text"/> <c:out value="${privilege}"/></p>
```

`src/main/webapp/WEB-INF/jsp/error/404.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="error.404.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="error.404.title"/></h1>
<p><common:message key="error.404.text"/></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B test -Dtest="WebInfrastructureTest,LoginPageRenderingTest"`
Expected: PASS (15 testov).
Ak kompilácia hlási chýbajúce `org.hamcrest`, pridaj do `pom.xml` test závislosť `org.hamcrest:hamcrest` (verziu riadi Boot parent).
Ak `unknownPathRendersCustomErrorPage` dostane telo bez „Chyba“, Spring Boot nenašiel view `error`. Over `server.error.whitelabel.enabled=false` a existenciu `WEB-INF/jsp/error.jsp`.

- [ ] **Step 7: Spusti všetky doterajšie testy**

Run: `mvn -q -B test`
Expected: PASS (všetky testy z Taskov 1–5).

- [ ] **Step 8: Checkpoint (bez commitu)** – `git status --short`

---

### Task 6: Blacklist – doména a in-memory služba (backend port)

**Files:**
- Create: `src/main/java/com/example/crm/blacklist/BlacklistState.java`, `StateReason.java`, `ConditionType.java`, `IdentifierType.java`
- Create: `src/main/java/com/example/crm/blacklist/Identifier.java`, `Note.java`, `BlacklistRecord.java`, `NewBlacklistRecord.java`
- Create: `src/main/java/com/example/crm/blacklist/BlacklistSearchCriteria.java`
- Create: `src/main/java/com/example/crm/blacklist/BlacklistRuleException.java`
- Create: `src/main/java/com/example/crm/blacklist/BlacklistService.java`
- Create: `src/main/java/com/example/crm/blacklist/InMemoryBlacklistService.java`
- Test: `src/test/java/com/example/crm/blacklist/InMemoryBlacklistServiceTest.java`

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

`src/test/java/com/example/crm/blacklist/InMemoryBlacklistServiceTest.java`:

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

Run: `mvn -q -B test -Dtest=InMemoryBlacklistServiceTest`
Expected: FAIL – kompilácia: `cannot find symbol: class InMemoryBlacklistService`.

- [ ] **Step 3: Implementuj doménu**

`src/main/java/com/example/crm/blacklist/BlacklistState.java`:

```java
package com.example.crm.blacklist;

public enum BlacklistState { ACTIVE, INACTIVE }
```

`src/main/java/com/example/crm/blacklist/StateReason.java`:

```java
package com.example.crm.blacklist;

/** Dôvod zaradenia na blacklist (zjednodušené, vymyslené hodnoty). */
public enum StateReason { FRAUD, DEBT, ABUSE }
```

`src/main/java/com/example/crm/blacklist/ConditionType.java`:

```java
package com.example.crm.blacklist;

/** Podmienka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType { BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL }
```

`src/main/java/com/example/crm/blacklist/IdentifierType.java`:

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

`src/main/java/com/example/crm/blacklist/Identifier.java`:

```java
package com.example.crm.blacklist;

public record Identifier(long id, IdentifierType type, String value) {
}
```

`src/main/java/com/example/crm/blacklist/Note.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDateTime;

public record Note(String text, String author, LocalDateTime createdAt) {
}
```

`src/main/java/com/example/crm/blacklist/BlacklistRecord.java`:

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

`src/main/java/com/example/crm/blacklist/NewBlacklistRecord.java`:

```java
package com.example.crm.blacklist;

import java.time.LocalDate;

/** Príkaz na vytvorenie záznamu – backend port nezávisí od webového formulára. */
public record NewBlacklistRecord(String firstName, String lastName, LocalDate birthDate, StateReason stateReason,
                                 ConditionType conditionType, LocalDate validFrom, LocalDate validTo,
                                 IdentifierType identifierType, String identifierValue) {
}
```

`src/main/java/com/example/crm/blacklist/BlacklistSearchCriteria.java`:

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

`src/main/java/com/example/crm/blacklist/BlacklistRuleException.java`:

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

`src/main/java/com/example/crm/blacklist/BlacklistService.java`:

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

`src/main/java/com/example/crm/blacklist/InMemoryBlacklistService.java`:

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

Run: `mvn -q -B test -Dtest=InMemoryBlacklistServiceTest`
Expected: PASS (15 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 7: Blacklist – vyhľadávanie a výsledky (render + action s PRG, `@SessionAttributes`)

**Files:**
- Create: `src/main/java/com/example/crm/blacklist/BlacklistSearchController.java`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/searchresult.jsp`
- Test: `src/test/java/com/example/crm/blacklist/BlacklistSearchWebTest.java`

**Interfaces:**
- Consumes: `BlacklistService`, `BlacklistSearchCriteria`, `BlacklistState` (Task 6); `@LegacyPortlet`, `CrmSession`, `TestSessions` (Task 5); `@RequiresPrivilege` (Task 3)
- Produces: routy `GET /blacklist/search`, `POST /blacklist/searchAction`, `GET /blacklist/searchresult`, `GET /blacklist/newSearch`; session atribút `searchCriteria`; view `blacklist/search`, `blacklist/searchresult` (model `results`)

- [ ] **Step 1: Napíš padajúci test**

`src/test/java/com/example/crm/blacklist/BlacklistSearchWebTest.java`:

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

Run: `mvn -q -B test -Dtest=BlacklistSearchWebTest`
Expected: FAIL – `searchPageHasEmptyCriteria` dostane 404 (controller neexistuje). Ostatné testy zlyhajú na redirect/404.

- [ ] **Step 3: Implementuj controller**

`src/main/java/com/example/crm/blacklist/BlacklistSearchController.java`:

```java
package com.example.crm.blacklist;

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

`src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`:

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
        <button type="submit" class="primary"><common:message key="common.search"/></button>
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

`src/main/webapp/WEB-INF/jsp/blacklist/searchresult.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.result.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_VIEW" wireframe="blacklist-result"/>
<h1><common:message key="blacklist.result.title"/></h1>
<table class="grid">
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

Run: `mvn -q -B test -Dtest=BlacklistSearchWebTest`
Expected: PASS (5 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 8: Blacklist – detail, zmena stavu, poznámka, AJAX odstránenie identifikátora

**Files:**
- Create: `src/main/java/com/example/crm/blacklist/BlacklistChangeForm.java`
- Create: `src/main/java/com/example/crm/blacklist/NoteForm.java`
- Create: `src/main/java/com/example/crm/blacklist/BlacklistDetailController.java`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers.jsp`
- Test: `src/test/java/com/example/crm/blacklist/BlacklistDetailWebTest.java`

**Interfaces:**
- Consumes: Task 6 (`BlacklistService`, `BlacklistRecord`, `BlacklistRuleException`, …), Task 5 (`@LegacyPortlet`, `CrmSession`, `TestSessions`)
- Produces: `GET /blacklist/detail?detailId=` (VIEW) → view `blacklist/detail` s modelom `record`, `identifiers`, `detailId`, `states`, `changeForm`, `noteForm`; `POST /blacklist/changeAction?detailId=` (MODIFY); `POST /blacklist/addNoteAction?detailId=` (MODIFY); `POST /blacklist/resource/removeIdentAction?detailId=&identId=` (MODIFY) → view `blacklist/fragments/identifiers` (model `identifiers`, `detailId`, voliteľne `errorKey`)

- [ ] **Step 1: Napíš padajúci test**

`src/test/java/com/example/crm/blacklist/BlacklistDetailWebTest.java`:

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
                    assertThat(note.author()).isEqualTo("Správca (všetky práva)");
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

Run: `mvn -q -B test -Dtest=BlacklistDetailWebTest`
Expected: FAIL – kompilácia: `cannot find symbol` (`BlacklistChangeForm`), resp. 404 na routách.

- [ ] **Step 3: Implementuj formuláre a controller**

`src/main/java/com/example/crm/blacklist/BlacklistChangeForm.java`:

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

`src/main/java/com/example/crm/blacklist/NoteForm.java`:

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

`src/main/java/com/example/crm/blacklist/BlacklistDetailController.java`:

```java
package com.example.crm.blacklist;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
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
    public String detail(@RequestParam long detailId, Model model) {
        return renderDetail(detailId, model);
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

`src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`:

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
        <button type="submit" class="primary"><common:message key="common.save"/></button>
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
        <div class="actions"><button type="submit"><common:message key="blacklist.detail.addNote"/></button></div>
    </form:form>
</common:aclFilter>

<portal:renderURL var="resultUrl"><portal:param name="render" value="searchresult"/></portal:renderURL>
<p><a href="${resultUrl}"><common:message key="common.back"/></a></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers.jsp`:

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

Run: `mvn -q -B test -Dtest=BlacklistDetailWebTest`
Expected: PASS (11 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 9: Blacklist – nový záznam (validácia, `@ModelAttribute` na úrovni triedy, AJAX refresh podmienok)

**Files:**
- Create: `src/main/java/com/example/crm/blacklist/BlacklistCreateForm.java`
- Create: `src/main/java/com/example/crm/blacklist/BlacklistCreateController.java`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`
- Create: `src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp`
- Test: `src/test/java/com/example/crm/blacklist/BlacklistCreateFormValidationTest.java`
- Test: `src/test/java/com/example/crm/blacklist/BlacklistCreateWebTest.java`

**Interfaces:**
- Consumes: Task 6 (`BlacklistService.create`, `allowedConditionTypes`, enumy, `NewBlacklistRecord`), Task 5
- Produces: `GET /blacklist/create` (MODIFY) → view `blacklist/create`, model `createForm` (validFrom = dnes), `conditionTypes`, `stateReasons`, `identifierTypes`; `GET /blacklist/resource/refreshCreateConditionTypes?stateReason=` → view `blacklist/fragments/conditionTypes`; `POST /blacklist/createAction` → redirect `/blacklist/detail?detailId={nové}`; `BlacklistCreateForm` s property `periodValid`, `identifierValid` (@AssertTrue) a `toCommand(): NewBlacklistRecord`

- [ ] **Step 1: Napíš padajúce testy**

`src/test/java/com/example/crm/blacklist/BlacklistCreateFormValidationTest.java`:

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

`src/test/java/com/example/crm/blacklist/BlacklistCreateWebTest.java`:

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

Run: `mvn -q -B test -Dtest="BlacklistCreateFormValidationTest,BlacklistCreateWebTest"`
Expected: FAIL – kompilácia: `cannot find symbol: class BlacklistCreateForm`.

- [ ] **Step 3: Implementuj formulár a controller**

`src/main/java/com/example/crm/blacklist/BlacklistCreateForm.java`:

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

`src/main/java/com/example/crm/blacklist/BlacklistCreateController.java`:

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

`src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`:

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
                <form:option value="">—</form:option>
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
                <form:option value="">—</form:option>
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
        <button type="submit" class="primary"><common:message key="common.save"/></button>
        <portal:renderURL var="backUrl"><portal:param name="render" value="search"/></portal:renderURL>
        <a href="${backUrl}"><common:message key="common.back"/></a>
    </div>
</form:form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<%-- Obyčajný <select> (nie form:select): fragment sa vracia aj samostatne cez AJAX, mimo <form:form>. --%>
<select name="conditionType" id="conditionTypes">
    <option value="">—</option>
    <c:forEach var="condition" items="${conditionTypes}">
        <option value="${condition}" ${condition == createForm.conditionType ? 'selected' : ''}><common:message key="blacklist.condition.${condition}"/></option>
    </c:forEach>
</select>
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B test -Dtest="BlacklistCreateFormValidationTest,BlacklistCreateWebTest"`
Expected: PASS (11 testov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 10: Zrušenie služby – doména, demo pravidlá dátumov, služby

**Files:**
- Create: `src/main/java/com/example/crm/deactivation/ProductType.java`, `CustomerProduct.java`, `DeactivationType.java`, `DeactivationRequest.java`, `DeactivationRules.java`
- Create: `src/main/java/com/example/crm/deactivation/CustomerProductService.java`, `InMemoryCustomerProductService.java`
- Create: `src/main/java/com/example/crm/deactivation/DeactivationService.java`, `InMemoryDeactivationService.java`
- Test: `src/test/java/com/example/crm/deactivation/DeactivationRulesTest.java`
- Test: `src/test/java/com/example/crm/deactivation/DeactivationServicesTest.java`

**Interfaces:**
- Consumes: bean `Clock` (Task 1)
- Produces:
  - `enum ProductType { TARIFF, PRODUCT }`; `record CustomerProduct(String id, String customerId, String name, ProductType type, String description)`
  - `enum DeactivationType { TRANSFER_TO_CUSTOMER, CUSTOMER_DEACTIVATION, CUSTOMER_POSTPRE }`
  - `record DeactivationRequest(long id, String productId, String customerId, String productName, DeactivationType type, LocalDate noticeDate, LocalDate effectiveDate, String operator, LocalDateTime createdAt)`
  - `DeactivationRules.earliestEffectiveDate(DeactivationType, LocalDate noticeDate): LocalDate` (static)
  - `interface CustomerProductService { List<CustomerProduct> productsOf(String customerId); Optional<CustomerProduct> find(String customerId, String productId); }`
  - `interface DeactivationService { DeactivationRequest submit(CustomerProduct, DeactivationType, LocalDate noticeDate, LocalDate effectiveDate, String operator); Optional<DeactivationRequest> find(long id); }`
  - Produkty: C-1001 → `T-1` „Tarif Neobmedzený“ (TARIFF), `T-2` „Tarif Štandard“ (TARIFF), `P-1` „+421 900 000 001“ (PRODUCT); C-1002 → `T-3` „Firemný tarif“ (TARIFF), `P-2` „+421 900 000 002“ (PRODUCT)

- [ ] **Step 1: Napíš padajúce testy**

`src/test/java/com/example/crm/deactivation/DeactivationRulesTest.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeactivationRulesTest {

    private static final LocalDate NOTICE = LocalDate.of(2026, 10, 8);

    @Test
    void transferIsImmediate() {
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.TRANSFER_TO_CUSTOMER, NOTICE)).isEqualTo(NOTICE);
    }

    @Test
    void deactivationHasThirtyDayNotice() {
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_DEACTIVATION, NOTICE))
                .isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    void prepaidTransferIsNextDay() {
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_POSTPRE, NOTICE))
                .isEqualTo(LocalDate.of(2026, 10, 9));
    }
}
```

`src/test/java/com/example/crm/deactivation/DeactivationServicesTest.java`:

```java
package com.example.crm.deactivation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeactivationServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final CustomerProductService products = new InMemoryCustomerProductService();
    private final DeactivationService deactivations = new InMemoryDeactivationService(CLOCK);

    @Test
    void productsBelongToCustomer() {
        assertThat(products.productsOf("C-1001")).extracting(CustomerProduct::id).containsExactly("T-1", "T-2", "P-1");
        assertThat(products.productsOf("C-1002")).extracting(CustomerProduct::id).containsExactly("T-3", "P-2");
    }

    @Test
    void foreignProductIsNotFound() {
        assertThat(products.find("C-1001", "T-3")).isEmpty();
        assertThat(products.find("C-1001", "T-1")).isPresent();
    }

    @Test
    void submitStoresRequest() {
        CustomerProduct product = products.find("C-1001", "T-1").orElseThrow();

        DeactivationRequest request = deactivations.submit(product, DeactivationType.CUSTOMER_POSTPRE,
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), "Správca");

        assertThat(request.id()).isEqualTo(1L);
        assertThat(request.productName()).isEqualTo("Tarif Neobmedzený");
        assertThat(request.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 15));
        assertThat(deactivations.find(1L)).contains(request);
    }

    @Test
    void backendRejectsTooEarlyDateEvenIfFrontendMissedIt() {
        CustomerProduct product = products.find("C-1001", "T-1").orElseThrow();

        assertThatThrownBy(() -> deactivations.submit(product, DeactivationType.CUSTOMER_DEACTIVATION,
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 9), "Správca"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B test -Dtest="DeactivationRulesTest,DeactivationServicesTest"`
Expected: FAIL – kompilácia: `cannot find symbol: class DeactivationRules`.

- [ ] **Step 3: Implementuj**

`src/main/java/com/example/crm/deactivation/ProductType.java`:

```java
package com.example.crm.deactivation;

public enum ProductType { TARIFF, PRODUCT }
```

`src/main/java/com/example/crm/deactivation/CustomerProduct.java`:

```java
package com.example.crm.deactivation;

/** Produkt alebo tarif zákazníka (zjednodušené – v legacy bohatý model produktového katalógu). */
public record CustomerProduct(String id, String customerId, String name, ProductType type, String description) {
}
```

`src/main/java/com/example/crm/deactivation/DeactivationType.java`:

```java
package com.example.crm.deactivation;

/** Typ žiadosti – rovnaké tri voľby ako formulár v legacy (prevod, zrušenie, prevod na predplatenú kartu). */
public enum DeactivationType { TRANSFER_TO_CUSTOMER, CUSTOMER_DEACTIVATION, CUSTOMER_POSTPRE }
```

`src/main/java/com/example/crm/deactivation/DeactivationRequest.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record DeactivationRequest(long id, String productId, String customerId, String productName,
                                  DeactivationType type, LocalDate noticeDate, LocalDate effectiveDate,
                                  String operator, LocalDateTime createdAt) {
}
```

`src/main/java/com/example/crm/deactivation/DeactivationRules.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;

/**
 * DEMO pravidlá – VYMYSLENÉ, nie business pravidlá klienta. Slúžia len na ukážku, že pravidlo žije na jednom mieste
 * a používa ho validácia formulára, JSON endpoint pre AJAX aj backend služba.
 */
public final class DeactivationRules {

    private DeactivationRules() {
    }

    public static LocalDate earliestEffectiveDate(DeactivationType type, LocalDate noticeDate) {
        return switch (type) {
            case TRANSFER_TO_CUSTOMER -> noticeDate;
            case CUSTOMER_DEACTIVATION -> noticeDate.plusDays(30);
            case CUSTOMER_POSTPRE -> noticeDate.plusDays(1);
        };
    }
}
```

`src/main/java/com/example/crm/deactivation/CustomerProductService.java`:

```java
package com.example.crm.deactivation;

import java.util.List;
import java.util.Optional;

/** Port na produkty zákazníka (v legacy viacero backend služieb). */
public interface CustomerProductService {

    List<CustomerProduct> productsOf(String customerId);

    /** Produkt len vtedy, ak patrí zákazníkovi – podvrhnuté ID produktu iného zákazníka vráti prázdny výsledok. */
    Optional<CustomerProduct> find(String customerId, String productId);
}
```

`src/main/java/com/example/crm/deactivation/InMemoryCustomerProductService.java`:

```java
package com.example.crm.deactivation;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

/** Vymyslené produkty demo zákazníkov (ID zákazníkov zodpovedajú {@code DemoDirectory}). */
@Service
public class InMemoryCustomerProductService implements CustomerProductService {

    private final List<CustomerProduct> products = List.of(
            new CustomerProduct("T-1", "C-1001", "Tarif Neobmedzený", ProductType.TARIFF, "Neobmedzené dáta, volania a SMS"),
            new CustomerProduct("T-2", "C-1001", "Tarif Štandard", ProductType.TARIFF, "5 GB dát, 100 minút"),
            new CustomerProduct("P-1", "C-1001", "+421 900 000 001", ProductType.PRODUCT, "Mobilné číslo – Tarif Neobmedzený"),
            new CustomerProduct("T-3", "C-1002", "Firemný tarif", ProductType.TARIFF, "Zdieľané dáta pre firmu"),
            new CustomerProduct("P-2", "C-1002", "+421 900 000 002", ProductType.PRODUCT, "Mobilné číslo – Firemný tarif"));

    @Override
    public List<CustomerProduct> productsOf(String customerId) {
        return products.stream().filter(p -> p.customerId().equals(customerId)).toList();
    }

    @Override
    public Optional<CustomerProduct> find(String customerId, String productId) {
        return productsOf(customerId).stream().filter(p -> p.id().equals(productId)).findFirst();
    }
}
```

`src/main/java/com/example/crm/deactivation/DeactivationService.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;
import java.util.Optional;

/** Port na backend žiadostí o zrušenie (v legacy manager + viacero backend služieb). */
public interface DeactivationService {

    DeactivationRequest submit(CustomerProduct product, DeactivationType type, LocalDate noticeDate,
                               LocalDate effectiveDate, String operator);

    Optional<DeactivationRequest> find(long id);
}
```

`src/main/java/com/example/crm/deactivation/InMemoryDeactivationService.java`:

```java
package com.example.crm.deactivation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

@Service
public class InMemoryDeactivationService implements DeactivationService {

    private final Clock clock;
    private final Map<Long, DeactivationRequest> requests = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    public InMemoryDeactivationService(Clock clock) {
        this.clock = clock;
    }

    @Override
    public DeactivationRequest submit(CustomerProduct product, DeactivationType type, LocalDate noticeDate,
                                      LocalDate effectiveDate, String operator) {
        LocalDate earliest = DeactivationRules.earliestEffectiveDate(type, noticeDate);
        // Backend overí pravidlo znova – validácia vo formulári je pre používateľa, nie ochrana dát.
        if (effectiveDate.isBefore(earliest)) {
            throw new IllegalArgumentException("Dátum účinnosti " + effectiveDate + " je pred " + earliest);
        }
        DeactivationRequest request = new DeactivationRequest(ids.incrementAndGet(), product.id(), product.customerId(),
                product.name(), type, noticeDate, effectiveDate, operator,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES));
        requests.put(request.id(), request);
        return request;
    }

    @Override
    public Optional<DeactivationRequest> find(long id) {
        return Optional.ofNullable(requests.get(id));
    }
}
```

- [ ] **Step 4: Spusti testy, musia prejsť**

Run: `mvn -q -B test -Dtest="DeactivationRulesTest,DeactivationServicesTest"`
Expected: PASS (7 testov).

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 11: Zrušenie služby – web (dashboard dispatch → URL, pasca `@ModelAttribute`, JSON endpoint, PRG)

**Files:**
- Create: `src/main/java/com/example/crm/deactivation/DeactivationForm.java`
- Create: `src/main/java/com/example/crm/deactivation/ProductsController.java`
- Create: `src/main/java/com/example/crm/deactivation/DeactivationController.java`
- Create: `src/main/webapp/WEB-INF/jsp/dashboard/products.jsp`
- Create: `src/main/webapp/WEB-INF/jsp/dashboard/deactivation/form.jsp`
- Create: `src/main/webapp/WEB-INF/jsp/dashboard/deactivation/done.jsp`
- Test: `src/test/java/com/example/crm/deactivation/DeactivationWebTest.java`

**Interfaces:**
- Consumes: Task 10 (služby, pravidlá, typy), Task 5 (`@LegacyPortlet`, `CustomerContextInterceptor`, `CrmSession`, `TestSessions`), Task 3 (`Privilege.DEACTIVATION`)
- Produces:
  - `GET /dashboard/{customerId}/products` → view `dashboard/products` (model `products`)
  - `GET /dashboard/{customerId}/products/{productId}/deactivation` → view `dashboard/deactivation/form` (model `deactivationForm`, `product`, `types`)
  - `GET …/deactivation/resource/earliestDate?type=&noticeDate=` → JSON `{"earliestDate":"YYYY-MM-DD"}`
  - `POST …/deactivation/submit` → redirect `…/deactivation/done?requestId={id}`
  - `GET …/deactivation/done?requestId=` → view `dashboard/deactivation/done` (model `deactivationRequest`)

- [ ] **Step 1: Napíš padajúci test**

`src/test/java/com/example/crm/deactivation/DeactivationWebTest.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;

import com.example.crm.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@SpringBootTest
@AutoConfigureMockMvc
class DeactivationWebTest {

    private static final String FORM_URL = "/dashboard/C-1001/products/T-1/deactivation";

    @Autowired
    MockMvc mvc;

    @Test
    void productsShowOnlyOwnProducts() throws Exception {
        mvc.perform(get("/dashboard/C-1001/products").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/products"))
                .andExpect(model().attribute("products", hasSize(3)));
    }

    @Test
    void foreignCustomerInUrlRedirectsToOwnDashboard() throws Exception {
        mvc.perform(get("/dashboard/C-1002/products").session(TestSessions.admin()))
                .andExpect(redirectedUrl("/dashboard/C-1001/products"));
    }

    @Test
    void readerLacksDeactivationPrivilege() throws Exception {
        mvc.perform(get("/dashboard/C-1001/products").session(TestSessions.reader()))
                .andExpect(status().isForbidden());
    }

    /** Pasca z logu predchádzajúceho PoC: GET formulára musí mať backing objekt v modeli. */
    @Test
    void formHasBackingObjectWithDefaults() throws Exception {
        LocalDate today = LocalDate.now();
        mvc.perform(get(FORM_URL).session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/deactivation/form"))
                .andExpect(model().attribute("deactivationForm", allOf(
                        hasProperty("noticeDate", is(today)),
                        hasProperty("type", is(DeactivationType.CUSTOMER_DEACTIVATION)),
                        hasProperty("effectiveDate", is(today.plusDays(30))))))
                .andExpect(model().attributeExists("product", "types"));
    }

    @Test
    void foreignProductIs404() throws Exception {
        mvc.perform(get("/dashboard/C-1001/products/T-3/deactivation").session(TestSessions.admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void earliestDateEndpointReturnsJson() throws Exception {
        mvc.perform(get(FORM_URL + "/resource/earliestDate").param("type", "CUSTOMER_POSTPRE")
                        .param("noticeDate", "2026-10-08").header("X-Requested-With", "XMLHttpRequest")
                        .session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earliestDate").value("2026-10-09"));
    }

    @Test
    void validSubmitRedirectsToDoneAndShowsRequest() throws Exception {
        MockHttpSession session = TestSessions.admin();
        String today = LocalDate.now().toString();

        MvcResult result = mvc.perform(post(FORM_URL + "/submit").session(session)
                        .param("noticeDate", today).param("type", "TRANSFER_TO_CUSTOMER").param("effectiveDate", today))
                .andExpect(redirectedUrlPattern(FORM_URL + "/done?requestId=*"))
                .andReturn();

        mvc.perform(get(result.getResponse().getRedirectedUrl()).session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/deactivation/done"))
                .andExpect(model().attribute("deactivationRequest",
                        hasProperty("type", is(DeactivationType.TRANSFER_TO_CUSTOMER))));
    }

    @Test
    void tooEarlyEffectiveDateIsFieldError() throws Exception {
        String today = LocalDate.now().toString();
        mvc.perform(post(FORM_URL + "/submit").session(TestSessions.admin())
                        .param("noticeDate", today).param("type", "CUSTOMER_DEACTIVATION").param("effectiveDate", today))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard/deactivation/form"))
                .andExpect(model().attributeHasFieldErrorCode("deactivationForm", "effectiveDate",
                        "deactivation.effectiveTooEarly"));
    }

    @Test
    void futureNoticeDateIsFieldError() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        mvc.perform(post(FORM_URL + "/submit").session(TestSessions.admin())
                        .param("noticeDate", tomorrow).param("type", "TRANSFER_TO_CUSTOMER").param("effectiveDate", tomorrow))
                .andExpect(model().attributeHasFieldErrorCode("deactivationForm", "noticeDate",
                        "deactivation.noticeInFuture"));
    }

    @Test
    void missingTypeIsRequired() throws Exception {
        String today = LocalDate.now().toString();
        // Prázdny reťazec (nie chýbajúci parameter): inak by ostal predvyplnený typ z @ModelAttribute metódy.
        mvc.perform(post(FORM_URL + "/submit").session(TestSessions.admin())
                        .param("noticeDate", today).param("type", "").param("effectiveDate", today))
                .andExpect(model().attributeHasFieldErrors("deactivationForm", "type"));
    }

    @Test
    void doneOfForeignRequestIs404() throws Exception {
        mvc.perform(get(FORM_URL + "/done").param("requestId", "999").session(TestSessions.admin()))
                .andExpect(status().isNotFound());
    }
}
```


- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B test -Dtest=DeactivationWebTest`
Expected: FAIL – testy dostanú 404 (controllery `ProductsController`/`DeactivationController` ešte neexistujú).

- [ ] **Step 3: Implementuj formulár a controllery**

`src/main/java/com/example/crm/deactivation/DeactivationForm.java`:

```java
package com.example.crm.deactivation;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;

public class DeactivationForm {

    @NotNull(message = "{validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate noticeDate;

    @NotNull(message = "{validation.required}")
    private DeactivationType type;

    @NotNull(message = "{validation.required}")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate effectiveDate;

    public LocalDate getNoticeDate() { return noticeDate; }
    public void setNoticeDate(LocalDate noticeDate) { this.noticeDate = noticeDate; }
    public DeactivationType getType() { return type; }
    public void setType(DeactivationType type) { this.type = type; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }
}
```

`src/main/java/com/example/crm/deactivation/ProductsController.java`:

```java
package com.example.crm.deactivation;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.web.portlet.LegacyPortlet;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Prehľad produktov zákazníka (zjednodušená stránka dashboardu).
 *
 * <p>Legacy dashboard bol JEDEN portlet, v ktorom vlastný HandlerMapping vyberal controller podľa parametra
 * {@code page} a zákazník bol v friendly URL. Tu má každá stránka vlastnú URL so zákazníkom v ceste;
 * kontrolu zákazníka robí {@code CustomerContextInterceptor}.
 */
@Controller
@LegacyPortlet("/dashboard/{customerId}")
@RequestMapping("/dashboard/{customerId}")
@RequiresPrivilege(Privilege.DEACTIVATION)
public class ProductsController {

    private final CustomerProductService products;

    public ProductsController(CustomerProductService products) {
        this.products = products;
    }

    @GetMapping("/products")
    public String products(@PathVariable String customerId, Model model) {
        model.addAttribute("products", products.productsOf(customerId));
        return "dashboard/products";
    }
}
```

`src/main/java/com/example/crm/deactivation/DeactivationController.java`:

```java
package com.example.crm.deactivation;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import com.example.crm.session.OperatorUser;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Zrušenie služby (zjednodušené). Legacy: stránka {@code page=tariffDeactivation} dashboard portletu
 * s render, action a viacerými resource fázami, veľkým managerom a 7+ backend službami.
 */
@Controller
@LegacyPortlet(DeactivationController.BASE)
@RequestMapping(DeactivationController.BASE)
@RequiresPrivilege(Privilege.DEACTIVATION)
public class DeactivationController {

    static final String BASE = "/dashboard/{customerId}/products/{productId}/deactivation";
    static final String FORM = "deactivationForm";

    private final CustomerProductService products;
    private final DeactivationService deactivations;
    private final Clock clock;

    public DeactivationController(CustomerProductService products, DeactivationService deactivations, Clock clock) {
        this.products = products;
        this.deactivations = deactivations;
        this.clock = clock;
    }

    /**
     * PASCA Z LOGU PoC: {@code <form:form modelAttribute="deactivationForm">} potrebuje objekt v modeli už pri GET.
     * V legacy ho dodávala práve takáto metóda na úrovni triedy; pri zlučovaní controllerov sa stratila
     * a stránka padala s „Neither BindingResult nor plain target object for bean name…“.
     *
     * <p>Zámerne BEZ {@code @SessionAttributes} (legacy formulár držal v session): pri otvorení formulára
     * pre iný produkt by sa inak zobrazil zastaraný stav z predchádzajúceho produktu.
     */
    @ModelAttribute(FORM)
    public DeactivationForm deactivationForm() {
        DeactivationForm form = new DeactivationForm();
        form.setNoticeDate(LocalDate.now(clock));
        form.setType(DeactivationType.CUSTOMER_DEACTIVATION);
        form.setEffectiveDate(DeactivationRules.earliestEffectiveDate(form.getType(), form.getNoticeDate()));
        return form;
    }

    /** Produkt musí patriť zákazníkovi z URL (a ten zákazníkovi v session – viď CustomerContextInterceptor). */
    @ModelAttribute("product")
    public CustomerProduct product(@PathVariable String customerId, @PathVariable String productId) {
        return products.find(customerId, productId)
                .orElseThrow(() -> new NotFoundException("produkt " + productId + " zákazníka " + customerId));
    }

    @ModelAttribute("types")
    public DeactivationType[] types() {
        return DeactivationType.values();
    }

    @GetMapping
    public String form() {
        return "dashboard/deactivation/form";
    }

    /** Resource fáza → JSON endpoint (REST časť dema). Pravidlo je to isté ako pri validácii a v backende. */
    @GetMapping(value = "/resource/earliestDate", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> earliestDate(@RequestParam DeactivationType type,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate noticeDate) {
        return Map.of("earliestDate", DeactivationRules.earliestEffectiveDate(type, noticeDate).toString());
    }

    @PostMapping("/submit")
    public String submit(@Valid @ModelAttribute(FORM) DeactivationForm form, BindingResult result,
                         @ModelAttribute("product") CustomerProduct product, CrmSession session,
                         RedirectAttributes redirectAttributes) {
        validateDates(form, result);
        if (result.hasErrors()) {
            return "dashboard/deactivation/form";
        }
        String operator = session.getOperator().map(OperatorUser::displayName).orElseThrow();
        DeactivationRequest request = deactivations.submit(product, form.getType(), form.getNoticeDate(),
                form.getEffectiveDate(), operator);
        session.pushInfo("deactivation.submitted");
        redirectAttributes.addAttribute("requestId", request.id());
        // {customerId} a {productId} doplní RedirectView z aktuálneho requestu.
        return "redirect:" + BASE + "/done";
    }

    @GetMapping("/done")
    public String done(@PathVariable String customerId, @PathVariable String productId,
                       @RequestParam long requestId, Model model) {
        DeactivationRequest request = deactivations.find(requestId)
                .filter(r -> r.customerId().equals(customerId) && r.productId().equals(productId))
                .orElseThrow(() -> new NotFoundException("žiadosť " + requestId));
        model.addAttribute("deactivationRequest", request);
        return "dashboard/deactivation/done";
    }

    private void validateDates(DeactivationForm form, BindingResult result) {
        LocalDate today = LocalDate.now(clock);
        if (form.getNoticeDate() != null && form.getNoticeDate().isAfter(today)) {
            result.rejectValue("noticeDate", "deactivation.noticeInFuture");
        }
        if (form.getNoticeDate() != null && form.getType() != null && form.getEffectiveDate() != null) {
            LocalDate earliest = DeactivationRules.earliestEffectiveDate(form.getType(), form.getNoticeDate());
            if (form.getEffectiveDate().isBefore(earliest)) {
                result.rejectValue("effectiveDate", "deactivation.effectiveTooEarly",
                        new Object[] {earliest.toString()}, null);
            }
        }
    }
}
```

- [ ] **Step 4: Vytvor JSP**

`src/main/webapp/WEB-INF/jsp/dashboard/products.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="dashboard.products.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="dashboard.products.title"/>: <c:out value="${layoutCustomer.displayName}"/></h1>
<p class="hint"><common:message key="dashboard.products.hint"/></p>
<ul class="plain panel">
    <c:forEach var="product" items="${products}">
        <li>
            <%-- Odkaz do INÉHO „portletu“: portal:renderURL by ho postavil k base path tejto stránky,
                 preto c:url. V legacy na to bol tag pre dashboard URL – v reálnom projekte tabuľka routovania. --%>
            <c:url var="deactivationUrl" value="/dashboard/${product.customerId}/products/${product.id}/deactivation"/>
            <a href="${deactivationUrl}"><strong><c:out value="${product.name}"/></strong></a>
            <span class="badge"><common:message key="dashboard.productType.${product.type}"/></span>
            <c:out value="${product.description}"/>
        </li>
    </c:forEach>
</ul>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/dashboard/deactivation/form.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="deactivation.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="deactivation.title"/></h1>

<dl class="panel">
    <dt><common:message key="deactivation.product"/></dt><dd><c:out value="${product.name}"/></dd>
    <dt><common:message key="deactivation.description"/></dt><dd><c:out value="${product.description}"/></dd>
</dl>

<portal:actionURL var="submitUrl"><portal:param name="action" value="submit"/></portal:actionURL>
<portal:resourceURL var="earliestDateUrl" id="earliestDate"/>

<form:form modelAttribute="deactivationForm" action="${submitUrl}" method="post" cssClass="panel"
           data-earliest-date-url="${earliestDateUrl}">
    <label><common:message key="deactivation.noticeDate"/> <form:input path="noticeDate" type="date"/></label>
    <form:errors path="noticeDate" cssClass="field-error"/>

    <fieldset>
        <legend><common:message key="deactivation.type"/></legend>
        <c:forEach var="type" items="${types}">
            <label><form:radiobutton path="type" value="${type}"/> <common:message key="deactivation.type.${type}"/></label>
        </c:forEach>
        <form:errors path="type" cssClass="field-error"/>
    </fieldset>

    <label><common:message key="deactivation.effectiveDate"/> <form:input path="effectiveDate" type="date"/></label>
    <form:errors path="effectiveDate" cssClass="field-error"/>

    <div class="actions">
        <button type="submit" class="primary"><common:message key="deactivation.submit"/></button>
        <c:url var="productsUrl" value="/dashboard/${product.customerId}/products"/>
        <a href="${productsUrl}"><common:message key="common.back"/></a>
    </div>
</form:form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

`src/main/webapp/WEB-INF/jsp/dashboard/deactivation/done.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="deactivation.done.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<h1><common:message key="deactivation.done.title"/></h1>
<dl class="panel">
    <dt><common:message key="deactivation.product"/></dt><dd><c:out value="${deactivationRequest.productName}"/></dd>
    <dt><common:message key="deactivation.type"/></dt><dd><common:message key="deactivation.type.${deactivationRequest.type}"/></dd>
    <dt><common:message key="deactivation.noticeDate"/></dt><dd>${deactivationRequest.noticeDate}</dd>
    <dt><common:message key="deactivation.effectiveDate"/></dt><dd>${deactivationRequest.effectiveDate}</dd>
    <dt><common:message key="deactivation.done.operator"/></dt><dd><c:out value="${deactivationRequest.operator}"/></dd>
</dl>
<c:url var="productsUrl" value="/dashboard/${deactivationRequest.customerId}/products"/>
<p><a href="${productsUrl}"><common:message key="deactivation.backToProducts"/></a></p>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
```

- [ ] **Step 5: Spusti test, musí prejsť**

Run: `mvn -q -B test -Dtest=DeactivationWebTest`
Expected: PASS (11 testov).
Ak `validSubmitRedirectsToDone…` zlyhá na redirect URL s `{customerId}` v texte, RedirectView nerozvinul premenné. Vtedy zostav URL explicitne:
`return "redirect:/dashboard/" + product.customerId() + "/products/" + product.id() + "/deactivation/done";`

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 12: End-to-end scenáre s reálnym Tomcatom + overenie spustením

**Files:**
- Test: `src/test/java/com/example/crm/it/BlacklistFlowTest.java`
- Test: `src/test/java/com/example/crm/it/DeactivationFlowTest.java`

**Interfaces:**
- Consumes: všetko predtým; `WebClient` (Task 5); seed dáta (Task 6 tabuľka – tu sa menia záznamy 5 (ident. 7) a 7; kontext s RANDOM_PORT je samostatný)

- [ ] **Step 1: Napíš testy (mali by prejsť hneď – overujú integráciu, nie novú logiku)**

`src/test/java/com/example/crm/it/BlacklistFlowTest.java`:

```java
package com.example.crm.it;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** Celý tok cez reálny Tomcat + Jasper: JSP, TLD, tagy, PRG redirecty, session cookie, UTF-8. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BlacklistFlowTest {

    @LocalServerPort
    int port;

    WebClient web;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        web = login("spravca");
    }

    @Test
    void searchWithDiacriticsFindsRecord() throws Exception {
        HttpResponse<String> response = web.post("/blacklist/searchAction",
                form("firstName", "Mária", "lastName", "", "identifierValue", "", "state", ""));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Príkladová").contains("/blacklist/detail?detailId=2");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void detailRendersUrlTagsAndDeactivationWorks() throws Exception {
        HttpResponse<String> detail = web.get("/blacklist/detail?detailId=7");
        assertThat(detail.body())
                .contains("action=\"/blacklist/changeAction?detailId=7\"")
                .contains("/blacklist/resource/removeIdentAction?detailId=7");
        WebClient.assertNoMissingMessages(detail.body());

        HttpResponse<String> changed = web.post("/blacklist/changeAction?detailId=7",
                form("state", "INACTIVE", "validTo", ""));

        assertThat(changed.body()).contains("Záznam bol zmenený.").contains("Neaktívny");
    }

    @Test
    void ajaxRemoveIdentifierReturnsFragmentOnly() throws Exception {
        HttpResponse<String> response = web.post("/blacklist/resource/removeIdentAction?detailId=5&identId=7",
                Map.of(), WebClient.AJAX);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("id=\"identifiers\"").contains("691224/3456")
                .doesNotContain("X7654321").doesNotContain("<html");
    }

    @Test
    void createEscapesHtmlInput() throws Exception {
        HttpResponse<String> response = web.post("/blacklist/createAction", form(
                "firstName", "<b>Zlý</b>", "lastName", "Vstup", "birthDate", "1990-01-01",
                "identifierType", "ID_CARD", "identifierValue", "ZZ123456",
                "stateReason", "DEBT", "conditionType", "REQUIRE_DEPOSIT", "validFrom", "2026-01-01", "validTo", ""));

        assertThat(response.body()).contains("Záznam bol vytvorený.")
                .contains("&lt;b&gt;Zlý&lt;/b&gt;")
                .doesNotContain("<b>Zlý</b>");
    }

    @Test
    void validationErrorsAreTranslated() throws Exception {
        HttpResponse<String> response = web.post("/blacklist/createAction", form("firstName", ""));

        assertThat(response.body()).contains("Povinné pole.");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void readerGets403PageForCreate() throws Exception {
        HttpResponse<String> response = login("citatel").get("/blacklist/create");

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("Prístup zamietnutý").contains("BLACKLIST_MODIFY");
    }

    @Test
    void ajaxWithoutSessionGets401() throws Exception {
        HttpResponse<String> response = new WebClient(port)
                .get("/blacklist/resource/refreshCreateConditionTypes?stateReason=FRAUD", WebClient.AJAX);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private WebClient login(String operator) throws Exception {
        WebClient client = new WebClient(port);
        HttpResponse<String> response = client.post("/login", form("operator", operator, "customer", "C-1001"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Prihlásenie prebehlo");
        return client;
    }

    private static Map<String, String> form(String... nameValuePairs) {
        Map<String, String> form = new LinkedHashMap<>();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            form.put(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return form;
    }
}
```

`src/test/java/com/example/crm/it/DeactivationFlowTest.java`:

```java
package com.example.crm.it;

import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeactivationFlowTest {

    private static final String FORM_URL = "/dashboard/C-1001/products/T-1/deactivation";

    @LocalServerPort
    int port;

    WebClient web;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        web = new WebClient(port);
        Map<String, String> login = new LinkedHashMap<>();
        login.put("operator", "spravca");
        login.put("customer", "C-1001");
        web.post("/login", login);
    }

    @Test
    void productsLinkToDeactivationForm() throws Exception {
        HttpResponse<String> response = web.get("/dashboard/C-1001/products");

        assertThat(response.body()).contains("Tarif Neobmedzený").contains("href=\"" + FORM_URL + "\"");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void formRendersWithBackingObjectAndAjaxUrl() throws Exception {
        HttpResponse<String> response = web.get(FORM_URL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("Zrušenie služby")
                .contains("data-earliest-date-url=\"" + FORM_URL + "/resource/earliestDate\"")
                .contains("value=\"" + LocalDate.now() + "\"");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void earliestDateJson() throws Exception {
        HttpResponse<String> response = web.get(FORM_URL + "/resource/earliestDate?type=CUSTOMER_DEACTIVATION&noticeDate=2026-10-08",
                WebClient.AJAX);

        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("json"));
        assertThat(response.body()).isEqualTo("{\"earliestDate\":\"2026-11-07\"}");
    }

    @Test
    void submitShowsConfirmation() throws Exception {
        LocalDate today = LocalDate.now();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("noticeDate", today.toString());
        form.put("type", "CUSTOMER_POSTPRE");
        form.put("effectiveDate", today.plusDays(1).toString());

        HttpResponse<String> response = web.post(FORM_URL + "/submit", form);

        assertThat(response.body())
                .contains("Žiadosť o zrušenie služby bola odoslaná.")
                .contains("Žiadam o prevod na predplatenú kartu");
        WebClient.assertNoMissingMessages(response.body());
    }

    @Test
    void foreignProductShows404Page() throws Exception {
        HttpResponse<String> response = web.get("/dashboard/C-1001/products/T-3/deactivation");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Nenájdené");
    }
}
```

- [ ] **Step 2: Spusti nové testy**

Run: `mvn -q -B test -Dtest="BlacklistFlowTest,DeactivationFlowTest"`
Expected: PASS (12 testov).
Ak `validationErrorsAreTranslated` ukáže doslova `{validation.required}`, Bean Validation neinterpoluje správy cez Spring
`MessageSource`. Oprava: v `com.example.crm.web.WebConfig` pridaj bean
`@Bean LocalValidatorFactoryBean validator(MessageSource messageSource) { var v = new LocalValidatorFactoryBean(); v.setValidationMessageSource(messageSource); return v; }`
a `getValidator()` vráti tento bean. Potom test spusti znova.
Ak niečo iné zlyhá, ide o skutočnú integračnú chybu (JSP, TLD, kódovanie, escapovanie). Postupuj podľa `superpowers:systematic-debugging` a test neupravuj, aby „prešiel“.

- [ ] **Step 3: Celý build**

Run: `mvn -B verify`
Expected: `BUILD SUCCESS`, v súhrne Surefire `Tests run: N, Failures: 0, Errors: 0` a vytvorený `target/crm-web-demo-0.1.0-SNAPSHOT.war`.

- [ ] **Step 4: Overenie spustením (nie odhadom)**

Spusti aplikáciu na pozadí (Bash):

```bash
java -jar target/crm-web-demo-0.1.0-SNAPSHOT.war --server.port=18080 > target/run.log 2>&1 &
for i in $(seq 1 60); do curl -s -o /dev/null http://localhost:18080/login && break; sleep 1; done
curl -s -c target/cookies.txt -b target/cookies.txt -o /dev/null -w "login GET %{http_code}\n" http://localhost:18080/login
curl -s -c target/cookies.txt -b target/cookies.txt -L -o target/after-login.html -w "login POST -> %{http_code}\n" \
     -d "operator=spravca&customer=C-1001" http://localhost:18080/login
grep -c "Prihlásenie prebehlo" target/after-login.html
curl -s -c target/cookies.txt -b target/cookies.txt -L -o target/result.html -w "search -> %{http_code}\n" \
     --data-urlencode "lastName=vzork" http://localhost:18080/blacklist/searchAction
grep -o "detailId=[0-9]*" target/result.html | sort -u
curl -s -b target/cookies.txt -H "X-Requested-With: XMLHttpRequest" \
     "http://localhost:18080/dashboard/C-1001/products/T-1/deactivation/resource/earliestDate?type=CUSTOMER_POSTPRE&noticeDate=2026-10-08"; echo
```

Expected: `login GET 200`, `login POST -> 200`, `1`, `search -> 200`, `detailId=1` a `detailId=8`, `{"earliestDate":"2026-10-09"}`.

Zastav aplikáciu (Bash na Windows):

```bash
PID=$(netstat -ano | grep ':18080 ' | grep LISTENING | awk '{print $5}' | head -1); taskkill //PID $PID //F
```

Zapíš skutočný výstup príkazov do poznámky pre Task 13 (sekcia „Overenie“ v README).

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 13: Dokumentácia – README, MIGRACIA.md, poznámky, CLAUDE.md, anonymizačná kontrola

**Files:**
- Create: `README.md`
- Create: `docs/MIGRACIA.md`
- Create: `poznamky/08-ponaucenia-z-implementacie.md`
- Modify: `poznamky/README.md` (pridať riadok pre 08), `poznamky/06-playbook-migracie-portletu.md` (odkaz na demo ako referenčnú implementáciu)
- Modify: `CLAUDE.md` (sekcia „Build & run“)
- Modify: `docs/ANALYZA.md` (len odkaz na `MIGRACIA.md` a `README.md` na konci kap. 6)

**Interfaces:**
- Consumes: hotový kód (cesty k súborom v odkazoch musia existovať), výstup overenia z Tasku 12

- [ ] **Step 1: Napíš `README.md`** (slovensky, s presne týmito sekciami)

1. **Čo je toto demo**: 3–4 vety. Učebné demo migrácie dvoch portletov legacy CRM z Liferay 6.2 (Spring Portlet MVC + JSP) na Spring Boot 4.1.1 (Spring MVC + JSP). Drží sa smeru z podkladov: JSP a tagy zostávajú, Liferay odchádza. Odkaz na `docs/ANALYZA.md` a `docs/MIGRACIA.md`.
2. **Čo ukazuje**: tabuľka obrazovka → čo sa na nej učíš:
   - vyhľadávanie: render/action → GET/POST+PRG, `@SessionAttributes`
   - detail: AJAX resource → HTML fragment, ACL tagy
   - nový záznam: Bean Validation, `@ModelAttribute` na úrovni triedy, AJAX refresh
   - produkty a zrušenie služby: dashboard dispatch → URL, kontext zákazníka, JSON endpoint, pasca z logu
3. **Predpoklady**: JDK 21, Maven 3.9+. Nič iné netreba: žiadna DB a žiadny Liferay.
4. **Spustenie**: `mvn verify` (testy), `mvn spring-boot:run` alebo `java -jar target/crm-web-demo-0.1.0-SNAPSHOT.war` → http://localhost:8080. Demo používatelia: tabuľka `spravca`/`citatel` + privilégiá, zákazníci C-1001/C-1002. Upozornenie, že login nie je autentifikácia.
5. **Štruktúra projektu**: strom balíkov s jednou vetou ku každému (podľa „Štruktúra súborov“ v tomto pláne).
6. **Testy**: tri úrovne (unit, MockMvc, reálny Tomcat) a čo každá chytá. Zmienka o `assertNoMissingMessages`.
7. **Overenie**: skutočný výstup z Tasku 12 Step 3 (počet testov) a Step 4 (curl výstupy) s dátumom.
8. **Mimo rozsahu**: zoznam zo spec kap. 9 + CSRF ochrana (bez Spring Security) + JS sa netestuje automaticky (iba ručne v prehliadači, ak bol overený – uveď pravdivo).
9. **Pôvod dát**: všetky dáta, mená a pravidlá sú vymyslené; žiadny kód ani dáta klienta.

- [ ] **Step 2: Napíš `docs/MIGRACIA.md`** (slovensky). Pre každý bod: **Pred** (anonymizovaná minimálna ukážka – použi presne ukážky nižšie), **Po** (odkaz na súbor + 5–15 riadkov výňatku zo skutočného kódu dema), **Prečo** (2–5 viet).

Kapitoly a ukážky „Pred“:

1. **Portlet → skupina controllerov** – Pred:
   ```xml
   <!-- portlet.xml -->
   <portlet>
       <portlet-name>blacklist</portlet-name>
       <portlet-class>org.springframework.web.portlet.DispatcherPortlet</portlet-class>
       <supports><mime-type>text/html</mime-type><portlet-mode>view</portlet-mode></supports>
   </portlet>
   <!-- WEB-INF/blacklist-portlet.xml: vlastný Spring kontext, view resolver, interceptory, component-scan -->
   ```
   Po: `@LegacyPortlet` + `WebConfig`.
2. **Fázy portletu → HTTP** – Pred:
   ```java
   @Controller
   @RequestMapping("VIEW")
   @SessionAttributes("searchCriteria")
   public class BlacklistSearchController {

       @RenderMapping(params = "render=search")
       public String showSearch() {
           return "search";
       }

       @ActionMapping(params = "action=searchAction")
       public void search(@ModelAttribute("searchCriteria") SearchCriteria criteria,
                          BindingResult result, ActionResponse response) {
           // ... validácia, volanie služby ...
           response.setRenderParameter("render", "searchresult");
       }
   }
   ```
   Po: `BlacklistSearchController` (GET/POST + `redirect:`). Tabuľka render/action/resource → GET / POST+redirect / AJAX.
3. **Resource fáza → AJAX endpoint** – Pred:
   ```java
   @ResourceMapping("removeIdentAction")
   public ModelAndView removeIdentifier(@RequestParam long identId, ModelMap model) {
       // ...
       return new ModelAndView("fragments/identifiers", model);
   }
   ```
   Po: `BlacklistDetailController.removeIdentifier` + `fragments/identifiers.jsp` + `crm.js`. Navyše JSON variant `DeactivationController.earliestDate`.
4. **URL tagy s rovnakým rozhraním** – Pred (implementácia legacy tagu):
   ```java
   public class ActionUrlTag extends TagSupport {
       public int doEndTag() throws JspException {
           MimeResponse response = (MimeResponse) pageContext.getRequest().getAttribute("javax.portlet.response");
           LiferayPortletURL url = (LiferayPortletURL) response.createActionURL();
           url.setCopyCurrentRenderParameters(false);
           params.forEach(url::setParameter);
           pageContext.setAttribute(var, url.toString());
           return EVAL_PAGE;
       }
   }
   ```
   JSP pred aj po je rovnaké (ukáž `<portal:actionURL var="searchUrl"><portal:param name="action" value="searchAction"/></portal:actionURL>`). Po: `PortletUrlTag` + `ActionUrlTag`. Prečo: JSP sa nemenia a závislosť na Liferay je len v implementácii tagu. Treba spomenúť aj obmedzenie konvencie a deeplinker.
5. **i18n tag** – Pred: legacy `<common:message>` nad DB/CMS MessageSource; predchádzajúci PoC vypisoval kľúče. Po: `MessageTag` + `messages.properties` + viditeľné `??kľúč??` + test `assertNoMissingMessages`.
6. **ACL** – Pred = Po v JSP (`<common:aclFilter privilegeType="BLACKLIST_MODIFY">`). Rozdiel je v implementácii: `AclTagSupport`, `@RequiresPrivilege` + `LegacyPortletInterceptor`. Prečo: podklady nemenia security.
7. **Session fasáda** – Pred:
   ```java
   getSessionManager().setPortalCustomer(customer);   // identita v session externého SSO portálu
   getSessionManager().pushInfo("label.saved");
   ```
   Po: `CrmSession` + `HttpSessionCrmSession`, prefix `APP_`. Prečo: kontrakt zachovaný, implementácia vymenená. Čo by bolo v produkcii: Spring Session + Redis, feature flag.
8. **Dashboard dispatch → URL a kontext zákazníka** – Pred:
   ```java
   public class DashboardHandlerMapping extends AbstractHandlerMapping {
       protected Object getHandlerInternal(PortletRequest request) {
           String page = request.getParameter("page");      // napr. "tariffDeactivation"
           return pageRegistry.controllerFor(page);
       }
   }
   ```
   ```xml
   <!-- friendly URL route -->
   <route><pattern>/{customerId}/{assetId}/{page}</pattern></route>
   ```
   Po: `ProductsController`, `DeactivationController` (cesty s `{customerId}`/`{productId}`), `CustomerContextInterceptor`.
9. **Pasca: `@ModelAttribute` na úrovni triedy** – Pred:
   ```java
   // portletový controller: metóda sa volá pred render aj action fázou
   @ModelAttribute("deactivationForm")
   public DeactivationForm form(PortletRequest request) { ... }
   ```
   Opíš chybu z logu PoC slovami. Text výnimky Springu je generický: „Neither BindingResult nor plain target object for bean name '…' available as request attribute“. Po: `DeactivationController.deactivationForm()`, `BlacklistCreateController.createForm()` + test `formHasBackingObjectWithDefaults`. Pridaj aj prečo demo nepoužíva `@SessionAttributes` pre formulár deaktivácie.
10. **Validácia** – Pred: samostatná trieda `implements org.springframework.validation.Validator` registrovaná v portlet kontexte. Po: Bean Validation (`jakarta.validation`) na formulároch + pravidlo závislé od backendu v controlleri. Zmienka `javax` → `jakarta`.
11. **Theme a kompozícia stránky** – Pred: separátny theme WAR (Velocity), portlet WAR ho vyžaduje; `<liferay-theme:defineObjects/>` + `${themeDisplay.pathThemeImages}`. Po: `layout/header.jspf`, `static/css/crm.css`, `LayoutModelInterceptor`.
12. **Chybové stránky** – Pred: hook prepisujúci chybové JSP portálu. Po: `GlobalExceptionHandler`, `error.jsp`, `error/403.jsp`, `error/404.jsp`.
13. **Mapovacia tabuľka Liferay koncept → Spring Boot náhrada**: rozšírená verzia `poznamky/05-mapa-konceptov.md`. Stĺpce: koncept | v legacy | v deme (súbor) | v reálnej migrácii. Riadky: aspoň všetky z `poznamky/05`.
14. **Čo demo zámerne nerieši a prečo**: SSO portál, duálna session, deeplinker, backend adaptéry, i18n z DB/CMS, page-flow objednávok, JBoss, upgrade backendu na Jakarta.

- [ ] **Step 3: Doplň poznámky a CLAUDE.md**

`poznamky/08-ponaucenia-z-implementacie.md` – zapíš skutočné zistenia z implementácie. Minimálne:
- Spring Boot 4: starter `spring-boot-starter-webmvc`; test anotácie `@WebMvcTest`/`@AutoConfigureMockMvc` sú v balíku `org.springframework.boot.webmvc.test.autoconfigure` (starter `spring-boot-starter-webmvc-test`).
- JSP v Boote len s WAR + `tomcat-embed-jasper` + Jakarta JSTL (`jakarta.tags.core`).
- EL (Tomcat 11) číta Java recordy.
- Tagy: Jasper poolinguje inštancie, preto treba stav resetovať v `doStartTag`.
- `@AssertTrue` metódy = krížová validácia naviazaná na pseudo-property.
- Testy zdieľajú in-memory stav v rámci Spring kontextu.
- Všetko neočakávané, na čo sa pri implementácii narazilo (napr. úpravy testov v Taskoch 9 a 11).

Do `poznamky/README.md` pridaj riadok tabuľky pre `08`. Do `poznamky/06-playbook-migracie-portletu.md` pridaj na koniec sekciu „Referenčná implementácia“ s odkazmi na demo súbory (`web/tag/`, `web/portlet/`, `blacklist/`, `deactivation/`).

V `CLAUDE.md` nahraď vetu „The demo Spring Boot project itself (Phase 2, not yet created).“ sekciou:

```markdown
- The demo Spring Boot project at the repo root (`pom.xml`, `src/`): Spring Boot 4.1.1, Java 21, WAR + JSP.

## Build & run

- `mvn verify` — all tests (unit, MockMvc, and real-Tomcat flow tests under `src/test/java/com/example/crm/it/`).
- `mvn -q -B test -Dtest=ClassName` — a single test class.
- `mvn spring-boot:run` or `java -jar target/crm-web-demo-0.1.0-SNAPSHOT.war` → http://localhost:8080 (fake login: `spravca` / `citatel`).
- JSPs: `src/main/webapp/WEB-INF/jsp/`, TLDs: `src/main/webapp/WEB-INF/tld/`, i18n: `src/main/resources/messages.properties`.
- Tests share in-memory state per Spring context — a test that mutates data uses its own seed record (table in the implementation plan, Task 6).
```

V `docs/ANALYZA.md` pridaj na koniec kap. 6 vetu: „Výsledok Fázy 2: pozri [`../README.md`](../README.md) a [`MIGRACIA.md`](MIGRACIA.md).“

- [ ] **Step 4: Anonymizačná kontrola a záverečný build**

Run (Bash, z koreňa repa):

```bash
bash poznamky/LOCAL-anonymizacia.sh
```

(Skript je git-ignorovaný, obsahuje zoznam zakázaných identifikátorov. Ak na počítači chýba, prenes ho ručne spolu s `poznamky/LOCAL-aliasy.md`.)

Expected: žiadne riadky, `grep exit=1`.

Run: `mvn -B verify`
Expected: `BUILD SUCCESS`, všetky testy zelené.

Run: `git status --short`
Expected: nové súbory projektu a dokumentácie. Nesmú sa zobraziť `input/`, `target/` ani `poznamky/LOCAL-aliasy.md`. **Necommituj.**
