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
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Jan Vzorový"));
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));

        assertThat(controller().requireCustomer().getCustomerId()).isEqualTo("C-1001");
        assertThat(controller().operatorName()).isEqualTo("Správce");
    }

    @Test
    @DisplayName("Login operátora pre audit, „?“ bez identity")
    void should_returnLogin_when_operatorPresent() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));
        assertThat(controller().operatorLogin()).isEqualTo("spravca");

        when(sessionManager.getOperator()).thenReturn(null);
        assertThat(controller().operatorLogin()).isEqualTo("?");
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
