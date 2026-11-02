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
                new PortalOperator("spravca", "Správce"), new PortalCustomer("C-1001", "Jan Vzorový")));
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
