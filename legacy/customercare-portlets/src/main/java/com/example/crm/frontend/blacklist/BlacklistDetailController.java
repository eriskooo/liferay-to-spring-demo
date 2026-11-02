package com.example.crm.frontend.blacklist;

import com.example.crm.frontend.backend.blacklist.BlacklistRecord;
import com.example.crm.frontend.backend.blacklist.BlacklistRuleException;
import com.example.crm.frontend.backend.blacklist.BlacklistService;
import com.example.crm.frontend.backend.blacklist.BlacklistState;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.web.BaseCrmController;
import com.example.crm.frontend.base.web.NotFoundException;
import java.time.LocalDate;
import javax.portlet.ActionResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.portlet.ModelAndView;
import org.springframework.web.portlet.bind.annotation.ActionMapping;
import org.springframework.web.portlet.bind.annotation.RenderMapping;
import org.springframework.web.portlet.bind.annotation.ResourceMapping;

/**
 * Detail záznamu: render, dve action a jeden resource. Resource fáza vracia HTML fragment cez {@link ModelAndView}
 * (legacy vzor) – po migrácii samostatný AJAX endpoint. Zmeny vyžadujú {@code BLACKLIST_MODIFY} nad rámec
 * privilégia portletu.
 */
@Controller
@RequestMapping(value = "VIEW", params = "viewPage=detail")
public class BlacklistDetailController extends BaseCrmController {

    static final int NOTE_MAX_LENGTH = 2000;

    private final BlacklistService blacklistService;

    // Bez Spring beanu: audit nemá závislosti; setter len pre testy (legacy štýl setter injection).
    private AuditLog auditLog = new AuditLog();

    @Autowired
    public BlacklistDetailController(BlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    void setAuditLog(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    /** Stavy pre formulár zmeny. */
    @ModelAttribute("states")
    public BlacklistState[] states() {
        return BlacklistState.values();
    }

    /** Detail záznamu. */
    @RenderMapping
    public String detail(@RequestParam("detailId") long detailId, Model model) {
        BlacklistRecord record = require(detailId);
        model.addAttribute("record", record);
        // Sdílená session: hlavička (aj v novej appke) ukáže, s kým operátor naposledy pracoval.
        getSessionManager().setGlobalAttribute(LastViewedRecord.KEY,
                new LastViewedRecord(record.getId(), record.getFullName()));
        return "blacklist/detail";
    }

    /** Změna stavu a platnosti. */
    @ActionMapping(params = "action=changeAction")
    public void change(@RequestParam("detailId") long detailId, @RequestParam("state") BlacklistState state,
                       @RequestParam(value = "validTo", required = false) LocalDate validTo, ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        blacklistService.change(detailId, state, validTo);
        BlacklistRecord changed = require(detailId);
        // Audit s platnosťou po doplnení dneška službou – rovnaká hodnota, akú vidí operátor a DB.
        auditLog.recordChanged(detailId, operatorLogin(), changed.getState(), changed.getValidTo());
        getSessionManager().pushInfo("blacklist.detail.changed");
        backToDetail(response, detailId);
    }

    /** Pridanie poznámky. */
    @ActionMapping(params = "action=addNoteAction")
    public void addNote(@RequestParam("detailId") long detailId,
                        @RequestParam(value = "text", required = false) String text, ActionResponse response) {
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        if (text == null || text.trim().isEmpty()) {
            getSessionManager().pushError("blacklist.detail.noteEmpty");
        } else if (text.trim().length() > NOTE_MAX_LENGTH) {
            // Dĺžka stĺpca v DB – inak by DB vrátila chybu a používateľ by dostal 500.
            getSessionManager().pushError("validation.maxLength");
        } else {
            blacklistService.addNote(detailId, text, operatorName());
            auditLog.noteAdded(detailId, operatorLogin());
            getSessionManager().pushInfo("blacklist.detail.noteAdded");
        }
        backToDetail(response, detailId);
    }

    /** AJAX: odstránenie identifikátora, vráti HTML fragment zoznamu. Len POST – resource URL nemá p_auth token. */
    @ResourceMapping("removeIdentAction")
    public ModelAndView removeIdentifier(@RequestParam("detailId") long detailId, @RequestParam("identId") long identId,
                                         ResourceRequest request, ResourceResponse response) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            response.setProperty(ResourceResponse.HTTP_STATUS_CODE, "405");
            return null;
        }
        checkPrivilege(Privilege.BLACKLIST_MODIFY);
        ModelAndView view = new ModelAndView("blacklist/fragments/identifiers");
        view.addObject("detailId", detailId);
        try {
            view.addObject("identifiers", blacklistService.removeIdentifier(detailId, identId));
            auditLog.identifierRemoved(detailId, identId, operatorLogin());
        } catch (BlacklistRuleException e) {
            view.addObject("identifiers", require(detailId).getIdentifiers());
            view.addObject("errorKey", e.getMessageKey());
        }
        return view;
    }

    private BlacklistRecord require(long id) {
        BlacklistRecord record = blacklistService.findById(id).orElse(null);
        if (record == null) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
        return record;
    }

    private static void backToDetail(ActionResponse response, long detailId) {
        response.setRenderParameter("viewPage", "detail");
        response.setRenderParameter("detailId", String.valueOf(detailId));
    }
}
