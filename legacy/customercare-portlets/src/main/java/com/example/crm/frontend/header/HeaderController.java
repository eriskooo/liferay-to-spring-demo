package com.example.crm.frontend.header;

import com.example.crm.frontend.base.session.ReadSourceFlag;
import com.example.crm.frontend.base.session.RequestAwareManager;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.web.BaseCrmController;
import com.example.crm.frontend.blacklist.LastViewedRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;

/**
 * Portlet hlavičky, ktorý theme vkladá na každú stránku ({@code $theme.runtime}). Ekvivalent legacy portletov
 * „hlavička / kontext zákazníka / info hlášky“ – portál skladá stránku z portletov; po migrácii z toho bude
 * fragment layout šablóny. Ladiaci riadok (zdroj čtení, SSO_ID) je len pre demo zdieľanej session.
 */
@Controller
@RequestMapping("VIEW")
public class HeaderController extends BaseCrmController {

    private final ReadSourceFlag readSourceFlag;
    private final RequestAwareManager requests;

    @Autowired
    public HeaderController(ReadSourceFlag readSourceFlag, RequestAwareManager requests) {
        this.readSourceFlag = readSourceFlag;
        this.requests = requests;
    }

    /** Hlavička: operátor, zákazník, posledný záznam, hlášky z action fázy. */
    @RenderMapping
    public String view(Model model) {
        SessionManagerCustom session = getSessionManager();
        model.addAttribute("operator", session.getOperator());
        model.addAttribute("customer", session.getCustomer());
        if (session.getOperator() == null) {
            model.addAttribute("loginUrl", session.getLoginUrl());
        } else {
            // Zákazníka mení SSO portál, nie CRM – jeho stránka pri platnej session ponúkne zmenu.
            model.addAttribute("changeCustomerUrl", session.getLoginUrl());
        }
        model.addAttribute("lastViewed", session.getGlobalAttribute(LastViewedRecord.KEY));
        model.addAttribute("messages", session.popMessages());
        model.addAttribute("readSource", readSourceFlag.current());
        model.addAttribute("ssoId", requests.getSsoSessionId());
        return "header/view";
    }
}
