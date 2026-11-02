package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.IdentifierType;
import com.example.crm.frontend.backend.blacklist.StateReason;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.web.BaseCrmController;
import java.time.Clock;
import java.time.LocalDate;
import javax.portlet.ActionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * Nový záznam. {@code @ModelAttribute} na úrovni triedy + {@code @SessionAttributes}: formulár prežije render,
 * AJAX resource aj action – a práve stratená {@code @ModelAttribute} metóda bola chyba v PoC logu.
 */
@Controller
@RequestMapping(value = "VIEW", params = "viewPage=create")
@SessionAttributes(BlacklistCreateController.FORM)
public class BlacklistCreateController extends BaseCrmController {

    static final String FORM = "createForm";

    private final BlacklistService blacklistService;
    private final Clock clock;
    private final BlacklistCreateValidator validator;

    // Bez Spring beanu: audit nemá závislosti; setter len pre testy (legacy štýl setter injection).
    private AuditLog auditLog = new AuditLog();

    @Autowired
    public BlacklistCreateController(BlacklistService blacklistService, Clock clock) {
        this.blacklistService = blacklistService;
        this.clock = clock;
        this.validator = new BlacklistCreateValidator(blacklistService);
    }

    void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** Nový formulár (len ak ešte nie je v session). */
    @ModelAttribute(FORM)
    public CreateForm form() {
        CreateForm form = new CreateForm();
        form.setValidFrom(LocalDate.now(clock));
        return form;
    }

    /** Dôvody pre select. */
    @ModelAttribute("reasons")
    public StateReason[] reasons() {
        return StateReason.values();
    }

    /** Typy identifikátorov pre select. */
    @ModelAttribute("identifierTypes")
    public IdentifierType[] identifierTypes() {
        return IdentifierType.values();
    }

    /** Formulár nového záznamu. */
    @RenderMapping
    public String create(@ModelAttribute(FORM) CreateForm form, Model model) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        model.addAttribute("conditionTypes", blacklistService.allowedConditionTypes(form.getStateReason()));
        return "blacklist/create";
    }

    /** AJAX: povolené podmienky pre zvolený dôvod (HTML fragment {@code <select>}). */
    @ResourceMapping("refreshCreateConditionTypes")
    public String refreshConditionTypes(@RequestParam(value = "stateReason", required = false) StateReason stateReason,
                                        Model model) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        model.addAttribute("conditionTypes", blacklistService.allowedConditionTypes(stateReason));
        return "blacklist/fragments/conditionTypes";
    }

    /** Vytvorenie záznamu. */
    @ActionMapping(params = "action=createAction")
    public void createAction(@ModelAttribute(FORM) CreateForm form, BindingResult result, SessionStatus status,
                             ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        validator.validate(form, result);
        if (result.hasErrors()) {
            response.setRenderParameter("viewPage", "create");
            return;
        }
        BlacklistRecord created = blacklistService.create(form.toNewRecord());
        auditLog.recordCreated(created.getId(), operatorLogin());
        status.setComplete();
        getSessionManager().pushInfo("blacklist.create.created");
        response.setRenderParameter("viewPage", "detail");
        response.setRenderParameter("detailId", String.valueOf(created.getId()));
    }

    /** Zrušení – späť na vyhľadávanie (legacy „goto“ akcia). */
    @ActionMapping(params = "action=gotoSearchAction")
    public void gotoSearch(SessionStatus status, ActionResponse response) {
        status.setComplete();
        response.setRenderParameter("render", "search");
    }
}
