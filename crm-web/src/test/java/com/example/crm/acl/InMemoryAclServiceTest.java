package com.example.crm.acl;

import com.example.crm.session.OperatorUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAclServiceTest {

    private final AclService acl = new InMemoryAclService();

    @Test
    void readerCanOnlyViewBlacklist() {
        OperatorUser reader = new OperatorUser("citatel", "Čtenář");

        assertThat(acl.hasPrivilege(reader, Privilege.BLACKLIST_VIEW)).isTrue();
        assertThat(acl.hasPrivilege(reader, Privilege.BLACKLIST_MODIFY)).isFalse();
        assertThat(acl.hasPrivilege(reader, Privilege.DEACTIVATION)).isFalse();
    }

    @Test
    void adminHasAllPrivileges() {
        assertThat(acl.privilegesOf(new OperatorUser("spravca", "Správce")))
                .containsExactlyInAnyOrder(Privilege.values());
    }

    @Test
    void unknownOperatorHasNothing() {
        assertThat(acl.privilegesOf(new OperatorUser("niekto", "?"))).isEmpty();
    }

    @Test
    void deniedExceptionCarriesPrivilege() {
        assertThat(new AclDeniedException(Privilege.DEACTIVATION).getPrivilege()).isEqualTo(Privilege.DEACTIVATION);
    }
}
