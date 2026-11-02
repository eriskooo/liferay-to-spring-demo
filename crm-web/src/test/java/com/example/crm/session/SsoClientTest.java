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
        sso.put("s-1", "spravca", "Správce", "C-1001", "Jan Vzorový");

        SsoIdentity identity = client.fetch("s-1").orElseThrow();

        assertThat(identity.ssoSessionId()).isEqualTo("s-1");
        assertThat(identity.operator()).isEqualTo(new OperatorUser("spravca", "Správce"));
        assertThat(identity.customer()).isEqualTo(new CustomerContext("C-1001", "Jan Vzorový"));
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
        sso.put("s-1", "spravca", "Správce", "C-1001", "Jan Vzorový");
        sso.delay(3000);
        assertThatThrownBy(() -> client.fetch("s-1")).isInstanceOf(SsoUnavailableException.class);
    }
}
