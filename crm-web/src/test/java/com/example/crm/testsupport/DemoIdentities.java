package com.example.crm.testsupport;

import com.example.crm.session.CustomerContext;
import com.example.crm.session.OperatorUser;

/** Rovnakí operátori a zákazníci ako v SSO mocku (a ACL). */
public final class DemoIdentities {

    public static final OperatorUser ADMIN = new OperatorUser("spravca", "Správce");
    public static final OperatorUser READER = new OperatorUser("citatel", "Čtenář");
    public static final CustomerContext PERSON = new CustomerContext("C-1001", "Jan Vzorový");
    public static final CustomerContext COMPANY = new CustomerContext("C-1002", "Firma Ukázka s.r.o.");

    private DemoIdentities() {
    }
}
