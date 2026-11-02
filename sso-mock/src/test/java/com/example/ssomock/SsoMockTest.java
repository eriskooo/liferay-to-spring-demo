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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Správce")))
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
                .andExpect(jsonPath("$.operator.displayName").value("Čtenář"))
                .andExpect(jsonPath("$.customer.customerId").value("C-1002"))
                .andExpect(jsonPath("$.customer.displayName").value("Firma Ukázka s.r.o."));
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Přihlášen: Správce")))
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
