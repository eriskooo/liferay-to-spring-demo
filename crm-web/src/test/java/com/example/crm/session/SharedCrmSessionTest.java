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

    private static final SsoIdentity ADMIN = new SsoIdentity("s-1", new OperatorUser("spravca", "Správce"),
            new CustomerContext("C-1001", "Jan Vzorový"));

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
                .setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(1, "Petr Vzorka"));

        assertThat(store.data().get("s-1"))
                .containsEntry("APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Petr Vzorka\"}");
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
        session.setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(1, "Petr Vzorka"));

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
