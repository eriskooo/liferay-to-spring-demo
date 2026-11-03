# Legacy appka „pred“ na Liferay 6.2 (podprojekt 1) – implementačný plán

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Postaviť spustiteľnú „starú“ appku – príklady blacklist (A) a zrušenie služby (B) ako Spring Portlet MVC portlety v reálnom Liferay 6.2 na Jave 8 – s mockom SSO portálu, duálnou session (PortletSession + Redis, feature flag) a Docker Compose za nginx, aby ju podprojekt 2 mohol zmigrovať na Spring Boot.

**Architecture:** Maven multi-module `legacy/` (Java 8 bytecode) kopíruje delenie legacy: `sso-portal-api` (API SSO portálu, shaded JAR do `tomcat/lib/ext`), `frontend-base-customization` (session fasáda, ACL, vlastné taglibs, `DispatcherPortletCustom`, base controller), `customercare-portlets` (portlety `blacklist`, `dashboard`, `header`, in-memory backend, startup hook, hooked JSP) a `customercare-theme` (Velocity theme). `sso-mock/` je samostatná Spring Boot 4.1.1 appka (Java 21). `docker/` spúšťa Liferay 6.2 GA6 (Zulu 8u252), Redis, sso-mock a nginx na `http://localhost:8000`.

**Tech Stack:** Liferay Portal 6.2 CE GA6 (6.2.5, Tomcat 7.0.62, HSQL), Zulu JDK 8u252 (runtime), JDK 21 s `--release 8` (build), Spring 4.3.6.RELEASE (`spring-webmvc-portlet`), Portlet 2.0, Servlet 3.0, JSP 2.2 + JSTL 1.2, Jackson 2.9.10, Jedis 2.9.0, JUnit 5.10.5 + Mockito 4.11.0 + AssertJ 3.27.3 + `spring-test` 4.3.6 (portlet mocky); sso-mock: Spring Boot 4.1.1, Java 21; Docker Compose, Redis 7, nginx 1.27.

**Spec:** [`docs/superpowers/specs/2026-10-12-legacy-liferay-app-design.md`](../specs/2026-10-12-legacy-liferay-app-design.md)

## Global Constraints

- Moduly v `legacy/`: **Java 8 bytecode** (`maven.compiler.release=8`), build na hostiteľskom JDK 21. Len Java 8 API: žiadne `record`, `var`, `List.of`/`Map.of`, `switch` výrazy, `String.isBlank()`, `Stream.toList()`, text blocks (`--release 8` to vynúti pri kompilácii). Globálne pravidlá „records, Optional vo verejnom API“ tu ustupujú požiadavke 1:1 s legacy (fasáda vracia `null` ako legacy).
- `sso-mock/`: Java **21**, Spring Boot **4.1.1** (`spring-boot-starter-parent`).
- Verzie (presne): Spring `4.3.6.RELEASE`, Liferay API `6.2.5` (`com.liferay.portal:portal-service`, `provided`), `javax.portlet:portlet-api:2.0`, `javax.servlet:javax.servlet-api:3.0.1`, `javax.servlet.jsp:jsp-api:2.2`, `javax.servlet:jstl:1.2`, `jackson-databind:2.9.10.8`, `redis.clients:jedis:2.9.0`, JUnit `5.10.5`, Mockito `4.11.0`, AssertJ `3.27.3`.
- Koordináty: `groupId=com.example.crm.legacy`, `version=0.1.0-SNAPSHOT`; balíky `com.example.sso.api`, `com.example.crm.frontend.base`, `com.example.crm.frontend`; sso-mock `groupId=com.example.sso`, balík `com.example.ssomock`.
- ID v Liferay: portlety `blacklist_WAR_customercareportlet`, `dashboard_WAR_customercareportlet`, `header_WAR_customercareportlet`; theme `customercare_WAR_customercaretheme`; site friendly URL `/crm`; stránky `/blacklist`, `/dashboard`.
- Príkazy sa spúšťajú z koreňa repa `C:\projekty\tmp\liferay-to-spring-demo`: `mvn -q -B -f legacy/pom.xml …`, `mvn -q -B -f sso-mock/pom.xml …`.
- **NIKDY nespúšťaj `git commit` ani `git push`.** Commituje používateľ. Krok „Checkpoint“ je len `git status --short`.
- **`input/` je read-only a NEČÍTA sa.** Všetok kód je v pláne napísaný nanovo; žiadne názvy klienta, dodávateľa, ľudí, interných systémov, interné URL, heslá ani dáta z logov (`poznamky/00-konvencie.md`).
- Jazyk: identifikátory po anglicky; komentáre, texty UI (`messages.properties`) a dokumentácia po **slovensky**. Komentáre vysvetľujú **legacy vzor a prečo je dôležitý pre migráciu**, nie čo kód robí. Verejné metódy majú krátky JavaDoc.
- Testy: JUnit 5, názvy `should_…_when…()`, `@DisplayName` po slovensky.
- Každá JSP v `customercare-portlets` začína `<%@ include file="/WEB-INF/jsp/init.jspf" %>` a nemá vlastnú `page` direktívu.
- Seed dáta (ID záznamov blacklistu, identifikátorov, produktov, zákazníkov `C-1001`/`C-1002`, operátorov `citatel`/`spravca`) sú **rovnaké ako v pláne podprojektu 2** – podprojekt 2 migruje tie isté dáta.
- Formy a odkazy, ktoré číta smoke test, majú pevné `id` (`searchForm`, `changeForm`, `noteForm`, `createForm`, `deactivationForm`, `detail-<id>`, `deactivate-<productId>`).

## Review Focus

1. **Diakritika v POST cez Liferay** (`firstName=Mária` v action fáze) musí nájsť záznam a zobraziť sa správne – Tomcat 7 bez filtra číta telo ako ISO-8859-1. Overuje smoke test v Tasku 16 (krok „diakritika“).
2. **Podvrhnutá alebo poškodená cookie `SSO_ID`** (`../admin`, prázdna, 500 znakov) sa musí správať ako „neprihlásený“ a nesmie vyvolať HTTP volanie na zostavenú cestu. Test `should_returnNullWithoutHttpCall_when_cookieValueIsMalformed` v Tasku 1.
3. **Open redirect v SSO mocku** – absolútne alebo `//` `returnUrl` musí skončiť na predvolenej stránke. Test `should_ignoreForeignReturnUrl_when_loginRedirects` v Tasku 2.
4. **Výpadok Redisu** pri flagu `PORTLET` nesmie zhodiť stránku (zápis zlyhá len do logu) a pri flagu `REDIS` sa číta zo starej session. Testy v Tasku 3 (`DualSessionStoreTest`) a smoke krok „Redis dole“ v Tasku 16.
5. **AJAX (resource) po odhlásení v SSO** musí dostať HTTP 401 bez HTML, nie prihlasovaciu výzvu vloženú do `<div>`. Test `should_return401Status_when_resourceWithoutLogin` v Tasku 8 a smoke krok v Tasku 16.

---

## Štruktúra súborov

```
legacy/pom.xml                                     parent (moduly pribúdajú v Taskoch 1, 3, 9, 15)
legacy/sso-portal-api/
  pom.xml (shade, relokácia Jacksonu)
  src/main/java/com/example/sso/api/PortalOperator, PortalCustomer, PortalSession, SsoUnavailableException,
      PortalSessionManager, SsoCookies, HttpPortalSessionManager, PortalServiceLocator
legacy/frontend-base-customization/
  src/main/java/com/example/crm/frontend/base/
    session/UiMessage, RequestAwareManager, PortletRequestAwareManager, SessionStore, PortletSessionStore,
            SharedKeys, RedisSessionStore, ReadSource, ReadSourceFlag, DualSessionStore,
            SessionManagerCustom, SessionManagerCustomImpl
    acl/Privilege, AclService, InMemoryAclService, OperatorAcl, AclDeniedException
    web/NotFoundException, AccessDecision, PortletAccessGuard, DispatcherPortletCustom, BaseCrmController,
        LocalDateEditor, SimpleMappingExceptionResolverCustom
    i18n/MessageSourceCustom
    tag/TagBeans, MessageTag, MsgArgTag, AclFilterTag, AclPageFilterTag,
        PortletUrlTagSupport, ActionUrlTag, RenderUrlTag, ResourceUrlTag, ParamTag
  src/main/resources/META-INF/common.tld, portal.tld
legacy/customercare-portlets/
  src/main/java/com/example/crm/frontend/
    backend/blacklist/(doména + BlacklistService + InMemoryBlacklistService)
    backend/deactivation/(doména + pravidlá + 2 služby)
    header/HeaderController
    blacklist/LastSearchCriteria, LastViewedRecord, BlacklistSearchController, BlacklistResultController,
              BlacklistDetailController, BlacklistCreateController, CreateForm, BlacklistCreateValidator,
              SearchCriteriaValidator
    dashboard/DashboardController, DeactivationForm, DeactivationFormValidator
    hook/CrmStartupAction
  src/main/resources/messages.properties, portal.properties
  src/main/webapp/WEB-INF/web.xml, applicationContext.xml, backend-context.xml, portlet.xml,
      liferay-portlet.xml, liferay-display.xml, liferay-plugin-package.properties, liferay-hook.xml,
      blacklist-portlet.xml, dashboard-portlet.xml, header-portlet.xml,
      jsp/init.jspf, jsp/common/login-required.jsp, jsp/error/{403,404,sso,error}.jsp, jsp/header/view.jsp,
      jsp/blacklist/{search,searchresult,detail,create}.jsp, jsp/blacklist/fragments/{identifiers,conditionTypes}.jsp,
      jsp/dashboard/products.jsp, jsp/dashboard/deactivation/{form,done}.jsp,
      hooked/html/portal/status.jsp
  src/main/webapp/css/crm.css, js/crm.js
legacy/customercare-theme/
  src/main/webapp/WEB-INF/liferay-look-and-feel.xml, liferay-plugin-package.properties,
  templates/{portal_normal,portal_pop_up,portlet,navigation,init_custom}.vm, css/main.css
sso-mock/pom.xml, Dockerfile, src/main/java/com/example/ssomock/…, src/main/resources/application.properties
docker/docker-compose.yml, liferay/Dockerfile, liferay/portal-ext.properties, nginx/default.conf,
       build-and-deploy.sh/.ps1, smoke.sh/.ps1
legacy/README.md, poznamky/10-liferay62-v-dockeri.md (+ úpravy 07, 09, README), README.md, CLAUDE.md, .gitignore
```

---

### Task 1: Parent POM a `sso-portal-api` (API SSO portálu + HTTP klient + service locator)

**Files:**
- Create: `legacy/pom.xml`
- Create: `legacy/sso-portal-api/pom.xml`
- Create: `legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalOperator.java`, `PortalCustomer.java`, `PortalSession.java`, `SsoUnavailableException.java`, `PortalSessionManager.java`, `SsoCookies.java`, `HttpPortalSessionManager.java`, `PortalServiceLocator.java`
- Test: `legacy/sso-portal-api/src/test/java/com/example/sso/api/HttpPortalSessionManagerTest.java`
- Test: `legacy/sso-portal-api/src/test/java/com/example/sso/api/PortalServiceLocatorTest.java`
- Modify: `.gitignore` (doplniť `docker/deploy/`, `docker/liferay/sso-portal-api.jar`)

**Interfaces:**
- Produces:
  - POJO `PortalOperator(String login, String displayName)`, `PortalCustomer(String customerId, String displayName)`, `PortalSession` (`getSessionId()`, `getOperator()`, `getCustomer()` + settery, bezparametrické konštruktory)
  - `SsoUnavailableException extends RuntimeException` (`(String message, Throwable cause)`)
  - `interface PortalSessionManager { String SSO_COOKIE = "SSO_ID"; PortalSession getPortalSession(HttpServletRequest); String getLoginUrl(String returnUrl); }`
  - `SsoCookies.readSessionId(HttpServletRequest): String` – hodnota cookie `SSO_ID`, ak zodpovedá `[A-Za-z0-9-]{1,64}`, inak `null`
  - `HttpPortalSessionManager(String apiUrl, String loginUrl, int connectTimeoutMs, int readTimeoutMs)`
  - `PortalServiceLocator.getPortalSessionManager()`, `setPortalSessionManager(PortalSessionManager)`; konštanty `API_URL_PROPERTY = "sso.portal.api-url"`, `LOGIN_URL_PROPERTY = "sso.portal.login-url"`
  - API SSO mocku (Task 2): `GET {apiUrl}/api/sessions/{id}` → 200 `{"operator":{"login","displayName"},"customer":{"customerId","displayName"}}` | 404

- [ ] **Step 1: Vytvor parent POM**

`legacy/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!--
      „Stará“ appka 1:1 s legacy: Java 8, Spring 4.3.6, Liferay 6.2.5 API. Všetko je v Maven Central,
      takže na rozdiel od reálneho legacy netreba súkromný repozitár. Build beží na JDK 21 s release 8
      (garantuje len Java 8 API), runtime je skutočná Java 8 v Docker image Liferay.
    -->
    <groupId>com.example.crm.legacy</groupId>
    <artifactId>legacy-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>sso-portal-api</module>
    </modules>

    <properties>
        <maven.compiler.release>8</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <spring.version>4.3.6.RELEASE</spring.version>
        <liferay.version>6.2.5</liferay.version>
        <jackson.version>2.9.10.8</jackson.version>
        <jedis.version>2.9.0</jedis.version>
        <junit.version>5.10.5</junit.version>
        <mockito.version>4.11.0</mockito.version>
        <assertj.version>3.27.3</assertj.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.example.crm.legacy</groupId>
                <artifactId>sso-portal-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.example.crm.legacy</groupId>
                <artifactId>frontend-base-customization</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-webmvc-portlet</artifactId>
                <version>${spring.version}</version>
            </dependency>
            <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-test</artifactId>
                <version>${spring.version}</version>
            </dependency>
            <dependency>
                <groupId>com.liferay.portal</groupId>
                <artifactId>portal-service</artifactId>
                <version>${liferay.version}</version>
            </dependency>
            <dependency>
                <groupId>javax.portlet</groupId>
                <artifactId>portlet-api</artifactId>
                <version>2.0</version>
            </dependency>
            <dependency>
                <groupId>javax.servlet</groupId>
                <artifactId>javax.servlet-api</artifactId>
                <version>3.0.1</version>
            </dependency>
            <dependency>
                <groupId>javax.servlet.jsp</groupId>
                <artifactId>jsp-api</artifactId>
                <version>2.2</version>
            </dependency>
            <dependency>
                <groupId>javax.servlet</groupId>
                <artifactId>jstl</artifactId>
                <version>1.2</version>
            </dependency>
            <dependency>
                <groupId>com.fasterxml.jackson.core</groupId>
                <artifactId>jackson-databind</artifactId>
                <version>${jackson.version}</version>
            </dependency>
            <dependency>
                <groupId>redis.clients</groupId>
                <artifactId>jedis</artifactId>
                <version>${jedis.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${junit.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.mockito</groupId>
            <artifactId>mockito-core</artifactId>
            <version>${mockito.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <version>${assertj.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                </plugin>
                <plugin>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                    <configuration>
                        <!-- Testy bežia na JDK 21 (bytecode je Java 8); Byte Buddy v Mockito 4 tak toleruje novšie JDK. -->
                        <argLine>-Dnet.bytebuddy.experimental=true -Dfile.encoding=UTF-8</argLine>
                    </configuration>
                </plugin>
                <plugin>
                    <artifactId>maven-resources-plugin</artifactId>
                    <version>3.3.1</version>
                </plugin>
                <plugin>
                    <artifactId>maven-jar-plugin</artifactId>
                    <version>3.4.2</version>
                </plugin>
                <plugin>
                    <artifactId>maven-war-plugin</artifactId>
                    <version>3.4.0</version>
                </plugin>
                <plugin>
                    <artifactId>maven-shade-plugin</artifactId>
                    <version>3.6.0</version>
                </plugin>
                <plugin>
                    <artifactId>maven-install-plugin</artifactId>
                    <version>3.1.3</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 2: Vytvor POM modulu `sso-portal-api`**

`legacy/sso-portal-api/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example.crm.legacy</groupId>
        <artifactId>legacy-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <!--
      Ekvivalent „provided“ API SSO portálu, ktoré legacy dostáva z JBoss modulu. Tu ide do tomcat/lib/ext,
      teda do spoločného classloadera všetkých WAR-ov. Jackson je relokovaný (shade), aby sa nebil s Jacksonom
      vo WAR-och ani v portáli.
    -->
    <artifactId>sso-portal-api</artifactId>

    <dependencies>
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
    </dependencies>

    <build>
        <finalName>sso-portal-api</finalName>
        <plugins>
            <plugin>
                <artifactId>maven-shade-plugin</artifactId>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <createDependencyReducedPom>true</createDependencyReducedPom>
                            <relocations>
                                <relocation>
                                    <pattern>com.fasterxml.jackson</pattern>
                                    <shadedPattern>com.example.sso.shaded.jackson</shadedPattern>
                                </relocation>
                            </relocations>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                        <exclude>META-INF/versions/**</exclude>
                                        <exclude>module-info.class</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Napíš padajúce testy**

`legacy/sso-portal-api/src/test/java/com/example/sso/api/HttpPortalSessionManagerTest.java`:

```java
package com.example.sso.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpPortalSessionManagerTest {

    private static final String SESSION_JSON = "{\"operator\":{\"login\":\"spravca\",\"displayName\":\"Správca\"},"
            + "\"customer\":{\"customerId\":\"C-1001\",\"displayName\":\"Ján Vzorový\"},\"unknownField\":1}";

    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;
    private String apiUrl;

    @BeforeEach
    void startFakePortal() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sso/api/sessions/", this::handle);
        server.start();
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/sso";
    }

    @AfterEach
    void stopFakePortal() {
        server.stop(0);
    }

    @Test
    @DisplayName("Vráti operátora a zákazníka, keď portál pozná session z cookie")
    void should_returnSession_when_portalKnowsCookie() {
        PortalSession session = manager(2000).getPortalSession(requestWithCookie("s-ok"));

        assertThat(session.getSessionId()).isEqualTo("s-ok");
        assertThat(session.getOperator().getLogin()).isEqualTo("spravca");
        assertThat(session.getOperator().getDisplayName()).isEqualTo("Správca");
        assertThat(session.getCustomer().getCustomerId()).isEqualTo("C-1001");
        assertThat(session.getCustomer().getDisplayName()).isEqualTo("Ján Vzorový");
    }

    @Test
    @DisplayName("Vráti null, keď portál session nepozná (404)")
    void should_returnNull_when_portalReturns404() {
        assertThat(manager(2000).getPortalSession(requestWithCookie("s-missing"))).isNull();
    }

    @Test
    @DisplayName("Bez cookie vráti null a portál nevolá")
    void should_returnNullWithoutHttpCall_when_cookieMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertThat(manager(2000).getPortalSession(request)).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("Poškodená alebo podvrhnutá cookie sa berie ako neprihlásený a portál sa nevolá")
    void should_returnNullWithoutHttpCall_when_cookieValueIsMalformed() {
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            tooLong.append('a');
        }

        assertThat(manager(2000).getPortalSession(requestWithCookie("../admin"))).isNull();
        assertThat(manager(2000).getPortalSession(requestWithCookie(""))).isNull();
        assertThat(manager(2000).getPortalSession(requestWithCookie(tooLong.toString()))).isNull();
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("HTTP 500 z portálu je nedostupný portál")
    void should_throwSsoUnavailable_when_portalReturns500() {
        assertThatThrownBy(() -> manager(2000).getPortalSession(requestWithCookie("s-broken")))
                .isInstanceOf(SsoUnavailableException.class)
                .hasMessageContaining("500");
    }

    @Test
    @DisplayName("Pomalý portál skončí timeoutom ako nedostupný portál")
    void should_throwSsoUnavailable_when_portalIsTooSlow() {
        assertThatThrownBy(() -> manager(200).getPortalSession(requestWithCookie("s-slow")))
                .isInstanceOf(SsoUnavailableException.class);
    }

    @Test
    @DisplayName("Vypnutý portál je nedostupný portál")
    void should_throwSsoUnavailable_when_portalIsDown() {
        server.stop(0);

        assertThatThrownBy(() -> manager(2000).getPortalSession(requestWithCookie("s-ok")))
                .isInstanceOf(SsoUnavailableException.class);
    }

    @Test
    @DisplayName("Návratová URL v login URL je zakódovaná")
    void should_encodeReturnUrl_when_buildingLoginUrl() {
        assertThat(manager(2000).getLoginUrl("/web/crm/blacklist?a=1&b=2"))
                .isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist%3Fa%3D1%26b%3D2");
    }

    private HttpPortalSessionManager manager(int readTimeoutMs) {
        return new HttpPortalSessionManager(apiUrl, "/sso/login", 500, readTimeoutMs);
    }

    private static HttpServletRequest requestWithCookie(String value) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("OTHER", "x"), new Cookie("SSO_ID", value)});
        return request;
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        if ("s-slow".equals(id)) {
            sleep(1500);
        }
        if ("s-ok".equals(id) || "s-slow".equals(id)) {
            byte[] body = SESSION_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        exchange.sendResponseHeaders("s-broken".equals(id) ? 500 : 404, -1);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

`legacy/sso-portal-api/src/test/java/com/example/sso/api/PortalServiceLocatorTest.java`:

```java
package com.example.sso.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortalServiceLocatorTest {

    @AfterEach
    void reset() {
        PortalServiceLocator.setPortalSessionManager(null);
        System.clearProperty(PortalServiceLocator.API_URL_PROPERTY);
        System.clearProperty(PortalServiceLocator.LOGIN_URL_PROPERTY);
    }

    @Test
    @DisplayName("Bez URL API zlyhá so zrozumiteľnou správou")
    void should_failWithClearMessage_when_apiUrlPropertyMissing() {
        assertThatThrownBy(PortalServiceLocator::getPortalSessionManager)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sso.portal.api-url");
    }

    @Test
    @DisplayName("Vráti podstrčenú implementáciu (testy, fake na lokálny beh)")
    void should_returnInjectedManager_when_setExplicitly() {
        PortalSessionManager fake = mock(PortalSessionManager.class);
        PortalServiceLocator.setPortalSessionManager(fake);

        assertThat(PortalServiceLocator.getPortalSessionManager()).isSameAs(fake);
    }

    @Test
    @DisplayName("Podľa system property vytvorí HTTP klienta raz a potom ho opakovane vracia")
    void should_createHttpManagerOnce_when_propertySet() {
        System.setProperty(PortalServiceLocator.API_URL_PROPERTY, "http://sso-mock:8090/sso");

        PortalSessionManager first = PortalServiceLocator.getPortalSessionManager();

        assertThat(first).isInstanceOf(HttpPortalSessionManager.class);
        assertThat(PortalServiceLocator.getPortalSessionManager()).isSameAs(first);
        assertThat(first.getLoginUrl("/x")).isEqualTo("/sso/login?returnUrl=%2Fx");
    }
}
```

- [ ] **Step 4: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class HttpPortalSessionManager`.

- [ ] **Step 5: Implementuj DTO a výnimku**

`legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalOperator.java`:

```java
package com.example.sso.api;

import java.io.Serializable;

/** Operátor prihlásený v SSO portáli. POJO s bezparametrickým konštruktorom kvôli JSON (Java 8, bez recordov). */
public class PortalOperator implements Serializable {

    private static final long serialVersionUID = 1L;

    private String login;
    private String displayName;

    public PortalOperator() {
    }

    public PortalOperator(String login, String displayName) {
        this.login = login;
        this.displayName = displayName;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalCustomer.java`:

```java
package com.example.sso.api;

import java.io.Serializable;

/** Zákazník, s ktorým operátor práve pracuje. V legacy ho vyberá SSO portál, nie CRM. */
public class PortalCustomer implements Serializable {

    private static final long serialVersionUID = 1L;

    private String customerId;
    private String displayName;

    public PortalCustomer() {
    }

    public PortalCustomer(String customerId, String displayName) {
        this.customerId = customerId;
        this.displayName = displayName;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalSession.java`:

```java
package com.example.sso.api;

import java.io.Serializable;

/** Session SSO portálu – zdieľaná s CRM. Identita a zákazník žijú tu, nie v Liferay ani v CRM. */
public class PortalSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private String sessionId;
    private PortalOperator operator;
    private PortalCustomer customer;

    public PortalSession() {
    }

    public PortalSession(String sessionId, PortalOperator operator, PortalCustomer customer) {
        this.sessionId = sessionId;
        this.operator = operator;
        this.customer = customer;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public PortalOperator getOperator() {
        return operator;
    }

    public void setOperator(PortalOperator operator) {
        this.operator = operator;
    }

    public PortalCustomer getCustomer() {
        return customer;
    }

    public void setCustomer(PortalCustomer customer) {
        this.customer = customer;
    }
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/SsoUnavailableException.java`:

```java
package com.example.sso.api;

/** SSO portál neodpovedá alebo odpovedá chybou – nie je to „neprihlásený používateľ“. */
public class SsoUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SsoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 6: Implementuj API, cookie, HTTP klienta a service locator**

`legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalSessionManager.java`:

```java
package com.example.sso.api;

import javax.servlet.http.HttpServletRequest;

/**
 * API SSO portálu, ako ho vidí CRM. V legacy je to „provided“ závislosť, ktorú dodáva prostredie (JBoss modul);
 * CRM nevie, či je za ňou HTTP, EJB alebo fake – implementáciu vyberá {@link PortalServiceLocator}.
 */
public interface PortalSessionManager {

    /** Cookie, ktorou portál páruje prehliadač so svojou session. */
    String SSO_COOKIE = "SSO_ID";

    /**
     * Vráti session portálu podľa cookie v requeste, alebo {@code null}, ak používateľ nie je prihlásený.
     *
     * @throws SsoUnavailableException ak portál neodpovedá
     */
    PortalSession getPortalSession(HttpServletRequest request);

    /** URL prihlasovacej stránky portálu, ktorá po prihlásení vráti používateľa na {@code returnUrl}. */
    String getLoginUrl(String returnUrl);
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/SsoCookies.java`:

```java
package com.example.sso.api;

import java.util.regex.Pattern;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

/** Čítanie cookie SSO portálu. Používa ho klient portálu aj CRM (kľúč zdieľanej session v Redise). */
public final class SsoCookies {

    // Hodnota ide do URL portálu aj do kľúča v Redise – iné znaky by umožnili podvrhnúť cestu alebo kľúč.
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private SsoCookies() {
    }

    /** Vráti ID session portálu z cookie {@code SSO_ID}, alebo {@code null}, ak chýba alebo má neplatný tvar. */
    public static String readSessionId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (PortalSessionManager.SSO_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value != null && VALID_ID.matcher(value).matches() ? value : null;
            }
        }
        return null;
    }
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/HttpPortalSessionManager.java`:

```java
package com.example.sso.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import javax.servlet.http.HttpServletRequest;

/**
 * Implementácia API nad REST rozhraním SSO mocku. Java 8 bez knižníc navyše ({@link HttpURLConnection}),
 * lebo JAR beží v spoločnom classloaderi portálu. Krátke timeouty: SSO sa volá pri každom requeste.
 */
public class HttpPortalSessionManager implements PortalSessionManager {

    private final String apiUrl;
    private final String loginUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Vytvorí klienta pre API na {@code apiUrl} (napr. {@code http://sso-mock:8090/sso}). */
    public HttpPortalSessionManager(String apiUrl, String loginUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.apiUrl = apiUrl;
        this.loginUrl = loginUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public PortalSession getPortalSession(HttpServletRequest request) {
        String sessionId = SsoCookies.readSessionId(request);
        if (sessionId == null) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(apiUrl + "/api/sessions/" + sessionId).openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new SsoUnavailableException("SSO portál vrátil HTTP " + status, null);
            }
            try (InputStream body = connection.getInputStream()) {
                PortalSession session = mapper.readValue(body, PortalSession.class);
                session.setSessionId(sessionId);
                return session;
            }
        } catch (IOException e) {
            throw new SsoUnavailableException("SSO portál je nedostupný: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public String getLoginUrl(String returnUrl) {
        try {
            return loginUrl + "?returnUrl=" + URLEncoder.encode(returnUrl, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

`legacy/sso-portal-api/src/main/java/com/example/sso/api/PortalServiceLocator.java`:

```java
package com.example.sso.api;

/**
 * Service locator ako v legacy: CRM si implementáciu API nevyberá samo, dostane ju od prostredia.
 * Tu podľa system properties (Docker: {@code CATALINA_OPTS}); testy a fake podstrčia vlastnú cez setter.
 * Pri migrácii sa locator nahradí obyčajným Spring beanom.
 */
public final class PortalServiceLocator {

    public static final String API_URL_PROPERTY = "sso.portal.api-url";
    public static final String LOGIN_URL_PROPERTY = "sso.portal.login-url";

    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int READ_TIMEOUT_MS = 2000;

    private static volatile PortalSessionManager instance;

    private PortalServiceLocator() {
    }

    /** Vráti implementáciu API SSO portálu (pri prvom volaní ju vytvorí podľa system properties). */
    public static PortalSessionManager getPortalSessionManager() {
        PortalSessionManager current = instance;
        if (current != null) {
            return current;
        }
        synchronized (PortalServiceLocator.class) {
            if (instance == null) {
                instance = create();
            }
            return instance;
        }
    }

    /** Podstrčí implementáciu (testy, lokálny fake); {@code null} vráti locator do pôvodného stavu. */
    public static void setPortalSessionManager(PortalSessionManager manager) {
        instance = manager;
    }

    private static PortalSessionManager create() {
        String apiUrl = System.getProperty(API_URL_PROPERTY);
        if (apiUrl == null || apiUrl.trim().isEmpty()) {
            throw new IllegalStateException("Chýba system property " + API_URL_PROPERTY + " (URL API SSO portálu)");
        }
        String loginUrl = System.getProperty(LOGIN_URL_PROPERTY, "/sso/login");
        return new HttpPortalSessionManager(apiUrl.trim(), loginUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }
}
```

- [ ] **Step 7: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (11 testov: 8 + 3).

- [ ] **Step 8: Over shaded JAR**

Run: `mvn -q -B -f legacy/pom.xml package -DskipTests && unzip -l legacy/sso-portal-api/target/sso-portal-api.jar | grep -c "com/example/sso/shaded/jackson/databind/ObjectMapper.class"; unzip -l legacy/sso-portal-api/target/sso-portal-api.jar | grep -c "com/fasterxml/"`
Expected: `1` a `0` (Jackson je relokovaný, pôvodný balík v JAR-e nie je).

- [ ] **Step 9: Doplň `.gitignore`**

Na koniec `.gitignore` pridaj:

```
# Docker: WAR-y na hot deploy a API JAR kopírovaný do image Liferay (build výstupy)
/docker/deploy/
/docker/liferay/sso-portal-api.jar
```

- [ ] **Step 10: Checkpoint (bez commitu)** – `git status --short`

---

### Task 2: SSO mock (samostatná Spring Boot appka)

**Files:**
- Create: `sso-mock/pom.xml`, `sso-mock/Dockerfile`
- Create: `sso-mock/src/main/java/com/example/ssomock/SsoMockApplication.java`, `Directory.java`, `PortalSessionStore.java`, `LoginPage.java`, `LoginController.java`, `ApiController.java`
- Create: `sso-mock/src/main/resources/application.properties`
- Test: `sso-mock/src/test/java/com/example/ssomock/SsoMockTest.java`

**Interfaces:**
- Consumes: kontrakt API z Tasku 1 (`GET /sso/api/sessions/{id}`, cookie `SSO_ID`)
- Produces (kontext `/sso`, port 8090):
  - `GET /sso/login?returnUrl=` – HTML: login formulár; ak je cookie platná, aj aktuálny stav, zmena zákazníka a odhlásenie
  - `POST /sso/login` (`operator`, `customer`, `returnUrl`) → 302 na bezpečný `returnUrl`, `Set-Cookie: SSO_ID=…; Path=/; HttpOnly; SameSite=Lax`; neznámy operátor/zákazník → 400
  - `POST /sso/customer` (`customer`, `returnUrl`) → 302; bez platnej session → 302 na `/sso/login?returnUrl=…`
  - `POST /sso/logout` → 302 na `/sso/login`, cookie `Max-Age=0`
  - `GET /sso/api/sessions/{id}` → 200 JSON / 404
  - Operátori `citatel` („Čitateľ“), `spravca` („Správca“); zákazníci `C-1001` („Ján Vzorový“), `C-1002` („Firma Ukážka s.r.o.“); predvolený `returnUrl` = `/web/crm/blacklist`

- [ ] **Step 1: Vytvor POM a konfiguráciu**

`sso-mock/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>

    <!-- Mock externého SSO portálu. Nie je to legacy, preto moderný stack (rovnaký ako appka „po“). -->
    <groupId>com.example.sso</groupId>
    <artifactId>sso-mock</artifactId>
    <version>0.1.0-SNAPSHOT</version>

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
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>sso-mock</finalName>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`sso-mock/src/main/resources/application.properties`:

```properties
server.port=8090
# Za nginx beží pod /sso/ – rovnaká cesta aj pri priamom prístupe.
server.servlet.context-path=/sso
spring.main.banner-mode=off
```

`sso-mock/Dockerfile`:

```dockerfile
# JAR zbuildí docker/build-and-deploy (mvn -f sso-mock/pom.xml verify) – image ho len zabalí.
FROM eclipse-temurin:21-jre
COPY target/sso-mock.jar /app/sso-mock.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/sso-mock.jar"]
```

- [ ] **Step 2: Napíš padajúci test**

`sso-mock/src/test/java/com/example/ssomock/SsoMockTest.java`:

```java
package com.example.ssomock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class SsoMockTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("Prihlasovacia stránka ponúkne operátorov a zákazníkov")
    void should_offerOperatorsAndCustomers_when_loginPageOpened() throws Exception {
        mvc.perform(get("/login").param("returnUrl", "/web/crm/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Správca")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("C-1002")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"/web/crm/dashboard\"")));
    }

    @Test
    @DisplayName("Prihlásenie nastaví cookie, presmeruje a nevytvorí HttpSession")
    void should_setCookieAndRedirectWithoutHttpSession_when_loginSucceeds() throws Exception {
        MvcResult result = login("spravca", "C-1001", "/web/crm/dashboard");

        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/web/crm/dashboard");
        assertThat(result.getResponse().getHeader("Set-Cookie"))
                .startsWith("SSO_ID=").contains("Path=/").contains("HttpOnly");
        // Za jednou adresou by JSESSIONID mocku prepísal session Liferay – mock preto session nikdy nevytvára.
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    @Test
    @DisplayName("Cudzí returnUrl (absolútny, //, spätné lomítko) skončí na predvolenej stránke")
    void should_ignoreForeignReturnUrl_when_loginRedirects() throws Exception {
        assertThat(login("spravca", "C-1001", "https://evil.example/x").getResponse().getHeader("Location"))
                .isEqualTo("/web/crm/blacklist");
        assertThat(login("spravca", "C-1001", "//evil.example/x").getResponse().getHeader("Location"))
                .isEqualTo("/web/crm/blacklist");
        assertThat(login("spravca", "C-1001", "/\\evil.example").getResponse().getHeader("Location"))
                .isEqualTo("/web/crm/blacklist");
    }

    @Test
    @DisplayName("Neznámy operátor alebo zákazník je 400")
    void should_reject_when_operatorOrCustomerUnknown() throws Exception {
        mvc.perform(post("/login").param("operator", "hacker").param("customer", "C-1001").param("returnUrl", "/"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/login").param("operator", "spravca").param("customer", "C-9999").param("returnUrl", "/"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("API vráti operátora a zákazníka podľa ID session")
    void should_returnSessionJson_when_sessionExists() throws Exception {
        String id = sessionId(login("citatel", "C-1002", "/"));

        mvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operator.login").value("citatel"))
                .andExpect(jsonPath("$.operator.displayName").value("Čitateľ"))
                .andExpect(jsonPath("$.customer.customerId").value("C-1002"))
                .andExpect(jsonPath("$.customer.displayName").value("Firma Ukážka s.r.o."));
    }

    @Test
    @DisplayName("API vráti 404 pre neznámu session")
    void should_return404_when_sessionUnknown() throws Exception {
        mvc.perform(get("/api/sessions/neexistuje")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Zmena zákazníka sa prejaví v API")
    void should_changeCustomer_when_postedWithValidCookie() throws Exception {
        String id = sessionId(login("spravca", "C-1001", "/"));

        mvc.perform(post("/customer").cookie(new Cookie("SSO_ID", id))
                        .param("customer", "C-1002").param("returnUrl", "/web/crm/dashboard"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/web/crm/dashboard"));
        mvc.perform(get("/api/sessions/" + id)).andExpect(jsonPath("$.customer.customerId").value("C-1002"));
    }

    @Test
    @DisplayName("Zmena zákazníka bez session presmeruje na login")
    void should_redirectToLogin_when_changingCustomerWithoutSession() throws Exception {
        mvc.perform(post("/customer").param("customer", "C-1002").param("returnUrl", "/web/crm/dashboard"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/sso/login?returnUrl=%2Fweb%2Fcrm%2Fdashboard"));
    }

    @Test
    @DisplayName("Odhlásenie zruší session a zmaže cookie")
    void should_forgetSessionAndExpireCookie_when_logout() throws Exception {
        String id = sessionId(login("spravca", "C-1001", "/"));

        MvcResult logout = mvc.perform(post("/logout").cookie(new Cookie("SSO_ID", id))).andReturn();

        assertThat(logout.getResponse().getStatus()).isEqualTo(302);
        assertThat(logout.getResponse().getHeader("Location")).isEqualTo("/sso/login");
        assertThat(logout.getResponse().getHeader("Set-Cookie")).startsWith("SSO_ID=").contains("Max-Age=0");
        mvc.perform(get("/api/sessions/" + id)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Prihlasovacia stránka s platnou cookie ukáže aktuálny stav a zmenu zákazníka")
    void should_showCurrentStateAndCustomerSwitch_when_cookieValid() throws Exception {
        String id = sessionId(login("spravca", "C-1001", "/"));

        mvc.perform(get("/login").cookie(new Cookie("SSO_ID", id)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Prihlásený: Správca")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("action=\"/sso/customer\"")));
    }

    private MvcResult login(String operator, String customer, String returnUrl) throws Exception {
        return mvc.perform(post("/login").param("operator", operator).param("customer", customer)
                .param("returnUrl", returnUrl)).andReturn();
    }

    private static String sessionId(MvcResult loginResult) {
        String setCookie = loginResult.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("SSO_ID=".length(), setCookie.indexOf(';'));
    }
}
```

- [ ] **Step 3: Spusti test, musí zlyhať**

Run: `mvn -q -B -f sso-mock/pom.xml test`
Expected: FAIL – kompilácia (chýba `SsoMockApplication`) alebo „Unable to find a @SpringBootConfiguration“.

- [ ] **Step 4: Implementuj aplikáciu, adresár a úložisko session**

`sso-mock/src/main/java/com/example/ssomock/SsoMockApplication.java`:

```java
package com.example.ssomock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Mock SSO portálu: drží identitu operátora a vybraného zákazníka, CRM (stará aj nová appka) ich číta cez REST. */
@SpringBootApplication
public class SsoMockApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsoMockApplication.class, args);
    }
}
```

`sso-mock/src/main/java/com/example/ssomock/Directory.java`:

```java
package com.example.ssomock;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Pevný zoznam demo operátorov a zákazníkov (vymyslené). Privilégiá tu nie sú – ACL je vecou CRM. */
@Component
public class Directory {

    public record Operator(String login, String displayName) {
    }

    public record Customer(String customerId, String displayName) {
    }

    private static final List<Operator> OPERATORS = List.of(
            new Operator("citatel", "Čitateľ"),
            new Operator("spravca", "Správca"));

    private static final List<Customer> CUSTOMERS = List.of(
            new Customer("C-1001", "Ján Vzorový"),
            new Customer("C-1002", "Firma Ukážka s.r.o."));

    /** Všetci demo operátori. */
    public List<Operator> operators() {
        return OPERATORS;
    }

    /** Všetci demo zákazníci. */
    public List<Customer> customers() {
        return CUSTOMERS;
    }

    /** Operátor podľa loginu. */
    public Optional<Operator> findOperator(String login) {
        return OPERATORS.stream().filter(o -> o.login().equals(login)).findFirst();
    }

    /** Zákazník podľa ID. */
    public Optional<Customer> findCustomer(String customerId) {
        return CUSTOMERS.stream().filter(c -> c.customerId().equals(customerId)).findFirst();
    }
}
```

`sso-mock/src/main/java/com/example/ssomock/PortalSessionStore.java`:

```java
package com.example.ssomock;

import com.example.ssomock.Directory.Customer;
import com.example.ssomock.Directory.Operator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Session portálu v pamäti procesu. Reálny portál ju drží vo svojom úložisku; pre demo stačí mapa. */
@Component
public class PortalSessionStore {

    public record PortalSession(String id, Operator operator, Customer customer) {
    }

    private final Map<String, PortalSession> sessions = new ConcurrentHashMap<>();

    /** Vytvorí session a vráti jej ID (UUID – zodpovedá formátu, ktorý akceptuje CRM). */
    public String create(Operator operator, Customer customer) {
        String id = UUID.randomUUID().toString();
        sessions.put(id, new PortalSession(id, operator, customer));
        return id;
    }

    /** Session podľa ID. */
    public Optional<PortalSession> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(sessions.get(id));
    }

    /** Zmení zákazníka; vráti {@code false}, ak session neexistuje. */
    public boolean changeCustomer(String id, Customer customer) {
        return sessions.computeIfPresent(id, (key, s) -> new PortalSession(key, s.operator(), customer)) != null;
    }

    /** Zruší session. */
    public void remove(String id) {
        if (id != null) {
            sessions.remove(id);
        }
    }
}
```

- [ ] **Step 5: Implementuj HTML stránku a controllery**

`sso-mock/src/main/java/com/example/ssomock/LoginPage.java`:

```java
package com.example.ssomock;

import com.example.ssomock.PortalSessionStore.PortalSession;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Jednoduchá HTML stránka bez šablónovacieho enginu – mock nemá byť ďalšia technológia na učenie. */
@Component
public class LoginPage {

    private final Directory directory;

    public LoginPage(Directory directory) {
        this.directory = directory;
    }

    /** Vykreslí login (a pri existujúcej session aj jej stav, zmenu zákazníka a odhlásenie). */
    public String render(String returnUrl, Optional<PortalSession> current) {
        String safeReturn = htmlEscape(returnUrl);
        String state = current.map(s -> """
                <section>
                  <p>Prihlásený: %s · zákazník: %s</p>
                  <form method="post" action="/sso/customer">
                    <input type="hidden" name="returnUrl" value="%s">
                    <select name="customer">%s</select>
                    <button type="submit">Zmeniť zákazníka</button>
                  </form>
                  <form method="post" action="/sso/logout"><button type="submit">Odhlásiť</button></form>
                  <p><a href="%s">Späť do CRM</a></p>
                </section>
                """.formatted(htmlEscape(s.operator().displayName()), htmlEscape(s.customer().displayName()),
                safeReturn, customerOptions(), safeReturn)).orElse("");
        return """
                <!DOCTYPE html>
                <html lang="sk"><head><meta charset="UTF-8"><title>SSO portál (mock)</title></head>
                <body>
                <h1>SSO portál (mock)</h1>
                %s
                <form method="post" action="/sso/login">
                  <input type="hidden" name="returnUrl" value="%s">
                  <label>Operátor <select name="operator">%s</select></label>
                  <label>Zákazník <select name="customer">%s</select></label>
                  <button type="submit">Prihlásiť</button>
                </form>
                </body></html>
                """.formatted(state, safeReturn, operatorOptions(), customerOptions());
    }

    private String operatorOptions() {
        return directory.operators().stream()
                .map(o -> option(o.login(), o.displayName() + " (" + o.login() + ")"))
                .collect(Collectors.joining());
    }

    private String customerOptions() {
        return directory.customers().stream()
                .map(c -> option(c.customerId(), c.customerId() + " – " + c.displayName()))
                .collect(Collectors.joining());
    }

    private static String option(String value, String label) {
        return "<option value=\"" + htmlEscape(value) + "\">" + htmlEscape(label) + "</option>";
    }

    // S kódovaním UTF-8 escapuje len &, <, >, " a ' – diakritika ostane čitateľná (bez &aacute;).
    private static String htmlEscape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
```

`sso-mock/src/main/java/com/example/ssomock/LoginController.java`:

```java
package com.example.ssomock;

import com.example.ssomock.Directory.Customer;
import com.example.ssomock.Directory.Operator;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

/**
 * Prihlásenie, zmena zákazníka a odhlásenie. Stav je len v {@link PortalSessionStore} a v cookie {@code SSO_ID};
 * {@code HttpSession} sa nepoužíva (kolízia JSESSIONID s Liferay za jednou adresou).
 */
@Controller
public class LoginController {

    static final String COOKIE = "SSO_ID";
    static final String DEFAULT_RETURN_URL = "/web/crm/blacklist";

    private final Directory directory;
    private final PortalSessionStore sessions;
    private final LoginPage page;

    public LoginController(Directory directory, PortalSessionStore sessions, LoginPage page) {
        this.directory = directory;
        this.sessions = sessions;
        this.page = page;
    }

    /** Prihlasovacia stránka. */
    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    @ResponseBody
    public String loginPage(@RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl,
                            @CookieValue(name = COOKIE, required = false) String sessionId) {
        return page.render(safeReturnUrl(returnUrl), sessions.find(sessionId));
    }

    /** Prihlásenie operátora so zákazníkom. */
    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestParam String operator, @RequestParam String customer,
                                      @RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl) {
        Operator op = directory.findOperator(operator)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "neznámy operátor"));
        Customer cu = requireCustomer(customer);
        String id = sessions.create(op, cu);
        return redirect(safeReturnUrl(returnUrl), cookie(id, Duration.ofHours(8)));
    }

    /** Zmena zákazníka v existujúcej session. */
    @PostMapping("/customer")
    public ResponseEntity<Void> changeCustomer(@RequestParam String customer,
                                               @RequestParam(defaultValue = DEFAULT_RETURN_URL) String returnUrl,
                                               @CookieValue(name = COOKIE, required = false) String sessionId) {
        String target = safeReturnUrl(returnUrl);
        if (sessions.find(sessionId).isEmpty()) {
            return redirect("/sso/login?returnUrl=" + URLEncoder.encode(target, StandardCharsets.UTF_8), null);
        }
        sessions.changeCustomer(sessionId, requireCustomer(customer));
        return redirect(target, null);
    }

    /** Odhlásenie – zruší session a cookie. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String sessionId) {
        sessions.remove(sessionId);
        return redirect("/sso/login", cookie("", Duration.ZERO));
    }

    /** Len relatívna cesta v rámci tej istej adresy – inak by mock slúžil ako open redirect. */
    static String safeReturnUrl(String url) {
        if (url == null || !url.startsWith("/") || url.startsWith("//") || url.contains("\\")) {
            return DEFAULT_RETURN_URL;
        }
        return url;
    }

    private Customer requireCustomer(String customerId) {
        return directory.findCustomer(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "neznámy zákazník"));
    }

    private static ResponseCookie cookie(String value, Duration maxAge) {
        // Path=/ – cookie musí vidieť Liferay aj (v podprojekte 2) nová appka za tým istým nginx.
        return ResponseCookie.from(COOKIE, value).path("/").httpOnly(true).sameSite("Lax").maxAge(maxAge).build();
    }

    private static ResponseEntity<Void> redirect(String location, ResponseCookie cookie) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location));
        if (cookie != null) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }
}
```

`sso-mock/src/main/java/com/example/ssomock/ApiController.java`:

```java
package com.example.ssomock;

import com.example.ssomock.Directory.Customer;
import com.example.ssomock.Directory.Operator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** REST API, ktoré volá CRM (stará appka cez {@code sso-portal-api}, nová v podprojekte 2 priamo). */
@RestController
public class ApiController {

    /** Tvar odpovede – kontrakt s {@code com.example.sso.api.PortalSession}. */
    public record SessionView(Operator operator, Customer customer) {
    }

    private final PortalSessionStore sessions;

    public ApiController(PortalSessionStore sessions) {
        this.sessions = sessions;
    }

    /** Operátor a zákazník session, alebo 404. */
    @GetMapping("/api/sessions/{id}")
    public ResponseEntity<SessionView> session(@PathVariable String id) {
        return sessions.find(id)
                .map(s -> ResponseEntity.ok(new SessionView(s.operator(), s.customer())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f sso-mock/pom.xml test`
Expected: PASS (10 testov).

> Ak `Set-Cookie` pri odhlásení obsahuje `Max-Age=0` aj `Expires=…`, test prejde (kontroluje len `Max-Age=0`).

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 3: `frontend-base-customization` – prístup k requestu a duálne úložisko session (PortletSession + Redis + feature flag)

**Files:**
- Modify: `legacy/pom.xml` (modul `frontend-base-customization`)
- Create: `legacy/frontend-base-customization/pom.xml`
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/session/UiMessage.java`, `RequestAwareManager.java`, `PortletRequestAwareManager.java`, `SessionStore.java`, `PortletSessionStore.java`, `SharedKeys.java`, `RedisSessionStore.java`, `ReadSource.java`, `ReadSourceFlag.java`, `DualSessionStore.java`
- Create (test helper): `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/FakeRequestAwareManager.java`
- Test: `…/base/session/PortletRequestAwareManagerTest.java`, `PortletSessionStoreTest.java`, `RedisSessionStoreTest.java`, `ReadSourceFlagTest.java`, `DualSessionStoreTest.java` (všetky v `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/`)

**Interfaces:**
- Consumes: `SsoCookies.readSessionId(HttpServletRequest)`, `PortalSessionManager.SSO_COOKIE` (Task 1)
- Produces:
  - `UiMessage` (POJO: `UiMessage(Level, String key)`, `getLevel()`, `getKey()`, `equals`), `enum UiMessage.Level { INFO, ERROR }`
  - `interface RequestAwareManager { PortletRequest getPortletRequest(); HttpServletRequest getHttpServletRequest(); String getSsoSessionId(); String getCurrentPageUrl(); Object getRequestAttribute(String); void setRequestAttribute(String, Object); }` + `PortletRequestAwareManager`
  - `interface SessionStore { Object get(String key); void put(String key, Object value); void remove(String key); void clear(); }`
  - `PortletSessionStore(RequestAwareManager)` – `APPLICATION_SCOPE`, `clear()` maže len kľúče s prefixom `PortletSessionStore.PREFIX = "APP_"`
  - `SharedKeys` – `MESSAGES = "APP_MESSAGES"` (typ `List<UiMessage>` je registrovaný vždy), `setTypes(Map<String, Class<?>>)`, `JavaType typeOf(String)`, `boolean isShared(String)`
  - `RedisSessionStore(JedisPool, RequestAwareManager, SharedKeys)` – hash `crm:session:{SSO_ID}`, TTL 1800 s
  - `enum ReadSource { PORTLET, REDIS }`, `ReadSourceFlag(JedisPool, RequestAwareManager)` s `ReadSource current()`; kľúč `crm:flags:session-read-source`
  - `DualSessionStore(SessionStore primary, SessionStore shared, ReadSourceFlag flag)`
  - Test helper `FakeRequestAwareManager` (public, používajú ho testy v Taskoch 4–8)

- [ ] **Step 1: Pridaj modul do parent POM a vytvor jeho POM**

V `legacy/pom.xml` doplň do `<modules>` za `sso-portal-api`:

```xml
        <module>frontend-base-customization</module>
```

`legacy/frontend-base-customization/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example.crm.legacy</groupId>
        <artifactId>legacy-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <!--
      Zdieľaná knižnica ako legacy FBC: session fasáda, ACL, vlastné tagy, DispatcherPortlet podtrieda,
      base controller. Pri migrácii sa prenáša ako celok – kontrakt ostáva, implementácia sa mení.
    -->
    <artifactId>frontend-base-customization</artifactId>

    <dependencies>
        <!-- provided: API SSO portálu je v tomcat/lib/ext (ako JBoss modul v legacy). -->
        <dependency>
            <groupId>com.example.crm.legacy</groupId>
            <artifactId>sso-portal-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-webmvc-portlet</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>redis.clients</groupId>
            <artifactId>jedis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.liferay.portal</groupId>
            <artifactId>portal-service</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.portlet</groupId>
            <artifactId>portlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.servlet.jsp</groupId>
            <artifactId>jsp-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Napíš test helper `FakeRequestAwareManager`**

`legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/FakeRequestAwareManager.java`:

```java
package com.example.crm.frontend.base.session;

import javax.portlet.PortletRequest;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.portlet.MockPortletSession;
import org.springframework.mock.web.portlet.MockRenderRequest;

/** Request bez Liferay a bez ThreadLocal: portlet request s danou session + servlet request s cookie SSO_ID. */
public class FakeRequestAwareManager implements RequestAwareManager {

    private final MockRenderRequest portletRequest = new MockRenderRequest();
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    private String ssoSessionId;
    private String currentPageUrl = "/web/crm/blacklist";

    /** Nový request s novou (prázdnou) portlet session. */
    public FakeRequestAwareManager() {
        this(new MockPortletSession());
    }

    /** Request nad danou session ({@code null} = session ešte neexistuje); viac requestov môže zdieľať jednu session. */
    public FakeRequestAwareManager(MockPortletSession session) {
        portletRequest.setSession(session);
    }

    /** Nastaví cookie SSO_ID (ID zdieľanej session). */
    public FakeRequestAwareManager withSsoSessionId(String id) {
        this.ssoSessionId = id;
        httpRequest.setCookies(new Cookie("SSO_ID", id));
        return this;
    }

    /** Nastaví URL aktuálnej stránky portálu. */
    public FakeRequestAwareManager withCurrentPageUrl(String url) {
        this.currentPageUrl = url;
        return this;
    }

    @Override
    public MockRenderRequest getPortletRequest() {
        return portletRequest;
    }

    @Override
    public HttpServletRequest getHttpServletRequest() {
        return httpRequest;
    }

    @Override
    public String getSsoSessionId() {
        return ssoSessionId;
    }

    @Override
    public String getCurrentPageUrl() {
        return currentPageUrl;
    }

    @Override
    public Object getRequestAttribute(String name) {
        return httpRequest.getAttribute(name);
    }

    @Override
    public void setRequestAttribute(String name, Object value) {
        httpRequest.setAttribute(name, value);
    }

    /** Pre testy, ktoré potrebujú typ {@link PortletRequest}. */
    public PortletRequest portletRequest() {
        return portletRequest;
    }
}
```

- [ ] **Step 3: Napíš padajúce testy**

`…/base/session/PortletRequestAwareManagerTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Layout;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.Portal;
import com.liferay.portal.util.PortalUtil;
import javax.portlet.PortletRequest;
import javax.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.portlet.MockRenderRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.portlet.context.PortletRequestAttributes;

class PortletRequestAwareManagerTest {

    private final MockRenderRequest portletRequest = new MockRenderRequest();
    private final MockHttpServletRequest httpRequest = new MockHttpServletRequest();
    private final PortletRequestAwareManager manager = new PortletRequestAwareManager();

    @BeforeEach
    void bindRequestToThread() throws Exception {
        // To isté robí FrameworkPortlet pred každou fázou – fasáda (singleton) si request berie z vlákna.
        RequestContextHolder.setRequestAttributes(new PortletRequestAttributes(portletRequest));
        Portal portal = mock(Portal.class);
        when(portal.getHttpServletRequest(any(PortletRequest.class))).thenReturn(httpRequest);
        when(portal.getOriginalServletRequest(httpRequest)).thenReturn(httpRequest);
        new PortalUtil().setPortal(portal);
    }

    @AfterEach
    void cleanUp() {
        RequestContextHolder.resetRequestAttributes();
        new PortalUtil().setPortal(null);
    }

    @Test
    @DisplayName("Vráti portlet request aktuálneho vlákna a pôvodný servlet request cez PortalUtil")
    void should_returnRequestsOfCurrentThread_when_insidePortletRequest() {
        assertThat(manager.getPortletRequest()).isSameAs(portletRequest);
        assertThat(manager.getHttpServletRequest()).isSameAs(httpRequest);
    }

    @Test
    @DisplayName("Mimo vlákna requestu zlyhá so zrozumiteľnou správou")
    void should_failWithClearMessage_when_outsideRequestThread() {
        RequestContextHolder.resetRequestAttributes();

        assertThatThrownBy(manager::getPortletRequest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RequestContextHolder");
    }

    @Test
    @DisplayName("ID SSO session číta z cookie pôvodného requestu")
    void should_readSsoSessionId_when_cookiePresent() {
        httpRequest.setCookies(new Cookie("SSO_ID", "abc-123"));

        assertThat(manager.getSsoSessionId()).isEqualTo("abc-123");
    }

    @Test
    @DisplayName("URL aktuálnej stránky zloží z ThemeDisplay (/web + site + stránka)")
    void should_composePageUrl_when_themeDisplayPresent() throws Exception {
        Group group = mock(Group.class);
        when(group.getFriendlyURL()).thenReturn("/crm");
        Layout layout = mock(Layout.class);
        when(layout.getGroup()).thenReturn(group);
        when(layout.getFriendlyURL()).thenReturn("/blacklist");
        ThemeDisplay themeDisplay = mock(ThemeDisplay.class);
        when(themeDisplay.getLayout()).thenReturn(layout);
        when(themeDisplay.getPathFriendlyURLPublic()).thenReturn("/web");
        portletRequest.setAttribute(WebKeys.THEME_DISPLAY, themeDisplay);

        assertThat(manager.getCurrentPageUrl()).isEqualTo("/web/crm/blacklist");
    }

    @Test
    @DisplayName("Bez ThemeDisplay vráti koreň portálu")
    void should_returnRoot_when_themeDisplayMissing() {
        assertThat(manager.getCurrentPageUrl()).isEqualTo("/");
    }

    @Test
    @DisplayName("Atribúty requestu ukladá do pôvodného servlet requestu (zdieľa ich celá stránka)")
    void should_storeAttributesInOriginalServletRequest_when_set() {
        manager.setRequestAttribute("x", "y");

        assertThat(httpRequest.getAttribute("x")).isEqualTo("y");
        assertThat(manager.getRequestAttribute("x")).isEqualTo("y");
    }
}
```

`…/base/session/PortletSessionStoreTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;

import javax.portlet.PortletSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockPortletSession;

class PortletSessionStoreTest {

    @Test
    @DisplayName("Zapisuje do APPLICATION_SCOPE (vidia to všetky portlety vo WAR-e)")
    void should_writeToApplicationScope_when_put() {
        MockPortletSession session = new MockPortletSession();
        PortletSessionStore store = new PortletSessionStore(new FakeRequestAwareManager(session));

        store.put("APP_X", "hodnota");

        assertThat(session.getAttribute("APP_X", PortletSession.APPLICATION_SCOPE)).isEqualTo("hodnota");
        assertThat(session.getAttribute("APP_X", PortletSession.PORTLET_SCOPE)).isNull();
        assertThat(store.get("APP_X")).isEqualTo("hodnota");
    }

    @Test
    @DisplayName("Čítanie nevytvorí session")
    void should_notCreateSession_when_reading() {
        FakeRequestAwareManager request = new FakeRequestAwareManager(null);

        assertThat(new PortletSessionStore(request).get("APP_X")).isNull();
        assertThat(request.getPortletRequest().getPortletSession(false)).isNull();
    }

    @Test
    @DisplayName("clear() zmaže len kľúče fasády (APP_), ostatné atribúty nechá")
    void should_removeOnlyPrefixedKeys_when_cleared() {
        MockPortletSession session = new MockPortletSession();
        session.setAttribute("APP_A", "1", PortletSession.APPLICATION_SCOPE);
        session.setAttribute("APP_B", "2", PortletSession.APPLICATION_SCOPE);
        session.setAttribute("OTHER", "3", PortletSession.APPLICATION_SCOPE);

        new PortletSessionStore(new FakeRequestAwareManager(session)).clear();

        assertThat(session.getAttribute("APP_A", PortletSession.APPLICATION_SCOPE)).isNull();
        assertThat(session.getAttribute("APP_B", PortletSession.APPLICATION_SCOPE)).isNull();
        assertThat(session.getAttribute("OTHER", PortletSession.APPLICATION_SCOPE)).isEqualTo("3");
    }

    @Test
    @DisplayName("remove() odstráni kľúč")
    void should_removeKey_when_removed() {
        MockPortletSession session = new MockPortletSession();
        PortletSessionStore store = new PortletSessionStore(new FakeRequestAwareManager(session));
        store.put("APP_X", "1");

        store.remove("APP_X");

        assertThat(store.get("APP_X")).isNull();
    }
}
```

`…/base/session/RedisSessionStoreTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

class RedisSessionStoreTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);
    private final SharedKeys keys = new SharedKeys();

    @BeforeEach
    void wirePool() {
        when(pool.getResource()).thenReturn(jedis);
    }

    @Test
    @DisplayName("Zdieľaný kľúč zapíše ako JSON do hashu podľa SSO_ID a predĺži TTL")
    void should_writeJsonUnderSsoHash_when_sharedKeyPut() {
        store("s-1").put(SharedKeys.MESSAGES, Collections.singletonList(new UiMessage(UiMessage.Level.INFO, "k")));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(jedis).hset(eq("crm:session:s-1"), eq("APP_MESSAGES"), json.capture());
        assertThat(json.getValue()).contains("\"level\":\"INFO\"").contains("\"key\":\"k\"");
        verify(jedis).expire("crm:session:s-1", 1800);
    }

    @Test
    @DisplayName("Prečíta JSON späť do typu z registra kľúčov")
    void should_readTypedValue_when_jsonStored() {
        when(jedis.hget("crm:session:s-1", "APP_MESSAGES")).thenReturn("[{\"level\":\"ERROR\",\"key\":\"x\"}]");

        Object value = store("s-1").get(SharedKeys.MESSAGES);

        assertThat(value).isEqualTo(Arrays.asList(new UiMessage(UiMessage.Level.ERROR, "x")));
    }

    @Test
    @DisplayName("Kľúč mimo registra sa do Redisu nezapíše (zdieľa sa len to, čo je dohodnuté)")
    void should_skipRedis_when_keyNotShared() {
        store("s-1").put("APP_LOCAL_ONLY", "x");

        verifyNoInteractions(pool);
    }

    @Test
    @DisplayName("Bez SSO session sa Redis nevolá")
    void should_doNothing_when_noSsoSession() {
        RedisSessionStore store = store(null);

        store.put(SharedKeys.MESSAGES, Collections.emptyList());

        assertThat(store.get(SharedKeys.MESSAGES)).isNull();
        verifyNoInteractions(pool);
    }

    @Test
    @DisplayName("Typy ďalších kľúčov sa registrujú konfiguráciou (Spring XML)")
    void should_useConfiguredType_when_keyRegistered() {
        keys.setTypes(Collections.<String, Class<?>>singletonMap("APP_LAST", String.class));
        when(jedis.hget("crm:session:s-1", "APP_LAST")).thenReturn("\"abc\"");

        assertThat(store("s-1").get("APP_LAST")).isEqualTo("abc");
        assertThat(keys.isShared("APP_LAST")).isTrue();
    }

    @Test
    @DisplayName("null hodnota kľúč z hashu odstráni, clear() zmaže celý hash")
    void should_deleteFieldAndHash_when_nullPutAndClear() {
        RedisSessionStore store = store("s-1");

        store.put(SharedKeys.MESSAGES, null);
        store.clear();

        verify(jedis).hdel("crm:session:s-1", "APP_MESSAGES");
        verify(jedis).del("crm:session:s-1");
    }

    private RedisSessionStore store(String ssoId) {
        FakeRequestAwareManager request = new FakeRequestAwareManager();
        if (ssoId != null) {
            request.withSsoSessionId(ssoId);
        }
        return new RedisSessionStore(pool, request, keys);
    }
}
```

`…/base/session/ReadSourceFlagTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisConnectionException;

class ReadSourceFlagTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    @BeforeEach
    void wirePool() {
        when(pool.getResource()).thenReturn(jedis);
    }

    @Test
    @DisplayName("Chýbajúci flag = PORTLET (stará session je zdrojom pravdy)")
    void should_returnPortlet_when_flagMissing() {
        assertThat(flag().current()).isEqualTo(ReadSource.PORTLET);
    }

    @Test
    @DisplayName("Hodnota „redis“ (bez ohľadu na veľkosť písmen) prepne čítanie na Redis")
    void should_returnRedis_when_flagSet() {
        when(jedis.get("crm:flags:session-read-source")).thenReturn(" redis ");

        assertThat(flag().current()).isEqualTo(ReadSource.REDIS);
    }

    @Test
    @DisplayName("Neznáma hodnota = PORTLET")
    void should_returnPortlet_when_valueUnknown() {
        when(jedis.get("crm:flags:session-read-source")).thenReturn("oboje");

        assertThat(flag().current()).isEqualTo(ReadSource.PORTLET);
    }

    @Test
    @DisplayName("Nedostupný Redis = PORTLET, request nespadne")
    void should_returnPortlet_when_redisDown() {
        when(pool.getResource()).thenThrow(new JedisConnectionException("down"));

        assertThat(flag().current()).isEqualTo(ReadSource.PORTLET);
    }

    @Test
    @DisplayName("V rámci jedného requestu sa flag číta z Redisu len raz")
    void should_readRedisOnce_when_askedRepeatedlyInOneRequest() {
        ReadSourceFlag flag = flag();

        flag.current();
        flag.current();

        verify(pool, times(1)).getResource();
    }

    private ReadSourceFlag flag() {
        return new ReadSourceFlag(pool, new FakeRequestAwareManager());
    }
}
```

`…/base/session/DualSessionStoreTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DualSessionStoreTest {

    private final SessionStore primary = mock(SessionStore.class);
    private final SessionStore shared = mock(SessionStore.class);
    private final ReadSourceFlag flag = mock(ReadSourceFlag.class);
    private final DualSessionStore store = new DualSessionStore(primary, shared, flag);

    @Test
    @DisplayName("Zápis ide do oboch úložísk")
    void should_writeToBoth_when_put() {
        store.put("APP_X", "1");

        verify(primary).put("APP_X", "1");
        verify(shared).put("APP_X", "1");
    }

    @Test
    @DisplayName("Zlyhanie Redisu pri zápise request nezhodí, stará session je zapísaná")
    void should_keepPrimaryWrite_when_sharedWriteFails() {
        doThrow(new RuntimeException("redis down")).when(shared).put("APP_X", "1");

        store.put("APP_X", "1");

        verify(primary).put("APP_X", "1");
    }

    @Test
    @DisplayName("Flag PORTLET: číta sa zo starej session, Redis sa nepýta")
    void should_readPrimary_when_flagPortlet() {
        when(flag.current()).thenReturn(ReadSource.PORTLET);
        when(primary.get("APP_X")).thenReturn("stará");

        assertThat(store.get("APP_X")).isEqualTo("stará");
        verify(shared, never()).get("APP_X");
    }

    @Test
    @DisplayName("Flag REDIS: číta sa z Redisu")
    void should_readShared_when_flagRedis() {
        when(flag.current()).thenReturn(ReadSource.REDIS);
        when(shared.get("APP_X")).thenReturn("redis");

        assertThat(store.get("APP_X")).isEqualTo("redis");
    }

    @Test
    @DisplayName("Flag REDIS a Redis dole: fallback na starú session")
    void should_fallBackToPrimary_when_sharedReadFails() {
        when(flag.current()).thenReturn(ReadSource.REDIS);
        when(shared.get("APP_X")).thenThrow(new RuntimeException("redis down"));
        when(primary.get("APP_X")).thenReturn("stará");

        assertThat(store.get("APP_X")).isEqualTo("stará");
    }

    @Test
    @DisplayName("remove a clear idú do oboch, zlyhanie Redisu sa len zaloguje")
    void should_removeAndClearBoth_when_called() {
        doThrow(new RuntimeException("redis down")).when(shared).clear();

        store.remove("APP_X");
        store.clear();

        verify(primary).remove("APP_X");
        verify(shared).remove("APP_X");
        verify(primary).clear();
    }
}
```

- [ ] **Step 4: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class PortletRequestAwareManager` (a ďalšie).

- [ ] **Step 5: Implementuj `UiMessage`, `RequestAwareManager` a `PortletRequestAwareManager`**

`legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/session/UiMessage.java`:

```java
package com.example.crm.frontend.base.session;

import java.io.Serializable;
import java.util.Objects;

/**
 * Hláška pre používateľa (i18n kľúč). Prežije action → render a v podprojekte 2 aj prechod do novej appky,
 * preto je to jednoduché DTO serializovateľné do JSON (žiadna Java serializácia doménových tried).
 */
public class UiMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Úroveň hlášky. */
    public enum Level { INFO, ERROR }

    private Level level;
    private String key;

    public UiMessage() {
    }

    public UiMessage(Level level, String key) {
        this.level = level;
        this.key = key;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof UiMessage)) {
            return false;
        }
        UiMessage that = (UiMessage) other;
        return level == that.level && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, key);
    }

    @Override
    public String toString() {
        return level + ":" + key;
    }
}
```

`…/base/session/RequestAwareManager.java`:

```java
package com.example.crm.frontend.base.session;

import javax.portlet.PortletRequest;
import javax.servlet.http.HttpServletRequest;

/**
 * Prístup k aktuálnemu requestu pre singletony (fasáda session, ACL). Legacy vzor: singleton si request
 * vždy dohľadá z vlákna, preto funguje len vo vlákne requestu (nie v {@code @Async} ani vo vlastnom executore).
 */
public interface RequestAwareManager {

    /** Portlet request aktuálneho vlákna. */
    PortletRequest getPortletRequest();

    /** Pôvodný servlet request prehliadača (cookies, atribúty zdieľané všetkými portletmi stránky). */
    HttpServletRequest getHttpServletRequest();

    /** ID session SSO portálu z cookie, alebo {@code null}. */
    String getSsoSessionId();

    /** URL stránky portálu, na ktorej je používateľ (návrat po prihlásení). */
    String getCurrentPageUrl();

    /** Atribút pôvodného servlet requestu. */
    Object getRequestAttribute(String name);

    /** Nastaví atribút pôvodného servlet requestu (cache na jeden HTTP request). */
    void setRequestAttribute(String name, Object value);
}
```

`…/base/session/PortletRequestAwareManager.java`:

```java
package com.example.crm.frontend.base.session;

import com.example.sso.api.SsoCookies;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.model.Layout;
import com.liferay.portal.theme.ThemeDisplay;
import com.liferay.portal.util.PortalUtil;
import javax.portlet.PortletRequest;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.portlet.context.PortletRequestAttributes;

/**
 * Liferay implementácia: request z {@link RequestContextHolder} (nastaví ho FrameworkPortlet pred každou fázou),
 * servlet request cez {@link PortalUtil} – typické legacy volanie „dostaň sa k servlet requestu“.
 * Pri migrácii celá trieda odpadne: v Spring MVC dostane controller request priamo.
 */
public class PortletRequestAwareManager implements RequestAwareManager {

    private static final Log LOG = LogFactory.getLog(PortletRequestAwareManager.class);

    @Override
    public PortletRequest getPortletRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof PortletRequestAttributes)) {
            throw new IllegalStateException("Mimo portletového requestu: RequestContextHolder neobsahuje portlet request"
                    + " (session manager funguje len vo vlákne requestu)");
        }
        return ((PortletRequestAttributes) attributes).getRequest();
    }

    @Override
    public HttpServletRequest getHttpServletRequest() {
        return PortalUtil.getOriginalServletRequest(PortalUtil.getHttpServletRequest(getPortletRequest()));
    }

    @Override
    public String getSsoSessionId() {
        return SsoCookies.readSessionId(getHttpServletRequest());
    }

    @Override
    public String getCurrentPageUrl() {
        ThemeDisplay themeDisplay = (ThemeDisplay) getPortletRequest().getAttribute(WebKeys.THEME_DISPLAY);
        if (themeDisplay == null || themeDisplay.getLayout() == null) {
            return "/";
        }
        Layout layout = themeDisplay.getLayout();
        try {
            return themeDisplay.getPathFriendlyURLPublic() + layout.getGroup().getFriendlyURL() + layout.getFriendlyURL();
        } catch (PortalException | SystemException e) {
            LOG.warn("URL aktuálnej stránky sa nedá zostaviť, návrat po prihlásení pôjde na koreň portálu", e);
            return "/";
        }
    }

    @Override
    public Object getRequestAttribute(String name) {
        return getHttpServletRequest().getAttribute(name);
    }

    @Override
    public void setRequestAttribute(String name, Object value) {
        getHttpServletRequest().setAttribute(name, value);
    }
}
```

- [ ] **Step 6: Implementuj úložiská a flag**

`…/base/session/SessionStore.java`:

```java
package com.example.crm.frontend.base.session;

/** Úložisko session fasády. Duálna session = dve implementácie za jedným rozhraním (slide 12 prezentácie). */
public interface SessionStore {

    /** Hodnota kľúča, alebo {@code null}. */
    Object get(String key);

    /** Uloží hodnotu (vždy celú, nikdy „úpravou na mieste“ – replikované úložisko by zmenu nevidelo). */
    void put(String key, Object value);

    /** Odstráni kľúč. */
    void remove(String key);

    /** Odstráni všetky kľúče fasády. */
    void clear();
}
```

`…/base/session/PortletSessionStore.java`:

```java
package com.example.crm.frontend.base.session;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import javax.portlet.PortletSession;

/**
 * Stará session: {@link PortletSession} v {@code APPLICATION_SCOPE} – vidia ju všetky portlety tohto WAR-u.
 * Prefix {@code APP_} oddeľuje kľúče fasády od ostatných atribútov (po migrácii na jednu HttpSession
 * nahrádza namespace, ktorý dnes robí portlet scope).
 */
public class PortletSessionStore implements SessionStore {

    public static final String PREFIX = "APP_";

    private final RequestAwareManager requests;

    public PortletSessionStore(RequestAwareManager requests) {
        this.requests = requests;
    }

    @Override
    public Object get(String key) {
        PortletSession session = requests.getPortletRequest().getPortletSession(false);
        return session == null ? null : session.getAttribute(key, PortletSession.APPLICATION_SCOPE);
    }

    @Override
    public void put(String key, Object value) {
        requests.getPortletRequest().getPortletSession(true).setAttribute(key, value, PortletSession.APPLICATION_SCOPE);
    }

    @Override
    public void remove(String key) {
        PortletSession session = requests.getPortletRequest().getPortletSession(false);
        if (session != null) {
            session.removeAttribute(key, PortletSession.APPLICATION_SCOPE);
        }
    }

    @Override
    public void clear() {
        PortletSession session = requests.getPortletRequest().getPortletSession(false);
        if (session == null) {
            return;
        }
        List<String> keys = new ArrayList<>();
        Enumeration<String> names = session.getAttributeNames(PortletSession.APPLICATION_SCOPE);
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.startsWith(PREFIX)) {
                keys.add(name);
            }
        }
        for (String key : keys) {
            session.removeAttribute(key, PortletSession.APPLICATION_SCOPE);
        }
    }
}
```

`…/base/session/SharedKeys.java`:

```java
package com.example.crm.frontend.base.session;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Register kľúčov zdieľanej session: kľúč → typ pre JSON. Do Redisu ide len to, čo je tu – zdieľa sa vedome
 * a len jednoduché DTO, ktoré prečíta Java 8 (stará appka) aj Java 21 (nová appka).
 */
public class SharedKeys {

    /** Hlášky pre používateľa – zdieľané vždy. */
    public static final String MESSAGES = "APP_MESSAGES";

    private final TypeFactory typeFactory = TypeFactory.defaultInstance();
    private final Map<String, JavaType> types = new HashMap<>();

    public SharedKeys() {
        types.put(MESSAGES, typeFactory.constructCollectionType(List.class, UiMessage.class));
    }

    /** Zaregistruje ďalšie zdieľané kľúče (Spring XML: mapa kľúč → trieda). */
    public void setTypes(Map<String, Class<?>> registered) {
        for (Map.Entry<String, Class<?>> entry : registered.entrySet()) {
            types.put(entry.getKey(), typeFactory.constructType(entry.getValue()));
        }
    }

    /** Typ hodnoty kľúča, alebo {@code null}, ak sa kľúč nezdieľa. */
    public JavaType typeOf(String key) {
        return types.get(key);
    }

    /** Či sa kľúč zapisuje do zdieľanej session. */
    public boolean isShared(String key) {
        return types.containsKey(key);
    }
}
```

`…/base/session/RedisSessionStore.java`:

```java
package com.example.crm.frontend.base.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Zdieľaná session v Redise: hash {@code crm:session:{SSO_ID}}, hodnoty v JSON. Kľúčom je ID session SSO portálu –
 * cookie {@code SSO_ID} majú obe appky, takže netreba vlastnú cookie (a portlet ju ani nevie nastaviť v render fáze).
 */
public class RedisSessionStore implements SessionStore {

    static final String KEY_PREFIX = "crm:session:";
    static final int TTL_SECONDS = 1800;

    private static final Log LOG = LogFactory.getLog(RedisSessionStore.class);

    private final JedisPool pool;
    private final RequestAwareManager requests;
    private final SharedKeys keys;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RedisSessionStore(JedisPool pool, RequestAwareManager requests, SharedKeys keys) {
        this.pool = pool;
        this.requests = requests;
        this.keys = keys;
    }

    @Override
    public Object get(String key) {
        String hash = hash();
        JavaType type = keys.typeOf(key);
        if (hash == null || type == null) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.hget(hash, key);
            jedis.expire(hash, TTL_SECONDS);
            return json == null ? null : mapper.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("Neplatný JSON v zdieľanej session pre kľúč " + key, e);
        }
    }

    @Override
    public void put(String key, Object value) {
        String hash = hash();
        if (hash == null) {
            return;
        }
        if (!keys.isShared(key)) {
            LOG.debug("Kľúč " + key + " sa nezdieľa, do Redisu sa nezapisuje");
            return;
        }
        if (value == null) {
            remove(key);
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(hash, key, mapper.writeValueAsString(value));
            jedis.expire(hash, TTL_SECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("Hodnotu kľúča " + key + " nejde zapísať ako JSON", e);
        }
    }

    @Override
    public void remove(String key) {
        String hash = hash();
        if (hash == null || !keys.isShared(key)) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(hash, key);
        }
    }

    @Override
    public void clear() {
        String hash = hash();
        if (hash == null) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(hash);
        }
    }

    private String hash() {
        String ssoId = requests.getSsoSessionId();
        return ssoId == null ? null : KEY_PREFIX + ssoId;
    }
}
```

`…/base/session/ReadSource.java`:

```java
package com.example.crm.frontend.base.session;

/** Odkiaľ fasáda číta: stará session alebo Redis. Zápis ide vždy do oboch. */
public enum ReadSource { PORTLET, REDIS }
```

`…/base/session/ReadSourceFlag.java`:

```java
package com.example.crm.frontend.base.session;

import java.util.Locale;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Feature flag „zdroj čítania“ zo slide 12: kľúč v Redise, prepína sa bez reštartu a bez deployu
 * ({@code redis-cli SET crm:flags:session-read-source REDIS}). Ten istý kľúč bude čítať aj nová appka.
 * Pri akejkoľvek pochybnosti platí PORTLET – stará session ostáva zdrojom pravdy, kým sa Redis neoverí.
 */
public class ReadSourceFlag {

    static final String FLAG_KEY = "crm:flags:session-read-source";
    static final String CACHE_ATTRIBUTE = ReadSourceFlag.class.getName();

    private static final Log LOG = LogFactory.getLog(ReadSourceFlag.class);

    private final JedisPool pool;
    private final RequestAwareManager requests;

    public ReadSourceFlag(JedisPool pool, RequestAwareManager requests) {
        this.pool = pool;
        this.requests = requests;
    }

    /** Aktuálny zdroj čítania (v rámci requestu sa číta z Redisu raz). */
    public ReadSource current() {
        Object cached = requests.getRequestAttribute(CACHE_ATTRIBUTE);
        if (cached instanceof ReadSource) {
            return (ReadSource) cached;
        }
        ReadSource source = load();
        requests.setRequestAttribute(CACHE_ATTRIBUTE, source);
        return source;
    }

    private ReadSource load() {
        String value;
        try (Jedis jedis = pool.getResource()) {
            value = jedis.get(FLAG_KEY);
        } catch (RuntimeException e) {
            LOG.error("Flag zdroja čítania sa nedá prečítať z Redisu, číta sa zo starej session", e);
            return ReadSource.PORTLET;
        }
        if (value == null) {
            return ReadSource.PORTLET;
        }
        try {
            return ReadSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("Neznáma hodnota flagu " + FLAG_KEY + "='" + value + "', použije sa PORTLET");
            return ReadSource.PORTLET;
        }
    }
}
```

`…/base/session/DualSessionStore.java`:

```java
package com.example.crm.frontend.base.session;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Duálna session: zápis do starej session aj do Redisu, čítanie podľa flagu. Zlyhanie Redisu nikdy nezhodí
 * request – zaloguje sa a pokračuje sa so starou session. Controllery o ničom z toho nevedia (volajú fasádu).
 */
public class DualSessionStore implements SessionStore {

    private static final Log LOG = LogFactory.getLog(DualSessionStore.class);

    private final SessionStore primary;
    private final SessionStore shared;
    private final ReadSourceFlag flag;

    public DualSessionStore(SessionStore primary, SessionStore shared, ReadSourceFlag flag) {
        this.primary = primary;
        this.shared = shared;
        this.flag = flag;
    }

    @Override
    public Object get(String key) {
        if (flag.current() == ReadSource.REDIS) {
            try {
                return shared.get(key);
            } catch (RuntimeException e) {
                LOG.error("Čítanie kľúča " + key + " zo zdieľanej session zlyhalo, fallback na PortletSession", e);
            }
        }
        return primary.get(key);
    }

    @Override
    public void put(String key, Object value) {
        primary.put(key, value);
        try {
            shared.put(key, value);
        } catch (RuntimeException e) {
            LOG.error("Zápis kľúča " + key + " do zdieľanej session zlyhal", e);
        }
    }

    @Override
    public void remove(String key) {
        primary.remove(key);
        try {
            shared.remove(key);
        } catch (RuntimeException e) {
            LOG.error("Odstránenie kľúča " + key + " zo zdieľanej session zlyhalo", e);
        }
    }

    @Override
    public void clear() {
        primary.clear();
        try {
            shared.clear();
        } catch (RuntimeException e) {
            LOG.error("Vymazanie zdieľanej session zlyhalo", e);
        }
    }
}
```

- [ ] **Step 7: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (Task 1: 11, Task 3: 6 + 4 + 6 + 5 + 6 = 27).

- [ ] **Step 8: Checkpoint (bez commitu)** – `git status --short`

---

### Task 4: Session fasáda `SessionManagerCustom` (identita zo SSO, hlášky, globálne atribúty)

**Files:**
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/session/SessionManagerCustom.java`, `SessionManagerCustomImpl.java`
- Test: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/SessionManagerCustomImplTest.java`

**Interfaces:**
- Consumes: `PortalSessionManager`, `PortalSession`, `PortalOperator`, `PortalCustomer`, `SsoUnavailableException` (Task 1); `RequestAwareManager`, `SessionStore`, `PortletSessionStore`, `SharedKeys.MESSAGES`, `UiMessage`, `FakeRequestAwareManager` (Task 3)
- Produces:
  - `interface SessionManagerCustom { PortalOperator getOperator(); PortalCustomer getCustomer(); boolean isLoggedIn(); String getLoginUrl(); void pushInfo(String key); void pushError(String key); List<UiMessage> popMessages(); void setGlobalAttribute(String key, Object value); Object getGlobalAttribute(String key); void removeGlobalAttribute(String key); void flushAll(); }`
  - `SessionManagerCustomImpl(RequestAwareManager, PortalSessionManager, SessionStore)`; `PREFIX = "APP_"` – globálny atribút `X` je v úložisku pod `APP_X`

- [ ] **Step 1: Napíš padajúci test**

`legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/session/SessionManagerCustomImplTest.java`:

```java
package com.example.crm.frontend.base.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import com.example.sso.api.PortalSession;
import com.example.sso.api.PortalSessionManager;
import com.example.sso.api.SsoUnavailableException;
import java.util.Arrays;
import java.util.List;
import javax.portlet.PortletSession;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.portlet.MockPortletSession;

class SessionManagerCustomImplTest {

    private final PortalSessionManager portal = mock(PortalSessionManager.class);

    @Test
    @DisplayName("Operátora a zákazníka berie zo SSO portálu, portál volá raz za request")
    void should_readIdentityFromPortalOnce_when_askedRepeatedly() {
        FakeRequestAwareManager request = new FakeRequestAwareManager();
        when(portal.getPortalSession(any(HttpServletRequest.class))).thenReturn(new PortalSession("s-1",
                new PortalOperator("spravca", "Správca"), new PortalCustomer("C-1001", "Ján Vzorový")));
        SessionManagerCustom manager = manager(request);

        assertThat(manager.isLoggedIn()).isTrue();
        assertThat(manager.getOperator().getLogin()).isEqualTo("spravca");
        assertThat(manager.getCustomer().getCustomerId()).isEqualTo("C-1001");
        verify(portal, times(1)).getPortalSession(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("Bez SSO session je operátor null a ani prázdny výsledok sa v requeste znova nepýta")
    void should_returnNullAndCacheIt_when_notLoggedIn() {
        SessionManagerCustom manager = manager(new FakeRequestAwareManager());

        assertThat(manager.isLoggedIn()).isFalse();
        assertThat(manager.getOperator()).isNull();
        assertThat(manager.getCustomer()).isNull();
        verify(portal, times(1)).getPortalSession(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("Nedostupný portál nie je „neprihlásený“ – výnimka prejde ďalej")
    void should_propagateSsoUnavailable_when_portalDown() {
        when(portal.getPortalSession(any(HttpServletRequest.class))).thenThrow(new SsoUnavailableException("down", null));

        assertThatThrownBy(() -> manager(new FakeRequestAwareManager()).getOperator())
                .isInstanceOf(SsoUnavailableException.class);
    }

    @Test
    @DisplayName("Login URL vedie späť na aktuálnu stránku")
    void should_returnToCurrentPage_when_buildingLoginUrl() {
        when(portal.getLoginUrl("/web/crm/dashboard")).thenReturn("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fdashboard");

        String url = manager(new FakeRequestAwareManager().withCurrentPageUrl("/web/crm/dashboard")).getLoginUrl();

        assertThat(url).isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fdashboard");
    }

    @Test
    @DisplayName("Hlášky z action fázy sa zobrazia v ďalšom requeste raz")
    void should_popMessagesOnce_when_nextRequestReadsThem() {
        MockPortletSession session = new MockPortletSession();
        SessionManagerCustom action = manager(new FakeRequestAwareManager(session));
        action.pushInfo("blacklist.detail.changed");
        action.pushError("blacklist.detail.lastIdentifier");

        SessionManagerCustom render = manager(new FakeRequestAwareManager(session));

        assertThat(render.popMessages()).containsExactly(
                new UiMessage(UiMessage.Level.INFO, "blacklist.detail.changed"),
                new UiMessage(UiMessage.Level.ERROR, "blacklist.detail.lastIdentifier"));
        assertThat(render.popMessages()).isEmpty();
    }

    @Test
    @DisplayName("Každá hláška zapíše nový zoznam (žiadna úprava na mieste – Redis by ju nevidel)")
    @SuppressWarnings("unchecked")
    void should_putNewListEveryTime_when_pushing() {
        SessionStore store = mock(SessionStore.class);
        SessionManagerCustom manager = new SessionManagerCustomImpl(new FakeRequestAwareManager(), portal, store);
        List<UiMessage> first = Arrays.asList(new UiMessage(UiMessage.Level.INFO, "a"));
        when(store.get(SharedKeys.MESSAGES)).thenReturn(first);

        manager.pushInfo("b");

        ArgumentCaptor<Object> written = ArgumentCaptor.forClass(Object.class);
        verify(store).put(eq(SharedKeys.MESSAGES), written.capture());
        assertThat((List<UiMessage>) written.getValue()).hasSize(2).isNotSameAs(first);
        assertThat(first).hasSize(1);
    }

    @Test
    @DisplayName("Globálne atribúty majú prefix APP_ a flushAll ich zmaže")
    void should_prefixGlobalAttributes_when_storedAndFlush() {
        MockPortletSession session = new MockPortletSession();
        SessionManagerCustom manager = manager(new FakeRequestAwareManager(session));

        manager.setGlobalAttribute("LAST_VIEWED_RECORD", "1");

        assertThat(session.getAttribute("APP_LAST_VIEWED_RECORD", PortletSession.APPLICATION_SCOPE)).isEqualTo("1");
        assertThat(manager.getGlobalAttribute("LAST_VIEWED_RECORD")).isEqualTo("1");
        manager.removeGlobalAttribute("LAST_VIEWED_RECORD");
        assertThat(manager.getGlobalAttribute("LAST_VIEWED_RECORD")).isNull();
        manager.setGlobalAttribute("A", "x");
        manager.flushAll();
        assertThat(manager.getGlobalAttribute("A")).isNull();
    }

    private SessionManagerCustom manager(FakeRequestAwareManager request) {
        return new SessionManagerCustomImpl(request, portal, new PortletSessionStore(request));
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test -pl frontend-base-customization -am -Dtest=SessionManagerCustomImplTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL – kompilácia: `cannot find symbol: class SessionManagerCustomImpl`.

- [ ] **Step 3: Implementuj fasádu**

`…/base/session/SessionManagerCustom.java`:

```java
package com.example.crm.frontend.base.session;

import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import java.util.List;

/**
 * Fasáda session – jediný bod, cez ktorý controllery pracujú so session (v legacy ~400 volaní).
 * Kontrakt ostáva pri migrácii rovnaký, mení sa len to, čo je pod ním. Metódy vracajú {@code null}
 * namiesto {@code Optional} – legacy štýl (Java 8 kód písaný v štýle Javy 6).
 */
public interface SessionManagerCustom {

    /** Prihlásený operátor zo SSO portálu, alebo {@code null}. */
    PortalOperator getOperator();

    /** Zákazník vybraný v SSO portáli, alebo {@code null}. */
    PortalCustomer getCustomer();

    /** Či je operátor prihlásený v SSO portáli. */
    boolean isLoggedIn();

    /** URL prihlásenia v SSO portáli s návratom na aktuálnu stránku. */
    String getLoginUrl();

    /** Pridá informačnú hlášku (i18n kľúč) – zobrazí sa v nasledujúcom renderi. */
    void pushInfo(String key);

    /** Pridá chybovú hlášku (i18n kľúč). */
    void pushError(String key);

    /** Vráti a zmaže čakajúce hlášky. */
    List<UiMessage> popMessages();

    /** Uloží atribút session (kľúč dostane prefix {@code APP_}). */
    void setGlobalAttribute(String key, Object value);

    /** Prečíta atribút session, alebo {@code null}. */
    Object getGlobalAttribute(String key);

    /** Odstráni atribút session. */
    void removeGlobalAttribute(String key);

    /** Zmaže všetky atribúty fasády. */
    void flushAll();
}
```

`…/base/session/SessionManagerCustomImpl.java`:

```java
package com.example.crm.frontend.base.session;

import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import com.example.sso.api.PortalSession;
import com.example.sso.api.PortalSessionManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Singleton fasády. Robí dve rôzne veci ako legacy: identitu a zákazníka číta zo SSO portálu,
 * všetko ostatné zo {@link SessionStore} (duálna session). Request si berie z {@link RequestAwareManager}.
 */
public class SessionManagerCustomImpl implements SessionManagerCustom {

    public static final String PREFIX = PortletSessionStore.PREFIX;

    static final String PORTAL_SESSION_CACHE = SessionManagerCustomImpl.class.getName() + ".PORTAL_SESSION";

    // Značka „portál povedal, že session nie je“ – aj prázdny výsledok sa v rámci requestu cachuje.
    private static final Object NO_SESSION = new Object();

    private final RequestAwareManager requests;
    private final PortalSessionManager portal;
    private final SessionStore store;

    public SessionManagerCustomImpl(RequestAwareManager requests, PortalSessionManager portal, SessionStore store) {
        this.requests = requests;
        this.portal = portal;
        this.store = store;
    }

    @Override
    public PortalOperator getOperator() {
        PortalSession session = portalSession();
        return session == null ? null : session.getOperator();
    }

    @Override
    public PortalCustomer getCustomer() {
        PortalSession session = portalSession();
        return session == null ? null : session.getCustomer();
    }

    @Override
    public boolean isLoggedIn() {
        return getOperator() != null;
    }

    @Override
    public String getLoginUrl() {
        return portal.getLoginUrl(requests.getCurrentPageUrl());
    }

    @Override
    public void pushInfo(String key) {
        push(new UiMessage(UiMessage.Level.INFO, key));
    }

    @Override
    public void pushError(String key) {
        push(new UiMessage(UiMessage.Level.ERROR, key));
    }

    @Override
    public List<UiMessage> popMessages() {
        List<UiMessage> messages = messages();
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        store.remove(SharedKeys.MESSAGES);
        return messages;
    }

    @Override
    public void setGlobalAttribute(String key, Object value) {
        store.put(PREFIX + key, value);
    }

    @Override
    public Object getGlobalAttribute(String key) {
        return store.get(PREFIX + key);
    }

    @Override
    public void removeGlobalAttribute(String key) {
        store.remove(PREFIX + key);
    }

    @Override
    public void flushAll() {
        store.clear();
    }

    private PortalSession portalSession() {
        // Cache na pôvodnom servlet requeste: hlavička aj obsahový portlet stránky zavolajú SSO spolu raz.
        Object cached = requests.getRequestAttribute(PORTAL_SESSION_CACHE);
        if (cached == NO_SESSION) {
            return null;
        }
        if (cached instanceof PortalSession) {
            return (PortalSession) cached;
        }
        PortalSession session = portal.getPortalSession(requests.getHttpServletRequest());
        requests.setRequestAttribute(PORTAL_SESSION_CACHE, session == null ? NO_SESSION : session);
        return session;
    }

    private void push(UiMessage message) {
        List<UiMessage> messages = new ArrayList<>(messages());
        messages.add(message);
        store.put(SharedKeys.MESSAGES, messages);
    }

    @SuppressWarnings("unchecked")
    private List<UiMessage> messages() {
        Object value = store.get(SharedKeys.MESSAGES);
        return value == null ? Collections.<UiMessage>emptyList() : (List<UiMessage>) value;
    }
}
```

- [ ] **Step 4: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 38 + 7 = 45).

- [ ] **Step 5: Checkpoint (bez commitu)** – `git status --short`

---

### Task 5: ACL a strážca prístupu portletu

**Files:**
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/acl/Privilege.java`, `AclService.java`, `InMemoryAclService.java`, `OperatorAcl.java`, `AclDeniedException.java`
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/web/NotFoundException.java`, `AccessDecision.java`, `PortletAccessGuard.java`
- Test: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/acl/AclTest.java`
- Test: `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/web/PortletAccessGuardTest.java`

**Interfaces:**
- Consumes: `SessionManagerCustom` (Task 4), `PortalOperator`, `SsoUnavailableException` (Task 1)
- Produces:
  - `enum Privilege { BLACKLIST_VIEW, BLACKLIST_MODIFY, DEACTIVATION }`
  - `interface AclService { boolean hasPrivilege(String operatorLogin, Privilege privilege); }`, `InMemoryAclService` (`citatel` → `BLACKLIST_VIEW`; `spravca` → všetky)
  - `OperatorAcl(SessionManagerCustom, AclService)`: `boolean hasPrivilege(Privilege)`, `boolean hasPrivilege(String privilegeName)` (neznáme meno → `IllegalArgumentException`), `void checkPrivilege(Privilege)` → `AclDeniedException`
  - `AclDeniedException(Privilege)` s `getPrivilege()`; `NotFoundException(String what)`
  - `enum AccessDecision { ALLOWED(200), LOGIN_REQUIRED(401), DENIED(403), SSO_UNAVAILABLE(503); int getHttpStatus() }`
  - `PortletAccessGuard(SessionManagerCustom, OperatorAcl)`: `AccessDecision decide(boolean loginRequired, Privilege required)` (`required` môže byť `null`), `String loginUrl()`

- [ ] **Step 1: Napíš padajúce testy**

`legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/acl/AclTest.java`:

```java
package com.example.crm.frontend.base.acl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AclTest {

    private final AclService aclService = new InMemoryAclService();
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = new OperatorAcl(sessionManager, aclService);

    @Test
    @DisplayName("Čitateľ smie len čítať blacklist, správca všetko, neznámy nič")
    void should_grantPrivilegesByOperator_when_asked() {
        assertThat(aclService.hasPrivilege("citatel", Privilege.BLACKLIST_VIEW)).isTrue();
        assertThat(aclService.hasPrivilege("citatel", Privilege.BLACKLIST_MODIFY)).isFalse();
        assertThat(aclService.hasPrivilege("citatel", Privilege.DEACTIVATION)).isFalse();
        for (Privilege privilege : Privilege.values()) {
            assertThat(aclService.hasPrivilege("spravca", privilege)).isTrue();
            assertThat(aclService.hasPrivilege("neznamy", privilege)).isFalse();
            assertThat(aclService.hasPrivilege(null, privilege)).isFalse();
        }
    }

    @Test
    @DisplayName("Bez prihláseného operátora nemá nikto žiadne privilégium")
    void should_denyEverything_when_noOperator() {
        assertThat(acl.hasPrivilege(Privilege.BLACKLIST_VIEW)).isFalse();
    }

    @Test
    @DisplayName("checkPrivilege vyhodí výnimku s menom chýbajúceho privilégia")
    void should_throwWithPrivilege_when_checkFails() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("citatel", "Čitateľ"));

        assertThatThrownBy(() -> acl.checkPrivilege(Privilege.BLACKLIST_MODIFY))
                .isInstanceOf(AclDeniedException.class)
                .extracting(e -> ((AclDeniedException) e).getPrivilege())
                .isEqualTo(Privilege.BLACKLIST_MODIFY);
    }

    @Test
    @DisplayName("Privilégium podľa mena (z JSP tagu); preklep v mene je chyba, nie tiché zamietnutie")
    void should_resolvePrivilegeByName_when_calledFromTag() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));

        assertThat(acl.hasPrivilege("BLACKLIST_MODIFY")).isTrue();
        assertThatThrownBy(() -> acl.hasPrivilege("BLACKLIST_MODIFI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLACKLIST_MODIFI");
    }
}
```

`legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/web/PortletAccessGuardTest.java`:

```java
package com.example.crm.frontend.base.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.SsoUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortletAccessGuardTest {

    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final PortletAccessGuard guard = new PortletAccessGuard(sessionManager, acl);

    @Test
    @DisplayName("Neprihlásený pri portlete s povinným prihlásením → LOGIN_REQUIRED")
    void should_requireLogin_when_notLoggedIn() {
        assertThat(guard.decide(true, Privilege.BLACKLIST_VIEW)).isEqualTo(AccessDecision.LOGIN_REQUIRED);
    }

    @Test
    @DisplayName("Portlet bez povinného prihlásenia (hlavička) pustí aj neprihláseného")
    void should_allow_when_loginNotRequiredAndNoPrivilege() {
        assertThat(guard.decide(false, null)).isEqualTo(AccessDecision.ALLOWED);
    }

    @Test
    @DisplayName("Prihlásený bez privilégia portletu → DENIED")
    void should_deny_when_privilegeMissing() {
        when(sessionManager.isLoggedIn()).thenReturn(true);

        assertThat(guard.decide(true, Privilege.DEACTIVATION)).isEqualTo(AccessDecision.DENIED);
    }

    @Test
    @DisplayName("Prihlásený s privilégiom alebo portlet bez privilégia → ALLOWED")
    void should_allow_when_privilegePresentOrNotRequired() {
        when(sessionManager.isLoggedIn()).thenReturn(true);
        when(acl.hasPrivilege(Privilege.BLACKLIST_VIEW)).thenReturn(true);

        assertThat(guard.decide(true, Privilege.BLACKLIST_VIEW)).isEqualTo(AccessDecision.ALLOWED);
        assertThat(guard.decide(true, null)).isEqualTo(AccessDecision.ALLOWED);
    }

    @Test
    @DisplayName("Nedostupný SSO portál → SSO_UNAVAILABLE (HTTP 503), nie výnimka")
    void should_reportSsoUnavailable_when_portalDown() {
        when(sessionManager.isLoggedIn()).thenThrow(new SsoUnavailableException("down", null));

        AccessDecision decision = guard.decide(true, Privilege.BLACKLIST_VIEW);

        assertThat(decision).isEqualTo(AccessDecision.SSO_UNAVAILABLE);
        assertThat(decision.getHttpStatus()).isEqualTo(503);
        assertThat(AccessDecision.LOGIN_REQUIRED.getHttpStatus()).isEqualTo(401);
        assertThat(AccessDecision.DENIED.getHttpStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("Login URL berie z fasády")
    void should_delegateLoginUrl_when_asked() {
        when(sessionManager.getLoginUrl()).thenReturn("/sso/login?returnUrl=%2F");

        assertThat(guard.loginUrl()).isEqualTo("/sso/login?returnUrl=%2F");
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class InMemoryAclService`.

- [ ] **Step 3: Implementuj ACL**

`…/base/acl/Privilege.java`:

```java
package com.example.crm.frontend.base.acl;

/** Business privilégiá vlastného ACL (legacy nepoužíva Liferay roly ani permissions na business autorizáciu). */
public enum Privilege { BLACKLIST_VIEW, BLACKLIST_MODIFY, DEACTIVATION }
```

`…/base/acl/AclService.java`:

```java
package com.example.crm.frontend.base.acl;

/** Port na ACL službu. V legacy je za ním backend; pri migrácii sa ponechá (prípadne obalí Spring Security). */
public interface AclService {

    /** Či má operátor s daným loginom privilégium. */
    boolean hasPrivilege(String operatorLogin, Privilege privilege);
}
```

`…/base/acl/InMemoryAclService.java`:

```java
package com.example.crm.frontend.base.acl;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Demo ACL: dvaja vymyslení operátori. SSO portál pozná len identitu, privilégiá sú vecou CRM. */
public class InMemoryAclService implements AclService {

    private final Map<String, Set<Privilege>> privileges = new HashMap<>();

    public InMemoryAclService() {
        privileges.put("citatel", EnumSet.of(Privilege.BLACKLIST_VIEW));
        privileges.put("spravca", EnumSet.allOf(Privilege.class));
    }

    @Override
    public boolean hasPrivilege(String operatorLogin, Privilege privilege) {
        if (operatorLogin == null) {
            return false;
        }
        Set<Privilege> granted = privileges.get(operatorLogin);
        return (granted == null ? Collections.<Privilege>emptySet() : granted).contains(privilege);
    }
}
```

`…/base/acl/AclDeniedException.java`:

```java
package com.example.crm.frontend.base.acl;

/** Chýbajúce privilégium. Exception resolver z nej spraví stránku 403 (resource: HTTP 403). */
public class AclDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

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

`…/base/acl/OperatorAcl.java`:

```java
package com.example.crm.frontend.base.acl;

import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalOperator;

/** ACL pre aktuálne prihláseného operátora – používajú ho controllery, JSP tagy aj DispatcherPortletCustom. */
public class OperatorAcl {

    private final SessionManagerCustom sessionManager;
    private final AclService aclService;

    public OperatorAcl(SessionManagerCustom sessionManager, AclService aclService) {
        this.sessionManager = sessionManager;
        this.aclService = aclService;
    }

    /** Či má aktuálny operátor privilégium (neprihlásený nemá žiadne). */
    public boolean hasPrivilege(Privilege privilege) {
        PortalOperator operator = sessionManager.getOperator();
        return operator != null && aclService.hasPrivilege(operator.getLogin(), privilege);
    }

    /** Variant pre JSP tagy (privilégium ako text); neznáme meno je chyba v JSP. */
    public boolean hasPrivilege(String privilegeName) {
        try {
            return hasPrivilege(Privilege.valueOf(privilegeName));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Neznáme privilégium '" + privilegeName + "'", e);
        }
    }

    /** Vyhodí {@link AclDeniedException}, ak aktuálny operátor privilégium nemá. */
    public void checkPrivilege(Privilege privilege) {
        if (!hasPrivilege(privilege)) {
            throw new AclDeniedException(privilege);
        }
    }
}
```

- [ ] **Step 4: Implementuj výnimku 404 a strážcu**

`…/base/web/NotFoundException.java`:

```java
package com.example.crm.frontend.base.web;

/** Neexistujúci záznam alebo záznam mimo kontextu zákazníka (podvrhnuté ID) – stránka 404, nie zmena dát. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String what) {
        super("Nenájdené: " + what);
    }
}
```

`…/base/web/AccessDecision.java`:

```java
package com.example.crm.frontend.base.web;

/** Rozhodnutie o prístupe k portletu; HTTP status sa použije v resource fáze (AJAX). */
public enum AccessDecision {
    ALLOWED(200), LOGIN_REQUIRED(401), DENIED(403), SSO_UNAVAILABLE(503);

    private final int httpStatus;

    AccessDecision(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
```

`…/base/web/PortletAccessGuard.java`:

```java
package com.example.crm.frontend.base.web;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.SsoUnavailableException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Logika kontroly v {@code DispatcherPortletCustom} oddelená od portletu, aby sa dala testovať bez portálu.
 * V legacy je táto kontrola zahrabaná v DispatcherPortlet podtriede; pri migrácii z nej bude interceptor.
 */
public class PortletAccessGuard {

    private static final Log LOG = LogFactory.getLog(PortletAccessGuard.class);

    private final SessionManagerCustom sessionManager;
    private final OperatorAcl acl;

    public PortletAccessGuard(SessionManagerCustom sessionManager, OperatorAcl acl) {
        this.sessionManager = sessionManager;
        this.acl = acl;
    }

    /** Rozhodne o prístupe; {@code required == null} znamená portlet bez privilégia. */
    public AccessDecision decide(boolean loginRequired, Privilege required) {
        try {
            boolean loggedIn = sessionManager.isLoggedIn();
            if (loginRequired && !loggedIn) {
                return AccessDecision.LOGIN_REQUIRED;
            }
            if (required != null && !acl.hasPrivilege(required)) {
                return AccessDecision.DENIED;
            }
            return AccessDecision.ALLOWED;
        } catch (SsoUnavailableException e) {
            LOG.error("SSO portál je nedostupný", e);
            return AccessDecision.SSO_UNAVAILABLE;
        }
    }

    /** URL prihlásenia s návratom na aktuálnu stránku. */
    public String loginUrl() {
        return sessionManager.getLoginUrl();
    }
}
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 45 + 4 + 6 = 55).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 6: Tagy `common:*` (i18n a ACL) a `MessageSourceCustom`

**Files:**
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/i18n/MessageSourceCustom.java`
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/tag/TagBeans.java`, `MessageTag.java`, `MsgArgTag.java`, `AclFilterTag.java`, `AclPageFilterTag.java`
- Create: `legacy/frontend-base-customization/src/main/resources/META-INF/common.tld`
- Create (test): `legacy/frontend-base-customization/src/test/resources/messages.properties`
- Create (test helper): `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/tag/TagTestSupport.java`
- Test: `…/base/tag/CommonTagsTest.java`, `…/base/tag/TldConsistencyTest.java` (v `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/tag/`)

**Interfaces:**
- Consumes: `OperatorAcl` (Task 5)
- Produces:
  - `MessageSourceCustom extends ResourceBundleMessageSource` (basename `messages`, UTF-8, bez fallbacku na systémový locale) – bean `messageSource` v Tasku 9
  - `TagBeans.get(PageContext, Class<T>)` – bean z root `WebApplicationContext`
  - TLD `http://example.com/crm/tags/common`: `<common:message key [var] [includeKey]>` + `<common:msgarg value/>`, `<common:aclFilter privilegeType>`, `<common:aclPageFilterTag privilegeType [wireframe]>`
  - Chýbajúci kľúč → `??key??` + WARN log; výstup tagu je HTML-escapovaný (UTF-8)
  - `TagTestSupport` (test helper): `pageContext(MessageSource, OperatorAcl)`, `content(PageContext)`

- [ ] **Step 1: Testovacie texty a helper**

`legacy/frontend-base-customization/src/test/resources/messages.properties`:

```properties
test.search=Hľadať
test.count=Počet záznamov: {0}
error.403=Nemáte oprávnenie {0}.
```

`legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/tag/TagTestSupport.java`:

```java
package com.example.crm.frontend.base.tag;

import com.example.crm.frontend.base.acl.OperatorAcl;
import java.io.UnsupportedEncodingException;
import javax.servlet.jsp.PageContext;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPageContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;

/** Page context s root Spring kontextom (ako vo WAR-e: ContextLoaderListener) bez servletového kontajnera. */
public final class TagTestSupport {

    private TagTestSupport() {
    }

    /** Page context, ktorého root kontext obsahuje daný MessageSource a ACL. */
    public static MockPageContext pageContext(MessageSource messageSource, OperatorAcl acl) {
        MockServletContext servletContext = new MockServletContext();
        GenericWebApplicationContext root = new GenericWebApplicationContext();
        root.setServletContext(servletContext);
        if (messageSource != null) {
            root.getBeanFactory().registerSingleton("messageSource", messageSource);
        }
        if (acl != null) {
            root.getBeanFactory().registerSingleton("operatorAcl", acl);
        }
        root.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, root);
        MockHttpServletResponse response = new MockHttpServletResponse();
        // Portál odpovedá v UTF-8; mock má predvolene ISO-8859-1 a diakritiku by zmenil na '?'.
        response.setCharacterEncoding("UTF-8");
        return new MockPageContext(servletContext, new MockHttpServletRequest(servletContext), response);
    }

    /** Text, ktorý tagy zapísali do výstupu. */
    public static String content(PageContext pageContext) {
        try {
            return ((MockHttpServletResponse) pageContext.getResponse()).getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

> `MockPageContext` zapisuje cez `MockJspWriter` priamo do odpovede, preto `getContentAsString()` vidí výstup tagov.

- [ ] **Step 2: Napíš padajúce testy**

`…/base/tag/CommonTagsTest.java`:

```java
package com.example.crm.frontend.base.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.i18n.MessageSourceCustom;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPageContext;

class CommonTagsTest {

    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final MockPageContext pageContext = TagTestSupport.pageContext(new MessageSourceCustom(), acl);

    @Test
    @DisplayName("Text z messages.properties v UTF-8")
    void should_writeUtf8Text_when_keyExists() throws JspException {
        message("test.search", null, false);

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("Hľadať");
    }

    @Test
    @DisplayName("Argumenty z <common:msgarg> a escapovanie HTML v argumente")
    void should_formatAndEscapeArguments_when_msgargPresent() throws JspException {
        MessageTag tag = new MessageTag();
        tag.setPageContext(pageContext);
        tag.setKey("test.count");
        tag.doStartTag();
        MsgArgTag arg = new MsgArgTag();
        arg.setPageContext(pageContext);
        arg.setParent(tag);
        arg.setValue("<b>3</b>");
        arg.doStartTag();
        arg.doEndTag();
        tag.doEndTag();

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("Počet záznamov: &lt;b&gt;3&lt;/b&gt;");
    }

    @Test
    @DisplayName("Chýbajúci kľúč je viditeľný ako ??kľúč?? (PoC ho ticho nahradil)")
    void should_showMissingKeyVisibly_when_keyUnknown() throws JspException {
        message("missing.key", null, false);

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("??missing.key??");
    }

    @Test
    @DisplayName("var uloží text do page scope a nič nevypíše; includeKey pridá kľúč")
    void should_storeIntoVarAndAppendKey_when_requested() throws JspException {
        message("test.search", "label", true);

        assertThat(pageContext.getAttribute("label")).isEqualTo("Hľadať [test.search]");
        assertThat(TagTestSupport.content(pageContext)).isEmpty();
    }

    @Test
    @DisplayName("msgarg mimo <common:message> je chyba v JSP")
    void should_fail_when_msgargOutsideMessage() {
        MsgArgTag arg = new MsgArgTag();
        arg.setPageContext(pageContext);
        arg.setValue("x");

        assertThatThrownBy(arg::doStartTag).isInstanceOf(JspException.class).hasMessageContaining("common:message");
    }

    @Test
    @DisplayName("aclFilter zobrazí telo len s privilégiom")
    void should_includeBodyOnlyWithPrivilege_when_aclFilter() throws JspException {
        when(acl.hasPrivilege("BLACKLIST_MODIFY")).thenReturn(true);

        assertThat(aclFilter("BLACKLIST_MODIFY").doStartTag()).isEqualTo(Tag.EVAL_BODY_INCLUDE);
        assertThat(aclFilter("DEACTIVATION").doStartTag()).isEqualTo(Tag.SKIP_BODY);
    }

    @Test
    @DisplayName("Preklep v mene privilégia v JSP je JspException, nie tiché skrytie")
    void should_fail_when_privilegeNameUnknown() {
        when(acl.hasPrivilege("BLACKLIST_MODIFI")).thenThrow(new IllegalArgumentException("Neznáme privilégium"));

        assertThatThrownBy(() -> aclFilter("BLACKLIST_MODIFI").doStartTag()).isInstanceOf(JspException.class);
    }

    @Test
    @DisplayName("aclPageFilterTag bez privilégia vypíše 403 a zastaví zvyšok stránky")
    void should_stopPage_when_pageFilterDenied() throws JspException {
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType("DEACTIVATION");
        tag.setWireframe("true");

        tag.doStartTag();

        assertThat(tag.doEndTag()).isEqualTo(Tag.SKIP_PAGE);
        assertThat(TagTestSupport.content(pageContext)).contains("Nemáte oprávnenie DEACTIVATION.");
    }

    @Test
    @DisplayName("aclPageFilterTag s privilégiom nič nevypíše a stránka pokračuje")
    void should_continuePage_when_pageFilterAllowed() throws JspException {
        when(acl.hasPrivilege("DEACTIVATION")).thenReturn(true);
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType("DEACTIVATION");

        tag.doStartTag();

        assertThat(tag.doEndTag()).isEqualTo(Tag.EVAL_PAGE);
        assertThat(TagTestSupport.content(pageContext)).isEmpty();
    }

    private void message(String key, String var, boolean includeKey) throws JspException {
        MessageTag tag = new MessageTag();
        tag.setPageContext(pageContext);
        tag.setKey(key);
        tag.setVar(var);
        tag.setIncludeKey(includeKey);
        tag.doStartTag();
        tag.doEndTag();
    }

    private AclFilterTag aclFilter(String privilege) {
        AclFilterTag tag = new AclFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType(privilege);
        return tag;
    }
}
```

`…/base/tag/TldConsistencyTest.java`:

```java
package com.example.crm.frontend.base.tag;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class TldConsistencyTest {

    @ParameterizedTest
    @ValueSource(strings = {"META-INF/common.tld"})
    @DisplayName("Každý tag v TLD má triedu a setter pre každý atribút (inak JSP spadne až v portáli)")
    void should_haveClassAndSetters_when_tldDeclaresTags(String tld) throws Exception {
        Document document;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(tld)) {
            assertThat(in).as(tld).isNotNull();
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        NodeList tags = document.getElementsByTagName("tag");
        assertThat(tags.getLength()).isPositive();
        for (int i = 0; i < tags.getLength(); i++) {
            Element tag = (Element) tags.item(i);
            Class<?> type = Class.forName(text(tag, "tag-class"));
            NodeList attributes = tag.getElementsByTagName("attribute");
            for (int j = 0; j < attributes.getLength(); j++) {
                String name = text((Element) attributes.item(j), "name");
                assertThat(hasSetter(type, name)).as(type.getSimpleName() + "." + name).isTrue();
            }
        }
    }

    private static String text(Element parent, String child) {
        return parent.getElementsByTagName(child).item(0).getTextContent().trim();
    }

    private static boolean hasSetter(Class<?> type, String attribute) {
        String setter = "set" + Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
        for (Method method : type.getMethods()) {
            if (method.getName().equals(setter) && method.getParameterTypes().length == 1) {
                return true;
            }
        }
        return false;
    }
}
```

> `@ParameterizedTest` je v artefakte `junit-jupiter` (agregátor obsahuje `junit-jupiter-params`).

- [ ] **Step 3: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class MessageSourceCustom`.

- [ ] **Step 4: Implementuj `MessageSourceCustom` a `TagBeans`**

`…/base/i18n/MessageSourceCustom.java`:

```java
package com.example.crm.frontend.base.i18n;

import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Zdroj prekladov. Legacy ich číta z DB a externého CMS (vlastný MessageSource); demo z {@code messages.properties}.
 * Rozhranie (Spring {@code MessageSource}) je rovnaké, takže tag {@code common:message} sa pri zmene zdroja nemení.
 */
public class MessageSourceCustom extends ResourceBundleMessageSource {

    public MessageSourceCustom() {
        setBasename("messages");
        setDefaultEncoding("UTF-8");
        // Bez fallbacku na locale servera: rovnaký text pre každý prehliadač (demo je len po slovensky).
        setFallbackToSystemLocale(false);
    }
}
```

`…/base/tag/TagBeans.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.servlet.jsp.PageContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

/** Tagy nie sú Spring beany – beany si berú z root kontextu WAR-u (ContextLoaderListener). */
final class TagBeans {

    private TagBeans() {
    }

    /** Bean daného typu z root {@code WebApplicationContext}. */
    static <T> T get(PageContext pageContext, Class<T> type) {
        return WebApplicationContextUtils.getRequiredWebApplicationContext(pageContext.getServletContext()).getBean(type);
    }
}
```

- [ ] **Step 5: Implementuj tagy**

`…/base/tag/MessageTag.java`:

```java
package com.example.crm.frontend.base.tag;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <common:message key="…" [var] [includeKey]>} s {@code <common:msgarg>} – legacy i18n tag (tisíce použití).
 * Chýbajúci kľúč je viditeľný ({@code ??key??}) a zalogovaný; PoC ho ticho nahrádzal a chyby nebolo vidieť.
 */
public class MessageTag extends TagSupport {

    private static final long serialVersionUID = 1L;
    private static final Log LOG = LogFactory.getLog(MessageTag.class);

    private final List<Object> arguments = new ArrayList<>();
    private String key;
    private String var;
    private boolean includeKey;

    public void setKey(String key) {
        this.key = key;
    }

    public void setVar(String var) {
        this.var = var;
    }

    public void setIncludeKey(boolean includeKey) {
        this.includeKey = includeKey;
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
        String text = HtmlUtils.htmlEscape(resolve(), "UTF-8");
        if (var != null) {
            pageContext.setAttribute(var, text);
            return EVAL_PAGE;
        }
        try {
            pageContext.getOut().write(text);
        } catch (IOException e) {
            throw new JspException(e);
        }
        return EVAL_PAGE;
    }

    private String resolve() {
        MessageSource messages = TagBeans.get(pageContext, MessageSource.class);
        Locale locale = pageContext.getRequest().getLocale();
        try {
            String text = messages.getMessage(key, arguments.toArray(), locale);
            return includeKey ? text + " [" + key + "]" : text;
        } catch (NoSuchMessageException e) {
            LOG.warn("Chýbajúci i18n kľúč '" + key + "'");
            return "??" + key + "??";
        }
    }
}
```

`…/base/tag/MsgArgTag.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;

/** {@code <common:msgarg value="…"/>} – argument nadradeného {@code <common:message>}. */
public class MsgArgTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private Object value;

    public void setValue(Object value) {
        this.value = value;
    }

    @Override
    public int doStartTag() throws JspException {
        MessageTag message = (MessageTag) findAncestorWithClass(this, MessageTag.class);
        if (message == null) {
            throw new JspException("<common:msgarg> musí byť vnútri <common:message>");
        }
        message.addArgument(value);
        return SKIP_BODY;
    }
}
```

`…/base/tag/AclFilterTag.java`:

```java
package com.example.crm.frontend.base.tag;

import com.example.crm.frontend.base.acl.OperatorAcl;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;

/** {@code <common:aclFilter privilegeType="…">} – telo len pre operátora s privilégiom (napr. tlačidlá zmeny). */
public class AclFilterTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private String privilegeType;

    public void setPrivilegeType(String privilegeType) {
        this.privilegeType = privilegeType;
    }

    @Override
    public int doStartTag() throws JspException {
        return allowed(pageContext, privilegeType) ? EVAL_BODY_INCLUDE : SKIP_BODY;
    }

    static boolean allowed(javax.servlet.jsp.PageContext pageContext, String privilegeType) throws JspException {
        try {
            return TagBeans.get(pageContext, OperatorAcl.class).hasPrivilege(privilegeType);
        } catch (IllegalArgumentException e) {
            throw new JspException(e.getMessage(), e);
        }
    }
}
```

`…/base/tag/AclPageFilterTag.java`:

```java
package com.example.crm.frontend.base.tag;

import java.io.IOException;
import java.util.Locale;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;
import org.springframework.context.MessageSource;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code <common:aclPageFilterTag privilegeType="…" wireframe="…"/>} – bez privilégia vypíše 403 a zastaví render
 * zvyšku JSP ({@code SKIP_PAGE}). Atribút {@code wireframe} sa prijíma kvôli kompatibilite JSP a ignoruje sa
 * (v legacy riadil vzhľad „kostry“ stránky v theme).
 */
public class AclPageFilterTag extends TagSupport {

    private static final long serialVersionUID = 1L;

    private String privilegeType;
    private boolean denied;

    public void setPrivilegeType(String privilegeType) {
        this.privilegeType = privilegeType;
    }

    public void setWireframe(String wireframe) {
        // zámerne ignorované – viď JavaDoc triedy
    }

    @Override
    public int doStartTag() throws JspException {
        denied = !AclFilterTag.allowed(pageContext, privilegeType);
        return SKIP_BODY;
    }

    @Override
    public int doEndTag() throws JspException {
        if (!denied) {
            return EVAL_PAGE;
        }
        MessageSource messages = TagBeans.get(pageContext, MessageSource.class);
        Locale locale = pageContext.getRequest().getLocale();
        String text = messages.getMessage("error.403", new Object[] {privilegeType}, "403", locale);
        try {
            pageContext.getOut().write("<div class=\"portlet-msg-error\">" + HtmlUtils.htmlEscape(text, "UTF-8") + "</div>");
        } catch (IOException e) {
            throw new JspException(e);
        }
        return SKIP_PAGE;
    }
}
```

- [ ] **Step 6: Vytvor TLD**

`legacy/frontend-base-customization/src/main/resources/META-INF/common.tld`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<taglib xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-jsptaglibrary_2_1.xsd"
        version="2.1">
    <description>Spoločné tagy CRM (i18n, ACL) – rozhranie ako v legacy.</description>
    <tlib-version>1.0</tlib-version>
    <short-name>common</short-name>
    <uri>http://example.com/crm/tags/common</uri>

    <tag>
        <name>message</name>
        <tag-class>com.example.crm.frontend.base.tag.MessageTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>key</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>var</name><required>false</required><rtexprvalue>false</rtexprvalue></attribute>
        <attribute><name>includeKey</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>msgarg</name>
        <tag-class>com.example.crm.frontend.base.tag.MsgArgTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>value</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>aclFilter</name>
        <tag-class>com.example.crm.frontend.base.tag.AclFilterTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>privilegeType</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>aclPageFilterTag</name>
        <tag-class>com.example.crm.frontend.base.tag.AclPageFilterTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>privilegeType</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>wireframe</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
</taglib>
```

- [ ] **Step 7: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 55 + 9 + 1 = 65).

- [ ] **Step 8: Checkpoint (bez commitu)** – `git status --short`

---

### Task 7: Tagy `portal:*URL` (skrytá Liferay závislosť `LiferayPortletURL`)

**Files:**
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/tag/PortletUrlTagSupport.java`, `ActionUrlTag.java`, `RenderUrlTag.java`, `ResourceUrlTag.java`, `ParamTag.java`
- Create: `legacy/frontend-base-customization/src/main/resources/META-INF/portal.tld`
- Modify: `…/base/tag/TldConsistencyTest.java` (pridať `META-INF/portal.tld`)
- Test: `…/base/tag/PortalUrlTagsTest.java`

**Interfaces:**
- Consumes: `TagTestSupport` (Task 6)
- Produces: TLD `http://example.com/crm/tags/portal`:
  - `<portal:actionURL [var] [escapeXml=true] [copyCurrentRenderParameters=false]>`, `<portal:renderURL …>`, `<portal:resourceURL id [var] [escapeXml] [copyCurrentRenderParameters]>`
  - `<portal:param name value/>` – viac rovnakých mien = viac hodnôt
  - URL sa vyrába cez `javax.portlet.response` (`MimeResponse.create*URL()`) a pretypuje na `LiferayPortletURL`; mimo portletu alebo bez Liferay → `JspException`
  - Výstup/`var` je pri `escapeXml=true` HTML-escapovaný (`&` → `&amp;`)

- [ ] **Step 1: Napíš padajúci test**

`…/base/tag/PortalUrlTagsTest.java`:

```java
package com.example.crm.frontend.base.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.liferay.portal.kernel.portlet.LiferayPortletURL;
import com.liferay.portal.kernel.util.JavaConstants;
import javax.portlet.PortletURL;
import javax.portlet.RenderResponse;
import javax.servlet.jsp.JspException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPageContext;

class PortalUrlTagsTest {

    private final RenderResponse response = mock(RenderResponse.class);
    private final LiferayPortletURL url = mock(LiferayPortletURL.class);
    private final MockPageContext pageContext = TagTestSupport.pageContext(null, null);

    @BeforeEach
    void exposePortletResponse() {
        // Liferay vystaví portlet response JSP-čku ako request atribút – presne odtiaľ ho legacy tagy berú.
        pageContext.getRequest().setAttribute(JavaConstants.JAVAX_PORTLET_RESPONSE, response);
        when(response.createActionURL()).thenReturn(url);
        when(response.createRenderURL()).thenReturn(url);
        when(response.createResourceURL()).thenReturn(url);
        when(url.toString()).thenReturn("/web/crm/blacklist?p_p_id=blacklist&action=searchAction");
    }

    @Test
    @DisplayName("actionURL nastaví parametre a vypíše escapované URL")
    void should_writeEscapedUrlWithParams_when_actionUrl() throws JspException {
        ActionUrlTag tag = new ActionUrlTag();
        run(tag, "action", "searchAction", "viewPage", "searchresult");

        verify(url).setParameter("action", new String[] {"searchAction"});
        verify(url).setParameter("viewPage", new String[] {"searchresult"});
        assertThat(TagTestSupport.content(pageContext))
                .isEqualTo("/web/crm/blacklist?p_p_id=blacklist&amp;action=searchAction");
    }

    @Test
    @DisplayName("var uloží URL do page scope; escapeXml=false nechá & bez zmeny")
    void should_storeRawUrlInVar_when_escapeDisabled() throws JspException {
        RenderUrlTag tag = new RenderUrlTag();
        tag.setVar("u");
        tag.setEscapeXml(false);
        run(tag);

        assertThat(pageContext.getAttribute("u")).isEqualTo("/web/crm/blacklist?p_p_id=blacklist&action=searchAction");
        assertThat(TagTestSupport.content(pageContext)).isEmpty();
    }

    @Test
    @DisplayName("resourceURL nastaví ID resource a kopírovanie render parametrov (Liferay rozšírenie)")
    void should_setResourceIdAndCopyFlag_when_resourceUrl() throws JspException {
        ResourceUrlTag tag = new ResourceUrlTag();
        tag.setId("removeIdentAction");
        tag.setCopyCurrentRenderParameters(true);
        run(tag);

        verify(url).setResourceID("removeIdentAction");
        verify(url).setCopyCurrentRenderParameters(true);
    }

    @Test
    @DisplayName("Rovnaké meno parametra viackrát = viac hodnôt")
    void should_collectMultipleValues_when_paramRepeated() throws JspException {
        run(new RenderUrlTag(), "id", "1", "id", "2");

        verify(url).setParameter("id", new String[] {"1", "2"});
    }

    @Test
    @DisplayName("Mimo JSP portletu (bez javax.portlet.response) je JspException s menom tagu")
    void should_fail_when_noPortletResponse() {
        pageContext.getRequest().removeAttribute(JavaConstants.JAVAX_PORTLET_RESPONSE);

        assertThatThrownBy(() -> run(new ActionUrlTag()))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("portal:actionURL");
    }

    @Test
    @DisplayName("Portál bez Liferay (iná implementácia PortletURL) je JspException – závislosť je explicitná")
    void should_fail_when_urlIsNotLiferay() {
        when(response.createActionURL()).thenReturn(mock(PortletURL.class));

        assertThatThrownBy(() -> run(new ActionUrlTag()))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("LiferayPortletURL");
    }

    @Test
    @DisplayName("portal:param mimo URL tagu je chyba v JSP")
    void should_fail_when_paramOutsideUrlTag() {
        ParamTag param = new ParamTag();
        param.setPageContext(pageContext);
        param.setName("x");
        param.setValue("y");

        assertThatThrownBy(param::doStartTag).isInstanceOf(JspException.class).hasMessageContaining("portal:param");
    }

    private void run(PortletUrlTagSupport tag, String... nameValues) throws JspException {
        tag.setPageContext(pageContext);
        tag.doStartTag();
        for (int i = 0; i < nameValues.length; i += 2) {
            ParamTag param = new ParamTag();
            param.setPageContext(pageContext);
            param.setParent(tag);
            param.setName(nameValues[i]);
            param.setValue(nameValues[i + 1]);
            param.doStartTag();
            param.doEndTag();
        }
        tag.doEndTag();
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class ActionUrlTag`.

- [ ] **Step 3: Implementuj URL tagy**

`…/base/tag/PortletUrlTagSupport.java`:

```java
package com.example.crm.frontend.base.tag;

import com.liferay.portal.kernel.portlet.LiferayPortletURL;
import com.liferay.portal.kernel.util.JavaConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.portlet.BaseURL;
import javax.portlet.MimeResponse;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;
import org.springframework.web.util.HtmlUtils;

/**
 * Spoločný základ {@code portal:*URL}. Toto je skrytá Liferay závislosť z analýzy: JSP vyzerajú „čisto“,
 * ale tag pretypuje URL na {@link LiferayPortletURL} (kvôli rozšíreniam mimo Portlet API). Pri migrácii sa tag
 * reimplementuje s rovnakým rozhraním nad servletovými URL – JSP ostanú bez zmeny.
 */
public abstract class PortletUrlTagSupport extends TagSupport {

    private static final long serialVersionUID = 1L;

    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private String var;
    private boolean escapeXml = true;
    private boolean copyCurrentRenderParameters;

    public void setVar(String var) {
        this.var = var;
    }

    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    public void setCopyCurrentRenderParameters(boolean copyCurrentRenderParameters) {
        this.copyCurrentRenderParameters = copyCurrentRenderParameters;
    }

    void addParameter(String name, String value) {
        List<String> values = parameters.get(name);
        if (values == null) {
            values = new ArrayList<>();
            parameters.put(name, values);
        }
        values.add(value);
    }

    @Override
    public int doStartTag() {
        parameters.clear();
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        Object response = pageContext.getRequest().getAttribute(JavaConstants.JAVAX_PORTLET_RESPONSE);
        if (!(response instanceof MimeResponse)) {
            throw new JspException("<" + tagName() + "> sa dá použiť len v JSP portletu (chýba javax.portlet.response)");
        }
        BaseURL url = createUrl((MimeResponse) response);
        if (!(url instanceof LiferayPortletURL)) {
            throw new JspException("<" + tagName() + "> vyžaduje Liferay (LiferayPortletURL), portál vrátil "
                    + url.getClass().getName());
        }
        ((LiferayPortletURL) url).setCopyCurrentRenderParameters(copyCurrentRenderParameters);
        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            url.setParameter(parameter.getKey(), parameter.getValue().toArray(new String[0]));
        }
        String value = escapeXml ? HtmlUtils.htmlEscape(url.toString(), "UTF-8") : url.toString();
        if (var != null) {
            pageContext.setAttribute(var, value);
            return EVAL_PAGE;
        }
        try {
            pageContext.getOut().write(value);
        } catch (IOException e) {
            throw new JspException(e);
        }
        return EVAL_PAGE;
    }

    /** Vytvorí URL danej fázy portletu. */
    protected abstract BaseURL createUrl(MimeResponse response);

    /** Meno tagu do chybových hlášok. */
    protected abstract String tagName();
}
```

`…/base/tag/ActionUrlTag.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.portlet.BaseURL;
import javax.portlet.MimeResponse;

/** {@code <portal:actionURL>} – URL action fázy (POST formulára, zmena stavu). */
public class ActionUrlTag extends PortletUrlTagSupport {

    private static final long serialVersionUID = 1L;

    @Override
    protected BaseURL createUrl(MimeResponse response) {
        return response.createActionURL();
    }

    @Override
    protected String tagName() {
        return "portal:actionURL";
    }
}
```

`…/base/tag/RenderUrlTag.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.portlet.BaseURL;
import javax.portlet.MimeResponse;

/** {@code <portal:renderURL>} – URL render fázy (odkaz na inú „stránku“ portletu). */
public class RenderUrlTag extends PortletUrlTagSupport {

    private static final long serialVersionUID = 1L;

    @Override
    protected BaseURL createUrl(MimeResponse response) {
        return response.createRenderURL();
    }

    @Override
    protected String tagName() {
        return "portal:renderURL";
    }
}
```

`…/base/tag/ResourceUrlTag.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.portlet.BaseURL;
import javax.portlet.MimeResponse;
import javax.portlet.ResourceURL;

/** {@code <portal:resourceURL id="…">} – URL resource fázy (AJAX: HTML fragment alebo JSON). */
public class ResourceUrlTag extends PortletUrlTagSupport {

    private static final long serialVersionUID = 1L;

    private String resourceId;

    // Atribút sa v JSP volá „id“ ako v legacy TLD; TagSupport už má setId pre id tagu, preto ho prepisujeme.
    @Override
    public void setId(String id) {
        this.resourceId = id;
    }

    @Override
    protected BaseURL createUrl(MimeResponse response) {
        ResourceURL url = response.createResourceURL();
        if (resourceId != null) {
            url.setResourceID(resourceId);
        }
        return url;
    }

    @Override
    protected String tagName() {
        return "portal:resourceURL";
    }
}
```

`…/base/tag/ParamTag.java`:

```java
package com.example.crm.frontend.base.tag;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;

/** {@code <portal:param name="…" value="…"/>} – parameter nadradeného URL tagu. */
public class ParamTag extends TagSupport {

    private static final long serialVersionUID = 1L;

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
        PortletUrlTagSupport url = (PortletUrlTagSupport) findAncestorWithClass(this, PortletUrlTagSupport.class);
        if (url == null) {
            throw new JspException("<portal:param> musí byť vnútri <portal:actionURL|renderURL|resourceURL>");
        }
        url.addParameter(name, value);
        return SKIP_BODY;
    }
}
```

- [ ] **Step 4: Vytvor TLD a doplň ho do testu konzistencie**

`legacy/frontend-base-customization/src/main/resources/META-INF/portal.tld`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<taglib xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-jsptaglibrary_2_1.xsd"
        version="2.1">
    <description>Portletové URL tagy CRM – rozhranie ako v legacy, vnútri závislosť na Liferay.</description>
    <tlib-version>1.0</tlib-version>
    <short-name>portal</short-name>
    <uri>http://example.com/crm/tags/portal</uri>

    <tag>
        <name>actionURL</name>
        <tag-class>com.example.crm.frontend.base.tag.ActionUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>var</name><required>false</required><rtexprvalue>false</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>copyCurrentRenderParameters</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>renderURL</name>
        <tag-class>com.example.crm.frontend.base.tag.RenderUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>var</name><required>false</required><rtexprvalue>false</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>copyCurrentRenderParameters</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>resourceURL</name>
        <tag-class>com.example.crm.frontend.base.tag.ResourceUrlTag</tag-class>
        <body-content>JSP</body-content>
        <attribute><name>id</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>var</name><required>false</required><rtexprvalue>false</rtexprvalue></attribute>
        <attribute><name>escapeXml</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>copyCurrentRenderParameters</name><required>false</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
    <tag>
        <name>param</name>
        <tag-class>com.example.crm.frontend.base.tag.ParamTag</tag-class>
        <body-content>empty</body-content>
        <attribute><name>name</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
        <attribute><name>value</name><required>true</required><rtexprvalue>true</rtexprvalue></attribute>
    </tag>
</taglib>
```

V `TldConsistencyTest` zmeň anotáciu na:

```java
    @ValueSource(strings = {"META-INF/common.tld", "META-INF/portal.tld"})
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 65 + 7 + 1 = 73).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 8: `DispatcherPortletCustom`, `BaseCrmController`, `LocalDateEditor`, exception resolver

**Files:**
- Create: `legacy/frontend-base-customization/src/main/java/com/example/crm/frontend/base/web/DispatcherPortletCustom.java`, `BaseCrmController.java`, `LocalDateEditor.java`, `SimpleMappingExceptionResolverCustom.java`
- Test: `…/base/web/DispatcherPortletCustomTest.java`, `BaseCrmControllerTest.java`, `SimpleMappingExceptionResolverCustomTest.java` (v `legacy/frontend-base-customization/src/test/java/com/example/crm/frontend/base/web/`)

**Interfaces:**
- Consumes: `PortletAccessGuard`, `AccessDecision`, `NotFoundException` (Task 5), `OperatorAcl`, `Privilege`, `AclDeniedException` (Task 5), `SessionManagerCustom` (Task 4), `SsoUnavailableException` (Task 1)
- Produces:
  - `DispatcherPortletCustom extends DispatcherPortlet`: init-params (cez bean properties) `requiredPrivilege` (meno `Privilege`, voliteľné) a `loginRequired` (default `true`); render: `LOGIN_REQUIRED` → include `/WEB-INF/jsp/common/login-required.jsp` s request atribútom `loginUrl`, `DENIED` → `/WEB-INF/jsp/error/403.jsp` s atribútom `privilege`, `SSO_UNAVAILABLE` → `/WEB-INF/jsp/error/sso.jsp`; action: `LOGIN_REQUIRED` → `sendRedirect(loginUrl)`, inak sa handler nespustí; resource: status `AccessDecision.getHttpStatus()`; beany berie z kontextu (`PortletAccessGuard`)
  - `abstract BaseCrmController`: `@Autowired setSessionManager(SessionManagerCustom)`, `@Autowired setOperatorAcl(OperatorAcl)`; `getSessionManager()`, `checkPrivilege(Privilege)`, `hasPrivilege(Privilege)`, `requireCustomer(): PortalCustomer` (→ `NotFoundException`), `operatorName(): String`; `@InitBinder` registruje `LocalDateEditor` pre `LocalDate`
  - `LocalDateEditor` (ISO `yyyy-MM-dd`, prázdne → `null`, zlý formát → `IllegalArgumentException`)
  - `SimpleMappingExceptionResolverCustom extends SimpleMappingExceptionResolver` (portlet): loguje, pri `AclDeniedException` nastaví request atribút `privilege`, v resource fáze nastaví HTTP status: `AclDeniedException` 403, `NotFoundException` 404, `TypeMismatchException`/`MissingPortletRequestParameterException`/`BindException` 400, `SsoUnavailableException` 503, inak 500

- [ ] **Step 1: Napíš padajúce testy**

`…/base/web/DispatcherPortletCustomTest.java`:

```java
package com.example.crm.frontend.base.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.Privilege;
import java.util.ArrayList;
import java.util.List;
import javax.portlet.MimeResponse;
import javax.portlet.PortletRequest;
import javax.portlet.ResourceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionRequest;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.mock.web.portlet.MockRenderRequest;
import org.springframework.mock.web.portlet.MockRenderResponse;
import org.springframework.mock.web.portlet.MockResourceRequest;
import org.springframework.mock.web.portlet.MockResourceResponse;

class DispatcherPortletCustomTest {

    private final PortletAccessGuard guard = mock(PortletAccessGuard.class);
    private final TestablePortlet portlet = new TestablePortlet(guard);

    @Test
    @DisplayName("Render bez prihlásenia vykreslí výzvu s login URL")
    void should_includeLoginPrompt_when_renderWithoutLogin() throws Exception {
        portlet.setRequiredPrivilege("BLACKLIST_VIEW");
        when(guard.decide(true, Privilege.BLACKLIST_VIEW)).thenReturn(AccessDecision.LOGIN_REQUIRED);
        when(guard.loginUrl()).thenReturn("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
        MockRenderRequest request = new MockRenderRequest();

        portlet.doRenderService(request, new MockRenderResponse());

        assertThat(portlet.included).containsExactly("/WEB-INF/jsp/common/login-required.jsp");
        assertThat(request.getAttribute("loginUrl")).isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
    }

    @Test
    @DisplayName("Render bez privilégia portletu vykreslí 403 s menom privilégia")
    void should_includeForbidden_when_renderDenied() throws Exception {
        portlet.setRequiredPrivilege("DEACTIVATION");
        when(guard.decide(true, Privilege.DEACTIVATION)).thenReturn(AccessDecision.DENIED);
        MockRenderRequest request = new MockRenderRequest();

        portlet.doRenderService(request, new MockRenderResponse());

        assertThat(portlet.included).containsExactly("/WEB-INF/jsp/error/403.jsp");
        assertThat(request.getAttribute("privilege")).isEqualTo("DEACTIVATION");
    }

    @Test
    @DisplayName("Render pri nedostupnom SSO vykreslí chybovú stránku SSO")
    void should_includeSsoError_when_portalDown() throws Exception {
        when(guard.decide(anyBoolean(), any())).thenReturn(AccessDecision.SSO_UNAVAILABLE);

        portlet.doRenderService(new MockRenderRequest(), new MockRenderResponse());

        assertThat(portlet.included).containsExactly("/WEB-INF/jsp/error/sso.jsp");
    }

    @Test
    @DisplayName("Action bez prihlásenia presmeruje na SSO login")
    void should_redirectToLogin_when_actionWithoutLogin() throws Exception {
        when(guard.decide(anyBoolean(), any())).thenReturn(AccessDecision.LOGIN_REQUIRED);
        when(guard.loginUrl()).thenReturn("/sso/login?returnUrl=%2F");
        MockActionResponse response = new MockActionResponse();

        portlet.doActionService(new MockActionRequest(), response);

        assertThat(response.getRedirectedUrl()).isEqualTo("/sso/login?returnUrl=%2F");
    }

    @Test
    @DisplayName("AJAX (resource) bez prihlásenia dostane HTTP 401 bez HTML")
    void should_return401Status_when_resourceWithoutLogin() throws Exception {
        when(guard.decide(anyBoolean(), any())).thenReturn(AccessDecision.LOGIN_REQUIRED);
        MockResourceResponse response = new MockResourceResponse();

        portlet.doResourceService(new MockResourceRequest(), response);

        assertThat(response.getProperty(ResourceResponse.HTTP_STATUS_CODE)).isEqualTo("401");
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(portlet.included).isEmpty();
    }

    @Test
    @DisplayName("AJAX bez privilégia dostane HTTP 403")
    void should_return403Status_when_resourceDenied() throws Exception {
        portlet.setRequiredPrivilege("BLACKLIST_VIEW");
        when(guard.decide(true, Privilege.BLACKLIST_VIEW)).thenReturn(AccessDecision.DENIED);
        MockResourceResponse response = new MockResourceResponse();

        portlet.doResourceService(new MockResourceRequest(), response);

        assertThat(response.getProperty(ResourceResponse.HTTP_STATUS_CODE)).isEqualTo("403");
    }

    @Test
    @DisplayName("Portlet s loginRequired=false a bez privilégia (hlavička) sa pýta strážcu s false a null")
    void should_passConfigToGuard_when_loginNotRequired() throws Exception {
        portlet.setLoginRequired(false);
        when(guard.decide(false, null)).thenReturn(AccessDecision.DENIED);

        portlet.doResourceService(new MockResourceRequest(), new MockResourceResponse());

        assertThat(portlet.decisions).containsExactly(AccessDecision.DENIED);
    }

    /** Portlet bez Spring kontextu: strážca je podstrčený, include sa len zaznamená. */
    static class TestablePortlet extends DispatcherPortletCustom {

        final List<String> included = new ArrayList<>();
        final List<AccessDecision> decisions = new ArrayList<>();
        private final PortletAccessGuard guard;

        TestablePortlet(PortletAccessGuard guard) {
            this.guard = guard;
        }

        @Override
        protected PortletAccessGuard guard() {
            return new PortletAccessGuard(null, null) {
                @Override
                public AccessDecision decide(boolean loginRequired, Privilege required) {
                    AccessDecision decision = guard.decide(loginRequired, required);
                    decisions.add(decision);
                    return decision;
                }

                @Override
                public String loginUrl() {
                    return guard.loginUrl();
                }
            };
        }

        @Override
        protected void include(String path, PortletRequest request, MimeResponse response) {
            included.add(path);
        }
    }
}
```

`…/base/web/BaseCrmControllerTest.java`:

```java
package com.example.crm.frontend.base.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.WebDataBinder;

class BaseCrmControllerTest {

    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final SampleController controller = new SampleController();

    SampleController controller() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(acl);
        return controller;
    }

    @Test
    @DisplayName("Bez zákazníka v SSO session je to 404 (kontext zákazníka chýba)")
    void should_throwNotFound_when_noCustomer() {
        assertThatThrownBy(() -> controller().requireCustomer()).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Zákazník a meno operátora zo SSO")
    void should_returnCustomerAndOperatorName_when_loggedIn() {
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Ján Vzorový"));
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));

        assertThat(controller().requireCustomer().getCustomerId()).isEqualTo("C-1001");
        assertThat(controller().operatorName()).isEqualTo("Správca");
    }

    @Test
    @DisplayName("checkPrivilege deleguje na ACL")
    void should_delegateToAcl_when_checkingPrivilege() {
        doThrow(new AclDeniedException(Privilege.BLACKLIST_MODIFY)).when(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);

        assertThatThrownBy(() -> controller().checkPrivilege(Privilege.BLACKLIST_MODIFY))
                .isInstanceOf(AclDeniedException.class);
    }

    @Test
    @DisplayName("Binder má editor pre LocalDate (portlet MVC nemá conversion service z <mvc:annotation-driven>)")
    void should_registerLocalDateEditor_when_initBinder() {
        WebDataBinder binder = new WebDataBinder(null);

        controller().initBaseBinder(binder);

        assertThat(binder.findCustomEditor(LocalDate.class, null)).isInstanceOf(LocalDateEditor.class);
    }

    @Test
    @DisplayName("LocalDateEditor: ISO dátum, prázdne = null, zlý formát = chyba")
    void should_parseIsoDates_when_editing() {
        LocalDateEditor editor = new LocalDateEditor();

        editor.setAsText("2026-10-12");
        assertThat(editor.getValue()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(editor.getAsText()).isEqualTo("2026-10-12");
        editor.setAsText("   ");
        assertThat(editor.getValue()).isNull();
        assertThat(editor.getAsText()).isEmpty();
        assertThatThrownBy(() -> editor.setAsText("12.10.2026")).isInstanceOf(IllegalArgumentException.class);
    }

    static class SampleController extends BaseCrmController {
    }
}
```

`…/base/web/SimpleMappingExceptionResolverCustomTest.java`:

```java
package com.example.crm.frontend.base.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.sso.api.SsoUnavailableException;
import java.util.Properties;
import javax.portlet.ResourceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.mock.web.portlet.MockRenderRequest;
import org.springframework.mock.web.portlet.MockRenderResponse;
import org.springframework.mock.web.portlet.MockResourceRequest;
import org.springframework.mock.web.portlet.MockResourceResponse;
import org.springframework.web.portlet.ModelAndView;

class SimpleMappingExceptionResolverCustomTest {

    private final SimpleMappingExceptionResolverCustom resolver = new SimpleMappingExceptionResolverCustom();

    @BeforeEach
    void configureLikeInXml() {
        Properties mappings = new Properties();
        mappings.setProperty(AclDeniedException.class.getName(), "error/403");
        mappings.setProperty(NotFoundException.class.getName(), "error/404");
        mappings.setProperty(TypeMismatchException.class.getName(), "error/404");
        mappings.setProperty(SsoUnavailableException.class.getName(), "error/sso");
        resolver.setExceptionMappings(mappings);
        resolver.setDefaultErrorView("error/error");
    }

    @Test
    @DisplayName("Render: ACL výnimka → view 403 a privilégium v requeste")
    void should_render403WithPrivilege_when_aclDenied() {
        MockRenderRequest request = new MockRenderRequest();

        ModelAndView view = resolver.resolveException(request, new MockRenderResponse(), null,
                new AclDeniedException(Privilege.BLACKLIST_MODIFY));

        assertThat(view.getViewName()).isEqualTo("error/403");
        assertThat(request.getAttribute("privilege")).isEqualTo("BLACKLIST_MODIFY");
    }

    @Test
    @DisplayName("Resource: HTTP status podľa výnimky")
    void should_setHttpStatus_when_resourceFails() {
        assertThat(statusFor(new NotFoundException("x"))).isEqualTo("404");
        assertThat(statusFor(new AclDeniedException(Privilege.DEACTIVATION))).isEqualTo("403");
        assertThat(statusFor(new TypeMismatchException("abc", Long.class))).isEqualTo("400");
        assertThat(statusFor(new SsoUnavailableException("down", null))).isEqualTo("503");
        assertThat(statusFor(new IllegalStateException("bug"))).isEqualTo("500");
    }

    @Test
    @DisplayName("Neznáma výnimka v renderi → všeobecná chybová stránka")
    void should_useDefaultView_when_exceptionUnmapped() {
        ModelAndView view = resolver.resolveException(new MockRenderRequest(), new MockRenderResponse(), null,
                new IllegalStateException("bug"));

        assertThat(view.getViewName()).isEqualTo("error/error");
    }

    private String statusFor(Exception exception) {
        MockResourceResponse response = new MockResourceResponse();
        resolver.resolveException(new MockResourceRequest(), response, null, exception);
        return response.getProperty(ResourceResponse.HTTP_STATUS_CODE);
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class DispatcherPortletCustom`.

- [ ] **Step 3: Implementuj `LocalDateEditor` a `BaseCrmController`**

`…/base/web/LocalDateEditor.java`:

```java
package com.example.crm.frontend.base.web;

import java.beans.PropertyEditorSupport;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Editor dátumu pre portlet MVC. Spring Portlet MVC nemá {@code <mvc:annotation-driven>} s conversion service,
 * legacy preto registruje vlastné PropertyEditory v base controlleri. Spring Boot to v migrácii rieši sám.
 */
public class LocalDateEditor extends PropertyEditorSupport {

    @Override
    public void setAsText(String text) {
        if (text == null || text.trim().isEmpty()) {
            setValue(null);
            return;
        }
        try {
            setValue(LocalDate.parse(text.trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Neplatný dátum '" + text + "' (očakáva sa RRRR-MM-DD)", e);
        }
    }

    @Override
    public String getAsText() {
        Object value = getValue();
        return value == null ? "" : value.toString();
    }
}
```

`…/base/web/BaseCrmController.java`:

```java
package com.example.crm.frontend.base.web;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Base controller ako v legacy (tam ~1900 riadkov a dedí z externej knižnice): prístup k fasáde session, ACL
 * a spoločný binder. Závislosti cez {@code @Autowired} settery – legacy štýl; pri migrácii konštruktor.
 */
public abstract class BaseCrmController {

    private SessionManagerCustom sessionManager;
    private OperatorAcl operatorAcl;

    @Autowired
    public void setSessionManager(SessionManagerCustom sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Autowired
    public void setOperatorAcl(OperatorAcl operatorAcl) {
        this.operatorAcl = operatorAcl;
    }

    /** Fasáda session (legacy: {@code getSessionManager()} ~400 volaní). */
    protected SessionManagerCustom getSessionManager() {
        return sessionManager;
    }

    /** Vyhodí {@code AclDeniedException}, ak operátor privilégium nemá (jemnejšie než privilégium portletu). */
    protected void checkPrivilege(Privilege privilege) {
        operatorAcl.checkPrivilege(privilege);
    }

    /** Či má operátor privilégium. */
    protected boolean hasPrivilege(Privilege privilege) {
        return operatorAcl.hasPrivilege(privilege);
    }

    /** Zákazník zo SSO session; bez neho stránka nemá kontext → 404. */
    protected PortalCustomer requireCustomer() {
        PortalCustomer customer = sessionManager.getCustomer();
        if (customer == null) {
            throw new NotFoundException("zákazník v SSO session");
        }
        return customer;
    }

    /** Meno operátora (autor poznámky, žiadosti). */
    protected String operatorName() {
        PortalOperator operator = sessionManager.getOperator();
        return operator == null ? "?" : operator.getDisplayName();
    }

    /** Spoločné editory pre všetky controllery. */
    @InitBinder
    public void initBaseBinder(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDate.class, new LocalDateEditor());
    }
}
```

- [ ] **Step 4: Implementuj exception resolver**

`…/base/web/SimpleMappingExceptionResolverCustom.java`:

```java
package com.example.crm.frontend.base.web;

import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.sso.api.SsoUnavailableException;
import javax.portlet.MimeResponse;
import javax.portlet.PortletRequest;
import javax.portlet.ResourceResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.validation.BindException;
import org.springframework.web.portlet.ModelAndView;
import org.springframework.web.portlet.bind.MissingPortletRequestParameterException;
import org.springframework.web.portlet.handler.SimpleMappingExceptionResolver;

/**
 * Mapovanie výnimiek na chybové JSP portletu (legacy má vlastnú podtriedu s rovnakým menom). Navyše: log
 * a HTTP status v resource fáze – AJAX volanie inak dostane 200 s HTML chybovej stránky.
 */
public class SimpleMappingExceptionResolverCustom extends SimpleMappingExceptionResolver {

    private static final Log LOG = LogFactory.getLog(SimpleMappingExceptionResolverCustom.class);

    @Override
    protected ModelAndView doResolveException(PortletRequest request, MimeResponse response, Object handler,
                                              Exception ex) {
        int status = statusOf(ex);
        if (status >= 500) {
            LOG.error("Chyba v portlete", ex);
        } else {
            LOG.warn("Požiadavku nejde spracovať (" + status + "): " + ex.getMessage());
        }
        if (ex instanceof AclDeniedException) {
            request.setAttribute("privilege", ((AclDeniedException) ex).getPrivilege().name());
        }
        if (response instanceof ResourceResponse) {
            response.setProperty(ResourceResponse.HTTP_STATUS_CODE, String.valueOf(status));
        }
        return super.doResolveException(request, response, handler, ex);
    }

    static int statusOf(Exception ex) {
        if (ex instanceof AclDeniedException) {
            return 403;
        }
        if (ex instanceof NotFoundException) {
            return 404;
        }
        if (ex instanceof TypeMismatchException || ex instanceof MissingPortletRequestParameterException
                || ex instanceof BindException) {
            return 400;
        }
        if (ex instanceof SsoUnavailableException) {
            return 503;
        }
        return 500;
    }
}
```

- [ ] **Step 5: Implementuj `DispatcherPortletCustom`**

`…/base/web/DispatcherPortletCustom.java`:

```java
package com.example.crm.frontend.base.web;

import com.example.crm.frontend.base.acl.Privilege;
import java.io.IOException;
import javax.portlet.ActionRequest;
import javax.portlet.ActionResponse;
import javax.portlet.MimeResponse;
import javax.portlet.PortletException;
import javax.portlet.PortletRequest;
import javax.portlet.RenderRequest;
import javax.portlet.RenderResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.web.portlet.DispatcherPortlet;

/**
 * Podtrieda Spring {@link DispatcherPortlet} ako v legacy: pred každou fázou overí prihlásenie v SSO a privilégium
 * portletu (init-param v portlet.xml; Spring ich nastaví cez settery). Pri migrácii z toho bude HandlerInterceptor
 * nad jedným DispatcherServletom.
 */
public class DispatcherPortletCustom extends DispatcherPortlet {

    static final String LOGIN_REQUIRED_VIEW = "/WEB-INF/jsp/common/login-required.jsp";
    static final String FORBIDDEN_VIEW = "/WEB-INF/jsp/error/403.jsp";
    static final String SSO_VIEW = "/WEB-INF/jsp/error/sso.jsp";

    private static final Log LOG = LogFactory.getLog(DispatcherPortletCustom.class);

    private Privilege requiredPrivilege;
    private boolean loginRequired = true;

    /** Init-param {@code requiredPrivilege} – meno {@link Privilege}; prázdne = portlet bez privilégia. */
    public void setRequiredPrivilege(String requiredPrivilege) {
        this.requiredPrivilege = requiredPrivilege == null || requiredPrivilege.trim().isEmpty()
                ? null : Privilege.valueOf(requiredPrivilege.trim());
    }

    /** Init-param {@code loginRequired} – {@code false} pre portlety viditeľné aj bez prihlásenia (hlavička). */
    public void setLoginRequired(boolean loginRequired) {
        this.loginRequired = loginRequired;
    }

    @Override
    protected void doRenderService(RenderRequest request, RenderResponse response) throws Exception {
        PortletAccessGuard guard = guard();
        AccessDecision decision = guard.decide(loginRequired, requiredPrivilege);
        switch (decision) {
            case ALLOWED:
                super.doRenderService(request, response);
                return;
            case LOGIN_REQUIRED:
                request.setAttribute("loginUrl", guard.loginUrl());
                include(LOGIN_REQUIRED_VIEW, request, response);
                return;
            case DENIED:
                request.setAttribute("privilege", requiredPrivilege.name());
                include(FORBIDDEN_VIEW, request, response);
                return;
            default:
                include(SSO_VIEW, request, response);
        }
    }

    @Override
    protected void doActionService(ActionRequest request, ActionResponse response) throws Exception {
        PortletAccessGuard guard = guard();
        AccessDecision decision = guard.decide(loginRequired, requiredPrivilege);
        if (decision == AccessDecision.ALLOWED) {
            super.doActionService(request, response);
            return;
        }
        if (decision == AccessDecision.LOGIN_REQUIRED) {
            // Action fáza môže presmerovať; render fáza nie (portál už skladá stránku) – tam ostane len odkaz.
            response.sendRedirect(guard.loginUrl());
            return;
        }
        // Handler sa nespustí; nasledujúci render zopakuje kontrolu a ukáže 403 / chybu SSO.
        LOG.warn("Action portletu " + getPortletName() + " zamietnutá: " + decision);
    }

    @Override
    protected void doResourceService(ResourceRequest request, ResourceResponse response) throws Exception {
        AccessDecision decision = guard().decide(loginRequired, requiredPrivilege);
        if (decision == AccessDecision.ALLOWED) {
            super.doResourceService(request, response);
            return;
        }
        // AJAX dostane holý status – prihlasovacia výzva vložená JS-kom do <div> by bola nezmysel.
        response.setProperty(ResourceResponse.HTTP_STATUS_CODE, String.valueOf(decision.getHttpStatus()));
    }

    /** Strážca z root kontextu (portlet kontext ho zdedí od rodiča). */
    protected PortletAccessGuard guard() {
        return getPortletApplicationContext().getBean(PortletAccessGuard.class);
    }

    /** Vloží JSP do výstupu portletu. */
    protected void include(String path, PortletRequest request, MimeResponse response)
            throws PortletException, IOException {
        getPortletContext().getRequestDispatcher(path).include(request, response);
    }
}
```

> `getPortletName()` v `TestablePortlet` (bez `init`) vráti `null` alebo zlyhá na chýbajúcom `PortletConfig` – testy preto action s `DENIED` nevolajú. Ak by `getPortletName()` v teste hodil NPE, nahraď ho v logu výrazom `String.valueOf(getPortletConfig() == null ? "?" : getPortletName())`.

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 73 + 7 + 5 + 3 = 88).

- [ ] **Step 7: Nainštaluj knižnice do lokálneho Maven repa (potrebuje ich WAR v Tasku 9)**

Run: `mvn -q -B -f legacy/pom.xml install -DskipTests`
Expected: bez chyby; `~/.m2/repository/com/example/crm/legacy/frontend-base-customization/0.1.0-SNAPSHOT/` existuje.

- [ ] **Step 8: Checkpoint (bez commitu)** – `git status --short`

---

### Task 9: `customercare-portlets` – kostra WAR-u, Spring kontexty, portlet `header`, spoločné JSP

**Files:**
- Modify: `legacy/pom.xml` (modul `customercare-portlets`)
- Create: `legacy/customercare-portlets/pom.xml`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/web.xml`, `applicationContext.xml`, `backend-context.xml`, `portlet.xml`, `liferay-portlet.xml`, `liferay-display.xml`, `liferay-plugin-package.properties`, `blacklist-portlet.xml`, `dashboard-portlet.xml`, `header-portlet.xml`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/init.jspf`, `jsp/common/login-required.jsp`, `jsp/error/403.jsp`, `jsp/error/404.jsp`, `jsp/error/sso.jsp`, `jsp/error/error.jsp`, `jsp/header/view.jsp`
- Create: `legacy/customercare-portlets/src/main/webapp/css/crm.css`, `js/crm.js`
- Create: `legacy/customercare-portlets/src/main/resources/messages.properties`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/LastSearchCriteria.java`, `LastViewedRecord.java`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/header/HeaderController.java`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/header/HeaderControllerTest.java`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/PortletContextWiringTest.java`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/MessageKeysTest.java`

**Interfaces:**
- Consumes: všetko z Taskov 1–8 (beany: `PortletRequestAwareManager`, `PortalServiceLocator`, `PortletSessionStore`, `RedisSessionStore`, `ReadSourceFlag`, `DualSessionStore`, `SharedKeys`, `SessionManagerCustomImpl`, `InMemoryAclService`, `OperatorAcl`, `PortletAccessGuard`, `MessageSourceCustom`, `DispatcherPortletCustom`, `SimpleMappingExceptionResolverCustom`, `BaseCrmController`)
- Produces:
  - Root beany (mená): `clock`, `objectMapper`, `requestAwareManager`, `portalSessionManager`, `jedisPool`, `sharedKeys`, `portletSessionStore`, `redisSessionStore`, `readSourceFlag`, `sessionStore`, `sessionManager`, `aclService`, `operatorAcl`, `portletAccessGuard`, `messageSource`; backend beany pribudnú v `backend-context.xml` (Task 10, 14)
  - System properties: `sso.portal.api-url` (povinná), `sso.portal.login-url` (default `/sso/login`), `crm.redis.host` (default `localhost`), `crm.redis.port` (default `6379`)
  - Portlety `blacklist` (privilégium `BLACKLIST_VIEW`), `dashboard` (`DEACTIVATION`), `header` (`loginRequired=false`); kontexty skenujú `com.example.crm.frontend.<portlet>`; view `x/y` → `/WEB-INF/jsp/x/y.jsp`; chybové view `error/403|404|sso|error`
  - DTO zdieľanej session: `LastSearchCriteria` (`KEY = "BLACKLIST_LAST_CRITERIA"`, polia `firstName`, `lastName`, `identifierValue`, `state` ako `String`), `LastViewedRecord` (`KEY = "LAST_VIEWED_RECORD"`, `id` long, `fullName`); registrované v `sharedKeys` pod `APP_…`
  - JS `Crm.removeIdentifier(link)`, `Crm.refreshConditionTypes(select)`, `Crm.earliestDate(form)` – URL čítajú z `data-url` atribútov (Tasky 12–14)
  - Hlavička: `operator`, `customer`, `lastViewed`, `messages`, `loginUrl`/`changeCustomerUrl`, ladiace `readSource` a `ssoId`

- [ ] **Step 1: Pridaj modul a vytvor POM**

V `legacy/pom.xml` doplň do `<modules>`:

```xml
        <module>customercare-portlets</module>
```

`legacy/customercare-portlets/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example.crm.legacy</groupId>
        <artifactId>legacy-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <!-- Portlet WAR ako legacy CP: portlety, JSP, Spring kontexty, in-memory backend, hook. -->
    <artifactId>customercare-portlets</artifactId>
    <packaging>war</packaging>

    <dependencies>
        <dependency>
            <groupId>com.example.crm.legacy</groupId>
            <artifactId>frontend-base-customization</artifactId>
        </dependency>
        <dependency>
            <groupId>com.example.crm.legacy</groupId>
            <artifactId>sso-portal-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-webmvc-portlet</artifactId>
        </dependency>
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>jstl</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.liferay.portal</groupId>
            <artifactId>portal-service</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.portlet</groupId>
            <artifactId>portlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.servlet</groupId>
            <artifactId>javax.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>javax.servlet.jsp</groupId>
            <artifactId>jsp-api</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <finalName>customercare-portlet</finalName>
    </build>
</project>
```

- [ ] **Step 2: Napíš padajúce testy**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/header/HeaderControllerTest.java`:

```java
package com.example.crm.frontend.header;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.session.ReadSource;
import com.example.crm.frontend.base.session.ReadSourceFlag;
import com.example.crm.frontend.base.session.RequestAwareManager;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.session.UiMessage;
import com.example.crm.frontend.blacklist.LastViewedRecord;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;

class HeaderControllerTest {

    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final ReadSourceFlag flag = mock(ReadSourceFlag.class);
    private final RequestAwareManager requests = mock(RequestAwareManager.class);
    private final HeaderController controller = new HeaderController(flag, requests);
    private final ExtendedModelMap model = new ExtendedModelMap();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(mock(OperatorAcl.class));
        when(flag.current()).thenReturn(ReadSource.PORTLET);
        when(sessionManager.getLoginUrl()).thenReturn("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
    }

    @Test
    @DisplayName("Neprihlásený: výzva s login URL, bez operátora")
    void should_offerLogin_when_notLoggedIn() {
        String view = controller.view(model);

        assertThat(view).isEqualTo("header/view");
        assertThat(model.get("operator")).isNull();
        assertThat(model.get("loginUrl")).isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
    }

    @Test
    @DisplayName("Prihlásený: operátor, zákazník, posledný záznam, hlášky a ladiace údaje o session")
    void should_showContextAndSessionDebug_when_loggedIn() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Ján Vzorový"));
        when(sessionManager.getGlobalAttribute(LastViewedRecord.KEY)).thenReturn(new LastViewedRecord(1, "Peter Vzorka"));
        when(sessionManager.popMessages()).thenReturn(Collections.singletonList(new UiMessage(UiMessage.Level.INFO, "k")));
        when(requests.getSsoSessionId()).thenReturn("s-1");
        when(flag.current()).thenReturn(ReadSource.REDIS);

        controller.view(model);

        assertThat(((PortalOperator) model.get("operator")).getDisplayName()).isEqualTo("Správca");
        assertThat(((PortalCustomer) model.get("customer")).getCustomerId()).isEqualTo("C-1001");
        assertThat(((LastViewedRecord) model.get("lastViewed")).getFullName()).isEqualTo("Peter Vzorka");
        assertThat((java.util.List<?>) model.get("messages")).hasSize(1);
        assertThat(model.get("changeCustomerUrl")).isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
        assertThat(model.get("readSource")).isEqualTo(ReadSource.REDIS);
        assertThat(model.get("ssoId")).isEqualTo("s-1");
    }
}
```

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/PortletContextWiringTest.java`:

```java
package com.example.crm.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.frontend.base.i18n.MessageSourceCustom;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.session.SharedKeys;
import com.example.crm.frontend.base.web.PortletAccessGuard;
import com.example.crm.frontend.base.web.SimpleMappingExceptionResolverCustom;
import com.example.sso.api.PortalServiceLocator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResourceLoader;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.portlet.MockPortletConfig;
import org.springframework.mock.web.portlet.MockPortletContext;
import org.springframework.web.context.support.XmlWebApplicationContext;
import org.springframework.web.portlet.context.XmlPortletApplicationContext;

/**
 * Načíta skutočné XML kontexty WAR-u (root + každý portlet) bez portálu. Preklep v XML alebo nevyriešiteľný
 * {@code @Autowired} v controlleri sa tak ukáže pri {@code mvn verify}, nie až v logu Liferay po deployi.
 */
class PortletContextWiringTest {

    private static final String WEBAPP = "src/main/webapp";

    @BeforeAll
    static void systemProperties() {
        System.setProperty(PortalServiceLocator.API_URL_PROPERTY, "http://127.0.0.1:1/sso");
    }

    @AfterAll
    static void cleanUp() {
        System.clearProperty(PortalServiceLocator.API_URL_PROPERTY);
        PortalServiceLocator.setPortalSessionManager(null);
    }

    @Test
    @DisplayName("Root kontext obsahuje fasádu, strážcu, i18n a register zdieľaných kľúčov")
    void should_wireRootBeans_when_applicationContextLoaded() {
        try (XmlWebApplicationContext root = root()) {
            assertThat(root.getBean(SessionManagerCustom.class)).isNotNull();
            assertThat(root.getBean(PortletAccessGuard.class)).isNotNull();
            assertThat(root.getBean("messageSource")).isInstanceOf(MessageSourceCustom.class);
            SharedKeys keys = root.getBean(SharedKeys.class);
            assertThat(keys.isShared("APP_BLACKLIST_LAST_CRITERIA")).isTrue();
            assertThat(keys.isShared("APP_LAST_VIEWED_RECORD")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"blacklist", "dashboard", "header"})
    @DisplayName("Kontext každého portletu sa načíta nad root kontextom a má exception resolver")
    void should_loadPortletContext_when_definedInPortletXml(String portlet) {
        try (XmlWebApplicationContext root = root()) {
            MockPortletContext portletContext = new MockPortletContext(WEBAPP, new FileSystemResourceLoader());
            XmlPortletApplicationContext context = new XmlPortletApplicationContext();
            context.setPortletContext(portletContext);
            context.setPortletConfig(new MockPortletConfig(portletContext, portlet));
            context.setParent(root);
            context.setConfigLocation("/WEB-INF/" + portlet + "-portlet.xml");
            context.refresh();

            assertThat(context.getBean(SimpleMappingExceptionResolverCustom.class)).isNotNull();
            context.close();
        }
    }

    private static XmlWebApplicationContext root() {
        XmlWebApplicationContext root = new XmlWebApplicationContext();
        root.setServletContext(new MockServletContext(WEBAPP, new FileSystemResourceLoader()));
        root.setConfigLocation("/WEB-INF/applicationContext.xml");
        root.refresh();
        return root;
    }
}
```

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/MessageKeysTest.java`:

```java
package com.example.crm.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Každý pevný kľúč z JSP ({@code key="…"} bez EL) musí byť v messages.properties – preklep inak uvidí až používateľ. */
class MessageKeysTest {

    private static final Pattern KEY = Pattern.compile("(?:key|code)=\"([a-zA-Z0-9_.]+)\"");

    @Test
    @DisplayName("Všetky i18n kľúče použité v JSP existujú")
    void should_defineEveryKey_when_usedInJsp() throws IOException {
        Properties messages = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("messages.properties");
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            messages.load(reader);
        }
        List<String> missing = new ArrayList<>();
        for (Path jsp : jsps()) {
            Matcher matcher = KEY.matcher(new String(Files.readAllBytes(jsp), StandardCharsets.UTF_8));
            while (matcher.find()) {
                if (!messages.containsKey(matcher.group(1))) {
                    missing.add(jsp.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    private static List<Path> jsps() throws IOException {
        try (Stream<Path> files = Files.walk(Paths.get("src/main/webapp/WEB-INF/jsp"))) {
            return files.filter(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".jspf"))
                    .collect(Collectors.toList());
        }
    }
}
```

- [ ] **Step 3: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class HeaderController`.

- [ ] **Step 4: DTO zdieľanej session a `HeaderController`**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/LastSearchCriteria.java`:

```java
package com.example.crm.frontend.blacklist;

import java.io.Serializable;

/**
 * Posledné kritériá vyhľadávania v zdieľanej session. Len reťazce – prečíta ich Java 8 aj nová appka (Java 21)
 * bez zdieľania doménových tried. Formulár samotný ostáva v {@code @SessionAttributes} (mimo zdieľania).
 */
public class LastSearchCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Kľúč vo fasáde (v úložisku {@code APP_BLACKLIST_LAST_CRITERIA}). */
    public static final String KEY = "BLACKLIST_LAST_CRITERIA";

    private String firstName;
    private String lastName;
    private String identifierValue;
    private String state;

    public LastSearchCriteria() {
    }

    public LastSearchCriteria(String firstName, String lastName, String identifierValue, String state) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.identifierValue = identifierValue;
        this.state = state;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public void setIdentifierValue(String identifierValue) {
        this.identifierValue = identifierValue;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
```

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/LastViewedRecord.java`:

```java
package com.example.crm.frontend.blacklist;

import java.io.Serializable;

/** Naposledy otvorený záznam blacklistu – hlavička ho ukazuje na každej stránke (aj v novej appke po migrácii). */
public class LastViewedRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Kľúč vo fasáde (v úložisku {@code APP_LAST_VIEWED_RECORD}). */
    public static final String KEY = "LAST_VIEWED_RECORD";

    private long id;
    private String fullName;

    public LastViewedRecord() {
    }

    public LastViewedRecord(long id, String fullName) {
        this.id = id;
        this.fullName = fullName;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
```

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/header/HeaderController.java`:

```java
package com.example.crm.frontend.header;

import com.example.crm.frontend.base.session.ReadSourceFlag;
import com.example.crm.frontend.base.session.RequestAwareManager;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.web.BaseCrmController;
import com.example.crm.frontend.blacklist.LastViewedRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;

/**
 * Portlet hlavičky, ktorý theme vkladá na každú stránku ({@code $theme.runtime}). Ekvivalent legacy portletov
 * „hlavička / kontext zákazníka / info hlášky“ – portál skladá stránku z portletov; po migrácii z toho bude
 * fragment layout šablóny. Ladiaci riadok (zdroj čítania, SSO_ID) je len pre demo zdieľanej session.
 */
@Controller
@RequestMapping("VIEW")
public class HeaderController extends BaseCrmController {

    private final ReadSourceFlag readSourceFlag;
    private final RequestAwareManager requests;

    @Autowired
    public HeaderController(ReadSourceFlag readSourceFlag, RequestAwareManager requests) {
        this.readSourceFlag = readSourceFlag;
        this.requests = requests;
    }

    /** Hlavička: operátor, zákazník, posledný záznam, hlášky z action fázy. */
    @RenderMapping
    public String view(Model model) {
        SessionManagerCustom session = getSessionManager();
        model.addAttribute("operator", session.getOperator());
        model.addAttribute("customer", session.getCustomer());
        if (session.getOperator() == null) {
            model.addAttribute("loginUrl", session.getLoginUrl());
        } else {
            // Zákazníka mení SSO portál, nie CRM – jeho stránka pri platnej session ponúkne zmenu.
            model.addAttribute("changeCustomerUrl", session.getLoginUrl());
        }
        model.addAttribute("lastViewed", session.getGlobalAttribute(LastViewedRecord.KEY));
        model.addAttribute("messages", session.popMessages());
        model.addAttribute("readSource", readSourceFlag.current());
        model.addAttribute("ssoId", requests.getSsoSessionId());
        return "header/view";
    }
}
```

- [ ] **Step 5: Deskriptory WAR-u a portletov**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/web.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
         version="3.0">
    <display-name>customercare-portlets</display-name>

    <!-- Root kontext WAR-u: fasáda, ACL, backend. Kontexty portletov (*-portlet.xml) sú jeho deti. -->
    <context-param>
        <param-name>contextConfigLocation</param-name>
        <param-value>/WEB-INF/applicationContext.xml</param-value>
    </context-param>
    <listener>
        <listener-class>org.springframework.web.context.ContextLoaderListener</listener-class>
    </listener>

    <!-- Spring Portlet MVC renderuje JSP cez tento servlet (portlet sám JSP view nevie vykresliť). -->
    <servlet>
        <servlet-name>ViewRendererServlet</servlet-name>
        <servlet-class>org.springframework.web.servlet.ViewRendererServlet</servlet-class>
        <load-on-startup>1</load-on-startup>
    </servlet>
    <servlet-mapping>
        <servlet-name>ViewRendererServlet</servlet-name>
        <url-pattern>/WEB-INF/servlet/view</url-pattern>
    </servlet-mapping>
</web-app>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/applicationContext.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xmlns:context="http://www.springframework.org/schema/context"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd
                           http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context.xsd">

    <!-- XML konfigurácia ako v legacy. System properties (CATALINA_OPTS) nahrádzajú profily root POM. -->
    <context:property-placeholder/>

    <!-- Backend „v tom istom JVM“ – v legacy import kontextov z backend JAR-ov. -->
    <import resource="backend-context.xml"/>

    <bean id="clock" class="java.time.Clock" factory-method="systemDefaultZone"/>
    <bean id="objectMapper" class="com.fasterxml.jackson.databind.ObjectMapper"/>

    <bean id="requestAwareManager" class="com.example.crm.frontend.base.session.PortletRequestAwareManager"/>

    <!-- API SSO portálu z tomcat/lib/ext cez service locator (legacy: provided JAR + locator). -->
    <bean id="portalSessionManager" class="com.example.sso.api.PortalServiceLocator"
          factory-method="getPortalSessionManager"/>

    <bean id="jedisPool" class="redis.clients.jedis.JedisPool" destroy-method="close">
        <constructor-arg index="0"><bean class="redis.clients.jedis.JedisPoolConfig"/></constructor-arg>
        <constructor-arg index="1" type="java.lang.String" value="${crm.redis.host:localhost}"/>
        <constructor-arg index="2" type="int" value="${crm.redis.port:6379}"/>
        <constructor-arg index="3" type="int" value="1000"/>
    </bean>

    <!-- Čo sa zdieľa so Spring appkou (podprojekt 2): len tieto kľúče + hlášky. -->
    <bean id="sharedKeys" class="com.example.crm.frontend.base.session.SharedKeys">
        <property name="types">
            <map>
                <entry key="APP_BLACKLIST_LAST_CRITERIA" value="com.example.crm.frontend.blacklist.LastSearchCriteria"/>
                <entry key="APP_LAST_VIEWED_RECORD" value="com.example.crm.frontend.blacklist.LastViewedRecord"/>
            </map>
        </property>
    </bean>

    <bean id="portletSessionStore" class="com.example.crm.frontend.base.session.PortletSessionStore">
        <constructor-arg ref="requestAwareManager"/>
    </bean>
    <bean id="redisSessionStore" class="com.example.crm.frontend.base.session.RedisSessionStore">
        <constructor-arg ref="jedisPool"/>
        <constructor-arg ref="requestAwareManager"/>
        <constructor-arg ref="sharedKeys"/>
    </bean>
    <bean id="readSourceFlag" class="com.example.crm.frontend.base.session.ReadSourceFlag">
        <constructor-arg ref="jedisPool"/>
        <constructor-arg ref="requestAwareManager"/>
    </bean>
    <!-- Duálna session (slide 12): zápis do oboch, čítanie podľa flagu v Redise. -->
    <bean id="sessionStore" class="com.example.crm.frontend.base.session.DualSessionStore">
        <constructor-arg ref="portletSessionStore"/>
        <constructor-arg ref="redisSessionStore"/>
        <constructor-arg ref="readSourceFlag"/>
    </bean>

    <bean id="sessionManager" class="com.example.crm.frontend.base.session.SessionManagerCustomImpl">
        <constructor-arg ref="requestAwareManager"/>
        <constructor-arg ref="portalSessionManager"/>
        <constructor-arg ref="sessionStore"/>
    </bean>

    <bean id="aclService" class="com.example.crm.frontend.base.acl.InMemoryAclService"/>
    <bean id="operatorAcl" class="com.example.crm.frontend.base.acl.OperatorAcl">
        <constructor-arg ref="sessionManager"/>
        <constructor-arg ref="aclService"/>
    </bean>
    <bean id="portletAccessGuard" class="com.example.crm.frontend.base.web.PortletAccessGuard">
        <constructor-arg ref="sessionManager"/>
        <constructor-arg ref="operatorAcl"/>
    </bean>

    <bean id="messageSource" class="com.example.crm.frontend.base.i18n.MessageSourceCustom"/>
</beans>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd">

    <!--
      In-memory náhrada backendu. V legacy tento kontext pochádza z backend JAR-u a obsahuje DAO, transakcie
      a klientov integračnej vrstvy – všetko v jednom JVM s frontendom (riziko č. 2 z analýzy).
    -->
</beans>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/blacklist-portlet.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xmlns:context="http://www.springframework.org/schema/context"
       xsi:schemaLocation="http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd
                           http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context.xsd">

    <!-- Vlastný Spring kontext portletu (legacy: <portlet>-portlet.xml). Po migrácii jeden DispatcherServlet. -->
    <context:component-scan base-package="com.example.crm.frontend.blacklist"/>

    <bean class="org.springframework.web.servlet.view.InternalResourceViewResolver">
        <property name="viewClass" value="org.springframework.web.servlet.view.JstlView"/>
        <property name="prefix" value="/WEB-INF/jsp/"/>
        <property name="suffix" value=".jsp"/>
    </bean>

    <bean class="com.example.crm.frontend.base.web.SimpleMappingExceptionResolverCustom">
        <property name="exceptionMappings">
            <props>
                <prop key="com.example.crm.frontend.base.acl.AclDeniedException">error/403</prop>
                <prop key="com.example.crm.frontend.base.web.NotFoundException">error/404</prop>
                <prop key="org.springframework.beans.TypeMismatchException">error/404</prop>
                <prop key="org.springframework.web.portlet.bind.MissingPortletRequestParameterException">error/404</prop>
                <prop key="com.example.sso.api.SsoUnavailableException">error/sso</prop>
            </props>
        </property>
        <property name="defaultErrorView" value="error/error"/>
    </bean>
</beans>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/dashboard-portlet.xml` – rovnaký obsah ako `blacklist-portlet.xml`, len riadok skenu je:

```xml
    <context:component-scan base-package="com.example.crm.frontend.dashboard"/>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/header-portlet.xml` – rovnaký obsah ako `blacklist-portlet.xml`, len riadok skenu je:

```xml
    <context:component-scan base-package="com.example.crm.frontend.header"/>
```

> Všetky tri súbory napíš celé (nie odkazom) – líšia sa len balíkom v `component-scan`.

`legacy/customercare-portlets/src/main/webapp/WEB-INF/portlet.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<portlet-app xmlns="http://java.sun.com/xml/ns/portlet/portlet-app_2_0.xsd"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://java.sun.com/xml/ns/portlet/portlet-app_2_0.xsd http://java.sun.com/xml/ns/portlet/portlet-app_2_0.xsd"
             version="2.0">

    <portlet>
        <portlet-name>blacklist</portlet-name>
        <display-name>Blacklist ne-zákazníkov</display-name>
        <portlet-class>com.example.crm.frontend.base.web.DispatcherPortletCustom</portlet-class>
        <init-param><name>contextConfigLocation</name><value>/WEB-INF/blacklist-portlet.xml</value></init-param>
        <init-param><name>requiredPrivilege</name><value>BLACKLIST_VIEW</value></init-param>
        <expiration-cache>0</expiration-cache>
        <supports><mime-type>text/html</mime-type><portlet-mode>view</portlet-mode></supports>
        <portlet-info><title>Blacklist ne-zákazníkov</title></portlet-info>
        <security-role-ref><role-name>guest</role-name></security-role-ref>
        <security-role-ref><role-name>user</role-name></security-role-ref>
    </portlet>

    <portlet>
        <portlet-name>dashboard</portlet-name>
        <display-name>Dashboard zákazníka</display-name>
        <portlet-class>com.example.crm.frontend.base.web.DispatcherPortletCustom</portlet-class>
        <init-param><name>contextConfigLocation</name><value>/WEB-INF/dashboard-portlet.xml</value></init-param>
        <init-param><name>requiredPrivilege</name><value>DEACTIVATION</value></init-param>
        <expiration-cache>0</expiration-cache>
        <supports><mime-type>text/html</mime-type><portlet-mode>view</portlet-mode></supports>
        <portlet-info><title>Dashboard zákazníka</title></portlet-info>
        <security-role-ref><role-name>guest</role-name></security-role-ref>
        <security-role-ref><role-name>user</role-name></security-role-ref>
    </portlet>

    <portlet>
        <portlet-name>header</portlet-name>
        <display-name>Hlavička CRM</display-name>
        <portlet-class>com.example.crm.frontend.base.web.DispatcherPortletCustom</portlet-class>
        <init-param><name>contextConfigLocation</name><value>/WEB-INF/header-portlet.xml</value></init-param>
        <init-param><name>loginRequired</name><value>false</value></init-param>
        <expiration-cache>0</expiration-cache>
        <supports><mime-type>text/html</mime-type><portlet-mode>view</portlet-mode></supports>
        <portlet-info><title>Hlavička CRM</title></portlet-info>
        <security-role-ref><role-name>guest</role-name></security-role-ref>
        <security-role-ref><role-name>user</role-name></security-role-ref>
    </portlet>
</portlet-app>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/liferay-portlet.xml` (poradie elementov určuje DTD 6.2):

```xml
<?xml version="1.0"?>
<!DOCTYPE liferay-portlet-app PUBLIC "-//Liferay//DTD Portlet Application 6.2.0//EN"
        "http://www.liferay.com/dtd/liferay-portlet-app_6_2_0.dtd">
<liferay-portlet-app>
    <!-- requires-namespaced-parameters=false: formuláre posielajú mená polí bez namespace portletu (ako legacy). -->
    <portlet>
        <portlet-name>blacklist</portlet-name>
        <instanceable>false</instanceable>
        <requires-namespaced-parameters>false</requires-namespaced-parameters>
        <header-portlet-css>/css/crm.css</header-portlet-css>
        <footer-portlet-javascript>/js/crm.js</footer-portlet-javascript>
        <css-class-wrapper>crm-portlet</css-class-wrapper>
    </portlet>
    <portlet>
        <portlet-name>dashboard</portlet-name>
        <instanceable>false</instanceable>
        <requires-namespaced-parameters>false</requires-namespaced-parameters>
        <header-portlet-css>/css/crm.css</header-portlet-css>
        <footer-portlet-javascript>/js/crm.js</footer-portlet-javascript>
        <css-class-wrapper>crm-portlet</css-class-wrapper>
    </portlet>
    <!-- Hlavičku vkladá theme ako runtime portlet (nie je na stránke) – hosť ju vidí len s add-default-resource. -->
    <portlet>
        <portlet-name>header</portlet-name>
        <instanceable>false</instanceable>
        <requires-namespaced-parameters>false</requires-namespaced-parameters>
        <header-portlet-css>/css/crm.css</header-portlet-css>
        <css-class-wrapper>crm-header-portlet</css-class-wrapper>
        <add-default-resource>true</add-default-resource>
    </portlet>
    <role-mapper><role-name>guest</role-name><role-link>Guest</role-link></role-mapper>
    <role-mapper><role-name>user</role-name><role-link>User</role-link></role-mapper>
</liferay-portlet-app>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/liferay-display.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE display PUBLIC "-//Liferay//DTD Display 6.2.0//EN" "http://www.liferay.com/dtd/liferay-display_6_2_0.dtd">
<display>
    <category name="CRM">
        <portlet id="blacklist"/>
        <portlet id="dashboard"/>
        <portlet id="header"/>
    </category>
</display>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/liferay-plugin-package.properties`:

```properties
name=Customer care portlets (demo)
module-group-id=com.example.crm
module-incremental-version=1
tags=crm
short-description=Legacy CRM portlety (blacklist, dashboard, hlavička) – demo migrácie
author=demo
licenses=MIT
liferay-versions=6.2.0+
security-manager-enabled=false
# Ako legacy: portlety vyžadujú nasadený theme (jeho šablóna vkladá portlet hlavičky).
required-deployment-contexts=customercare-theme
```

- [ ] **Step 6: Texty, spoločné JSP, CSS a JS**

`legacy/customercare-portlets/src/main/resources/messages.properties`:

```properties
# Texty UI (UTF-8). V legacy sa čítajú z DB a CMS cez vlastný MessageSource.
common.loginRequired=Na prácu s CRM sa prihláste cez SSO portál.
common.login=Prihlásiť
common.logout=Odhlásiť
common.changeCustomer=Zmeniť zákazníka
common.operator=Operátor
common.customer=Zákazník
common.noCustomer=bez zákazníka
common.lastViewed=Naposledy otvorený záznam
common.sessionDebug=Zdieľaná session
common.readSource=zdroj čítania
common.ssoSession=SSO_ID
common.back=Späť
error.403=Nemáte oprávnenie {0}.
error.404=Požadovaný záznam neexistuje alebo nepatrí aktuálnemu zákazníkovi.
error.sso=SSO portál je nedostupný. Skúste to o chvíľu znova.
error.generic=Nastala neočakávaná chyba.
typeMismatch=Neplatná hodnota.
typeMismatch.java.time.LocalDate=Neplatný dátum (formát RRRR-MM-DD).
validation.required=Povinný údaj.
validation.validToBeforeValidFrom=Dátum „platné do“ nesmie byť pred dátumom „platné od“.
validation.identifierFormat=Hodnota nezodpovedá formátu zvoleného typu identifikátora.
validation.conditionNotAllowed=Podmienka nie je pre zvolený dôvod povolená.
validation.noticeInFuture=Dátum podania nesmie byť v budúcnosti.
validation.effectiveTooEarly=Najskorší možný dátum účinnosti je {0}.

blacklist.search.title=Blacklist ne-zákazníkov – vyhľadávanie
blacklist.search.firstName=Meno
blacklist.search.lastName=Priezvisko
blacklist.search.identifier=Identifikátor
blacklist.search.state=Stav
blacklist.search.anyState=– ľubovoľný –
blacklist.search.submit=Hľadať
blacklist.search.clear=Vymazať
blacklist.search.create=Nový záznam
blacklist.search.noCriteria=Zadajte aspoň jedno kritérium.
blacklist.search.noResult=Nenašiel sa žiadny záznam.
blacklist.result.title=Výsledky vyhľadávania
blacklist.result.count=Počet záznamov: {0}
blacklist.result.name=Meno a priezvisko
blacklist.result.birthDate=Dátum narodenia
blacklist.result.state=Stav
blacklist.result.validity=Platnosť
blacklist.result.detail=Detail
blacklist.result.newSearch=Nové vyhľadávanie
blacklist.state.ACTIVE=Aktívny
blacklist.state.INACTIVE=Neaktívny
blacklist.reason.FRAUD=Podvod
blacklist.reason.DEBT=Neplatenie
blacklist.reason.ABUSE=Zneužitie služieb
blacklist.condition.BLOCK_ALL=Zákaz objednávok
blacklist.condition.REQUIRE_DEPOSIT=Objednávka len so zálohou
blacklist.condition.MANUAL_APPROVAL=Ručné schválenie
blacklist.identifier.BIRTH_CODE=Rodné číslo
blacklist.identifier.ID_CARD=Občiansky preukaz
blacklist.identifier.PASSPORT=Pas
blacklist.identifier.COMPANY_REG_NO=IČO
blacklist.detail.title=Detail záznamu
blacklist.detail.reason=Dôvod
blacklist.detail.condition=Podmienka
blacklist.detail.validFrom=Platné od
blacklist.detail.validTo=Platné do
blacklist.detail.unlimited=neobmedzene
blacklist.detail.identifiers=Identifikátory
blacklist.detail.removeIdentifier=Odstrániť
blacklist.detail.notes=Poznámky
blacklist.detail.noNotes=Bez poznámok.
blacklist.detail.addNote=Pridať poznámku
blacklist.detail.change=Zmena stavu a platnosti
blacklist.detail.save=Uložiť
blacklist.detail.changed=Stav záznamu bol zmenený.
blacklist.detail.noteAdded=Poznámka bola pridaná.
blacklist.detail.noteEmpty=Poznámka nesmie byť prázdna.
blacklist.detail.lastIdentifier=Posledný identifikátor nie je možné odstrániť.
blacklist.create.title=Nový záznam blacklistu
blacklist.create.birthDate=Dátum narodenia
blacklist.create.reason=Dôvod
blacklist.create.condition=Podmienka
blacklist.create.identifierType=Typ identifikátora
blacklist.create.identifierValue=Hodnota identifikátora
blacklist.create.submit=Vytvoriť
blacklist.create.cancel=Zrušiť
blacklist.create.created=Záznam bol vytvorený.
blacklist.create.choose=– vyberte –

dashboard.title=Produkty zákazníka
dashboard.products.name=Produkt
dashboard.products.type=Typ
dashboard.products.description=Popis
dashboard.products.deactivate=Zrušiť
dashboard.products.empty=Zákazník nemá žiadne produkty.
dashboard.type.TARIFF=Tarif
dashboard.type.PRODUCT=Produkt
dashboard.deactivation.title=Žiadosť o zrušenie
dashboard.deactivation.product=Produkt
dashboard.deactivation.type=Typ žiadosti
dashboard.deactivation.noticeDate=Dátum podania
dashboard.deactivation.effectiveDate=Ku dňu
dashboard.deactivation.earliest=Najskôr:
dashboard.deactivation.submit=Odoslať žiadosť
dashboard.deactivation.submitted=Žiadosť o zrušenie bola prijatá.
dashboard.deactivation.done=Žiadosť č. {0} na produkt {1} je zaevidovaná ku dňu {2}.
dashboard.deactivation.backToProducts=Späť na produkty
dashboard.deactivationType.TRANSFER_TO_CUSTOMER=Prevod na iného zákazníka
dashboard.deactivationType.CUSTOMER_DEACTIVATION=Zrušenie
dashboard.deactivationType.CUSTOMER_POSTPRE=Prevod na predplatenú kartu
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/init.jspf`:

```jsp
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Spoločné taglibs (legacy: init.jsp). portal: a common: sú vlastné tagy z frontend-base-customization. --%>
<%@ taglib uri="http://java.sun.com/portlet_2_0" prefix="portlet" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="http://www.springframework.org/tags/form" prefix="form" %>
<%@ taglib uri="http://example.com/crm/tags/portal" prefix="portal" %>
<%@ taglib uri="http://example.com/crm/tags/common" prefix="common" %>
<portlet:defineObjects/>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/common/login-required.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<div class="crm-login-required">
    <p><common:message key="common.loginRequired"/></p>
    <a id="loginLink" class="crm-button" href="${fn:escapeXml(loginUrl)}"><common:message key="common.login"/></a>
</div>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/error/403.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<div class="portlet-msg-error crm-error-403">
    <common:message key="error.403"><common:msgarg value="${privilege}"/></common:message>
</div>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/error/404.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:renderURL var="homeUrl"/>
<div class="portlet-msg-error crm-error-404">
    <common:message key="error.404"/>
    <a href="${homeUrl}"><common:message key="common.back"/></a>
</div>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/error/sso.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<div class="portlet-msg-error crm-error-sso"><common:message key="error.sso"/></div>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/error/error.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<div class="portlet-msg-error crm-error"><common:message key="error.generic"/></div>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/header/view.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<div class="crm-header">
    <c:choose>
        <c:when test="${empty operator}">
            <span><common:message key="common.loginRequired"/></span>
            <a id="headerLogin" class="crm-button" href="${fn:escapeXml(loginUrl)}"><common:message key="common.login"/></a>
        </c:when>
        <c:otherwise>
            <span id="headerOperator"><common:message key="common.operator"/>: <c:out value="${operator.displayName}"/></span>
            <span id="headerCustomer"><common:message key="common.customer"/>:
                <c:choose>
                    <c:when test="${empty customer}"><common:message key="common.noCustomer"/></c:when>
                    <c:otherwise><c:out value="${customer.displayName}"/> (<c:out value="${customer.customerId}"/>)</c:otherwise>
                </c:choose>
            </span>
            <c:if test="${not empty lastViewed}">
                <span id="headerLastViewed"><common:message key="common.lastViewed"/>: <c:out value="${lastViewed.fullName}"/></span>
            </c:if>
            <a id="changeCustomer" href="${fn:escapeXml(changeCustomerUrl)}"><common:message key="common.changeCustomer"/></a>
            <form id="logoutForm" method="post" action="/sso/logout" class="crm-inline">
                <button type="submit"><common:message key="common.logout"/></button>
            </form>
        </c:otherwise>
    </c:choose>
    <%-- Len pre demo zdieľanej session: odkiaľ fasáda práve číta a pod akým kľúčom je hash v Redise. --%>
    <div id="sessionDebug" class="crm-session-debug">
        <common:message key="common.sessionDebug"/>: <common:message key="common.readSource"/> = <c:out value="${readSource}"/>,
        <common:message key="common.ssoSession"/> = <c:out value="${empty ssoId ? '-' : ssoId}"/>
    </div>
    <c:forEach items="${messages}" var="m">
        <div class="${m.level == 'ERROR' ? 'portlet-msg-error' : 'portlet-msg-success'} crm-message">
            <common:message key="${m.key}"/>
        </div>
    </c:forEach>
</div>
```

`legacy/customercare-portlets/src/main/webapp/css/crm.css`:

```css
/* Neutrálny vzhľad bez brandingu. */
.crm-header { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; padding: 8px 12px; background: #eef2f6; border-bottom: 1px solid #c9d3dd; }
.crm-header .crm-message { flex-basis: 100%; }
.crm-session-debug { flex-basis: 100%; font: 12px monospace; color: #555; }
.crm-inline { display: inline; margin: 0; }
.crm-button { display: inline-block; padding: 4px 10px; border: 1px solid #4a6a8a; border-radius: 3px; text-decoration: none; }
.crm-portlet table { border-collapse: collapse; width: 100%; }
.crm-portlet th, .crm-portlet td { border: 1px solid #d5dde5; padding: 4px 8px; text-align: left; }
.crm-portlet .field-error { color: #b00020; }
.crm-portlet fieldset { border: 1px solid #d5dde5; margin: 8px 0; padding: 8px; }
.crm-portlet label { display: inline-block; min-width: 160px; }
#crm-ajax-error { display: none; color: #b00020; }
```

`legacy/customercare-portlets/src/main/webapp/js/crm.js`:

```js
/*
 * AJAX volania resource fázy portletov. URL dodáva JSP v data-url (tag portal:resourceURL), JS ich neskladá.
 * Legacy používa jQuery; demo drží závislosti minimálne (čistý XMLHttpRequest, beží aj v starom prehliadači).
 */
var Crm = window.Crm || {};

Crm.request = function (method, url, onSuccess) {
    var xhr = new XMLHttpRequest();
    xhr.open(method, url, true);
    xhr.onreadystatechange = function () {
        if (xhr.readyState !== 4) {
            return;
        }
        if (xhr.status === 200) {
            onSuccess(xhr.responseText);
        } else if (xhr.status === 401) {
            // SSO session vypršala – znovunačítanie stránky ukáže výzvu na prihlásenie.
            window.location.reload();
        } else {
            var box = document.getElementById('crm-ajax-error');
            if (box) {
                box.textContent = 'Chyba ' + xhr.status;
                box.style.display = 'block';
            }
        }
    };
    xhr.send(null);
};

Crm.withParams = function (url, params) {
    var query = [];
    for (var name in params) {
        if (params.hasOwnProperty(name)) {
            query.push(encodeURIComponent(name) + '=' + encodeURIComponent(params[name]));
        }
    }
    return url + (url.indexOf('?') < 0 ? '?' : '&') + query.join('&');
};

/** Odstránenie identifikátora: POST na resource URL, odpoveď je HTML fragment zoznamu. */
Crm.removeIdentifier = function (link) {
    Crm.request('POST', link.getAttribute('data-url'), function (html) {
        document.getElementById(link.getAttribute('data-target')).innerHTML = html;
    });
    return false;
};

/** Povolené podmienky podľa dôvodu: GET, odpoveď je HTML fragment <select>. */
Crm.refreshConditionTypes = function (select) {
    var url = Crm.withParams(select.getAttribute('data-url'), {stateReason: select.value});
    Crm.request('GET', url, function (html) {
        document.getElementById(select.getAttribute('data-target')).innerHTML = html;
    });
};

/** Najskorší dátum účinnosti: GET, odpoveď je JSON {"earliestDate":"RRRR-MM-DD"}. */
Crm.earliestDate = function (form) {
    var type = form.querySelector('[name=type]:checked') || form.querySelector('[name=type]');
    var notice = form.querySelector('[name=noticeDate]');
    var effective = form.querySelector('[name=effectiveDate]');
    if (!type || !type.value || !notice.value) {
        return;
    }
    var url = Crm.withParams(form.getAttribute('data-earliest-url'), {type: type.value, noticeDate: notice.value});
    Crm.request('GET', url, function (json) {
        var earliest = JSON.parse(json).earliestDate;
        effective.min = earliest;
        if (!effective.value || effective.value < earliest) {
            effective.value = earliest;
        }
        document.getElementById('earliestHint').textContent = earliest;
    });
};
```

- [ ] **Step 7: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 88 + 2 + 4 + 1 = 95).

> Ak `PortletContextWiringTest` zlyhá na `NoSuchBeanDefinitionException`, skontroluj, že `frontend-base-customization` bol nainštalovaný (Task 8, Step 7) – reactor build z `legacy/pom.xml` ho však vidí aj bez inštalácie.

- [ ] **Step 8: Over balenie WAR-u**

Run: `mvn -q -B -f legacy/pom.xml package -DskipTests && unzip -l legacy/customercare-portlets/target/customercare-portlet.war | grep -E "frontend-base-customization|sso-portal-api|portal-service|jstl|jedis"`
Expected: `WEB-INF/lib/frontend-base-customization-0.1.0-SNAPSHOT.jar`, `jstl-1.2.jar`, `jedis-2.9.0.jar`; **nie** `sso-portal-api` ani `portal-service` (sú `provided` – dodá ich portál).

- [ ] **Step 9: Checkpoint (bez commitu)** – `git status --short`

---

### Task 10: Backend blacklistu (doména + in-memory služba, Java 8)

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/BlacklistState.java`, `StateReason.java`, `ConditionType.java`, `IdentifierType.java`, `Identifier.java`, `Note.java`, `BlacklistRecord.java`, `NewBlacklistRecord.java`, `BlacklistSearchCriteria.java`, `BlacklistRuleException.java`, `BlacklistService.java`, `InMemoryBlacklistService.java`
- Modify: `legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/InMemoryBlacklistServiceTest.java`

**Interfaces:**
- Consumes: `NotFoundException` (Task 5), bean `clock` (Task 9)
- Produces:
  - `enum BlacklistState { ACTIVE, INACTIVE }`, `enum StateReason { FRAUD, DEBT, ABUSE }`, `enum ConditionType { BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL }`
  - `enum IdentifierType { BIRTH_CODE, ID_CARD, PASSPORT, COMPANY_REG_NO; boolean isValid(String) }`
  - `Identifier(long id, IdentifierType type, String value)` – `getId()`, `getType()`, `getValue()`; `Note(String text, String author, LocalDateTime createdAt)` – gettery, `equals`
  - `BlacklistRecord` – `getId()`, `getFirstName()`, `getLastName()`, `getFullName()`, `getBirthDate()`, `getState()`, `getStateReason()`, `getConditionType()`, `getValidFrom()`, `getValidTo()`, `getIdentifiers()`, `getNotes()`
  - `NewBlacklistRecord(String firstName, String lastName, LocalDate birthDate, StateReason, ConditionType, LocalDate validFrom, LocalDate validTo, IdentifierType, String identifierValue)` – gettery
  - `BlacklistSearchCriteria implements Serializable` – get/set `firstName`, `lastName`, `identifierValue`, `state`; `boolean isAnyCriterionFilled()`
  - `BlacklistRuleException(String messageKey)` – `getMessageKey()`
  - `interface BlacklistService`: `List<BlacklistRecord> search(BlacklistSearchCriteria)`, `Optional<BlacklistRecord> findById(long)`, `BlacklistRecord create(NewBlacklistRecord)`, `void change(long id, BlacklistState, LocalDate validTo)`, `void addNote(long id, String text, String author)`, `List<Identifier> removeIdentifier(long recordId, long identifierId)`, `List<ConditionType> allowedConditionTypes(StateReason)` (`null` → všetky)
  - Bean `blacklistService` (`InMemoryBlacklistService(Clock)`) v `backend-context.xml`

Seed dáta (rovnaké ako v pláne podprojektu 2):

| ID | Meno | Stav / dôvod / podmienka | Platnosť | Identifikátory (ID) |
|---|---|---|---|---|
| 1 | Peter Vzorka, 1980-05-12 | ACTIVE / FRAUD / BLOCK_ALL | 2024-01-10 → – | (1) BIRTH_CODE 800512/1234, (2) ID_CARD AB123456 |
| 2 | Mária Príkladová, 1975-11-03 | ACTIVE / DEBT / REQUIRE_DEPOSIT | 2023-06-01 → 2027-06-01 | (3) BIRTH_CODE 755103/2345 |
| 3 | Ján Testovací, 1990-02-20 | INACTIVE / DEBT / MANUAL_APPROVAL | 2022-03-15 → 2024-03-15 | (4) ID_CARD CD987654 |
| 4 | Eva Ukážková, 1988-07-30 | ACTIVE / ABUSE / MANUAL_APPROVAL | 2025-02-01 → – | (5) PASSPORT P1234567 |
| 5 | Tomáš Fiktívny, 1969-12-24 | ACTIVE / FRAUD / BLOCK_ALL | 2021-09-09 → – | (6) BIRTH_CODE 691224/3456, (7) PASSPORT X7654321 |
| 6 | Lucia Demová, 1995-04-18 | INACTIVE / ABUSE / BLOCK_ALL | 2020-01-01 → 2021-01-01 | (8) ID_CARD EF555444 |
| 7 | Karol Modelový, 1983-08-08 | ACTIVE / DEBT / REQUIRE_DEPOSIT | 2025-10-01 → – | (9) COMPANY_REG_NO 12345678 |
| 8 | Peter Vzorkový, 1979-01-01 | ACTIVE / DEBT / MANUAL_APPROVAL | 2024-05-05 → – | (10) BIRTH_CODE 790101/4567 |

Záznam 1 má poznámku „Opakované pokusy o objednávku na cudzie doklady.“ (autor „Systém“, 2024-01-10T09:00).

- [ ] **Step 1: Napíš padajúci test**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/blacklist/InMemoryBlacklistServiceTest.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);

    @Test
    @DisplayName("Seed obsahuje 8 záznamov")
    void should_haveEightRecords_when_seeded() {
        assertThat(service.search(new BlacklistSearchCriteria())).hasSize(8);
    }

    @Test
    @DisplayName("Priezvisko sa hľadá ako podreťazec bez ohľadu na veľkosť písmen")
    void should_matchLastNameContainsIgnoreCase_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    @DisplayName("Hľadanie funguje s diakritikou")
    void should_matchDiacritics_when_searchingFirstName() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("mária"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Identifikátor sa hľadá presne")
    void should_matchIdentifierExactly_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("AB123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    @DisplayName("Hľadanie podľa stavu")
    void should_filterByState_when_stateGiven() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    @DisplayName("Deaktivácia bez „platné do“ doplní dnešok")
    void should_useToday_when_deactivatingWithoutValidTo() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).get();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu je NotFound")
    void should_throwNotFound_when_changingUnknownRecord() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Posledný identifikátor sa odstrániť nedá")
    void should_refuse_when_removingLastIdentifier() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
    }

    @Test
    @DisplayName("Identifikátor iného záznamu (podvrhnuté ID) je NotFound")
    void should_throwNotFound_when_identifierBelongsToOtherRecord() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Odstránenie vráti zostávajúce identifikátory")
    void should_returnRemaining_when_identifierRemoved() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("Povolené podmienky závisia od dôvodu, null = všetky")
    void should_dependOnReason_when_listingConditionTypes() {
        assertThat(service.allowedConditionTypes(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(service.allowedConditionTypes(StateReason.ABUSE))
                .containsExactly(ConditionType.MANUAL_APPROVAL, ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(null)).containsExactly(ConditionType.values());
    }

    @Test
    @DisplayName("Nový záznam dostane ďalšie ID a je aktívny")
    void should_assignIdsAndActivate_when_created() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getIdentifiers()).extracting(Identifier::getId).containsExactly(11L);
    }

    @Test
    @DisplayName("Poznámka má čas z hodín a orezaný text")
    void should_useClockAndTrim_when_addingNote() {
        service.addNote(6, "  Overené telefonicky.  ", "Správca");

        assertThat(service.findById(6).get().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správca", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    @DisplayName("Formáty identifikátorov")
    void should_validateFormats_when_checkingIdentifiers() {
        assertThat(IdentifierType.BIRTH_CODE.isValid("800512/1234")).isTrue();
        assertThat(IdentifierType.BIRTH_CODE.isValid("abc")).isFalse();
        assertThat(IdentifierType.ID_CARD.isValid("AB123456")).isTrue();
        assertThat(IdentifierType.PASSPORT.isValid("P1234567")).isTrue();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid("1234567")).isFalse();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Prázdne kritériá (aj medzery) nie sú vyplnené")
    void should_reportEmpty_when_criteriaBlank() {
        assertThat(criteria(c -> c.setLastName("   ")).isAnyCriterionFilled()).isFalse();
        assertThat(criteria(c -> c.setState(BlacklistState.ACTIVE)).isAnyCriterionFilled()).isTrue();
    }

    private static BlacklistSearchCriteria criteria(Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class InMemoryBlacklistService`.

- [ ] **Step 3: Implementuj doménu**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/blacklist/BlacklistState.java`:

```java
package com.example.crm.frontend.backend.blacklist;

/** Stav záznamu blacklistu. */
public enum BlacklistState { ACTIVE, INACTIVE }
```

`…/backend/blacklist/StateReason.java`:

```java
package com.example.crm.frontend.backend.blacklist;

/** Dôvod zaradenia na blacklist (zjednodušené, vymyslené hodnoty). */
public enum StateReason { FRAUD, DEBT, ABUSE }
```

`…/backend/blacklist/ConditionType.java`:

```java
package com.example.crm.frontend.backend.blacklist;

/** Podmienka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType { BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL }
```

`…/backend/blacklist/IdentifierType.java`:

```java
package com.example.crm.frontend.backend.blacklist;

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

    /** Či hodnota zodpovedá formátu typu. */
    public boolean isValid(String value) {
        return value != null && format.matcher(value).matches();
    }
}
```

`…/backend/blacklist/Identifier.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.util.Objects;

/** Identifikátor osoby (Java 8 – trieda namiesto recordu). */
public final class Identifier {

    private final long id;
    private final IdentifierType type;
    private final String value;

    public Identifier(long id, IdentifierType type, String value) {
        this.id = id;
        this.type = type;
        this.value = value;
    }

    public long getId() {
        return id;
    }

    public IdentifierType getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Identifier)) {
            return false;
        }
        Identifier that = (Identifier) other;
        return id == that.id && type == that.type && Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type, value);
    }
}
```

`…/backend/blacklist/Note.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.time.LocalDateTime;
import java.util.Objects;

/** Poznámka k záznamu. */
public final class Note {

    private final String text;
    private final String author;
    private final LocalDateTime createdAt;

    public Note(String text, String author, LocalDateTime createdAt) {
        this.text = text;
        this.author = author;
        this.createdAt = createdAt;
    }

    public String getText() {
        return text;
    }

    public String getAuthor() {
        return author;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Note)) {
            return false;
        }
        Note that = (Note) other;
        return Objects.equals(text, that.text) && Objects.equals(author, that.author)
                && Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(text, author, createdAt);
    }

    @Override
    public String toString() {
        return createdAt + " " + author + ": " + text;
    }
}
```

`…/backend/blacklist/BlacklistRecord.java`:

```java
package com.example.crm.frontend.backend.blacklist;

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

    public long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public BlacklistState getState() {
        return state;
    }

    public StateReason getStateReason() {
        return stateReason;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public List<Identifier> getIdentifiers() {
        return Collections.unmodifiableList(identifiers);
    }

    public List<Note> getNotes() {
        return Collections.unmodifiableList(notes);
    }

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

`…/backend/blacklist/NewBlacklistRecord.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.time.LocalDate;

/** Príkaz na vytvorenie záznamu – backend port nezávisí od webového formulára. */
public final class NewBlacklistRecord {

    private final String firstName;
    private final String lastName;
    private final LocalDate birthDate;
    private final StateReason stateReason;
    private final ConditionType conditionType;
    private final LocalDate validFrom;
    private final LocalDate validTo;
    private final IdentifierType identifierType;
    private final String identifierValue;

    public NewBlacklistRecord(String firstName, String lastName, LocalDate birthDate, StateReason stateReason,
                              ConditionType conditionType, LocalDate validFrom, LocalDate validTo,
                              IdentifierType identifierType, String identifierValue) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.birthDate = birthDate;
        this.stateReason = stateReason;
        this.conditionType = conditionType;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.identifierType = identifierType;
        this.identifierValue = identifierValue;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public StateReason getStateReason() {
        return stateReason;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }
}
```

`…/backend/blacklist/BlacklistSearchCriteria.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.io.Serializable;

/**
 * Kritériá vyhľadávania. Ako v legacy slúžia zároveň ako formulár (držaný v {@code @SessionAttributes})
 * aj ako dotaz pre backend – preto {@link Serializable}.
 */
public class BlacklistSearchCriteria implements Serializable {

    private static final long serialVersionUID = 1L;

    private String firstName;
    private String lastName;
    private String identifierValue;
    private BlacklistState state;

    /** Aspoň jedno kritérium (medzery sa nerátajú). */
    public boolean isAnyCriterionFilled() {
        return hasText(firstName) || hasText(lastName) || hasText(identifierValue) || state != null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public void setIdentifierValue(String identifierValue) {
        this.identifierValue = identifierValue;
    }

    public BlacklistState getState() {
        return state;
    }

    public void setState(BlacklistState state) {
        this.state = state;
    }
}
```

`…/backend/blacklist/BlacklistRuleException.java`:

```java
package com.example.crm.frontend.backend.blacklist;

/** Porušené business pravidlo – nesie i18n kľúč hlášky pre používateľa. */
public class BlacklistRuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

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

`…/backend/blacklist/BlacklistService.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port na backend blacklistu. V legacy je za ním databázová služba backendu v tom istom JVM;
 * demo má in-memory implementáciu. Migrácia sem pripojí adaptér – controllery sa nemenia.
 */
public interface BlacklistService {

    /** Záznamy zodpovedajúce kritériám (prázdne kritériá = všetky). */
    List<BlacklistRecord> search(BlacklistSearchCriteria criteria);

    /** Záznam podľa ID. */
    Optional<BlacklistRecord> findById(long id);

    /** Vytvorí aktívny záznam s jedným identifikátorom. */
    BlacklistRecord create(NewBlacklistRecord data);

    /** Zmena stavu a platnosti. Deaktivácia bez „platné do“ doplní dnešný dátum. */
    void change(long id, BlacklistState state, LocalDate validTo);

    /** Pridá poznámku s aktuálnym časom. */
    void addNote(long id, String text, String author);

    /** Vráti zostávajúce identifikátory; posledný sa odstrániť nedá ({@link BlacklistRuleException}). */
    List<Identifier> removeIdentifier(long recordId, long identifierId);

    /** Povolené podmienky pre dôvod; {@code null} = všetky. */
    List<ConditionType> allowedConditionTypes(StateReason reason);
}
```

`…/backend/blacklist/InMemoryBlacklistService.java`:

```java
package com.example.crm.frontend.backend.blacklist;

import static com.example.crm.frontend.backend.blacklist.BlacklistState.ACTIVE;
import static com.example.crm.frontend.backend.blacklist.BlacklistState.INACTIVE;
import static com.example.crm.frontend.backend.blacklist.ConditionType.BLOCK_ALL;
import static com.example.crm.frontend.backend.blacklist.ConditionType.MANUAL_APPROVAL;
import static com.example.crm.frontend.backend.blacklist.ConditionType.REQUIRE_DEPOSIT;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.BIRTH_CODE;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.COMPANY_REG_NO;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.ID_CARD;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.PASSPORT;
import static com.example.crm.frontend.backend.blacklist.StateReason.ABUSE;
import static com.example.crm.frontend.backend.blacklist.StateReason.DEBT;
import static com.example.crm.frontend.backend.blacklist.StateReason.FRAUD;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * In-memory náhrada backendu (vymyslené dáta). Metódy sú {@code synchronized} – jednoduchosť pred výkonom;
 * reálny backend rieši konzistenciu transakciou v DB.
 */
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
        return records.values().stream().filter(r -> matches(r, criteria)).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<BlacklistRecord> findById(long id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized BlacklistRecord create(NewBlacklistRecord data) {
        BlacklistRecord record = add(data.getFirstName(), data.getLastName(), data.getBirthDate(), ACTIVE,
                data.getStateReason(), data.getConditionType(), data.getValidFrom(), data.getValidTo());
        identifier(record, data.getIdentifierType(), data.getIdentifierValue());
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
        Identifier identifier = null;
        for (Identifier candidate : record.getIdentifiers()) {
            if (candidate.getId() == identifierId) {
                identifier = candidate;
            }
        }
        if (identifier == null) {
            // Identifikátor iného záznamu = podvrhnuté ID → 404, nie zmena cudzích dát.
            throw new NotFoundException("identifikátor " + identifierId + " záznamu " + recordId);
        }
        if (record.getIdentifiers().size() == 1) {
            throw new BlacklistRuleException("blacklist.detail.lastIdentifier");
        }
        record.removeIdentifier(identifier);
        return Collections.unmodifiableList(new ArrayList<>(record.getIdentifiers()));
    }

    @Override
    public List<ConditionType> allowedConditionTypes(StateReason reason) {
        if (reason == null) {
            return Arrays.asList(ConditionType.values());
        }
        switch (reason) {
            case FRAUD:
                return Collections.singletonList(BLOCK_ALL);
            case DEBT:
                return Arrays.asList(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            default:
                return Arrays.asList(MANUAL_APPROVAL, BLOCK_ALL);
        }
    }

    private BlacklistRecord require(long id) {
        BlacklistRecord record = records.get(id);
        if (record == null) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
        return record;
    }

    private static boolean matches(BlacklistRecord record, BlacklistSearchCriteria criteria) {
        return containsIgnoreCase(record.getFirstName(), criteria.getFirstName())
                && containsIgnoreCase(record.getLastName(), criteria.getLastName())
                && identifierMatches(record, criteria.getIdentifierValue())
                && (criteria.getState() == null || criteria.getState() == record.getState());
    }

    private static boolean identifierMatches(BlacklistRecord record, String query) {
        if (isBlank(query)) {
            return true;
        }
        for (Identifier identifier : record.getIdentifiers()) {
            if (identifier.getValue().equalsIgnoreCase(query.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return isBlank(query) || value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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

- [ ] **Step 5: Zaregistruj bean v `backend-context.xml`**

V `legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml` pridaj pred `</beans>`:

```xml
    <bean id="blacklistService" class="com.example.crm.frontend.backend.blacklist.InMemoryBlacklistService">
        <constructor-arg ref="clock"/>
    </bean>
```

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 95 + 15 = 110).

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 11: Blacklist – vyhľadávanie a výsledky (`viewPage`, `@SessionAttributes`, action → render)

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/SearchCriteriaValidator.java`, `BlacklistSearchController.java`, `BlacklistResultController.java`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`, `searchresult.jsp`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistSearchControllerTest.java`

**Interfaces:**
- Consumes: `BlacklistService`, `InMemoryBlacklistService`, `BlacklistSearchCriteria`, `BlacklistState` (Task 10); `BaseCrmController`, `SessionManagerCustom` (Task 4, 8); `LastSearchCriteria` (Task 9)
- Produces:
  - `BlacklistSearchController` (`@RequestMapping("VIEW")`, default controller portletu): `@SessionAttributes("searchCriteria")`; render default → `blacklist/search`; action `searchAction`, `clearFieldsAction`, `gotoCreateAction`
  - `BlacklistResultController` (`params = "viewPage=searchresult"`): render → `blacklist/searchresult` (model `records`)
  - Render parametre: `render=search`, `viewPage=searchresult|create|detail`, `detailId`
  - JSP: form `id="searchForm"`, odkazy na detail `id="detail-<id>"`, `p id="resultCount"`

- [ ] **Step 1: Napíš padajúci test**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistSearchControllerTest.java`:

```java
package com.example.crm.frontend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistSearchCriteria;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.backend.blacklist.InMemoryBlacklistService;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

class BlacklistSearchControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final BlacklistSearchController search = new BlacklistSearchController(service);
    private final BlacklistResultController result = new BlacklistResultController(service);
    private final MockActionResponse response = new MockActionResponse();

    @BeforeEach
    void wire() {
        for (com.example.crm.frontend.base.web.BaseCrmController controller
                : new com.example.crm.frontend.base.web.BaseCrmController[] {search, result}) {
            controller.setSessionManager(sessionManager);
            controller.setOperatorAcl(mock(OperatorAcl.class));
        }
    }

    @Test
    @DisplayName("Default render je formulár vyhľadávania so stavmi")
    void should_renderSearchForm_when_noParams() {
        assertThat(search.search()).isEqualTo("blacklist/search");
        assertThat(search.states()).containsExactly(BlacklistState.ACTIVE, BlacklistState.INACTIVE);
        assertThat(search.criteria().isAnyCriterionFilled()).isFalse();
    }

    @Test
    @DisplayName("Úspešné hľadanie: render parameter na výsledky a kópia kritérií do zdieľanej session")
    void should_goToResultsAndShareCriteria_when_found() {
        BlacklistSearchCriteria criteria = criteria("Vzork");

        search.searchAction(criteria, bindingResult(criteria), response);

        assertThat(response.getRenderParameter("viewPage")).isEqualTo("searchresult");
        verify(sessionManager).setGlobalAttribute(eq(LastSearchCriteria.KEY),
                argThat(shared -> "Vzork".equals(((LastSearchCriteria) shared).getLastName())));
    }

    @Test
    @DisplayName("Bez kritérií: globálna chyba a návrat na formulár, nič sa nezdieľa")
    void should_rejectAndStay_when_noCriteria() {
        BlacklistSearchCriteria criteria = criteria("   ");
        BindingResult errors = bindingResult(criteria);

        search.searchAction(criteria, errors, response);

        assertThat(errors.getGlobalErrors()).extracting(e -> e.getCode()).containsExactly("blacklist.search.noCriteria");
        assertThat(response.getRenderParameter("render")).isEqualTo("search");
        verify(sessionManager, never()).setGlobalAttribute(any(), any());
    }

    @Test
    @DisplayName("Bez výsledku: informačná hláška a návrat na formulár")
    void should_pushInfo_when_nothingFound() {
        BlacklistSearchCriteria criteria = criteria("Neexistujúci");

        search.searchAction(criteria, bindingResult(criteria), response);

        verify(sessionManager).pushInfo("blacklist.search.noResult");
        assertThat(response.getRenderParameter("render")).isEqualTo("search");
    }

    @Test
    @DisplayName("Vymazanie ukončí @SessionAttributes (inak formulár visí v session navždy)")
    void should_completeSessionStatus_when_clearingFields() {
        SimpleSessionStatus status = new SimpleSessionStatus();

        search.clearFields(status, response);

        assertThat(status.isComplete()).isTrue();
        assertThat(response.getRenderParameter("render")).isEqualTo("search");
    }

    @Test
    @DisplayName("Navigačná action len nastaví render parameter (legacy vzor; po migrácii obyčajný odkaz)")
    void should_onlySetRenderParameter_when_gotoCreate() {
        search.gotoCreate(response);

        assertThat(response.getRenderParameter("viewPage")).isEqualTo("create");
    }

    @Test
    @DisplayName("Výsledky zobrazia záznamy podľa kritérií zo session")
    void should_listRecords_when_criteriaInSession() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = result.result(criteria("Vzork"), model);

        assertThat(view).isEqualTo("blacklist/searchresult");
        @SuppressWarnings("unchecked")
        List<BlacklistRecord> records = (List<BlacklistRecord>) model.get("records");
        assertThat(records).extracting(BlacklistRecord::getId).containsExactly(1L, 8L);
    }

    @Test
    @DisplayName("Výsledky bez kritérií (napr. po vypršaní session) vrátia formulár")
    void should_returnSearchForm_when_criteriaEmpty() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(result.result(new BlacklistSearchCriteria(), model)).isEqualTo("blacklist/search");
        assertThat(model.get("states")).isNotNull();
    }

    private static BlacklistSearchCriteria criteria(String lastName) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        criteria.setLastName(lastName);
        return criteria;
    }

    private static BindingResult bindingResult(BlacklistSearchCriteria criteria) {
        return new BeanPropertyBindingResult(criteria, BlacklistSearchController.CRITERIA);
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class BlacklistSearchController`.

- [ ] **Step 3: Implementuj validátor a controllery**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/SearchCriteriaValidator.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistSearchCriteria;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/** Vlastný Spring {@link Validator} ako v legacy; po migrácii ho nahradí Bean Validation ({@code @AssertTrue}). */
public class SearchCriteriaValidator implements Validator {

    @Override
    public boolean supports(Class<?> type) {
        return BlacklistSearchCriteria.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        if (!((BlacklistSearchCriteria) target).isAnyCriterionFilled()) {
            errors.reject("blacklist.search.noCriteria");
        }
    }
}
```

`…/blacklist/BlacklistSearchController.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistSearchCriteria;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.base.web.BaseCrmController;
import javax.portlet.ActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;

/**
 * Default controller portletu blacklist (bez {@code viewPage}). Legacy vzory, ktoré migrácia mení:
 * action nemá výstup a len nastaví render parametre (→ POST + redirect), kritériá žijú v {@code @SessionAttributes}
 * v PortletSession (→ HttpSession), validačné chyby prenesie do renderu Spring cez „implicit model“ v session.
 */
@Controller
@RequestMapping("VIEW")
@SessionAttributes(BlacklistSearchController.CRITERIA)
public class BlacklistSearchController extends BaseCrmController {

    static final String CRITERIA = "searchCriteria";

    private final BlacklistService blacklistService;
    private final SearchCriteriaValidator validator = new SearchCriteriaValidator();

    @Autowired
    public BlacklistSearchController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /** Prázdne kritériá – volá sa len vtedy, keď ešte nie sú v session. */
    @ModelAttribute(CRITERIA)
    public BlacklistSearchCriteria criteria() {
        return new BlacklistSearchCriteria();
    }

    /** Stavy pre select. */
    @ModelAttribute("states")
    public BlacklistState[] states() {
        return BlacklistState.values();
    }

    /** Formulár vyhľadávania. */
    @RenderMapping
    public String search() {
        return "blacklist/search";
    }

    /** Vyhľadanie – výsledok ukáže render s {@code viewPage=searchresult}. */
    @ActionMapping(params = "action=searchAction")
    public void searchAction(@ModelAttribute(CRITERIA) BlacklistSearchCriteria criteria, BindingResult result,
                             ActionResponse response) {
        validator.validate(criteria, result);
        if (result.hasErrors()) {
            response.setRenderParameter("render", "search");
            return;
        }
        getSessionManager().setGlobalAttribute(LastSearchCriteria.KEY, new LastSearchCriteria(criteria.getFirstName(),
                criteria.getLastName(), criteria.getIdentifierValue(),
                criteria.getState() == null ? null : criteria.getState().name()));
        if (blacklistService.search(criteria).isEmpty()) {
            getSessionManager().pushInfo("blacklist.search.noResult");
            response.setRenderParameter("render", "search");
            return;
        }
        response.setRenderParameter("viewPage", "searchresult");
    }

    /** Vymazanie kritérií. */
    @ActionMapping(params = "action=clearFieldsAction")
    public void clearFields(SessionStatus status, ActionResponse response) {
        status.setComplete();
        response.setRenderParameter("render", "search");
    }

    /** Prechod na nový záznam – action, ktorá len presmeruje na iný render (legacy „goto“ akcia). */
    @ActionMapping(params = "action=gotoCreateAction")
    public void gotoCreate(ActionResponse response) {
        response.setRenderParameter("viewPage", "create");
    }
}
```

`…/blacklist/BlacklistResultController.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistSearchCriteria;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.base.web.BaseCrmController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.portlet.bind.annotation.RenderMapping;

/** „Stránka“ výsledkov: controller sa vyberá render parametrom {@code viewPage} (legacy dispatch v rámci portletu). */
@Controller
@RequestMapping(value = "VIEW", params = "viewPage=searchresult")
@SessionAttributes(BlacklistSearchController.CRITERIA)
public class BlacklistResultController extends BaseCrmController {

    private final BlacklistService blacklistService;

    @Autowired
    public BlacklistResultController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /** Ak kritériá v session nie sú (vypršala), vznikne prázdny objekt a stránka vráti formulár. */
    @ModelAttribute(BlacklistSearchController.CRITERIA)
    public BlacklistSearchCriteria criteria() {
        return new BlacklistSearchCriteria();
    }

    /** Tabuľka výsledkov. */
    @RenderMapping
    public String result(@ModelAttribute(BlacklistSearchController.CRITERIA) BlacklistSearchCriteria criteria,
                         Model model) {
        if (!criteria.isAnyCriterionFilled()) {
            model.addAttribute("states", BlacklistState.values());
            return "blacklist/search";
        }
        model.addAttribute("records", blacklistService.search(criteria));
        return "blacklist/searchresult";
    }
}
```

- [ ] **Step 4: JSP**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/search.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:actionURL var="searchUrl" escapeXml="false"><portal:param name="action" value="searchAction"/></portal:actionURL>
<portal:actionURL var="clearUrl"><portal:param name="action" value="clearFieldsAction"/></portal:actionURL>
<portal:actionURL var="gotoCreateUrl"><portal:param name="action" value="gotoCreateAction"/></portal:actionURL>

<h2><common:message key="blacklist.search.title"/></h2>
<form:form id="searchForm" method="post" action="${searchUrl}" modelAttribute="searchCriteria">
    <form:errors path="*" cssClass="field-error" element="div"/>
    <div>
        <label for="firstName"><common:message key="blacklist.search.firstName"/></label>
        <form:input path="firstName"/>
    </div>
    <div>
        <label for="lastName"><common:message key="blacklist.search.lastName"/></label>
        <form:input path="lastName"/>
    </div>
    <div>
        <label for="identifierValue"><common:message key="blacklist.search.identifier"/></label>
        <form:input path="identifierValue"/>
    </div>
    <div>
        <label for="state"><common:message key="blacklist.search.state"/></label>
        <form:select path="state">
            <form:option value=""><common:message key="blacklist.search.anyState"/></form:option>
            <c:forEach items="${states}" var="s">
                <form:option value="${s}"><common:message key="blacklist.state.${s}"/></form:option>
            </c:forEach>
        </form:select>
    </div>
    <button type="submit"><common:message key="blacklist.search.submit"/></button>
</form:form>
<form id="clearForm" method="post" action="${clearUrl}" class="crm-inline">
    <button type="submit"><common:message key="blacklist.search.clear"/></button>
</form>
<common:aclFilter privilegeType="BLACKLIST_MODIFY">
    <form id="gotoCreateForm" method="post" action="${gotoCreateUrl}" class="crm-inline">
        <button type="submit"><common:message key="blacklist.search.create"/></button>
    </form>
</common:aclFilter>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/searchresult.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:renderURL var="newSearchUrl"><portal:param name="render" value="search"/></portal:renderURL>

<h2><common:message key="blacklist.result.title"/></h2>
<p id="resultCount"><common:message key="blacklist.result.count"><common:msgarg value="${fn:length(records)}"/></common:message></p>
<table>
    <thead>
    <tr>
        <th><common:message key="blacklist.result.name"/></th>
        <th><common:message key="blacklist.result.birthDate"/></th>
        <th><common:message key="blacklist.result.state"/></th>
        <th><common:message key="blacklist.result.validity"/></th>
    </tr>
    </thead>
    <tbody>
    <c:forEach items="${records}" var="r">
        <portal:renderURL var="detailUrl">
            <portal:param name="viewPage" value="detail"/>
            <portal:param name="detailId" value="${r.id}"/>
        </portal:renderURL>
        <tr>
            <td><a id="detail-${r.id}" href="${detailUrl}"><c:out value="${r.fullName}"/></a></td>
            <td>${r.birthDate}</td>
            <td><common:message key="blacklist.state.${r.state}"/></td>
            <td>${r.validFrom} –
                <c:choose>
                    <c:when test="${empty r.validTo}"><common:message key="blacklist.detail.unlimited"/></c:when>
                    <c:otherwise>${r.validTo}</c:otherwise>
                </c:choose>
            </td>
        </tr>
    </c:forEach>
    </tbody>
</table>
<a href="${newSearchUrl}"><common:message key="blacklist.result.newSearch"/></a>
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 110 + 8 = 118; `PortletContextWiringTest` teraz overí aj `@Autowired` nových controllerov).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 12: Blacklist – detail, zmena stavu, poznámka, AJAX odstránenie identifikátora

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/BlacklistDetailController.java`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`, `jsp/blacklist/fragments/identifiers.jsp`, `jsp/blacklist/fragments/identifiers-list.jspf`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistDetailControllerTest.java`

**Interfaces:**
- Consumes: `BlacklistService`, `BlacklistRecord`, `BlacklistState`, `Identifier`, `BlacklistRuleException` (Task 10); `BaseCrmController`, `Privilege`, `AclDeniedException`, `NotFoundException` (Task 5, 8); `LastViewedRecord` (Task 9); JS `Crm.removeIdentifier` (Task 9)
- Produces:
  - `BlacklistDetailController` (`params = "viewPage=detail"`): render (`detailId`) → `blacklist/detail` (model `record`, `states`), zapíše `LastViewedRecord`; action `changeAction` (`detailId`, `state`, `validTo`), `addNoteAction` (`detailId`, `text`); resource `removeIdentAction` (`detailId`, `identId`) → `ModelAndView("blacklist/fragments/identifiers")` s `identifiers`, `detailId`, prípadne `errorKey`
  - JSP: `form id="changeForm"`, `form id="noteForm"`, `td id="recordState"`, `div id="identifiers"`, odkazy `id="remove-ident-<id>"`

- [ ] **Step 1: Napíš padajúci test**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistDetailControllerTest.java`:

```java
package com.example.crm.frontend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.backend.blacklist.Identifier;
import com.example.crm.frontend.backend.blacklist.InMemoryBlacklistService;
import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.web.NotFoundException;
import com.example.sso.api.PortalOperator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.portlet.ModelAndView;

class BlacklistDetailControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final BlacklistDetailController controller = new BlacklistDetailController(service);
    private final MockActionResponse response = new MockActionResponse();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(acl);
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));
    }

    @Test
    @DisplayName("Detail zobrazí záznam a zapíše ho do zdieľanej session ako posledný otvorený")
    void should_renderRecordAndShareLastViewed_when_detailOpened() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.detail(1, model)).isEqualTo("blacklist/detail");
        assertThat(((BlacklistRecord) model.get("record")).getFullName()).isEqualTo("Peter Vzorka");
        verify(sessionManager).setGlobalAttribute(eq(LastViewedRecord.KEY),
                argThat(v -> ((LastViewedRecord) v).getId() == 1 && "Peter Vzorka".equals(((LastViewedRecord) v).getFullName())));
    }

    @Test
    @DisplayName("Neexistujúci záznam je 404")
    void should_throwNotFound_when_recordUnknown() {
        assertThatThrownBy(() -> controller.detail(999, new ExtendedModelMap())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Zmena stavu s privilégiom: zmena, hláška a návrat na detail")
    void should_changeStateAndReturnToDetail_when_privileged() {
        controller.change(5, BlacklistState.INACTIVE, null, response);

        assertThat(service.findById(5).get().getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(service.findById(5).get().getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
        verify(sessionManager).pushInfo("blacklist.detail.changed");
        assertThat(response.getRenderParameter("viewPage")).isEqualTo("detail");
        assertThat(response.getRenderParameter("detailId")).isEqualTo("5");
    }

    @Test
    @DisplayName("Zmena bez privilégia BLACKLIST_MODIFY sa neuskutoční")
    void should_refuse_when_notPrivileged() {
        doThrow(new AclDeniedException(Privilege.BLACKLIST_MODIFY)).when(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);

        assertThatThrownBy(() -> controller.change(5, BlacklistState.INACTIVE, null, response))
                .isInstanceOf(AclDeniedException.class);
        assertThat(service.findById(5).get().getState()).isEqualTo(BlacklistState.ACTIVE);
    }

    @Test
    @DisplayName("Prázdna poznámka je chyba, vyplnená sa uloží s menom operátora")
    void should_validateAndStoreNote_when_adding() {
        controller.addNote(6, "   ", response);
        verify(sessionManager).pushError("blacklist.detail.noteEmpty");

        controller.addNote(6, "Overené telefonicky.", response);

        assertThat(service.findById(6).get().getNotes()).extracting(n -> n.getAuthor()).containsExactly("Správca");
        verify(sessionManager).pushInfo("blacklist.detail.noteAdded");
    }

    @Test
    @DisplayName("AJAX odstránenie vráti fragment so zostávajúcimi identifikátormi")
    @SuppressWarnings("unchecked")
    void should_returnFragmentWithRemaining_when_identifierRemoved() {
        ModelAndView view = controller.removeIdentifier(1, 2);

        assertThat(view.getViewName()).isEqualTo("blacklist/fragments/identifiers");
        assertThat((List<Identifier>) view.getModel().get("identifiers")).extracting(Identifier::getId).containsExactly(1L);
        assertThat(view.getModel().get("errorKey")).isNull();
    }

    @Test
    @DisplayName("Posledný identifikátor: fragment s chybovým kľúčom a nezmeneným zoznamom")
    @SuppressWarnings("unchecked")
    void should_returnErrorKey_when_removingLastIdentifier() {
        ModelAndView view = controller.removeIdentifier(4, 5);

        assertThat(view.getModel().get("errorKey")).isEqualTo("blacklist.detail.lastIdentifier");
        assertThat((List<Identifier>) view.getModel().get("identifiers")).hasSize(1);
    }

    @Test
    @DisplayName("Identifikátor iného záznamu je 404 (resolver z toho spraví HTTP 404)")
    void should_throwNotFound_when_identifierOfOtherRecord() {
        assertThatThrownBy(() -> controller.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class BlacklistDetailController`.

- [ ] **Step 3: Implementuj controller**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/BlacklistDetailController.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistRuleException;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.web.BaseCrmController;
import com.example.crm.frontend.base.web.NotFoundException;
import java.time.LocalDate;
import javax.portlet.ActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.portlet.ModelAndView;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * Detail záznamu: render, dve action a jeden resource. Resource fáza vracia HTML fragment cez {@link ModelAndView}
 * (legacy vzor) – po migrácii samostatný AJAX endpoint. Zmeny vyžadujú {@code BLACKLIST_MODIFY} nad rámec
 * privilégia portletu.
 */
@Controller
@RequestMapping(value = "VIEW", params = "viewPage=detail")
public class BlacklistDetailController extends BaseCrmController {

    private final BlacklistService blacklistService;

    @Autowired
    public BlacklistDetailController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /** Stavy pre formulár zmeny. */
    @ModelAttribute("states")
    public BlacklistState[] states() {
        return BlacklistState.values();
    }

    /** Detail záznamu. */
    @RenderMapping
    public String detail(@RequestParam("detailId") long detailId, Model model) {
        BlacklistRecord record = require(detailId);
        model.addAttribute("record", record);
        // Zdieľaná session: hlavička (aj v novej appke) ukáže, s kým operátor naposledy pracoval.
        getSessionManager().setGlobalAttribute(LastViewedRecord.KEY,
                new LastViewedRecord(record.getId(), record.getFullName()));
        return "blacklist/detail";
    }

    /** Zmena stavu a platnosti. */
    @ActionMapping(params = "action=changeAction")
    public void change(@RequestParam("detailId") long detailId, @RequestParam("state") BlacklistState state,
                       @RequestParam(value = "validTo", required = false) LocalDate validTo, ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        blacklistService.change(detailId, state, validTo);
        getSessionManager().pushInfo("blacklist.detail.changed");
        backToDetail(response, detailId);
    }

    /** Pridanie poznámky. */
    @ActionMapping(params = "action=addNoteAction")
    public void addNote(@RequestParam("detailId") long detailId,
                        @RequestParam(value = "text", required = false) String text, ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        if (text == null || text.trim().isEmpty()) {
            getSessionManager().pushError("blacklist.detail.noteEmpty");
        } else {
            blacklistService.addNote(detailId, text, operatorName());
            getSessionManager().pushInfo("blacklist.detail.noteAdded");
        }
        backToDetail(response, detailId);
    }

    /** AJAX: odstránenie identifikátora, vráti HTML fragment zoznamu. */
    @ResourceMapping("removeIdentAction")
    public ModelAndView removeIdentifier(@RequestParam("detailId") long detailId, @RequestParam("identId") long identId) {
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

    private BlacklistRecord require(long id) {
        BlacklistRecord record = blacklistService.findById(id).orElse(null);
        if (record == null) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
        return record;
    }

    private static void backToDetail(ActionResponse response, long detailId) {
        response.setRenderParameter("viewPage", "detail");
        response.setRenderParameter("detailId", String.valueOf(detailId));
    }
}
```

- [ ] **Step 4: JSP**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers-list.jspf`:

```jsp
<%-- Zoznam identifikátorov: v detaile (statický include) aj ako AJAX fragment resource fázy. Potrebuje
     premenné identifiers, detailId a voliteľne errorKey. --%>
<c:if test="${not empty errorKey}">
    <div class="portlet-msg-error"><common:message key="${errorKey}"/></div>
</c:if>
<ul>
    <c:forEach items="${identifiers}" var="i">
        <li>
            <common:message key="blacklist.identifier.${i.type}"/>: <c:out value="${i.value}"/>
            <common:aclFilter privilegeType="BLACKLIST_MODIFY">
                <portal:resourceURL id="removeIdentAction" var="removeUrl">
                    <portal:param name="viewPage" value="detail"/>
                    <portal:param name="detailId" value="${detailId}"/>
                    <portal:param name="identId" value="${i.id}"/>
                </portal:resourceURL>
                <a id="remove-ident-${i.id}" href="#" data-url="${removeUrl}" data-target="identifiers"
                   onclick="return Crm.removeIdentifier(this);"><common:message key="blacklist.detail.removeIdentifier"/></a>
            </common:aclFilter>
        </li>
    </c:forEach>
</ul>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/fragments/identifiers.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<%@ include file="/WEB-INF/jsp/blacklist/fragments/identifiers-list.jspf" %>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/detail.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:renderURL var="backUrl"><portal:param name="viewPage" value="searchresult"/></portal:renderURL>
<portal:actionURL var="changeUrl">
    <portal:param name="viewPage" value="detail"/>
    <portal:param name="action" value="changeAction"/>
</portal:actionURL>
<portal:actionURL var="noteUrl">
    <portal:param name="viewPage" value="detail"/>
    <portal:param name="action" value="addNoteAction"/>
</portal:actionURL>

<h2><common:message key="blacklist.detail.title"/>: <c:out value="${record.fullName}"/></h2>
<div id="crm-ajax-error"></div>
<table id="recordSummary">
    <tr><th><common:message key="blacklist.result.birthDate"/></th><td>${record.birthDate}</td></tr>
    <tr><th><common:message key="blacklist.result.state"/></th>
        <td id="recordState"><common:message key="blacklist.state.${record.state}"/></td></tr>
    <tr><th><common:message key="blacklist.detail.reason"/></th>
        <td><common:message key="blacklist.reason.${record.stateReason}"/></td></tr>
    <tr><th><common:message key="blacklist.detail.condition"/></th>
        <td><common:message key="blacklist.condition.${record.conditionType}"/></td></tr>
    <tr><th><common:message key="blacklist.detail.validFrom"/></th><td>${record.validFrom}</td></tr>
    <tr><th><common:message key="blacklist.detail.validTo"/></th>
        <td id="recordValidTo">
            <c:choose>
                <c:when test="${empty record.validTo}"><common:message key="blacklist.detail.unlimited"/></c:when>
                <c:otherwise>${record.validTo}</c:otherwise>
            </c:choose>
        </td></tr>
</table>

<h3><common:message key="blacklist.detail.identifiers"/></h3>
<c:set var="identifiers" value="${record.identifiers}"/>
<c:set var="detailId" value="${record.id}"/>
<div id="identifiers"><%@ include file="/WEB-INF/jsp/blacklist/fragments/identifiers-list.jspf" %></div>

<h3><common:message key="blacklist.detail.notes"/></h3>
<c:choose>
    <c:when test="${empty record.notes}"><p><common:message key="blacklist.detail.noNotes"/></p></c:when>
    <c:otherwise>
        <ul id="notes">
            <c:forEach items="${record.notes}" var="n">
                <li>${n.createdAt} <c:out value="${n.author}"/>: <c:out value="${n.text}"/></li>
            </c:forEach>
        </ul>
    </c:otherwise>
</c:choose>

<common:aclFilter privilegeType="BLACKLIST_MODIFY">
    <form id="noteForm" method="post" action="${noteUrl}">
        <input type="hidden" name="detailId" value="${record.id}"/>
        <textarea name="text" rows="2" cols="60"></textarea>
        <button type="submit"><common:message key="blacklist.detail.addNote"/></button>
    </form>
    <fieldset>
        <legend><common:message key="blacklist.detail.change"/></legend>
        <form id="changeForm" method="post" action="${changeUrl}">
            <input type="hidden" name="detailId" value="${record.id}"/>
            <label for="state"><common:message key="blacklist.result.state"/></label>
            <select id="state" name="state">
                <c:forEach items="${states}" var="s">
                    <option value="${s}" ${s == record.state ? 'selected' : ''}><common:message key="blacklist.state.${s}"/></option>
                </c:forEach>
            </select>
            <label for="validTo"><common:message key="blacklist.detail.validTo"/></label>
            <input id="validTo" type="date" name="validTo" value="${record.validTo}"/>
            <button type="submit"><common:message key="blacklist.detail.save"/></button>
        </form>
    </fieldset>
</common:aclFilter>
<a href="${backUrl}"><common:message key="common.back"/></a>
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 118 + 8 = 126).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 13: Blacklist – nový záznam (vlastný `Validator`, AJAX refresh podmienok, `@SessionAttributes`)

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/CreateForm.java`, `BlacklistCreateValidator.java`, `BlacklistCreateController.java`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`, `jsp/blacklist/fragments/conditionTypes.jsp`, `jsp/blacklist/fragments/conditionTypes-select.jspf`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistCreateControllerTest.java`

**Interfaces:**
- Consumes: `BlacklistService`, `NewBlacklistRecord`, `StateReason`, `ConditionType`, `IdentifierType`, `BlacklistRecord` (Task 10); `BaseCrmController`, `Privilege` (Task 5, 8); bean `clock` (Task 9); JS `Crm.refreshConditionTypes` (Task 9)
- Produces:
  - `CreateForm implements Serializable` – get/set `firstName`, `lastName`, `birthDate`, `stateReason`, `conditionType`, `validFrom`, `validTo`, `identifierType`, `identifierValue`; `NewBlacklistRecord toNewRecord()`
  - `BlacklistCreateValidator(BlacklistService) implements Validator` – kódy `validation.required`, `validation.validToBeforeValidFrom`, `validation.identifierFormat`, `validation.conditionNotAllowed`
  - `BlacklistCreateController(BlacklistService, Clock)` (`params = "viewPage=create"`, `@SessionAttributes("createForm")`): render → `blacklist/create` (model `conditionTypes`); resource `refreshCreateConditionTypes` (`stateReason`) → `blacklist/fragments/conditionTypes`; action `createAction`, `gotoSearchAction`
  - JSP: `form id="createForm"`, `span id="conditionTypeBox"`

- [ ] **Step 1: Napíš padajúci test**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/blacklist/BlacklistCreateControllerTest.java`:

```java
package com.example.crm.frontend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.crm.frontend.backend.blacklist.ConditionType;
import com.example.crm.frontend.backend.blacklist.IdentifierType;
import com.example.crm.frontend.backend.blacklist.InMemoryBlacklistService;
import com.example.crm.frontend.backend.blacklist.StateReason;
import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

class BlacklistCreateControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final BlacklistCreateController controller = new BlacklistCreateController(service, CLOCK);
    private final MockActionResponse response = new MockActionResponse();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(acl);
    }

    @Test
    @DisplayName("Nový formulár má „platné od“ dnes a ponúka všetky podmienky")
    void should_prefillValidFromAndOfferAllConditions_when_rendered() {
        CreateForm form = controller.form();
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.create(form, model)).isEqualTo("blacklist/create");
        assertThat(form.getValidFrom()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(model.get("conditionTypes")).isEqualTo(service.allowedConditionTypes(null));
        verify(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);
    }

    @Test
    @DisplayName("Bez BLACKLIST_MODIFY sa formulár nezobrazí")
    void should_refuse_when_notPrivileged() {
        doThrow(new AclDeniedException(Privilege.BLACKLIST_MODIFY)).when(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);

        assertThatThrownBy(() -> controller.create(controller.form(), new ExtendedModelMap()))
                .isInstanceOf(AclDeniedException.class);
    }

    @Test
    @DisplayName("AJAX refresh vráti fragment s podmienkami povolenými pre dôvod")
    void should_returnAllowedConditions_when_reasonChanged() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.refreshConditionTypes(StateReason.DEBT, model)).isEqualTo("blacklist/fragments/conditionTypes");
        assertThat(model.get("conditionTypes")).isEqualTo(java.util.Arrays.asList(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL));
    }

    @Test
    @DisplayName("Platný formulár: záznam vznikne, session atribút sa ukončí, render na detail")
    void should_createAndGoToDetail_when_formValid() {
        CreateForm form = validForm();
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.createAction(form, bindingResult(form), status, response);

        assertThat(service.findById(9)).isPresent();
        assertThat(status.isComplete()).isTrue();
        verify(sessionManager).pushInfo("blacklist.create.created");
        assertThat(response.getRenderParameter("viewPage")).isEqualTo("detail");
        assertThat(response.getRenderParameter("detailId")).isEqualTo("9");
    }

    @Test
    @DisplayName("Prázdny formulár: chyby pri povinných poliach, späť na formulár, nič nevznikne")
    void should_reportRequiredFields_when_formEmpty() {
        CreateForm form = new CreateForm();
        BindingResult errors = bindingResult(form);
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.createAction(form, errors, status, response);

        assertThat(fieldsWithCode(errors, "validation.required")).contains("firstName", "lastName", "birthDate",
                "stateReason", "conditionType", "validFrom", "identifierType", "identifierValue");
        assertThat(response.getRenderParameter("viewPage")).isEqualTo("create");
        assertThat(status.isComplete()).isFalse();
        assertThat(service.findById(9)).isNotPresent();
    }

    @Test
    @DisplayName("Business pravidlá: platnosť, formát identifikátora, podmienka podľa dôvodu")
    void should_reportRuleViolations_when_valuesInconsistent() {
        CreateForm form = validForm();
        form.setValidTo(LocalDate.of(2026, 1, 1));
        form.setIdentifierValue("12AB");
        form.setStateReason(StateReason.FRAUD);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        BindingResult errors = bindingResult(form);

        new BlacklistCreateValidator(service).validate(form, errors);

        assertThat(errors.getFieldError("validTo").getCode()).isEqualTo("validation.validToBeforeValidFrom");
        assertThat(errors.getFieldError("identifierValue").getCode()).isEqualTo("validation.identifierFormat");
        assertThat(errors.getFieldError("conditionType").getCode()).isEqualTo("validation.conditionNotAllowed");
    }

    @Test
    @DisplayName("Zlý dátum (chyba konverzie) nedostane navyše „povinný údaj“")
    void should_keepTypeMismatchOnly_when_dateUnparseable() {
        CreateForm form = validForm();
        form.setBirthDate(null);
        BindingResult errors = bindingResult(form);
        errors.rejectValue("birthDate", "typeMismatch");

        new BlacklistCreateValidator(service).validate(form, errors);

        assertThat(errors.getFieldErrors("birthDate")).extracting(e -> e.getCode()).containsExactly("typeMismatch");
    }

    @Test
    @DisplayName("Zrušenie ukončí formulár v session a vráti vyhľadávanie")
    void should_completeAndReturnToSearch_when_cancelled() {
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.gotoSearch(status, response);

        assertThat(status.isComplete()).isTrue();
        assertThat(response.getRenderParameter("render")).isEqualTo("search");
    }

    private static CreateForm validForm() {
        CreateForm form = new CreateForm();
        form.setFirstName("Nový");
        form.setLastName("Záznam");
        form.setBirthDate(LocalDate.of(1990, 1, 1));
        form.setStateReason(StateReason.DEBT);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        form.setValidFrom(LocalDate.of(2026, 10, 8));
        form.setIdentifierType(IdentifierType.ID_CARD);
        form.setIdentifierValue("ZZ123456");
        return form;
    }

    private static BindingResult bindingResult(CreateForm form) {
        return new BeanPropertyBindingResult(form, BlacklistCreateController.FORM);
    }

    private static List<String> fieldsWithCode(BindingResult errors, String code) {
        return errors.getFieldErrors().stream().filter(e -> code.equals(e.getCode()))
                .map(e -> e.getField()).collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class BlacklistCreateController`.

- [ ] **Step 3: Implementuj formulár, validátor a controller**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/blacklist/CreateForm.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.ConditionType;
import com.example.crm.frontend.backend.blacklist.IdentifierType;
import com.example.crm.frontend.backend.blacklist.NewBlacklistRecord;
import com.example.crm.frontend.backend.blacklist.StateReason;
import java.io.Serializable;
import java.time.LocalDate;

/** Formulár nového záznamu, držaný v {@code @SessionAttributes} počas AJAX refreshov aj chýb validácie. */
public class CreateForm implements Serializable {

    private static final long serialVersionUID = 1L;

    private String firstName;
    private String lastName;
    private LocalDate birthDate;
    private StateReason stateReason;
    private ConditionType conditionType;
    private LocalDate validFrom;
    private LocalDate validTo;
    private IdentifierType identifierType;
    private String identifierValue;

    /** Príkaz pre backend (hodnoty sú už zvalidované). */
    public NewBlacklistRecord toNewRecord() {
        return new NewBlacklistRecord(firstName.trim(), lastName.trim(), birthDate, stateReason, conditionType,
                validFrom, validTo, identifierType, identifierValue.trim());
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public StateReason getStateReason() {
        return stateReason;
    }

    public void setStateReason(StateReason stateReason) {
        this.stateReason = stateReason;
    }

    public ConditionType getConditionType() {
        return conditionType;
    }

    public void setConditionType(ConditionType conditionType) {
        this.conditionType = conditionType;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate validFrom) {
        this.validFrom = validFrom;
    }

    public LocalDate getValidTo() {
        return validTo;
    }

    public void setValidTo(LocalDate validTo) {
        this.validTo = validTo;
    }

    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    public void setIdentifierType(IdentifierType identifierType) {
        this.identifierType = identifierType;
    }

    public String getIdentifierValue() {
        return identifierValue;
    }

    public void setIdentifierValue(String identifierValue) {
        this.identifierValue = identifierValue;
    }
}
```

`…/blacklist/BlacklistCreateValidator.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistService;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * Vlastný Spring {@link Validator} (legacy vzor; po migrácii Bean Validation + jedno vlastné pravidlo).
 * Povolená podmienka pre dôvod je pravidlo backendu – validátor sa naň pýta služby, neopakuje ho.
 */
public class BlacklistCreateValidator implements Validator {

    private static final String REQUIRED = "validation.required";

    private final BlacklistService blacklistService;

    public BlacklistCreateValidator(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    @Override
    public boolean supports(Class<?> type) {
        return CreateForm.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        CreateForm form = (CreateForm) target;
        requireText(errors, "firstName", form.getFirstName());
        requireText(errors, "lastName", form.getLastName());
        requireValue(errors, "birthDate", form.getBirthDate());
        requireValue(errors, "stateReason", form.getStateReason());
        requireValue(errors, "conditionType", form.getConditionType());
        requireValue(errors, "validFrom", form.getValidFrom());
        requireValue(errors, "identifierType", form.getIdentifierType());
        requireText(errors, "identifierValue", form.getIdentifierValue());
        if (form.getValidFrom() != null && form.getValidTo() != null && form.getValidTo().isBefore(form.getValidFrom())) {
            errors.rejectValue("validTo", "validation.validToBeforeValidFrom");
        }
        if (form.getIdentifierType() != null && hasText(form.getIdentifierValue())
                && !form.getIdentifierType().isValid(form.getIdentifierValue().trim())) {
            errors.rejectValue("identifierValue", "validation.identifierFormat");
        }
        if (form.getStateReason() != null && form.getConditionType() != null
                && !blacklistService.allowedConditionTypes(form.getStateReason()).contains(form.getConditionType())) {
            errors.rejectValue("conditionType", "validation.conditionNotAllowed");
        }
    }

    private static void requireText(Errors errors, String field, String value) {
        if (!hasText(value) && !errors.hasFieldErrors(field)) {
            errors.rejectValue(field, REQUIRED);
        }
    }

    // Pole s chybou konverzie (napr. zlý dátum) je null – nepridávame k nej ešte „povinný údaj“.
    private static void requireValue(Errors errors, String field, Object value) {
        if (value == null && !errors.hasFieldErrors(field)) {
            errors.rejectValue(field, REQUIRED);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
```

`…/blacklist/BlacklistCreateController.java`:

```java
package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.IdentifierType;
import com.example.crm.frontend.backend.blacklist.StateReason;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.web.BaseCrmController;
import java.time.Clock;
import java.time.LocalDate;
import javax.portlet.ActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * Nový záznam. {@code @ModelAttribute} na úrovni triedy + {@code @SessionAttributes}: formulár prežije render,
 * AJAX resource aj action – a práve stratená {@code @ModelAttribute} metóda bola chyba v PoC logu.
 */
@Controller
@RequestMapping(value = "VIEW", params = "viewPage=create")
@SessionAttributes(BlacklistCreateController.FORM)
public class BlacklistCreateController extends BaseCrmController {

    static final String FORM = "createForm";

    private final BlacklistService blacklistService;
    private final Clock clock;
    private final BlacklistCreateValidator validator;

    @Autowired
    public BlacklistCreateController(BlacklistService blacklistService, Clock clock) {
        this.blacklistService = blacklistService;
        this.clock = clock;
        this.validator = new BlacklistCreateValidator(blacklistService);
    }

    /** Nový formulár (len ak ešte nie je v session). */
    @ModelAttribute(FORM)
    public CreateForm form() {
        CreateForm form = new CreateForm();
        form.setValidFrom(LocalDate.now(clock));
        return form;
    }

    /** Dôvody pre select. */
    @ModelAttribute("reasons")
    public StateReason[] reasons() {
        return StateReason.values();
    }

    /** Typy identifikátorov pre select. */
    @ModelAttribute("identifierTypes")
    public IdentifierType[] identifierTypes() {
        return IdentifierType.values();
    }

    /** Formulár nového záznamu. */
    @RenderMapping
    public String create(@ModelAttribute(FORM) CreateForm form, Model model) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        model.addAttribute("conditionTypes", blacklistService.allowedConditionTypes(form.getStateReason()));
        return "blacklist/create";
    }

    /** AJAX: povolené podmienky pre zvolený dôvod (HTML fragment {@code <select>}). */
    @ResourceMapping("refreshCreateConditionTypes")
    public String refreshConditionTypes(@RequestParam(value = "stateReason", required = false) StateReason stateReason,
                                        Model model) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        model.addAttribute("conditionTypes", blacklistService.allowedConditionTypes(stateReason));
        return "blacklist/fragments/conditionTypes";
    }

    /** Vytvorenie záznamu. */
    @ActionMapping(params = "action=createAction")
    public void createAction(@ModelAttribute(FORM) CreateForm form, BindingResult result, SessionStatus status,
                             ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        validator.validate(form, result);
        if (result.hasErrors()) {
            response.setRenderParameter("viewPage", "create");
            return;
        }
        BlacklistRecord created = blacklistService.create(form.toNewRecord());
        status.setComplete();
        getSessionManager().pushInfo("blacklist.create.created");
        response.setRenderParameter("viewPage", "detail");
        response.setRenderParameter("detailId", String.valueOf(created.getId()));
    }

    /** Zrušenie – späť na vyhľadávanie (legacy „goto“ akcia). */
    @ActionMapping(params = "action=gotoSearchAction")
    public void gotoSearch(SessionStatus status, ActionResponse response) {
        status.setComplete();
        response.setRenderParameter("render", "search");
    }
}
```

- [ ] **Step 4: JSP**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes-select.jspf`:

```jsp
<%-- Select podmienok: vo formulári (statický include) aj ako AJAX fragment. Obyčajný <select>, lebo fragment
     resource fázy nemá kontext <form:form>. --%>
<select id="conditionType" name="conditionType">
    <option value=""><common:message key="blacklist.create.choose"/></option>
    <c:forEach items="${conditionTypes}" var="t">
        <option value="${t}" ${t == selectedConditionType ? 'selected' : ''}><common:message key="blacklist.condition.${t}"/></option>
    </c:forEach>
</select>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<%@ include file="/WEB-INF/jsp/blacklist/fragments/conditionTypes-select.jspf" %>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/blacklist/create.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_MODIFY" wireframe="false"/>
<portal:actionURL var="createUrl" escapeXml="false">
    <portal:param name="viewPage" value="create"/>
    <portal:param name="action" value="createAction"/>
</portal:actionURL>
<portal:actionURL var="cancelUrl">
    <portal:param name="viewPage" value="create"/>
    <portal:param name="action" value="gotoSearchAction"/>
</portal:actionURL>
<portal:resourceURL id="refreshCreateConditionTypes" var="conditionTypesUrl" escapeXml="false">
    <portal:param name="viewPage" value="create"/>
</portal:resourceURL>

<h2><common:message key="blacklist.create.title"/></h2>
<div id="crm-ajax-error"></div>
<form:form id="createForm" method="post" action="${createUrl}" modelAttribute="createForm">
    <div>
        <label for="firstName"><common:message key="blacklist.search.firstName"/></label>
        <form:input path="firstName"/> <form:errors path="firstName" cssClass="field-error"/>
    </div>
    <div>
        <label for="lastName"><common:message key="blacklist.search.lastName"/></label>
        <form:input path="lastName"/> <form:errors path="lastName" cssClass="field-error"/>
    </div>
    <div>
        <label for="birthDate"><common:message key="blacklist.create.birthDate"/></label>
        <form:input path="birthDate" type="date"/> <form:errors path="birthDate" cssClass="field-error"/>
    </div>
    <div>
        <label for="stateReason"><common:message key="blacklist.create.reason"/></label>
        <form:select path="stateReason" data-url="${conditionTypesUrl}" data-target="conditionTypeBox"
                     onchange="Crm.refreshConditionTypes(this)">
            <form:option value=""><common:message key="blacklist.create.choose"/></form:option>
            <c:forEach items="${reasons}" var="r">
                <form:option value="${r}"><common:message key="blacklist.reason.${r}"/></form:option>
            </c:forEach>
        </form:select>
        <form:errors path="stateReason" cssClass="field-error"/>
    </div>
    <div>
        <label for="conditionType"><common:message key="blacklist.create.condition"/></label>
        <c:set var="selectedConditionType" value="${createForm.conditionType}"/>
        <span id="conditionTypeBox"><%@ include file="/WEB-INF/jsp/blacklist/fragments/conditionTypes-select.jspf" %></span>
        <form:errors path="conditionType" cssClass="field-error"/>
    </div>
    <div>
        <label for="validFrom"><common:message key="blacklist.detail.validFrom"/></label>
        <form:input path="validFrom" type="date"/> <form:errors path="validFrom" cssClass="field-error"/>
    </div>
    <div>
        <label for="validTo"><common:message key="blacklist.detail.validTo"/></label>
        <form:input path="validTo" type="date"/> <form:errors path="validTo" cssClass="field-error"/>
    </div>
    <div>
        <label for="identifierType"><common:message key="blacklist.create.identifierType"/></label>
        <form:select path="identifierType">
            <form:option value=""><common:message key="blacklist.create.choose"/></form:option>
            <c:forEach items="${identifierTypes}" var="t">
                <form:option value="${t}"><common:message key="blacklist.identifier.${t}"/></form:option>
            </c:forEach>
        </form:select>
        <form:errors path="identifierType" cssClass="field-error"/>
    </div>
    <div>
        <label for="identifierValue"><common:message key="blacklist.create.identifierValue"/></label>
        <form:input path="identifierValue"/> <form:errors path="identifierValue" cssClass="field-error"/>
    </div>
    <button type="submit"><common:message key="blacklist.create.submit"/></button>
</form:form>
<form id="cancelForm" method="post" action="${cancelUrl}" class="crm-inline">
    <button type="submit"><common:message key="blacklist.create.cancel"/></button>
</form>
```

- [ ] **Step 5: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 126 + 8 = 134).

- [ ] **Step 6: Checkpoint (bez commitu)** – `git status --short`

---

### Task 14: Dashboard – zrušenie služby (`params="page=…"`, pasca `@ModelAttribute`, JSON resource)

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/deactivation/ProductType.java`, `CustomerProduct.java`, `DeactivationType.java`, `DeactivationRequest.java`, `DeactivationRules.java`, `CustomerProductService.java`, `InMemoryCustomerProductService.java`, `DeactivationService.java`, `InMemoryDeactivationService.java`
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/dashboard/DeactivationForm.java`, `DeactivationFormValidator.java`, `DashboardController.java`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/dashboard/products.jsp`, `jsp/dashboard/deactivation/form.jsp`, `jsp/dashboard/deactivation/done.jsp`
- Modify: `legacy/customercare-portlets/src/main/webapp/WEB-INF/backend-context.xml`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/deactivation/DeactivationBackendTest.java`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/dashboard/DashboardControllerTest.java`

**Interfaces:**
- Consumes: `BaseCrmController`, `NotFoundException` (Task 8, 5); `PortalCustomer` (Task 1); beany `clock`, `objectMapper` (Task 9); JS `Crm.earliestDate` (Task 9)
- Produces:
  - `enum ProductType { TARIFF, PRODUCT }`; `CustomerProduct(String id, String customerId, String name, ProductType type, String description)` – gettery
  - `enum DeactivationType { TRANSFER_TO_CUSTOMER, CUSTOMER_DEACTIVATION, CUSTOMER_POSTPRE }`
  - `DeactivationRequest(long id, String productId, String customerId, String productName, DeactivationType type, LocalDate noticeDate, LocalDate effectiveDate, String operator, LocalDateTime createdAt)` – gettery
  - `DeactivationRules.earliestEffectiveDate(DeactivationType, LocalDate): LocalDate` (prevod = hneď, zrušenie = +30 dní, predplatená karta = +1 deň)
  - `interface CustomerProductService { List<CustomerProduct> productsOf(String customerId); Optional<CustomerProduct> find(String customerId, String productId); }`
  - `interface DeactivationService { DeactivationRequest submit(CustomerProduct, DeactivationType, LocalDate noticeDate, LocalDate effectiveDate, String operator); Optional<DeactivationRequest> find(long id); }` – príliš skorý dátum → `IllegalArgumentException`
  - Produkty: C-1001 → `T-1` „Tarif Neobmedzený“ (TARIFF), `T-2` „Tarif Štandard“ (TARIFF), `P-1` „+421 900 000 001“ (PRODUCT); C-1002 → `T-3` „Firemný tarif“ (TARIFF), `P-2` „+421 900 000 002“ (PRODUCT)
  - `DashboardController(CustomerProductService, DeactivationService, Clock, ObjectMapper)`: render default → `dashboard/products`; render `page=tariffDeactivation` + `productId` → `dashboard/deactivation/form`; resource `earliestDate` (`type`, `noticeDate`) → JSON `{"earliestDate":"…"}`; action `action=submit`; render `page=tariffDeactivationDone` + `requestId` → `dashboard/deactivation/done`
  - JSP: odkazy `id="deactivate-<productId>"`, `form id="deactivationForm"` s `data-earliest-url`, `p id="deactivationDone"`

- [ ] **Step 1: Napíš padajúce testy**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/backend/deactivation/DeactivationBackendTest.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeactivationBackendTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private static final LocalDate NOTICE = LocalDate.of(2026, 10, 8);

    private final CustomerProductService products = new InMemoryCustomerProductService();
    private final DeactivationService deactivations = new InMemoryDeactivationService(CLOCK);

    @Test
    @DisplayName("Demo pravidlá: prevod hneď, zrušenie +30 dní, predplatená karta +1 deň")
    void should_computeEarliestDate_when_typeGiven() {
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.TRANSFER_TO_CUSTOMER, NOTICE)).isEqualTo(NOTICE);
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_DEACTIVATION, NOTICE))
                .isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_POSTPRE, NOTICE))
                .isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("Produkty patria zákazníkovi; cudzí produkt sa nenájde")
    void should_findOnlyOwnProducts_when_queried() {
        assertThat(products.productsOf("C-1001")).extracting(CustomerProduct::getId).containsExactly("T-1", "T-2", "P-1");
        assertThat(products.productsOf("C-1002")).extracting(CustomerProduct::getId).containsExactly("T-3", "P-2");
        assertThat(products.find("C-1001", "T-3")).isNotPresent();
        assertThat(products.find("C-1001", "T-1")).isPresent();
    }

    @Test
    @DisplayName("Žiadosť sa uloží s ID, názvom produktu a časom z hodín")
    void should_storeRequest_when_submitted() {
        CustomerProduct product = products.find("C-1001", "T-1").get();

        DeactivationRequest request = deactivations.submit(product, DeactivationType.CUSTOMER_POSTPRE, NOTICE,
                LocalDate.of(2026, 10, 9), "Správca");

        assertThat(request.getId()).isEqualTo(1L);
        assertThat(request.getProductName()).isEqualTo("Tarif Neobmedzený");
        assertThat(request.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 15));
        assertThat(deactivations.find(1L).get()).isSameAs(request);
    }

    @Test
    @DisplayName("Backend odmietne príliš skorý dátum, aj keby ho formulár prepustil")
    void should_reject_when_effectiveDateTooEarly() {
        CustomerProduct product = products.find("C-1001", "T-1").get();

        assertThatThrownBy(() -> deactivations.submit(product, DeactivationType.CUSTOMER_DEACTIVATION, NOTICE,
                LocalDate.of(2026, 10, 9), "Správca")).isInstanceOf(IllegalArgumentException.class);
    }
}
```

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/dashboard/DashboardControllerTest.java`:

```java
package com.example.crm.frontend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.backend.deactivation.CustomerProduct;
import com.example.crm.frontend.backend.deactivation.DeactivationRequest;
import com.example.crm.frontend.backend.deactivation.DeactivationType;
import com.example.crm.frontend.backend.deactivation.InMemoryCustomerProductService;
import com.example.crm.frontend.backend.deactivation.InMemoryDeactivationService;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.web.NotFoundException;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.mock.web.portlet.MockResourceResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

class DashboardControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryCustomerProductService products = new InMemoryCustomerProductService();
    private final InMemoryDeactivationService deactivations = new InMemoryDeactivationService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final DashboardController controller =
            new DashboardController(products, deactivations, CLOCK, new ObjectMapper());
    private final MockActionResponse response = new MockActionResponse();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(mock(OperatorAcl.class));
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Ján Vzorový"));
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správca"));
    }

    @Test
    @DisplayName("Prehľad ukáže produkty zákazníka zo SSO session")
    @SuppressWarnings("unchecked")
    void should_listProductsOfSsoCustomer_when_rendered() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.products(model)).isEqualTo("dashboard/products");
        assertThat((List<CustomerProduct>) model.get("products")).extracting(CustomerProduct::getId)
                .containsExactly("T-1", "T-2", "P-1");
    }

    @Test
    @DisplayName("Bez zákazníka v SSO session je dashboard 404")
    void should_throwNotFound_when_noCustomer() {
        when(sessionManager.getCustomer()).thenReturn(null);

        assertThatThrownBy(() -> controller.products(new ExtendedModelMap())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Pasca z PoC: @ModelAttribute metóda dodá formulár s predvolenými hodnotami")
    void should_provideDefaultForm_when_modelAttributeMethodCalled() {
        DeactivationForm form = controller.form();

        assertThat(form.getType()).isEqualTo(DeactivationType.CUSTOMER_DEACTIVATION);
        assertThat(form.getNoticeDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Formulár zrušenia: produkt, ID produktu vo formulári a najskorší dátum")
    void should_renderFormForProduct_when_pageTariffDeactivation() {
        ExtendedModelMap model = new ExtendedModelMap();
        DeactivationForm form = controller.form();

        assertThat(controller.deactivationForm("T-1", form, model)).isEqualTo("dashboard/deactivation/form");
        assertThat(((CustomerProduct) model.get("product")).getName()).isEqualTo("Tarif Neobmedzený");
        assertThat(form.getProductId()).isEqualTo("T-1");
        assertThat(model.get("earliestDate")).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    @DisplayName("Produkt iného zákazníka (podvrhnuté ID) je 404")
    void should_throwNotFound_when_productOfOtherCustomer() {
        assertThatThrownBy(() -> controller.deactivationForm("T-3", controller.form(), new ExtendedModelMap()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Legacy pasca @SessionAttributes: pri ďalšom produkte ostane typ z predchádzajúceho formulára")
    void should_keepStaleValues_when_formReusedFromSession() {
        DeactivationForm sessionForm = controller.form();
        controller.deactivationForm("T-1", sessionForm, new ExtendedModelMap());
        sessionForm.setType(DeactivationType.CUSTOMER_POSTPRE);

        controller.deactivationForm("T-2", sessionForm, new ExtendedModelMap());

        assertThat(sessionForm.getProductId()).isEqualTo("T-2");
        assertThat(sessionForm.getType()).isEqualTo(DeactivationType.CUSTOMER_POSTPRE);
    }

    @Test
    @DisplayName("Resource vráti JSON s najskorším dátumom")
    void should_returnJson_when_earliestDateRequested() throws Exception {
        MockResourceResponse resource = new MockResourceResponse();

        controller.earliestDate(DeactivationType.CUSTOMER_DEACTIVATION, LocalDate.of(2026, 10, 8), resource);

        assertThat(resource.getContentType()).startsWith("application/json");
        assertThat(resource.getContentAsString()).isEqualTo("{\"earliestDate\":\"2026-11-07\"}");
    }

    @Test
    @DisplayName("Platná žiadosť: uloží sa, formulár sa ukončí, render na potvrdenie")
    void should_submitAndShowDone_when_formValid() {
        DeactivationForm form = form("T-1", DeactivationType.CUSTOMER_POSTPRE, "2026-10-08", "2026-10-09");
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.submit(form, bindingResult(form), status, response);

        assertThat(deactivations.find(1).get().getOperator()).isEqualTo("Správca");
        assertThat(status.isComplete()).isTrue();
        verify(sessionManager).pushInfo("dashboard.deactivation.submitted");
        assertThat(response.getRenderParameter("page")).isEqualTo("tariffDeactivationDone");
        assertThat(response.getRenderParameter("requestId")).isEqualTo("1");
    }

    @Test
    @DisplayName("Príliš skorý dátum účinnosti: chyba pri poli a späť na formulár produktu")
    void should_rejectEarlyDate_when_submitting() {
        DeactivationForm form = form("T-1", DeactivationType.CUSTOMER_DEACTIVATION, "2026-10-08", "2026-10-09");
        BindingResult errors = bindingResult(form);

        controller.submit(form, errors, new SimpleSessionStatus(), response);

        assertThat(errors.getFieldError("effectiveDate").getCode()).isEqualTo("validation.effectiveTooEarly");
        assertThat(errors.getFieldError("effectiveDate").getArguments()).containsExactly("2026-11-07");
        assertThat(response.getRenderParameter("page")).isEqualTo("tariffDeactivation");
        assertThat(response.getRenderParameter("productId")).isEqualTo("T-1");
        assertThat(deactivations.find(1)).isNotPresent();
    }

    @Test
    @DisplayName("Dátum podania v budúcnosti a chýbajúci typ sú chyby")
    void should_rejectFutureNoticeAndMissingType_when_submitting() {
        DeactivationForm form = form("T-1", null, "2026-10-09", "2026-12-31");
        BindingResult errors = bindingResult(form);

        controller.submit(form, errors, new SimpleSessionStatus(), response);

        assertThat(errors.getFieldError("noticeDate").getCode()).isEqualTo("validation.noticeInFuture");
        assertThat(errors.getFieldError("type").getCode()).isEqualTo("validation.required");
    }

    @Test
    @DisplayName("Potvrdenie ukáže žiadosť; žiadosť iného zákazníka je 404")
    void should_showOwnRequestOnly_when_doneRendered() {
        DeactivationRequest own = deactivations.submit(products.find("C-1001", "T-1").get(),
                DeactivationType.TRANSFER_TO_CUSTOMER, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), "Správca");
        DeactivationRequest foreign = deactivations.submit(products.find("C-1002", "T-3").get(),
                DeactivationType.TRANSFER_TO_CUSTOMER, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), "Správca");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.done(own.getId(), model)).isEqualTo("dashboard/deactivation/done");
        assertThat(model.get("requestNumber")).isEqualTo(String.valueOf(own.getId()));
        assertThatThrownBy(() -> controller.done(foreign.getId(), new ExtendedModelMap()))
                .isInstanceOf(NotFoundException.class);
    }

    private static DeactivationForm form(String productId, DeactivationType type, String notice, String effective) {
        DeactivationForm form = new DeactivationForm();
        form.setProductId(productId);
        form.setType(type);
        form.setNoticeDate(LocalDate.parse(notice));
        form.setEffectiveDate(LocalDate.parse(effective));
        return form;
    }

    private static BindingResult bindingResult(DeactivationForm form) {
        return new BeanPropertyBindingResult(form, DashboardController.FORM);
    }
}
```

- [ ] **Step 2: Spusti testy, musia zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class DashboardController`.

- [ ] **Step 3: Implementuj backend zrušenia služby**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/backend/deactivation/ProductType.java`:

```java
package com.example.crm.frontend.backend.deactivation;

/** Typ produktu zákazníka. */
public enum ProductType { TARIFF, PRODUCT }
```

`…/backend/deactivation/CustomerProduct.java`:

```java
package com.example.crm.frontend.backend.deactivation;

/** Produkt alebo tarif zákazníka (zjednodušené – v legacy bohatý model produktového katalógu). */
public final class CustomerProduct {

    private final String id;
    private final String customerId;
    private final String name;
    private final ProductType type;
    private final String description;

    public CustomerProduct(String id, String customerId, String name, ProductType type, String description) {
        this.id = id;
        this.customerId = customerId;
        this.name = name;
        this.type = type;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public ProductType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }
}
```

`…/backend/deactivation/DeactivationType.java`:

```java
package com.example.crm.frontend.backend.deactivation;

/** Typ žiadosti – tri voľby ako formulár v legacy (prevod, zrušenie, prevod na predplatenú kartu). */
public enum DeactivationType { TRANSFER_TO_CUSTOMER, CUSTOMER_DEACTIVATION, CUSTOMER_POSTPRE }
```

`…/backend/deactivation/DeactivationRequest.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Zaevidovaná žiadosť o zrušenie. */
public final class DeactivationRequest {

    private final long id;
    private final String productId;
    private final String customerId;
    private final String productName;
    private final DeactivationType type;
    private final LocalDate noticeDate;
    private final LocalDate effectiveDate;
    private final String operator;
    private final LocalDateTime createdAt;

    public DeactivationRequest(long id, String productId, String customerId, String productName, DeactivationType type,
                               LocalDate noticeDate, LocalDate effectiveDate, String operator, LocalDateTime createdAt) {
        this.id = id;
        this.productId = productId;
        this.customerId = customerId;
        this.productName = productName;
        this.type = type;
        this.noticeDate = noticeDate;
        this.effectiveDate = effectiveDate;
        this.operator = operator;
        this.createdAt = createdAt;
    }

    public long getId() {
        return id;
    }

    public String getProductId() {
        return productId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductName() {
        return productName;
    }

    public DeactivationType getType() {
        return type;
    }

    public LocalDate getNoticeDate() {
        return noticeDate;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public String getOperator() {
        return operator;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
```

`…/backend/deactivation/DeactivationRules.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.time.LocalDate;

/**
 * DEMO pravidlá – VYMYSLENÉ, nie business pravidlá klienta. Žijú na jednom mieste a používa ich validátor
 * formulára, JSON resource pre AJAX aj backend služba.
 */
public final class DeactivationRules {

    private DeactivationRules() {
    }

    /** Najskorší dátum účinnosti pre typ žiadosti a dátum podania. */
    public static LocalDate earliestEffectiveDate(DeactivationType type, LocalDate noticeDate) {
        switch (type) {
            case TRANSFER_TO_CUSTOMER:
                return noticeDate;
            case CUSTOMER_DEACTIVATION:
                return noticeDate.plusDays(30);
            default:
                return noticeDate.plusDays(1);
        }
    }
}
```

`…/backend/deactivation/CustomerProductService.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.util.List;
import java.util.Optional;

/** Port na produkty zákazníka (v legacy viacero backend služieb). */
public interface CustomerProductService {

    /** Produkty zákazníka. */
    List<CustomerProduct> productsOf(String customerId);

    /** Produkt len vtedy, ak patrí zákazníkovi – podvrhnuté ID produktu iného zákazníka vráti prázdny výsledok. */
    Optional<CustomerProduct> find(String customerId, String productId);
}
```

`…/backend/deactivation/InMemoryCustomerProductService.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Vymyslené produkty demo zákazníkov (ID zákazníkov zodpovedajú SSO mocku). */
public class InMemoryCustomerProductService implements CustomerProductService {

    private final List<CustomerProduct> products = Arrays.asList(
            new CustomerProduct("T-1", "C-1001", "Tarif Neobmedzený", ProductType.TARIFF, "Neobmedzené dáta, volania a SMS"),
            new CustomerProduct("T-2", "C-1001", "Tarif Štandard", ProductType.TARIFF, "5 GB dát, 100 minút"),
            new CustomerProduct("P-1", "C-1001", "+421 900 000 001", ProductType.PRODUCT, "Mobilné číslo – Tarif Neobmedzený"),
            new CustomerProduct("T-3", "C-1002", "Firemný tarif", ProductType.TARIFF, "Zdieľané dáta pre firmu"),
            new CustomerProduct("P-2", "C-1002", "+421 900 000 002", ProductType.PRODUCT, "Mobilné číslo – Firemný tarif"));

    @Override
    public List<CustomerProduct> productsOf(String customerId) {
        return products.stream().filter(p -> p.getCustomerId().equals(customerId)).collect(Collectors.toList());
    }

    @Override
    public Optional<CustomerProduct> find(String customerId, String productId) {
        return productsOf(customerId).stream().filter(p -> p.getId().equals(productId)).findFirst();
    }
}
```

`…/backend/deactivation/DeactivationService.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.time.LocalDate;
import java.util.Optional;

/** Port na backend žiadostí o zrušenie (v legacy manager + viacero backend služieb). */
public interface DeactivationService {

    /** Zaeviduje žiadosť; príliš skorý dátum účinnosti → {@link IllegalArgumentException}. */
    DeactivationRequest submit(CustomerProduct product, DeactivationType type, LocalDate noticeDate,
                               LocalDate effectiveDate, String operator);

    /** Žiadosť podľa ID. */
    Optional<DeactivationRequest> find(long id);
}
```

`…/backend/deactivation/InMemoryDeactivationService.java`:

```java
package com.example.crm.frontend.backend.deactivation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory evidencia žiadostí. */
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
        // Backend overí pravidlo znova – validácia formulára je pre používateľa, nie ochrana dát.
        if (effectiveDate.isBefore(earliest)) {
            throw new IllegalArgumentException("Dátum účinnosti " + effectiveDate + " je pred " + earliest);
        }
        DeactivationRequest request = new DeactivationRequest(ids.incrementAndGet(), product.getId(),
                product.getCustomerId(), product.getName(), type, noticeDate, effectiveDate, operator,
                LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES));
        requests.put(request.getId(), request);
        return request;
    }

    @Override
    public Optional<DeactivationRequest> find(long id) {
        return Optional.ofNullable(requests.get(id));
    }
}
```

V `backend-context.xml` pridaj pred `</beans>`:

```xml
    <bean id="customerProductService"
          class="com.example.crm.frontend.backend.deactivation.InMemoryCustomerProductService"/>
    <bean id="deactivationService" class="com.example.crm.frontend.backend.deactivation.InMemoryDeactivationService">
        <constructor-arg ref="clock"/>
    </bean>
```

- [ ] **Step 4: Implementuj formulár, validátor a controller**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/dashboard/DeactivationForm.java`:

```java
package com.example.crm.frontend.dashboard;

import com.example.crm.frontend.backend.deactivation.DeactivationType;
import java.io.Serializable;
import java.time.LocalDate;

/** Formulár žiadosti o zrušenie; v legacy držaný v {@code @SessionAttributes} (viď pasca v DashboardController). */
public class DeactivationForm implements Serializable {

    private static final long serialVersionUID = 1L;

    private String productId;
    private DeactivationType type;
    private LocalDate noticeDate;
    private LocalDate effectiveDate;

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public DeactivationType getType() {
        return type;
    }

    public void setType(DeactivationType type) {
        this.type = type;
    }

    public LocalDate getNoticeDate() {
        return noticeDate;
    }

    public void setNoticeDate(LocalDate noticeDate) {
        this.noticeDate = noticeDate;
    }

    public LocalDate getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(LocalDate effectiveDate) {
        this.effectiveDate = effectiveDate;
    }
}
```

`…/dashboard/DeactivationFormValidator.java`:

```java
package com.example.crm.frontend.dashboard;

import com.example.crm.frontend.backend.deactivation.DeactivationRules;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/** Vlastný validátor formulára zrušenia (legacy vzor). Pravidlo dátumu berie z {@link DeactivationRules}. */
public class DeactivationFormValidator implements Validator {

    private static final String REQUIRED = "validation.required";

    private final Clock clock;

    public DeactivationFormValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean supports(Class<?> type) {
        return DeactivationForm.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        DeactivationForm form = (DeactivationForm) target;
        require(errors, "type", form.getType());
        require(errors, "noticeDate", form.getNoticeDate());
        require(errors, "effectiveDate", form.getEffectiveDate());
        if (form.getNoticeDate() != null && form.getNoticeDate().isAfter(LocalDate.now(clock))) {
            errors.rejectValue("noticeDate", "validation.noticeInFuture");
        }
        if (form.getType() != null && form.getNoticeDate() != null && form.getEffectiveDate() != null) {
            LocalDate earliest = DeactivationRules.earliestEffectiveDate(form.getType(), form.getNoticeDate());
            if (form.getEffectiveDate().isBefore(earliest)) {
                errors.rejectValue("effectiveDate", "validation.effectiveTooEarly", new Object[] {earliest.toString()}, null);
            }
        }
    }

    private static void require(Errors errors, String field, Object value) {
        if (value == null && !errors.hasFieldErrors(field)) {
            errors.rejectValue(field, REQUIRED);
        }
    }
}
```

`…/dashboard/DashboardController.java`:

```java
package com.example.crm.frontend.dashboard;

import com.example.crm.frontend.backend.deactivation.CustomerProduct;
import com.example.crm.frontend.backend.deactivation.CustomerProductService;
import com.example.crm.frontend.backend.deactivation.DeactivationRequest;
import com.example.crm.frontend.backend.deactivation.DeactivationRules;
import com.example.crm.frontend.backend.deactivation.DeactivationService;
import com.example.crm.frontend.backend.deactivation.DeactivationType;
import com.example.crm.frontend.base.web.BaseCrmController;
import com.example.crm.frontend.base.web.NotFoundException;
import com.example.sso.api.PortalCustomer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import javax.portlet.ActionResponse;
import javax.portlet.ResourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * Dashboard zákazníka a stránka zrušenia služby. „Stránka“ sa vyberá render parametrom {@code page}
 * (legacy používa vlastný HandlerMapping a friendly URL; tu štandardné {@code params}). Po migrácii z toho budú
 * URL cesty {@code /dashboard/{customerId}/products/{productId}/deactivation}.
 */
@Controller
@RequestMapping("VIEW")
@SessionAttributes(DashboardController.FORM)
public class DashboardController extends BaseCrmController {

    static final String FORM = "deactivationForm";

    private final CustomerProductService products;
    private final DeactivationService deactivations;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final DeactivationFormValidator validator;

    @Autowired
    public DashboardController(CustomerProductService products, DeactivationService deactivations, Clock clock,
                               ObjectMapper objectMapper) {
        this.products = products;
        this.deactivations = deactivations;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.validator = new DeactivationFormValidator(clock);
    }

    /**
     * Pasca z PoC logu: bez tejto metódy JSP s {@code <form:form modelAttribute="deactivationForm">} spadne
     * na „Neither BindingResult nor plain target object…“. Pri prepise portletov sa ľahko stratí.
     */
    @ModelAttribute(FORM)
    public DeactivationForm form() {
        DeactivationForm form = new DeactivationForm();
        form.setType(DeactivationType.CUSTOMER_DEACTIVATION);
        form.setNoticeDate(LocalDate.now(clock));
        return form;
    }

    /** Typy žiadosti pre radio buttony. */
    @ModelAttribute("deactivationTypes")
    public DeactivationType[] deactivationTypes() {
        return DeactivationType.values();
    }

    /** Prehľad produktov zákazníka zo SSO session. */
    @RenderMapping
    public String products(Model model) {
        PortalCustomer customer = requireCustomer();
        model.addAttribute("customer", customer);
        model.addAttribute("products", products.productsOf(customer.getCustomerId()));
        return "dashboard/products";
    }

    /** Formulár zrušenia produktu. */
    @RenderMapping(params = "page=tariffDeactivation")
    public String deactivationForm(@RequestParam("productId") String productId,
                                   @ModelAttribute(FORM) DeactivationForm form, Model model) {
        CustomerProduct product = requireProduct(productId);
        // Vedomá reprodukcia legacy: formulár žije v @SessionAttributes, takže typ a dátumy ostanú
        // z predchádzajúceho produktu. Migrácia to opraví (nový formulár pre každý produkt).
        form.setProductId(product.getId());
        model.addAttribute("product", product);
        if (form.getType() != null && form.getNoticeDate() != null) {
            model.addAttribute("earliestDate", DeactivationRules.earliestEffectiveDate(form.getType(), form.getNoticeDate()));
        }
        return "dashboard/deactivation/form";
    }

    /** AJAX: najskorší dátum účinnosti ako JSON. */
    @ResourceMapping("earliestDate")
    public void earliestDate(@RequestParam("type") DeactivationType type, @RequestParam("noticeDate") LocalDate noticeDate,
                             ResourceResponse response) throws IOException {
        LocalDate earliest = DeactivationRules.earliestEffectiveDate(type, noticeDate);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                Collections.singletonMap("earliestDate", earliest.toString())));
    }

    /** Odoslanie žiadosti. */
    @ActionMapping(params = "action=submit")
    public void submit(@ModelAttribute(FORM) DeactivationForm form, BindingResult result, SessionStatus status,
                       ActionResponse response) {
        CustomerProduct product = requireProduct(form.getProductId());
        validator.validate(form, result);
        if (result.hasErrors()) {
            response.setRenderParameter("page", "tariffDeactivation");
            response.setRenderParameter("productId", product.getId());
            return;
        }
        DeactivationRequest request = deactivations.submit(product, form.getType(), form.getNoticeDate(),
                form.getEffectiveDate(), operatorName());
        status.setComplete();
        getSessionManager().pushInfo("dashboard.deactivation.submitted");
        response.setRenderParameter("page", "tariffDeactivationDone");
        response.setRenderParameter("requestId", String.valueOf(request.getId()));
    }

    /** Potvrdenie žiadosti (len žiadosť aktuálneho zákazníka). */
    @RenderMapping(params = "page=tariffDeactivationDone")
    public String done(@RequestParam("requestId") long requestId, Model model) {
        DeactivationRequest request = deactivations.find(requestId).orElse(null);
        if (request == null || !request.getCustomerId().equals(requireCustomer().getCustomerId())) {
            throw new NotFoundException("žiadosť " + requestId);
        }
        model.addAttribute("deactivationRequest", request);
        // Číslo ako text: MessageFormat by inak číslo naformátoval podľa locale (1 000).
        model.addAttribute("requestNumber", String.valueOf(request.getId()));
        return "dashboard/deactivation/done";
    }

    private CustomerProduct requireProduct(String productId) {
        CustomerProduct product = products.find(requireCustomer().getCustomerId(), productId).orElse(null);
        if (product == null) {
            throw new NotFoundException("produkt " + productId + " aktuálneho zákazníka");
        }
        return product;
    }
}
```

- [ ] **Step 5: JSP**

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/dashboard/products.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<h2><common:message key="dashboard.title"/> – <c:out value="${customer.displayName}"/></h2>
<c:choose>
    <c:when test="${empty products}"><p><common:message key="dashboard.products.empty"/></p></c:when>
    <c:otherwise>
        <table id="products">
            <thead>
            <tr>
                <th><common:message key="dashboard.products.name"/></th>
                <th><common:message key="dashboard.products.type"/></th>
                <th><common:message key="dashboard.products.description"/></th>
                <th></th>
            </tr>
            </thead>
            <tbody>
            <c:forEach items="${products}" var="p">
                <portal:renderURL var="deactivateUrl">
                    <portal:param name="page" value="tariffDeactivation"/>
                    <portal:param name="productId" value="${p.id}"/>
                </portal:renderURL>
                <tr>
                    <td><c:out value="${p.name}"/></td>
                    <td><common:message key="dashboard.type.${p.type}"/></td>
                    <td><c:out value="${p.description}"/></td>
                    <td><a id="deactivate-${p.id}" href="${deactivateUrl}"><common:message key="dashboard.products.deactivate"/></a></td>
                </tr>
            </c:forEach>
            </tbody>
        </table>
    </c:otherwise>
</c:choose>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/dashboard/deactivation/form.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:actionURL var="submitUrl" escapeXml="false"><portal:param name="action" value="submit"/></portal:actionURL>
<portal:resourceURL id="earliestDate" var="earliestUrl" escapeXml="false"/>
<portal:renderURL var="productsUrl"/>

<h2><common:message key="dashboard.deactivation.title"/></h2>
<p><common:message key="dashboard.deactivation.product"/>: <strong id="deactivationProduct"><c:out value="${product.name}"/></strong></p>
<div id="crm-ajax-error"></div>
<form:form id="deactivationForm" method="post" action="${submitUrl}" modelAttribute="deactivationForm"
           data-earliest-url="${earliestUrl}">
    <form:hidden path="productId"/>
    <fieldset>
        <legend><common:message key="dashboard.deactivation.type"/></legend>
        <c:forEach items="${deactivationTypes}" var="t">
            <label><form:radiobutton path="type" value="${t}" onchange="Crm.earliestDate(this.form)"/>
                <common:message key="dashboard.deactivationType.${t}"/></label><br/>
        </c:forEach>
        <form:errors path="type" cssClass="field-error"/>
    </fieldset>
    <div>
        <label for="noticeDate"><common:message key="dashboard.deactivation.noticeDate"/></label>
        <form:input path="noticeDate" type="date" onchange="Crm.earliestDate(this.form)"/>
        <form:errors path="noticeDate" cssClass="field-error"/>
    </div>
    <div>
        <label for="effectiveDate"><common:message key="dashboard.deactivation.effectiveDate"/></label>
        <form:input path="effectiveDate" type="date"/>
        <common:message key="dashboard.deactivation.earliest"/> <span id="earliestHint">${earliestDate}</span>
        <form:errors path="effectiveDate" cssClass="field-error"/>
    </div>
    <button type="submit"><common:message key="dashboard.deactivation.submit"/></button>
</form:form>
<a href="${productsUrl}"><common:message key="dashboard.deactivation.backToProducts"/></a>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/jsp/dashboard/deactivation/done.jsp`:

```jsp
<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:renderURL var="productsUrl"/>
<h2><common:message key="dashboard.deactivation.title"/></h2>
<p id="deactivationDone">
    <common:message key="dashboard.deactivation.done">
        <common:msgarg value="${requestNumber}"/>
        <common:msgarg value="${deactivationRequest.productName}"/>
        <common:msgarg value="${deactivationRequest.effectiveDate}"/>
    </common:message>
</p>
<a href="${productsUrl}"><common:message key="dashboard.deactivation.backToProducts"/></a>
```

> `${deactivationRequest.effectiveDate}` je `LocalDate` – `MessageFormat` ho vypíše cez `toString()` (ISO dátum).

- [ ] **Step 6: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 134 + 4 + 11 = 149).

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 15: Startup hook, hooked chybová stránka a theme WAR

**Files:**
- Create: `legacy/customercare-portlets/src/main/java/com/example/crm/frontend/hook/CrmStartupAction.java`
- Create: `legacy/customercare-portlets/src/main/resources/portal.properties`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/liferay-hook.xml`
- Create: `legacy/customercare-portlets/src/main/webapp/WEB-INF/hooked/html/portal/status.jsp`
- Test: `legacy/customercare-portlets/src/test/java/com/example/crm/frontend/hook/CrmStartupActionTest.java`
- Modify: `legacy/pom.xml` (modul `customercare-theme`)
- Create: `legacy/customercare-theme/pom.xml`
- Create: `legacy/customercare-theme/src/main/webapp/WEB-INF/web.xml`, `WEB-INF/liferay-look-and-feel.xml`, `WEB-INF/liferay-plugin-package.properties`
- Create: `legacy/customercare-theme/src/main/webapp/templates/portal_normal.vm`, `portal_pop_up.vm`, `portlet.vm`, `navigation.vm`, `init_custom.vm`
- Create: `legacy/customercare-theme/src/main/webapp/css/main.css`

**Interfaces:**
- Consumes: Liferay 6.2.5 API (`*LocalServiceUtil`, `PortletPermissionUtil`, `SimpleAction`); ID portletov a theme z Global Constraints
- Produces:
  - `CrmStartupAction extends SimpleAction` – idempotentne pre každú company: site „CRM“ (`/crm`, `TYPE_SITE_OPEN`), theme `customercare_WAR_customercaretheme`, verejné stránky `/blacklist` (portlet `blacklist_WAR_customercareportlet`) a `/dashboard` (`dashboard_WAR_customercareportlet`) s layoutom `1_column`, hosť má VIEW na inštancie portletov
  - Hook: `application.startup.events`, `custom-jsp-dir=/WEB-INF/hooked` (prepíše `/html/portal/status.jsp` – element `id="crmPortalError"`)
  - Theme `customercare` (WAR `customercare-theme`), vkladá `$theme.runtime("header_WAR_customercareportlet")`

- [ ] **Step 1: Napíš padajúci test hooku**

`legacy/customercare-portlets/src/test/java/com/example/crm/frontend/hook/CrmStartupActionTest.java`:

```java
package com.example.crm.frontend.hook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.liferay.portal.kernel.bean.BeanLocator;
import com.liferay.portal.kernel.bean.PortalBeanLocatorUtil;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Layout;
import com.liferay.portal.model.LayoutTypePortlet;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.Role;
import com.liferay.portal.model.RoleConstants;
import com.liferay.portal.service.GroupLocalService;
import com.liferay.portal.service.LayoutLocalService;
import com.liferay.portal.service.LayoutSetLocalService;
import com.liferay.portal.service.ResourcePermissionLocalService;
import com.liferay.portal.service.RoleLocalService;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.UserLocalService;
import com.liferay.portal.service.permission.PortletPermission;
import com.liferay.portal.service.permission.PortletPermissionUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CrmStartupActionTest {

    private static final long COMPANY = 20155L;
    private static final long GROUP = 30001L;

    // *LocalServiceUtil v 6.2 si službu nájde cez PortalBeanLocatorUtil a uloží staticky (setService je prázdny
    // deprecated) – mocky sú preto statické, podstrčené raz cez BeanLocator a pred každým testom resetované.
    private static final GroupLocalService groups = mock(GroupLocalService.class);
    private static final LayoutLocalService layouts = mock(LayoutLocalService.class);
    private static final LayoutSetLocalService layoutSets = mock(LayoutSetLocalService.class);
    private static final RoleLocalService roles = mock(RoleLocalService.class);
    private static final UserLocalService users = mock(UserLocalService.class);
    private static final ResourcePermissionLocalService permissions = mock(ResourcePermissionLocalService.class);
    private static final PortletPermission portletPermission = mock(PortletPermission.class);
    private final Group site = mock(Group.class);

    @BeforeAll
    static void injectLiferayServices() {
        BeanLocator locator = mock(BeanLocator.class);
        when(locator.locate(GroupLocalService.class.getName())).thenReturn(groups);
        when(locator.locate(LayoutLocalService.class.getName())).thenReturn(layouts);
        when(locator.locate(LayoutSetLocalService.class.getName())).thenReturn(layoutSets);
        when(locator.locate(RoleLocalService.class.getName())).thenReturn(roles);
        when(locator.locate(UserLocalService.class.getName())).thenReturn(users);
        when(locator.locate(ResourcePermissionLocalService.class.getName())).thenReturn(permissions);
        PortalBeanLocatorUtil.setBeanLocator(locator);
        new PortletPermissionUtil().setPortletPermission(portletPermission);
    }

    @BeforeEach
    void stubLiferayServices() throws Exception {
        reset(groups, layouts, layoutSets, roles, users, permissions, portletPermission);
        when(users.getDefaultUserId(COMPANY)).thenReturn(10L);
        Role guest = mock(Role.class);
        when(guest.getRoleId()).thenReturn(7L);
        when(roles.getRole(COMPANY, RoleConstants.GUEST)).thenReturn(guest);
        when(site.getGroupId()).thenReturn(GROUP);
        when(portletPermission.getPrimaryKey(anyLong(), anyString())).thenReturn("pk");
    }

    @Test
    @DisplayName("Prvý štart: vytvorí site, obe stránky s portletmi, nastaví theme a VIEW pre hosťa")
    void should_createSiteAndPages_when_missing() throws Exception {
        when(groups.addGroup(anyLong(), anyLong(), any(), anyLong(), anyLong(), eq("CRM"), anyString(), anyInt(),
                anyBoolean(), anyInt(), eq("/crm"), anyBoolean(), anyBoolean(), any(ServiceContext.class))).thenReturn(site);
        Layout layout = newLayout();
        when(layouts.addLayout(anyLong(), eq(GROUP), eq(false), anyLong(), anyString(), anyString(), anyString(),
                anyString(), eq(false), anyString(), any(ServiceContext.class))).thenReturn(layout);
        when(layouts.updateLayout(anyLong(), anyBoolean(), anyLong(), anyString())).thenReturn(layout);

        new CrmStartupAction().run(new String[] {String.valueOf(COMPANY)});

        verify(layouts).addLayout(anyLong(), eq(GROUP), eq(false), anyLong(), eq("Blacklist"), anyString(), anyString(),
                anyString(), eq(false), eq("/blacklist"), any(ServiceContext.class));
        verify(layouts).addLayout(anyLong(), eq(GROUP), eq(false), anyLong(), eq("Dashboard"), anyString(), anyString(),
                anyString(), eq(false), eq("/dashboard"), any(ServiceContext.class));
        verify((LayoutTypePortlet) layout.getLayoutType()).addPortletId(10L, "blacklist_WAR_customercareportlet",
                "column-1", -1, false);
        verify(layoutSets).updateLookAndFeel(GROUP, false, CrmStartupAction.THEME_ID, "01", "", false);
        verify(permissions, times(2)).setResourcePermissions(eq(COMPANY), anyString(),
                eq(ResourceConstants.SCOPE_INDIVIDUAL), eq("pk"), eq(7L), any(String[].class));
    }

    @Test
    @DisplayName("Ďalší štart (reštart Liferay, redeploy): nič nevytvára znova")
    void should_notDuplicate_when_siteAndPagesExist() throws Exception {
        when(groups.fetchFriendlyURLGroup(COMPANY, "/crm")).thenReturn(site);
        Layout layout = newLayout();
        when(layouts.fetchLayoutByFriendlyURL(eq(GROUP), eq(false), anyString())).thenReturn(layout);

        new CrmStartupAction().run(new String[] {String.valueOf(COMPANY)});

        verify(groups, never()).addGroup(anyLong(), anyLong(), any(), anyLong(), anyLong(), anyString(), anyString(),
                anyInt(), anyBoolean(), anyInt(), anyString(), anyBoolean(), anyBoolean(), any(ServiceContext.class));
        verify(layouts, never()).addLayout(anyLong(), anyLong(), anyBoolean(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyBoolean(), anyString(), any(ServiceContext.class));
    }

    private static Layout newLayout() {
        Layout layout = mock(Layout.class);
        LayoutTypePortlet type = mock(LayoutTypePortlet.class);
        when(layout.getLayoutType()).thenReturn(type);
        when(layout.getGroupId()).thenReturn(GROUP);
        when(layout.getPlid()).thenReturn(500L);
        when(layout.getTypeSettings()).thenReturn("layout-template-id=1_column");
        return layout;
    }
}
```

- [ ] **Step 2: Spusti test, musí zlyhať**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: FAIL – kompilácia: `cannot find symbol: class CrmStartupAction`.

- [ ] **Step 3: Implementuj startup action a hook**

`legacy/customercare-portlets/src/main/java/com/example/crm/frontend/hook/CrmStartupAction.java`:

```java
package com.example.crm.frontend.hook;

import com.liferay.portal.kernel.events.ActionException;
import com.liferay.portal.kernel.events.SimpleAction;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.GroupConstants;
import com.liferay.portal.model.Layout;
import com.liferay.portal.model.LayoutConstants;
import com.liferay.portal.model.LayoutTypePortlet;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.RoleConstants;
import com.liferay.portal.security.permission.ActionKeys;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portal.service.LayoutLocalServiceUtil;
import com.liferay.portal.service.LayoutSetLocalServiceUtil;
import com.liferay.portal.service.ResourcePermissionLocalServiceUtil;
import com.liferay.portal.service.RoleLocalServiceUtil;
import com.liferay.portal.service.ServiceContext;
import com.liferay.portal.service.UserLocalServiceUtil;
import com.liferay.portal.service.permission.PortletPermissionUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Startup hook ako v legacy: pri štarte portálu (a pri každom deployi WAR-u) vytvorí site, stránky a rozmiestni
 * portlety cez {@code *LocalServiceUtil}. Musí byť idempotentný. Po migrácii odpadá celý – nie je portál,
 * stránky sú URL cesty v controlleroch.
 */
public class CrmStartupAction extends SimpleAction {

    static final String SITE_NAME = "CRM";
    static final String SITE_FRIENDLY_URL = "/crm";
    static final String THEME_ID = "customercare_WAR_customercaretheme";

    private static final Log LOG = LogFactory.getLog(CrmStartupAction.class);
    private static final String[][] PAGES = {
            {"/blacklist", "Blacklist", "blacklist_WAR_customercareportlet"},
            {"/dashboard", "Dashboard", "dashboard_WAR_customercareportlet"},
    };

    @Override
    public void run(String[] companyIds) throws ActionException {
        for (String companyId : companyIds) {
            try {
                setUp(Long.parseLong(companyId));
            } catch (PortalException | SystemException e) {
                throw new ActionException("Nastavenie site CRM zlyhalo pre company " + companyId, e);
            }
        }
    }

    private void setUp(long companyId) throws PortalException, SystemException {
        long userId = UserLocalServiceUtil.getDefaultUserId(companyId);
        ServiceContext serviceContext = new ServiceContext();
        serviceContext.setCompanyId(companyId);
        serviceContext.setUserId(userId);
        serviceContext.setAddGuestPermissions(true);
        serviceContext.setAddGroupPermissions(true);

        Group site = GroupLocalServiceUtil.fetchFriendlyURLGroup(companyId, SITE_FRIENDLY_URL);
        if (site == null) {
            site = GroupLocalServiceUtil.addGroup(userId, GroupConstants.DEFAULT_PARENT_GROUP_ID, null, 0,
                    GroupConstants.DEFAULT_LIVE_GROUP_ID, SITE_NAME, "Demo legacy CRM", GroupConstants.TYPE_SITE_OPEN,
                    true, GroupConstants.DEFAULT_MEMBERSHIP_RESTRICTION, SITE_FRIENDLY_URL, true, true, serviceContext);
            LOG.info("Vytvorená site " + SITE_NAME);
        }
        LayoutSetLocalServiceUtil.updateLookAndFeel(site.getGroupId(), false, THEME_ID, "01", "", false);
        long guestRoleId = RoleLocalServiceUtil.getRole(companyId, RoleConstants.GUEST).getRoleId();
        for (String[] page : PAGES) {
            ensurePage(companyId, site, userId, guestRoleId, serviceContext, page[0], page[1], page[2]);
        }
    }

    private void ensurePage(long companyId, Group site, long userId, long guestRoleId, ServiceContext serviceContext,
                            String friendlyUrl, String name, String portletId) throws PortalException, SystemException {
        Layout layout = LayoutLocalServiceUtil.fetchLayoutByFriendlyURL(site.getGroupId(), false, friendlyUrl);
        if (layout == null) {
            layout = LayoutLocalServiceUtil.addLayout(userId, site.getGroupId(), false,
                    LayoutConstants.DEFAULT_PARENT_LAYOUT_ID, name, name, "", LayoutConstants.TYPE_PORTLET, false,
                    friendlyUrl, serviceContext);
            LayoutTypePortlet type = (LayoutTypePortlet) layout.getLayoutType();
            type.setLayoutTemplateId(userId, "1_column", false);
            type.addPortletId(userId, portletId, "column-1", -1, false);
            layout = LayoutLocalServiceUtil.updateLayout(layout.getGroupId(), layout.isPrivateLayout(),
                    layout.getLayoutId(), layout.getTypeSettings());
            LOG.info("Vytvorená stránka " + friendlyUrl + " s portletom " + portletId);
        }
        try {
            // Hosť = každý operátor (identitu pozná len SSO, nie Liferay). Pri prvom deployi ešte portlet nemusí
            // byť zaregistrovaný – vtedy to dorobí ďalší štart alebo predvolené oprávnenia portletu.
            ResourcePermissionLocalServiceUtil.setResourcePermissions(companyId, portletId,
                    ResourceConstants.SCOPE_INDIVIDUAL, PortletPermissionUtil.getPrimaryKey(layout.getPlid(), portletId),
                    guestRoleId, new String[] {ActionKeys.VIEW});
        } catch (PortalException | SystemException | RuntimeException e) {
            LOG.warn("Oprávnenie VIEW pre hosťa na " + portletId + " sa nepodarilo nastaviť: " + e.getMessage());
        }
    }
}
```

`legacy/customercare-portlets/src/main/resources/portal.properties`:

```properties
# Hook portal.properties (legacy: startup event, ktorý pripraví site a stránky).
application.startup.events=com.example.crm.frontend.hook.CrmStartupAction
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/liferay-hook.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE hook PUBLIC "-//Liferay//DTD Hook 6.2.0//EN" "http://www.liferay.com/dtd/liferay-hook_6_2_0.dtd">
<hook>
    <portal-properties>portal.properties</portal-properties>
    <!-- Prepis JSP portálu (legacy: chybové stránky a meta tagy). Pôvodný súbor Liferay odloží ako *.portal.jsp. -->
    <custom-jsp-dir>/WEB-INF/hooked</custom-jsp-dir>
</hook>
```

`legacy/customercare-portlets/src/main/webapp/WEB-INF/hooked/html/portal/status.jsp`:

```jsp
<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Hook custom-jsp-dir: portálová stránka chýb (neexistujúca stránka, chýbajúce oprávnenie). Po migrácii ju
     nahradí chybová stránka Spring Boot – portál už nebude. --%>
<div class="crm-portal-error" id="crmPortalError">
    <h2>Stránka nie je dostupná</h2>
    <p>Požadovaná stránka neexistuje alebo k nej nemáte prístup.</p>
    <p><a href="/web/crm/blacklist">Späť do CRM</a></p>
</div>
```

- [ ] **Step 4: Spusti testy, musia prejsť**

Run: `mvn -q -B -f legacy/pom.xml test`
Expected: PASS (doteraz 149 + 2 = 151).

- [ ] **Step 5: Theme WAR**

V `legacy/pom.xml` doplň do `<modules>`:

```xml
        <module>customercare-theme</module>
```

`legacy/customercare-theme/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.example.crm.legacy</groupId>
        <artifactId>legacy-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <!--
      Liferay theme ako samostatný WAR (legacy: Velocity šablóny). Bez liferay-maven-plugin: theme je úplný sám
      o sebe (nededí z _styled), preto je vzhľad portálových prvkov (dockbar) holý – pre demo to stačí.
    -->
    <artifactId>customercare-theme</artifactId>
    <packaging>war</packaging>

    <build>
        <finalName>customercare-theme</finalName>
    </build>
</project>
```

`legacy/customercare-theme/src/main/webapp/WEB-INF/web.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Minimálny deskriptor theme WAR-u; Liferay pri deployi doplní vlastné listenery a filtre. -->
<web-app xmlns="http://java.sun.com/xml/ns/javaee" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd"
         version="3.0">
    <display-name>customercare-theme</display-name>
</web-app>
```

`legacy/customercare-theme/src/main/webapp/WEB-INF/liferay-look-and-feel.xml`:

```xml
<?xml version="1.0"?>
<!DOCTYPE look-and-feel PUBLIC "-//Liferay//DTD Look and Feel 6.2.0//EN"
        "http://www.liferay.com/dtd/liferay-look-and-feel_6_2_0.dtd">
<look-and-feel>
    <compatibility>
        <version>6.2.0+</version>
    </compatibility>
    <theme id="customercare" name="CRM (demo)">
        <template-extension>vm</template-extension>
        <settings>
            <setting configurable="false" key="portlet-setup-show-borders-default" value="false"/>
        </settings>
    </theme>
</look-and-feel>
```

`legacy/customercare-theme/src/main/webapp/WEB-INF/liferay-plugin-package.properties`:

```properties
name=Customer care theme (demo)
module-group-id=com.example.crm
module-incremental-version=1
tags=theme
short-description=Jednoduchý theme legacy CRM – hlavička je portlet vložený cez $theme.runtime
author=demo
licenses=MIT
liferay-versions=6.2.0+
```

`legacy/customercare-theme/src/main/webapp/templates/init_custom.vm`:

```
## Vlastné premenné theme (legacy ich tu mal pre branding). Liferay tento súbor vyžaduje, aj keď je prázdny.
```

`legacy/customercare-theme/src/main/webapp/templates/portal_normal.vm`:

```
<!DOCTYPE html>
#parse ($init)
<html class="$root_css_class" dir="#language ("lang.dir")" lang="$w3c_language_id">
<head>
    <title>$the_title - CRM</title>
    <meta content="initial-scale=1.0, width=device-width" name="viewport" />
    $theme.include($top_head_include)
</head>
<body class="$css_class">
$theme.include($body_top_include)
#if ($is_signed_in)
    #dockbar()
#end
<div id="wrapper" class="crm-wrapper">
    <header id="banner" role="banner">
        <div class="crm-brand">CRM – legacy (Liferay 6.2)</div>
        #parse ("$full_templates_path/navigation.vm")
        ## Hlavička CRM je portlet (legacy: stránku skladá portál z portletov a theme).
        $theme.runtime("header_WAR_customercareportlet")
    </header>
    <div id="content">
        #if ($selectable)
            $theme.include($content_include)
        #else
            $portletDisplay.recycle()
            $portletDisplay.setTitle($the_title)
            $theme.wrapPortlet("portlet.vm", $content_include)
        #end
    </div>
</div>
$theme.include($body_bottom_include)
$theme.include($bottom_include)
</body>
</html>
```

`legacy/customercare-theme/src/main/webapp/templates/portal_pop_up.vm`:

```
<!DOCTYPE html>
#parse ($init)
<html class="$root_css_class" dir="#language ("lang.dir")" lang="$w3c_language_id">
<head>
    <title>$the_title</title>
    $theme.include($top_head_include)
</head>
<body class="portal-popup $css_class">
$theme.include($content_include)
$theme.include($bottom_ext_include)
</body>
</html>
```

`legacy/customercare-theme/src/main/webapp/templates/portlet.vm`:

```
#set ($portlet_display = $portletDisplay)
#set ($portlet_id = $htmlUtil.escapeAttribute($portlet_display.getId()))
<section class="portlet" id="portlet_$portlet_id">
    <div class="portlet-content">
        $portlet_display.writeContent($writer)
    </div>
</section>
```

`legacy/customercare-theme/src/main/webapp/templates/navigation.vm`:

```
<nav id="navigation" class="crm-nav" role="navigation">
    <ul>
        #foreach ($nav_item in $nav_items)
            <li #if ($nav_item.isSelected()) class="selected" #end>
                <a href="$nav_item.getURL()" $nav_item.getTarget()>$nav_item.getName()</a>
            </li>
        #end
    </ul>
</nav>
```

`legacy/customercare-theme/src/main/webapp/css/main.css`:

```css
/* Neutrálny theme bez brandingu. */
body { margin: 0; font-family: Arial, Helvetica, sans-serif; font-size: 14px; color: #222; background: #fff; }
.crm-wrapper { max-width: 1100px; margin: 0 auto; }
.crm-brand { padding: 10px 12px; font-size: 18px; font-weight: bold; background: #33475b; color: #fff; }
.crm-nav ul { list-style: none; margin: 0; padding: 0; display: flex; background: #dfe6ee; }
.crm-nav li a { display: block; padding: 8px 14px; color: #33475b; text-decoration: none; }
.crm-nav li.selected a { background: #fff; font-weight: bold; }
#content { padding: 12px; }
.portlet-msg-error { color: #b00020; padding: 6px 0; }
.portlet-msg-success { color: #1b5e20; padding: 6px 0; }
.crm-portal-error { padding: 20px; }
```

- [ ] **Step 6: Over balenie**

Run: `mvn -q -B -f legacy/pom.xml verify && ls legacy/customercare-theme/target/customercare-theme.war legacy/customercare-portlets/target/customercare-portlet.war legacy/sso-portal-api/target/sso-portal-api.jar`
Expected: PASS všetkých 151 testov a tri artefakty existujú.

- [ ] **Step 7: Checkpoint (bez commitu)** – `git status --short`

---

### Task 16: Docker stack, skripty a overenie spustením (smoke test, zdieľaná session naživo)

**Files:**
- Create: `docker/docker-compose.yml`, `docker/liferay/Dockerfile`, `docker/liferay/portal-ext.properties`, `docker/nginx/default.conf`
- Create: `docker/build-and-deploy.sh`, `docker/build-and-deploy.ps1`, `docker/smoke.sh`, `docker/smoke.ps1`, `docker/show-session.sh`, `docker/show-session.ps1`, `docker/session-flag.sh`, `docker/session-flag.ps1`

**Interfaces:**
- Consumes: artefakty z Taskov 1–15 (`sso-portal-api.jar`, `customercare-portlet.war`, `customercare-theme.war`), `sso-mock/Dockerfile` (Task 2), ID/URL z Global Constraints
- Produces:
  - `http://localhost:8000` (nginx): `/` → 302 `/web/crm/blacklist`, `/web/…` → Liferay, `/sso/` → sso-mock; v podprojekte 2 pribudne `/crm/`
  - `http://localhost:8080` – priamy Liferay (admin `test@liferay.com` / `test`), `http://localhost:5540` – RedisInsight, `localhost:6379` – Redis
  - Skripty: `build-and-deploy` (build + `docker compose up -d --build` + hot deploy), `smoke` (end-to-end, exit kód = počet zlyhaní), `show-session` (flag + všetky `crm:session:*` s TTL), `session-flag PORTLET|REDIS`
  - `.ps1` skripty majú len ASCII texty (PowerShell 5.1 číta súbor bez BOM ako ANSI)

- [ ] **Step 1: Docker súbory**

`docker/liferay/Dockerfile`:

```dockerfile
# Liferay Portal 6.2 CE GA6 (Tomcat 7) na Jave 8 – 1:1 s legacy.
# Java 8 MUSÍ mať update <= 255: zabalená ICU4J neprečíta "1.8.0_504" (Invalid version number ... greater than 255),
# portál potom nevytvorí company a padá na ArrayIndexOutOfBoundsException v PortalInstances.
FROM azul/zulu-openjdk:8u252

RUN apt-get update && apt-get install -y --no-install-recommends curl unzip && rm -rf /var/lib/apt/lists/*

ARG BUNDLE_URL=https://releases-cdn.liferay.com/portal/6.2.5-ga6/liferay-portal-tomcat-6.2-ce-ga6-20160112152609836.zip
ARG BUNDLE_SHA256=930f3969a19dd2bdd847dbdd48e8176fa4884ef2608f5fec38efcad9b279dc8c

# Bundle sa sťahuje pri builde image (do repa nepatrí); HSQL z bundlu sa zmaže, portál si pri prvom štarte
# vytvorí čistú schému v pomenovanom volume.
RUN curl -fsSL -o /tmp/liferay.zip "$BUNDLE_URL" \
    && echo "$BUNDLE_SHA256  /tmp/liferay.zip" | sha256sum -c - \
    && unzip -q /tmp/liferay.zip -d /opt \
    && mv /opt/liferay-portal-6.2-ce-ga6 /opt/liferay \
    && rm -rf /tmp/liferay.zip /opt/liferay/data/hsql

COPY portal-ext.properties /opt/liferay/portal-ext.properties
# API SSO portálu do spoločného classloadera (legacy: provided JBoss modul). Kopíruje ho build-and-deploy.
COPY sso-portal-api.jar /opt/liferay/tomcat-7.0.62/lib/ext/sso-portal-api.jar

EXPOSE 8080
CMD ["/opt/liferay/tomcat-7.0.62/bin/catalina.sh", "run"]
```

`docker/liferay/portal-ext.properties`:

```properties
setup.wizard.enabled=false
liferay.home=/opt/liferay
auto.deploy.deploy.dir=/opt/liferay/deploy
web.server.display.node=false
```

`docker/nginx/default.conf`:

```nginx
# Jedna adresa pre všetko: cookies SSO_ID a JSESSIONID tak vidia všetky aplikácie (cookies nerozlišujú port).
server {
    listen 80;

    # Mock SSO portálu (externý systém).
    location /sso/ {
        proxy_pass http://sso-mock:8090;
        proxy_set_header Host $http_host;
    }

    # Podprojekt 2: zmigrované stránky (Spring Boot) – location /crm/ { proxy_pass http://crm-web:8080; ... }

    location = / {
        return 302 /web/crm/blacklist;
    }

    # Stará appka (Liferay). Host hlavička ostáva localhost:8000, aby portál skladal URL na túto adresu.
    location / {
        proxy_pass http://liferay:8080;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

`docker/docker-compose.yml`:

```yaml
name: crm-legacy

services:
  redis:
    image: redis:7.4-alpine
    ports: ["6379:6379"]

  # GUI na zdieľanú session: http://localhost:5540 (databázu "crm-shared-session" zaregistruje build-and-deploy cez API).
  redisinsight:
    image: redis/redisinsight:3.6.0
    ports: ["5540:5540"]
    depends_on: [redis]

  sso-mock:
    build: ../sso-mock
    expose: ["8090"]

  liferay:
    build: ./liferay
    environment:
      CATALINA_OPTS: >-
        -Xmx2g -Dfile.encoding=UTF-8
        -Dsso.portal.api-url=http://sso-mock:8090/sso
        -Dsso.portal.login-url=/sso/login
        -Dcrm.redis.host=redis
    ports: ["8080:8080"]
    volumes:
      - liferay-data:/opt/liferay/data
      - ./deploy:/opt/liferay/deploy
    depends_on: [redis, sso-mock]

  nginx:
    image: nginx:1.27-alpine
    ports: ["8000:80"]
    volumes:
      - ./nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on: [liferay, sso-mock]

volumes:
  liferay-data:
```

- [ ] **Step 2: Build a deploy skripty**

`docker/build-and-deploy.sh`:

```bash
#!/usr/bin/env bash
# Build legacy appky a SSO mocku, štart Docker stacku a hot deploy WAR-ov do Liferay.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

mvn -q -B -f "$ROOT/legacy/pom.xml" verify
mvn -q -B -f "$ROOT/sso-mock/pom.xml" verify
cp "$ROOT/legacy/sso-portal-api/target/sso-portal-api.jar" "$ROOT/docker/liferay/sso-portal-api.jar"
mkdir -p "$ROOT/docker/deploy"

cd "$ROOT/docker"
docker compose up -d --build

# Theme pred portletmi: portlety ho majú v required-deployment-contexts. Liferay WAR po spracovaní z deploy/ zmaže.
cp "$ROOT/legacy/customercare-theme/target/customercare-theme.war" deploy/
cp "$ROOT/legacy/customercare-portlets/target/customercare-portlet.war" deploy/

# RedisInsight 3.x nepreberá pripojenie z premenných prostredia – databázu zdieľanej session zaregistrujeme cez jeho API.
for _ in $(seq 1 30); do curl -s -o /dev/null http://localhost:5540/api/databases && break; sleep 2; done
curl -s http://localhost:5540/api/databases | grep -q crm-shared-session     || curl -s -o /dev/null -X POST http://localhost:5540/api/databases -H 'Content-Type: application/json'         -d '{"name":"crm-shared-session","host":"redis","port":6379}'
echo "Hotovo. Liferay štartuje 1-3 min: docker compose -f docker/docker-compose.yml logs -f liferay"
```

`docker/build-and-deploy.ps1`:

```powershell
# Build legacy app and SSO mock, start the Docker stack and hot-deploy the WARs (PowerShell 5.1).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

mvn -q -B -f "$root\legacy\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of legacy failed' }
mvn -q -B -f "$root\sso-mock\pom.xml" verify
if ($LASTEXITCODE -ne 0) { throw 'Build of sso-mock failed' }
Copy-Item "$root\legacy\sso-portal-api\target\sso-portal-api.jar" "$PSScriptRoot\liferay\sso-portal-api.jar" -Force
New-Item -ItemType Directory -Force "$PSScriptRoot\deploy" | Out-Null

Push-Location $PSScriptRoot
try {
    docker compose up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed' }
} finally {
    Pop-Location
}

# Theme first: the portlets list it in required-deployment-contexts.
Copy-Item "$root\legacy\customercare-theme\target\customercare-theme.war" "$PSScriptRoot\deploy\" -Force
Copy-Item "$root\legacy\customercare-portlets\target\customercare-portlet.war" "$PSScriptRoot\deploy\" -Force

# RedisInsight 3.x ignores connection env vars - register the shared-session database through its API.
$api = 'http://localhost:5540/api/databases'
$existing = $null
for ($i = 0; $i -lt 30; $i++) {
    try { $existing = @(Invoke-RestMethod $api); break } catch { Start-Sleep -Seconds 2 }
}
if (-not ($existing | Where-Object { $_.name -eq 'crm-shared-session' })) {
    $body = '{"name":"crm-shared-session","host":"redis","port":6379}'
    Invoke-RestMethod -Method Post -Uri $api -ContentType 'application/json' -Body $body | Out-Null
}
Write-Output 'Done. Liferay needs 1-3 min: docker compose -f docker\docker-compose.yml logs -f liferay'
```

- [ ] **Step 3: Skripty na zdieľanú session**

`docker/show-session.sh`:

```bash
#!/usr/bin/env bash
# Zdieľaná session „fyzicky“: flag zdroja čítania a všetky hashe crm:session:{SSO_ID} s TTL a obsahom.
cd "$(dirname "$0")"
rc() { docker compose exec -T redis redis-cli "$@" | tr -d '\r'; }

flag="$(rc GET crm:flags:session-read-source)"
echo "flag crm:flags:session-read-source = ${flag:-(nenastavený = PORTLET)}"
keys="$(rc --scan --pattern 'crm:session:*')"
[ -z "$keys" ] && echo "(žiadna zdieľaná session – prihlás sa a otvor stránku CRM)"
for key in $keys; do
    echo
    echo "== $key  (TTL $(rc TTL "$key") s)"
    rc HGETALL "$key" | paste - -
done
```

`docker/show-session.ps1`:

```powershell
# Shared session "physically": read-source flag and every crm:session:{SSO_ID} hash with TTL and content.
Push-Location $PSScriptRoot
try {
    $flag = docker compose exec -T redis redis-cli GET crm:flags:session-read-source
    if (-not $flag) { $flag = '(not set = PORTLET)' }
    Write-Output "flag crm:flags:session-read-source = $flag"
    $keys = docker compose exec -T redis redis-cli --scan --pattern 'crm:session:*'
    foreach ($key in $keys) {
        if (-not $key) { continue }
        $ttl = docker compose exec -T redis redis-cli TTL $key
        Write-Output ''
        Write-Output "== $key  (TTL $ttl s)"
        docker compose exec -T redis redis-cli HGETALL $key
    }
} finally {
    Pop-Location
}
```

`docker/session-flag.sh`:

```bash
#!/usr/bin/env bash
# Prepne zdroj čítania session bez reštartu a bez deployu (feature flag zo slide 12): PORTLET | REDIS.
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-}" in
    PORTLET|REDIS) docker compose exec -T redis redis-cli SET crm:flags:session-read-source "$1" ;;
    *) echo "Použitie: $0 PORTLET|REDIS" >&2; exit 2 ;;
esac
```

`docker/session-flag.ps1`:

```powershell
# Switch the session read source without restart or deploy (feature flag): PORTLET | REDIS.
param([Parameter(Mandatory = $true)][ValidateSet('PORTLET', 'REDIS')][string]$Source)
Push-Location $PSScriptRoot
try {
    docker compose exec -T redis redis-cli SET crm:flags:session-read-source $Source
} finally {
    Pop-Location
}
```

- [ ] **Step 4: Smoke test**

`docker/smoke.sh`:

```bash
#!/usr/bin/env bash
# End-to-end overenie bežiaceho stacku cez nginx (http://localhost:8000): SSO login, blacklist, dashboard, AJAX,
# zdieľaná session v Redise, prepnutie flagu, výpadok Redisu, chybová stránka portálu, ACL čitateľa.
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
abs()   { case "$1" in http*) echo "$1" ;; *) echo "$B$1" ;; esac; }
fetch() { curl -s -L -b "$JAR" -c "$JAR" -o "$2" "$(abs "$1")"; }
post()  { local url out; url="$(abs "$1")"; out="$2"; shift 2; curl -s -L -b "$JAR" -c "$JAR" -o "$out" "$@" "$url"; }
# Atribút elementu s daným id (Liferay môže vrátiť HTML na jednom riadku).
attr_of() {
    grep -oE "<[a-z]+ id=\"$1\"[^>]*>" "$2" | head -1 | grep -oE " $3=\"[^\"]*\"" | head -1 \
        | sed -E "s/^ $3=\"//; s/\"$//; s/&amp;/\&/g"
}
rc()    { docker compose exec -T redis redis-cli "$@" | tr -d '\r'; }
login() {
    rm -f "$JAR"
    curl -s -c "$JAR" -b "$JAR" -o /dev/null -X POST "$B/sso/login" \
        -d "operator=$1" -d "customer=C-1001" -d "returnUrl=/web/crm/blacklist"
}

echo "Čakám na Liferay (prvý štart 1-3 min)..."
code=000
for _ in $(seq 1 90); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$B/web/crm/blacklist")"
    [ "$code" = "200" ] && break
    sleep 5
done
[ "$code" = "200" ] || { echo "Liferay neodpovedá (posledný HTTP $code)"; exit 1; }

echo "== 1. Bez prihlásenia"
rm -f "$JAR"
fetch /web/crm/blacklist "$WORK/anon.html"
expect "blacklist bez SSO ukáže výzvu na prihlásenie" 'id="loginLink"' "$WORK/anon.html"
expect "hlavička je vložená z theme (runtime portlet)" 'id="sessionDebug"' "$WORK/anon.html"

echo "== 2. Prihlásenie v SSO mocku (správca, zákazník C-1001)"
login spravca
SSO_ID="$(grep SSO_ID "$JAR" | awk '{print $7}')"
if [ -n "$SSO_ID" ]; then ok "cookie SSO_ID=$SSO_ID"; else fail "cookie SSO_ID chýba"; fi
fetch /web/crm/blacklist "$WORK/search.html"
expect "hlavička ukazuje operátora" 'id="headerOperator"' "$WORK/search.html"
expect "operátor Správca" 'Správca' "$WORK/search.html"
expect "formulár vyhľadávania" 'id="searchForm"' "$WORK/search.html"

echo "== 3. Diakritika v action fáze"
post "$(attr_of searchForm "$WORK/search.html" action)" "$WORK/result1.html" \
    -d "firstName=M%C3%A1ria" -d lastName= -d identifierValue= -d state=
# Bajty UTF-8 explicitne: curl na Windows by z argumentu „Mária“ poslal %E1 (ANSI kódová stránka), prehliadač pošle UTF-8.
expect "Mária nájdená" 'id="detail-2"' "$WORK/result1.html"

echo "== 4. Vyhľadávanie a detail"
fetch /web/crm/blacklist "$WORK/search2.html"
post "$(attr_of searchForm "$WORK/search2.html" action)" "$WORK/result2.html" \
    -d firstName= -d lastName=Vzork -d identifierValue= -d state=
expect "výsledok: záznam 1" 'id="detail-1"' "$WORK/result2.html"
expect "výsledok: záznam 8" 'id="detail-8"' "$WORK/result2.html"
DETAIL="$(attr_of detail-1 "$WORK/result2.html" href)"
fetch "$DETAIL" "$WORK/detail.html"
expect "detail záznamu 1" 'AB123456' "$WORK/detail.html"

echo "== 5. Zmena stavu (BLACKLIST_MODIFY)"
post "$(attr_of changeForm "$WORK/detail.html" action)" "$WORK/changed.html" -d detailId=1 -d state=INACTIVE -d validTo=
expect "hláška v hlavičke" 'Stav záznamu bol zmenený.' "$WORK/changed.html"
expect "stav Neaktívny" 'id="recordState">Neaktívny' "$WORK/changed.html"

echo "== 6. Zdieľaná session v Redise"
rc HGETALL "crm:session:$SSO_ID" > "$WORK/redis.txt"
expect "Redis: posledný otvorený záznam" 'Peter Vzorka' "$WORK/redis.txt"
expect "Redis: posledné kritériá" 'Vzork' "$WORK/redis.txt"
TTL="$(rc TTL "crm:session:$SSO_ID")"
if [ "${TTL:-0}" -gt 0 ] 2>/dev/null; then ok "TTL $TTL s"; else fail "TTL $TTL"; fi

echo "== 7. Dashboard: zrušenie služby a JSON resource"
fetch /web/crm/dashboard "$WORK/products.html"
expect "produkty C-1001" 'id="deactivate-T-1"' "$WORK/products.html"
fetch "$(attr_of deactivate-T-1 "$WORK/products.html" href)" "$WORK/form.html"
expect "formulár pre Tarif Neobmedzený" 'Tarif Neobmedzený' "$WORK/form.html"
EARLIEST="$(abs "$(attr_of deactivationForm "$WORK/form.html" data-earliest-url)")&type=CUSTOMER_DEACTIVATION&noticeDate=2026-10-08"
curl -s -b "$JAR" -o "$WORK/earliest.json" "$EARLIEST"
expect "JSON najskorší dátum" '"earliestDate":"2026-11-07"' "$WORK/earliest.json"
code="$(curl -s -o /dev/null -w '%{http_code}' "$EARLIEST")"
if [ "$code" = "401" ]; then ok "AJAX bez SSO cookie -> 401"; else fail "AJAX bez SSO cookie -> $code (čakal 401)"; fi
post "$(attr_of deactivationForm "$WORK/form.html" action)" "$WORK/done.html" \
    -d productId=T-1 -d type=CUSTOMER_POSTPRE -d "noticeDate=$(date -u +%F)" -d effectiveDate=2099-12-31
expect "potvrdenie žiadosti" 'id="deactivationDone"' "$WORK/done.html"
expect "hláška o prijatí" 'Žiadosť o zrušenie bola prijatá.' "$WORK/done.html"

echo "== 8. Feature flag REDIS: fasáda číta zo zdieľanej session"
rc SET crm:flags:session-read-source REDIS > /dev/null
fetch "$DETAIL" "$WORK/detail-redis.html"
expect "hlavička: zdroj čítania REDIS" 'zdroj čítania = REDIS' "$WORK/detail-redis.html"
expect "posledný záznam prečítaný z Redisu" 'id="headerLastViewed"' "$WORK/detail-redis.html"
rc SET crm:flags:session-read-source PORTLET > /dev/null

echo "== 9. Výpadok Redisu nezhodí stránku"
docker compose stop redis > /dev/null 2>&1
fetch /web/crm/blacklist "$WORK/no-redis.html"
expect "stránka beží bez Redisu" 'id="searchForm"' "$WORK/no-redis.html"
docker compose start redis > /dev/null 2>&1

echo "== 10. Prepísaná chybová stránka portálu (hook)"
fetch /web/crm/neexistuje "$WORK/404.html"
expect "hooked status.jsp" 'id="crmPortalError"' "$WORK/404.html"

echo "== 11. Čitateľ: bez zmien a bez dashboardu"
login citatel
fetch /web/crm/blacklist "$WORK/reader.html"
expect "čitateľ vidí vyhľadávanie" 'id="searchForm"' "$WORK/reader.html"
expect_not "čitateľ nevidí „Nový záznam“" 'id="gotoCreateForm"' "$WORK/reader.html"
fetch /web/crm/dashboard "$WORK/reader-dashboard.html"
expect "čitateľ na dashboarde dostane 403" 'crm-error-403' "$WORK/reader-dashboard.html"

rm -rf "$WORK"
echo
if [ "$FAILS" -eq 0 ]; then echo "SMOKE OK"; else echo "SMOKE: $FAILS zlyhaní"; fi
exit "$FAILS"
```

`docker/smoke.ps1`:

```powershell
# The smoke test is a bash script (curl + grep); this wrapper runs it with Git Bash.
$bash = Join-Path $env:ProgramFiles 'Git\bin\bash.exe'
if (-not (Test-Path $bash)) { throw 'Smoke test needs Git Bash (C:\Program Files\Git\bin\bash.exe)' }
& $bash "$PSScriptRoot/smoke.sh"
exit $LASTEXITCODE
```

- [ ] **Step 5: Uvoľni porty a spusti stack**

Porty 8000, 8080, 5540 a 6379 musia byť voľné (iné Docker stacky zastav: `docker ps`).

Run (na pozadí, trvá niekoľko minút – sťahuje sa bundle Liferay): `bash docker/build-and-deploy.sh`
Expected: `Hotovo. Liferay štartuje 1-3 min…`; `docker compose -f docker/docker-compose.yml ps` ukáže 5 bežiacich služieb.

- [ ] **Step 6: Over deploy v logu Liferay**

Run: `docker compose -f docker/docker-compose.yml logs liferay | grep -E "customercare-theme is available|portlets for customercare-portlets are available|Hook for customercare-portlets is available|Vytvorená"`
Expected (po 1–3 min): theme available, `3 portlets for customercare-portlets are available for use`, `Hook for customercare-portlets is available for use`, pri prvom štarte aj `Vytvorená site CRM` a `Vytvorená stránka /blacklist…`, `/dashboard…`. V logu nesmie byť `SEVERE`/`ERROR` s `customercare` (`grep -iE "error|severe" | grep -i customercare` → nič).

- [ ] **Step 7: Smoke test**

Run: `bash docker/smoke.sh`
Expected: všetky riadky `OK`, na konci `SMOKE OK`, exit 0.

Ak niečo zlyhá, oprav príčinu (nie test) a zopakuj build + deploy (`bash docker/build-and-deploy.sh`) a smoke test. Najpravdepodobnejšie príčiny:

| Príznak | Príčina | Riešenie |
|---|---|---|
| „You do not have the roles required to access this portlet“ | hook nastavil VIEW skôr, než bol portlet zaregistrovaný | `docker compose -f docker/docker-compose.yml restart liferay` (hook beží znova pri štarte, portlety už existujú) |
| chýba `id="sessionDebug"` / hlavička | theme nie je nasadený alebo stránka nemá theme | log: `customercare-theme is available`; v admin rozhraní (`:8080`, Control Panel → Sites → CRM → Look and Feel) over theme `CRM (demo)` |
| hláška z action nie je v hlavičke | theme vykresľuje hlavičku pred spracovaním action (poradie renderu) | vykresli `popMessages()` aj v obsahovom portlete a zapíš to do `poznamky/07-pasce.md` |
| „Mária“ nenájdená | telo POST dekódované ako ISO-8859-1 | over `request.getCharacterEncoding()` v portlete; ak je null, pridaj do `web.xml` `CharacterEncodingFilter` (UTF-8, `forceEncoding`) a zapíš pascu |
| AJAX bez cookie vráti 200/302 namiesto 401 | Liferay ignoruje `HTTP_STATUS_CODE` v resource fáze | over v logu, že `DispatcherPortletCustom.doResourceService` beží; zapíš zistenie do pasce a do README |
| `status.jsp` nie je prepísaná | portál pri neexistujúcej stránke nepoužije `/html/portal/status.jsp` | over `layout.show.http.status` a skutočnú JSP v logu; uprav cestu v `WEB-INF/hooked/` |

- [ ] **Step 8: Zdieľaná session naživo**

Run: `bash docker/show-session.sh`
Expected: flag (prázdny = PORTLET) a aspoň jeden hash `crm:session:<SSO_ID>` s poľami `APP_MESSAGES`/`APP_BLACKLIST_LAST_CRITERIA`/`APP_LAST_VIEWED_RECORD` v JSON a kladným TTL.

Run: `curl -s -o /dev/null -w '%{http_code}\n' http://localhost:5540/`
Expected: `200` (RedisInsight). Ak databáza `crm-shared-session` nie je predvyplnená, pridá sa ručne: host `redis`, port `6379` (zapíš do README).

- [ ] **Step 9: Checkpoint (bez commitu)** – `git status --short` (artefakty `docker/deploy/` a `docker/liferay/sso-portal-api.jar` sú ignorované)

---

### Task 17: Dokumentácia – README legacy appky s návodom na preklikanie, poznámky, CLAUDE.md

**Files:**
- Create: `legacy/README.md`
- Create: `poznamky/10-liferay62-v-dockeri.md`
- Modify: `poznamky/07-pasce.md` (body 11–15), `poznamky/09-session-liferay-vs-spring.md` (nová sekcia 7), `poznamky/README.md` (riadok 10)
- Modify: `README.md` (stav a štruktúra), `CLAUDE.md` (stav, build & run)

**Interfaces:**
- Consumes: všetko z Taskov 1–16 (výsledky smoke testu, skutočné správanie stacku)
- Produces: dokumentácia; žiadny kód

- [ ] **Step 1: `legacy/README.md`**

~~~markdown
# Legacy appka „pred“ (Liferay 6.2)

Príklady **blacklist ne-zákazníkov** a **zrušenie služby** ako portlety v reálnom Liferay 6.2 – rovnaký stack
a mechanizmy ako legacy CRM, napísané nanovo a anonymizované. Podprojekt 2 ich zmigruje na Spring Boot.

## Stack

| Vec | Verzia |
|---|---|
| Portál | Liferay Portal 6.2 CE GA6 (Tomcat 7.0.62, HSQL) v Dockeri |
| Java | 8 (Zulu 8u252 – update musí byť ≤ 255, viď `poznamky/10`) |
| Framework | Spring 4.3.6 Portlet MVC, JSP + JSTL, vlastné taglibs |
| Zdieľaná session | Redis 7.4 (Jedis 2.9), JSON |
| SSO portál | mock – Spring Boot 4.1.1 / Java 21 (`../sso-mock`) |

## Moduly (rovnaké delenie ako legacy)

| Modul | Čo obsahuje | V reálnom legacy |
|---|---|---|
| `sso-portal-api` | API SSO portálu, service locator, HTTP klient (shaded JAR v `tomcat/lib/ext`) | `provided` API + JBoss modul |
| `frontend-base-customization` | session fasáda `SessionManagerCustom` + duálna session, ACL, tagy `portal:*`/`common:*`, `DispatcherPortletCustom`, `BaseCrmController` | `FBC` |
| `customercare-portlets` | portlety `blacklist`, `dashboard`, `header`, in-memory backend, JSP, startup hook, hooked `status.jsp` | `CP`, `J` |
| `customercare-theme` | Velocity theme, vkladá portlet hlavičky | `customercare-theme` |

## Spustenie

Predpoklady: Docker Desktop (~3 GB RAM pre Liferay), JDK 21, Maven 3.9, Git Bash (smoke test).

```bash
bash docker/build-and-deploy.sh     # build + docker compose up + hot deploy (PowerShell: docker\build-and-deploy.ps1)
bash docker/smoke.sh                # end-to-end overenie, na konci "SMOKE OK"
```

Prvý štart trvá 1–3 minúty (stiahnutie bundlu, vytvorenie DB). Log: `docker compose -f docker/docker-compose.yml logs -f liferay`.
Zastavenie: `docker compose -f docker/docker-compose.yml down` (dáta portálu ostanú vo volume; `down -v` ich zmaže).

| Adresa | Čo |
|---|---|
| http://localhost:8000 | **CRM** (nginx → Liferay + SSO mock), začni tu |
| http://localhost:8000/sso/login | SSO portál (mock) – prihlásenie, zmena zákazníka, odhlásenie |
| http://localhost:5540 | RedisInsight – zdieľaná session v Redise |
| http://localhost:8080 | priamo Liferay (admin `test@liferay.com` / `test`) |

Demo operátori: **Správca** (`spravca`) – všetko; **Čitateľ** (`citatel`) – len čítanie blacklistu.
Zákazníci: `C-1001` Ján Vzorový (3 produkty), `C-1002` Firma Ukážka s.r.o. (2 produkty).

## Preklikanie

1. Otvor **http://localhost:8000** → presmeruje na `/web/crm/blacklist`. Nie si prihlásený: hlavička aj portlet
   ukážu „Prihlásiť“. Pod hlavičkou je ladiaci riadok *Zdieľaná session: zdroj čítania = PORTLET, SSO_ID = -*.
2. Klikni **Prihlásiť** → stránka SSO mocku. Vyber *Správca* a zákazníka *C-1001* → **Prihlásiť**.
   Vrátiš sa do CRM: hlavička ukazuje operátora, zákazníka a v ladiacom riadku tvoje `SSO_ID`.
3. **Blacklist – vyhľadávanie:** priezvisko `vzork` → **Hľadať**. Action fáza nastaví render parameter
   a zobrazia sa výsledky (Peter Vzorka, Peter Vzorkový). Skús aj meno `Mária` (diakritika) alebo prázdny formulár
   (chyba „Zadajte aspoň jedno kritérium.“ – validačná chyba prežila action → render).
4. **Detail:** klikni na *Peter Vzorka*. Hlavička teraz ukazuje *Naposledy otvorený záznam: Peter Vzorka* –
   to je hodnota zo session fasády (a v Redise).
   - **Zmena stavu a platnosti** → *Neaktívny* → **Uložiť**: hláška v hlavičke (raz), stav sa zmení.
   - **Pridať poznámku**: poznámka s menom operátora; prázdna poznámka → chybová hláška.
   - **Odstrániť** pri identifikátore: AJAX (resource fáza) prekreslí len zoznam; posledný identifikátor
     sa odstrániť nedá (hláška vo fragmente).
5. **Nový záznam** (na stránke vyhľadávania): zmena *Dôvodu* cez AJAX prekreslí povolené *Podmienky*.
   Odošli neúplný formulár → chyby pri poliach; vyplnený → detail nového záznamu.
6. **Dashboard** (navigácia hore): produkty zákazníka zo SSO. **Zrušiť** pri *Tarif Neobmedzený* →
   formulár (`page=tariffDeactivation`). Zmena typu alebo dátumu podania zavolá JSON resource a nastaví
   *Najskôr:*. Príliš skorý dátum → chyba; správny → potvrdenie so žiadosťou.
   Legacy pasca: otvor potom zrušenie *Tarif Štandard* – typ a dátumy ostali z predchádzajúceho formulára
   (`@SessionAttributes`), migrácia to opraví.
7. **Zmena zákazníka:** v hlavičke **Zmeniť zákazníka** → v SSO mocku vyber `C-1002` → dashboard ukáže iné produkty.
   Zákazníka vlastní SSO portál, nie CRM.
8. **ACL:** **Odhlásiť** → prihlás sa ako *Čitateľ*. Blacklist vidí, ale bez tlačidiel zmeny a bez „Nový záznam“;
   dashboard ukáže *Nemáte oprávnenie DEACTIVATION.*
9. **Chybová stránka portálu:** http://localhost:8000/web/crm/neexistuje → prepísaná `status.jsp` (hook).

## Zdieľaná session naživo

Fasáda (`SessionManagerCustom`) zapisuje hlášky a vybrané atribúty **do PortletSession aj do Redisu**
(hash `crm:session:{SSO_ID}`, JSON, TTL 30 min). Feature flag `crm:flags:session-read-source` určuje, odkiaľ číta.

- **V prehliadači:** ladiaci riadok pod hlavičkou ukazuje zdroj čítania a `SSO_ID`.
- **V termináli:** `bash docker/show-session.sh` (PowerShell: `docker\show-session.ps1`) vypíše flag
  a všetky zdieľané session s TTL a obsahom, napr.
  `APP_LAST_VIEWED_RECORD  {"id":1,"fullName":"Peter Vzorka"}`. Spusti ho po každom kroku preklikania –
  uvidíš, ako pribúdajú a miznú hlášky (`APP_MESSAGES`) a mení sa posledný záznam.
- **V GUI:** http://localhost:5540 → databáza *crm-shared-session* (zaregistruje ju `build-and-deploy`;
  ak chýba: *Add database*, host `redis`, port `6379`) → kľúče `crm:session:*`.
- **Prepnutie čítania na Redis** (bez reštartu a deployu): `bash docker/session-flag.sh REDIS`
  (`docker\session-flag.ps1 REDIS`). Obnov stránku – ladiaci riadok ukáže `REDIS`, hlavička číta posledný
  záznam z Redisu. Späť: `session-flag.sh PORTLET`.
- **Výpadok Redisu:** `docker compose -f docker/docker-compose.yml stop redis` – CRM beží ďalej (zápis do Redisu
  sa len zaloguje, pri flagu `REDIS` sa číta zo starej session). `… start redis` ho vráti.
- **Čo sa nezdieľa:** formuláre v `@SessionAttributes` (kritériá vyhľadávania, nový záznam, žiadosť o zrušenie)
  ostávajú v `PortletSession` – zdieľa sa len to, čo ide cez fasádu a je v registri `SharedKeys`.

V podprojekte 2 bude za tým istým nginx aj Spring Boot appka (`/crm/`), ktorá prečíta ten istý hash.

## Testy

```bash
mvn -q -B -f legacy/pom.xml verify     # unit + wiring testy (JUnit 5, portlet mocky Spring 4.3)
mvn -q -B -f sso-mock/pom.xml verify   # SSO mock (MockMvc)
bash docker/smoke.sh                   # bežiaci stack end-to-end
```

## Známe obmedzenia (vedome)

- Render fáza portletu nevie presmerovať – bez SSO session portlet ukáže odkaz „Prihlásiť“, nie redirect.
- Theme nededí z Liferay `_styled`, admin dockbar je preto bez štýlov.
- Pre Liferay je každý operátor hosť; identitu pozná len SSO mock (v legacy autologin cez SSO portál).
- HSQL namiesto Oracle, Tomcat namiesto JBoss, `params="page=…"` namiesto vlastného dashboard dispatchu.
~~~

- [ ] **Step 2: `poznamky/10-liferay62-v-dockeri.md`**

```markdown
# Liferay 6.2 lokálne v Dockeri (ako rozbehnúť „starú“ appku)

Zistené pri stavbe dema (2026-10). Recept je v `docker/liferay/Dockerfile`.

## Recept
- Oficiálny Docker image pre 6.2 neexistuje (oficiálne sú od 7.0). Vlastný image: bundle
  `liferay-portal-tomcat-6.2-ce-ga6-*.zip` z `releases-cdn.liferay.com` (≈300 MB, Tomcat 7.0.62) + JDK 8.
- **Java 8 s update ≤ 255** (napr. `azul/zulu-openjdk:8u252`). Na 8u504 portál nenaštartuje: zabalená ICU4J
  parsuje `java.version` a hodí `IllegalArgumentException: Invalid version number: Version number may be negative
  or greater than 255`; následne nevznikne company a každý request padá na `ArrayIndexOutOfBoundsException: 0`
  v `PortalInstances._getDefaultCompanyId`. Na reálnom projekte: pozor pri „bezpečnostnom“ upgrade JDK 8.
- Bundlovaná HSQL sa zmaže, portál si pri prvom štarte vytvorí čistú schému (`Create tables and populate with
  default data`). Chyba `object not found: LOCK_` pri prvom štarte je očakávaná.
- `portal-ext.properties`: `setup.wizard.enabled=false`; admin `test@liferay.com` / `test`.
- Štart 55–80 s; prvý request počas „zahrievania“ môže vrátiť 400.
- Build pluginov bez súkromného repa: Liferay 6.2.5 API (`portal-service`, `util-*`) a Spring 4.3.6 sú v Maven
  Central; JDK 21 s `maven.compiler.release=8` vyrobí Java 8 bytecode.

## Deploy
- Hot deploy: WAR do `deploy/` (v deme mount `docker/deploy`). Po spracovaní ho Liferay zmaže. Úspech v logu:
  `N portlets for <war> are available for use`, `Hook for <war> is available for use`.
- Poradie: theme pred portletmi, ak ho portlety majú v `required-deployment-contexts`.
- JAR v `tomcat/lib/ext` (náhrada JBoss modulu) sa nedá hot-deploynúť – mení sa rebuildom image.
- Po `docker compose up` s novým image sú WAR-y preč (webapps nie je vo volume) – skript ich nasadí znova.

## Oprávnenia a stránky
- Hosť nevidí portlet, ktorý nie je na stránke („You do not have the roles required…“). Riešenie: portlet umiestniť
  na stránku (startup hook) alebo `add-default-resource=true` (runtime portlet v theme).
- Startup hook (`application.startup.events`) beží pri každom deployi aj štarte – musí byť idempotentný.

## Jedna adresa pre staré aj nové stránky
- nginx pred portálom; `proxy_set_header Host $http_host`, aby portál skladal URL na verejnú adresu.
- Cookies nerozlišujú port ani aplikáciu: dve appky za jednou adresou si prepíšu `JSESSIONID`. Mock SSO preto
  `HttpSession` nepoužíva; nová appka v podprojekte 2 musí mať iný názov session cookie alebo žiadnu session.
```

- [ ] **Step 3: Doplň `poznamky/07-pasce.md`**

Na koniec súboru pridaj:

```markdown
11. **Java 8 update > 255 zhodí Liferay 6.2.** Stará ICU4J v portáli neprečíta `1.8.0_504`. Detail
    a riešenie: [10-liferay62-v-dockeri.md](10-liferay62-v-dockeri.md).
12. **Kolízia `JSESSIONID` za jednou adresou.** Stará a nová appka (aj SSO) za tým istým hostom zdieľajú cookies
    bez ohľadu na port/cestu `/`. Druhá appka musí mať vlastný názov session cookie alebo session nepoužívať.
13. **Render fáza nevie presmerovať ani nastaviť cookie.** Kontrola prihlásenia v render fáze portletu môže len
    vykresliť odkaz; redirect funguje v action fáze. Kľúč zdieľanej session preto nie je vlastná cookie, ale
    `SSO_ID`, ktorú obe appky už majú.
14. **Hosť a portlety.** Identitu má SSO, Liferay vidí hosťa: každý portlet potrebuje VIEW pre Guest (stránka
    + inštancia portletu, runtime portlet `add-default-resource`). Inak „You do not have the roles required“.
15. **Implicit model medzi action a render.** Spring Portlet MVC prenesie model (aj validačné chyby) z action
    do renderu cez session (render parameter `implicitModel`). V servlet MVC to nie je – chyby sa vykreslia
    priamo (bez redirectu) alebo cez flash atribúty.
```

> Ak smoke test v Tasku 16 odhalil ďalšie pasce (poradie renderu hlavičky, kódovanie POST, status v resource
> fáze…), doplň ich sem ako ďalšie body s konkrétnym príznakom a riešením.

- [ ] **Step 4: Doplň `poznamky/09-session-liferay-vs-spring.md` a index**

Na koniec `poznamky/09-session-liferay-vs-spring.md` pridaj:

```markdown
## 7. Implementované v deme: duálna session v starej appke

Stará appka (`legacy/`, Liferay 6.2) implementuje návrh z 1.2:
- fasáda `SessionManagerCustom` (`legacy/frontend-base-customization/.../session/`): identita a zákazník zo SSO
  mocku cez `sso-portal-api`, ostatné cez `SessionStore`;
- `DualSessionStore`: zápis do `PortletSession` (`APPLICATION_SCOPE`, prefix `APP_`) aj do Redisu
  (`RedisSessionStore`, hash `crm:session:{SSO_ID}`, JSON, TTL 30 min), čítanie podľa flagu
  `crm:flags:session-read-source` (`ReadSourceFlag`, default `PORTLET`);
- zdieľa sa len register `SharedKeys` (hlášky, posledné kritériá, posledný záznam) – formuláre
  v `@SessionAttributes` nie;
- výpadok Redisu request nezhodí (zápis sa zaloguje, čítanie spadne späť na `PortletSession`).

Ako to vidieť naživo: `legacy/README.md`, sekcia „Zdieľaná session naživo“.
```

V `poznamky/README.md` pridaj do tabuľky za riadok `09-…`:

```markdown
| [10-liferay62-v-dockeri.md](10-liferay62-v-dockeri.md) | ako rozbehnúť Liferay 6.2 lokálne (Java 8 ≤ 255, HSQL, deploy, oprávnenia hosťa, nginx) |
```

- [ ] **Step 5: `README.md` a `CLAUDE.md`**

V `README.md` nahraď tabuľku v sekcii „Stav“ touto:

```markdown
| Fáza | Výstup | Stav |
|---|---|---|
| 1. Analýza legacy projektu | [`docs/ANALYZA.md`](docs/ANALYZA.md) | hotovo |
| Znalostná báza pre reálny projekt | [`poznamky/`](poznamky/README.md) | hotovo, priebežne sa dopĺňa |
| 2a. Stará appka „pred“ (Liferay 6.2) | [`legacy/`](legacy/README.md), [`sso-mock/`](sso-mock), [`docker/`](docker) – [spec](docs/superpowers/specs/2026-10-12-legacy-liferay-app-design.md), [plán](docs/superpowers/plans/2026-10-12-legacy-liferay-app.md) | hotovo |
| 2b. Zmigrovaná appka „po“ (Spring Boot) | [spec](docs/superpowers/specs/2026-10-08-liferay-migracia-demo-design.md), [plán](docs/superpowers/plans/2026-10-08-liferay-migracia-demo.md) | čaká na úpravu (zdieľaná session z Redisu) |
```

a v sekcii „Štruktúra“ blok nahraď:

```
legacy/      stará appka na Liferay 6.2 (Java 8, Spring Portlet MVC) – 4 moduly ako legacy
sso-mock/    mock SSO portálu (Spring Boot 4.1.1)
docker/      Liferay 6.2 + Redis + RedisInsight + SSO mock + nginx, skripty (build, smoke, zdieľaná session)
docs/        analýza, (neskôr) MIGRACIA.md, superpowers spec + plány
poznamky/    prenositeľná znalostná báza: kde čo hľadať, mapa konceptov, playbook, pasce
input/       klientske podklady – git-ignorované, read-only, nie sú súčasťou repa
```

V `CLAUDE.md` v sekcii „Current status“ nahraď bod začínajúci „2026-10-12 scope change“ týmto:

```markdown
- 2026-10-12 scope change – the demo shows **both** states, split into two sub-projects:
  1. **Legacy "before" app — done** (`legacy/`, `sso-mock/`, `docker/`; spec + plan `2026-10-12-legacy-liferay-app*`):
     Liferay 6.2 CE GA6 + Java 8 (Zulu 8u252 – update must be ≤ 255, ICU bug) + Spring Portlet MVC 4.3.6,
     SSO mock (HTTP + API JAR in `lib/ext`), dual session via Redis (flag `crm:flags:session-read-source`).
  2. **Spring Boot "after" app** – next: revise the 2026-10-08 spec/plan (read the shared session from Redis,
     run behind the same nginx under `/crm/`, Boot app in `crm-web/`, own session cookie name). Not started.
```

a za sekciu „Anonymization (hard rule)“ vlož:

```markdown
## Build & run (legacy "before" app)

- Unit tests: `mvn -q -B -f legacy/pom.xml verify`, `mvn -q -B -f sso-mock/pom.xml verify`;
  one class: `mvn -q -B -f legacy/pom.xml test -pl <module> -am -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- Stack: `bash docker/build-and-deploy.sh` (or `docker\build-and-deploy.ps1`), then `bash docker/smoke.sh` → `SMOKE OK`.
  CRM at http://localhost:8000, RedisInsight :5540, Liferay admin :8080 (`test@liferay.com`/`test`).
- Shared session: `bash docker/show-session.sh`, `bash docker/session-flag.sh PORTLET|REDIS`.
- Legacy modules compile with `--release 8` – Java 8 API only (no records, `var`, `List.of`, `isBlank`, `toList()`).
```

- [ ] **Step 6: Anonymizačná kontrola**

Run: `bash poznamky/LOCAL-anonymizacia.sh; echo "exit=$?"`
Expected: žiadny výstup a `exit=1` (grep nič nenašiel). Ak skript na stroji chýba (`No such file`), uveď to v odovzdaní – používateľ ho skopíruje ručne.

- [ ] **Step 7: Záverečné overenie**

Run: `mvn -q -B -f legacy/pom.xml verify && mvn -q -B -f sso-mock/pom.xml verify && bash docker/smoke.sh`
Expected: všetky testy PASS a `SMOKE OK`.

- [ ] **Step 8: Checkpoint (bez commitu)** – `git status --short`
