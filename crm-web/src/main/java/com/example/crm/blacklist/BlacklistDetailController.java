package com.example.crm.blacklist;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import com.example.crm.session.LastViewedRecord;
import com.example.crm.session.OperatorUser;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Detail záznamu. Legacy: render {@code detail}, action {@code changeAction}/{@code addNoteAction} a resource
 * {@code removeIdentAction} (AJAX, vracal HTML fragment cez ModelAndView) – všetko v jednom portletovom controlleri.
 */
@Controller
@LegacyPortlet("/blacklist")
@RequestMapping("/blacklist")
public class BlacklistDetailController {

    private final BlacklistService service;
    private final AuditLog audit;

    public BlacklistDetailController(BlacklistService service, AuditLog audit) {
        this.service = service;
        this.audit = audit;
    }

    @GetMapping("/detail")
    @RequiresPrivilege(Privilege.BLACKLIST_VIEW)
    public String detail(@RequestParam long detailId, Model model, CrmSession session) {
        String view = renderDetail(detailId, model);
        BlacklistRecord record = (BlacklistRecord) model.getAttribute("record");
        // Sdílená session: hlavička starej appky (dashboard v Liferay) ukáže, s kým operátor naposledy pracoval.
        session.setGlobalAttribute(LastViewedRecord.KEY, new LastViewedRecord(record.getId(), record.getFullName()));
        return view;
    }

    @PostMapping("/changeAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String change(@RequestParam long detailId, @Valid @ModelAttribute("changeForm") BlacklistChangeForm form,
                         BindingResult result, Model model, CrmSession session) {
        if (result.hasErrors()) {
            return renderDetail(detailId, model);
        }
        service.change(detailId, form.getState(), form.getValidTo());
        BlacklistRecord changed = load(detailId);
        // Audit s platnosťou po doplnení dneška službou – rovnaká hodnota, akú vidí operátor a DB.
        audit.recordChanged(detailId, session.operatorLogin(), changed.getState(), changed.getValidTo());
        session.pushInfo("blacklist.detail.changed");
        return "redirect:/blacklist/detail?detailId=" + detailId;
    }

    @PostMapping("/addNoteAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String addNote(@RequestParam long detailId, @Valid @ModelAttribute("noteForm") NoteForm form,
                          BindingResult result, Model model, CrmSession session) {
        if (result.hasErrors()) {
            return renderDetail(detailId, model);
        }
        String author = session.getOperator().map(OperatorUser::displayName).orElseThrow();
        service.addNote(detailId, form.getText(), author);
        audit.noteAdded(detailId, session.operatorLogin());
        session.pushInfo("blacklist.detail.noteAdded");
        return "redirect:/blacklist/detail?detailId=" + detailId;
    }

    /** Resource fáza → AJAX endpoint. Vracia HTML fragment rovnako ako legacy – JS na stránke sa nemení. */
    @PostMapping("/resource/removeIdentAction")
    @RequiresPrivilege(Privilege.BLACKLIST_MODIFY)
    public String removeIdentifier(@RequestParam long detailId, @RequestParam long identId, Model model,
                                   CrmSession session) {
        try {
            model.addAttribute("identifiers", service.removeIdentifier(detailId, identId));
            audit.identifierRemoved(detailId, identId, session.operatorLogin());
        } catch (BlacklistRuleException e) {
            model.addAttribute("identifiers", load(detailId).getIdentifiers());
            model.addAttribute("errorKey", e.getMessageKey());
        }
        model.addAttribute("detailId", detailId);
        return "blacklist/fragments/identifiers";
    }

    private String renderDetail(long detailId, Model model) {
        BlacklistRecord record = load(detailId);
        model.addAttribute("record", record);
        model.addAttribute("identifiers", record.getIdentifiers());
        model.addAttribute("detailId", detailId);
        model.addAttribute("states", BlacklistState.values());
        // Pri validačnej chybe už model obsahuje odoslaný formulár s chybami – nesmie sa prepísať.
        if (!model.containsAttribute("changeForm")) {
            model.addAttribute("changeForm", BlacklistChangeForm.from(record));
        }
        if (!model.containsAttribute("noteForm")) {
            model.addAttribute("noteForm", new NoteForm());
        }
        return "blacklist/detail";
    }

    private BlacklistRecord load(long detailId) {
        return service.findById(detailId).orElseThrow(() -> new NotFoundException("záznam blacklistu " + detailId));
    }
}
