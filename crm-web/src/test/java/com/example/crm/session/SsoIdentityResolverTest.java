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
        SsoIdentity identity = new SsoIdentity("s-1", new OperatorUser("spravca", "Správce"), null);
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
