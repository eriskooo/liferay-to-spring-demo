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
    @DisplayName("Přihlášen: operátor, zákazník, posledný záznam, hlášky a ladiace údaje o session")
    void should_showContextAndSessionDebug_when_loggedIn() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Jan Vzorový"));
        when(sessionManager.getGlobalAttribute(LastViewedRecord.KEY)).thenReturn(new LastViewedRecord(1, "Petr Vzorka"));
        when(sessionManager.popMessages()).thenReturn(Collections.singletonList(new UiMessage(UiMessage.Level.INFO, "k")));
        when(requests.getSsoSessionId()).thenReturn("s-1");
        when(flag.current()).thenReturn(ReadSource.REDIS);

        controller.view(model);

        assertThat(((PortalOperator) model.get("operator")).getDisplayName()).isEqualTo("Správce");
        assertThat(((PortalCustomer) model.get("customer")).getCustomerId()).isEqualTo("C-1001");
        assertThat(((LastViewedRecord) model.get("lastViewed")).getFullName()).isEqualTo("Petr Vzorka");
        assertThat((java.util.List<?>) model.get("messages")).hasSize(1);
        assertThat(model.get("changeCustomerUrl")).isEqualTo("/sso/login?returnUrl=%2Fweb%2Fcrm%2Fblacklist");
        assertThat(model.get("readSource")).isEqualTo(ReadSource.REDIS);
        assertThat(model.get("ssoId")).isEqualTo("s-1");
    }
}
