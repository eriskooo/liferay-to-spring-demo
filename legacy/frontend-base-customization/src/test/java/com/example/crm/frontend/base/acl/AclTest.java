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
    @DisplayName("Čtenář smie len čítať blacklist, správca všetko, neznámy nič")
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
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("citatel", "Čtenář"));

        assertThatThrownBy(() -> acl.checkPrivilege(Privilege.BLACKLIST_MODIFY))
                .isInstanceOf(AclDeniedException.class)
                .extracting(e -> ((AclDeniedException) e).getPrivilege())
                .isEqualTo(Privilege.BLACKLIST_MODIFY);
    }

    @Test
    @DisplayName("Privilégium podľa mena (z JSP tagu); preklep v mene je chyba, nie tiché zamietnutie")
    void should_resolvePrivilegeByName_when_calledFromTag() {
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));

        assertThat(acl.hasPrivilege("BLACKLIST_MODIFY")).isTrue();
        assertThatThrownBy(() -> acl.hasPrivilege("BLACKLIST_MODIFI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BLACKLIST_MODIFI");
    }
}
