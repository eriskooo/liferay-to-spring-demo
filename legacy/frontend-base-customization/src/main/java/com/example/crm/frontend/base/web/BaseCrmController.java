package com.example.crm.frontend.base.web;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Base controller ako v legacy (tam ~1900 riadkov a dedí z externej knižnice): prístup k fasáde session, ACL
 * a spoločný binder. Závislosti cez {@code @Autowired} settery – legacy štýl; pri migrácii konštruktor.
 */
public abstract class BaseCrmController {

    private SessionManagerCustom sessionManager;
    private OperatorAcl operatorAcl;

    @Autowired
    public void setSessionManager(SessionManagerCustom sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Autowired
    public void setOperatorAcl(OperatorAcl operatorAcl) {
        this.operatorAcl = operatorAcl;
    }

    /** Fasáda session (legacy: {@code getSessionManager()} ~400 volaní). */
    protected SessionManagerCustom getSessionManager() {
        return sessionManager;
    }

    /** Vyhodí {@code AclDeniedException}, ak operátor privilégium nemá (jemnejšie než privilégium portletu). */
    protected void checkPrivilege(Privilege privilege) {
        operatorAcl.checkPrivilege(privilege);
    }

    /** Či má operátor privilégium. */
    protected boolean hasPrivilege(Privilege privilege) {
        return operatorAcl.hasPrivilege(privilege);
    }

    /** Zákazník zo SSO session; bez neho stránka nemá kontext → 404. */
    protected PortalCustomer requireCustomer() {
        PortalCustomer customer = sessionManager.getCustomer();
        if (customer == null) {
            throw new NotFoundException("zákazník v SSO session");
        }
        return customer;
    }

    /** Jméno operátora (autor poznámky, žiadosti). */
    protected String operatorName() {
        PortalOperator operator = sessionManager.getOperator();
        return operator == null ? "?" : operator.getDisplayName();
    }

    /** Login operátora pre audit (meno je pre UI); „?“ ak identita chýba. */
    protected String operatorLogin() {
        PortalOperator operator = sessionManager.getOperator();
        return operator == null ? "?" : operator.getLogin();
    }

    /** Spoločné editory pre všetky controllery. */
    @InitBinder
    public void initBaseBinder(WebDataBinder binder) {
        binder.registerCustomEditor(LocalDate.class, new LocalDateEditor());
    }
}
