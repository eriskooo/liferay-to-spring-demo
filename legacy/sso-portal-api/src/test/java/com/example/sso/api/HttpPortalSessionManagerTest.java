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

    private static final String SESSION_JSON = "{\"operator\":{\"login\":\"spravca\",\"displayName\":\"Správce\"},"
            + "\"customer\":{\"customerId\":\"C-1001\",\"displayName\":\"Jan Vzorový\"},\"unknownField\":1}";

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
        assertThat(session.getOperator().getDisplayName()).isEqualTo("Správce");
        assertThat(session.getCustomer().getCustomerId()).isEqualTo("C-1001");
        assertThat(session.getCustomer().getDisplayName()).isEqualTo("Jan Vzorový");
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
