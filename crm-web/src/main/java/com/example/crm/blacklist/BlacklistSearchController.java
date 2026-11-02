package com.example.crm.blacklist;

import com.example.crm.acl.Privilege;
import com.example.crm.acl.RequiresPrivilege;
import com.example.crm.session.CrmSession;
import com.example.crm.session.LastSearchCriteria;
import com.example.crm.web.portlet.LegacyPortlet;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.support.SessionStatus;

/**
 * Vyhľadávanie v blackliste. Legacy: render {@code search}/{@code searchresult} + action {@code searchAction}
 * v jednom portletovom controlleri s {@code @SessionAttributes}. Tu: render = GET, action = POST + redirect (PRG).
 */
@Controller
@LegacyPortlet("/blacklist")
@RequestMapping("/blacklist")
@SessionAttributes(BlacklistSearchController.SEARCH_MODEL)
@RequiresPrivilege(Privilege.BLACKLIST_VIEW)
public class BlacklistSearchController {

    static final String SEARCH_MODEL = "searchCriteria";

    private final BlacklistService service;

    public BlacklistSearchController(BlacklistService service) {
        this.service = service;
    }

    /** Volá sa len vtedy, keď kritériá ešte nie sú v session (vlastnosť {@code @SessionAttributes}). */
    @ModelAttribute(SEARCH_MODEL)
    public BlacklistSearchCriteria searchCriteria() {
        return new BlacklistSearchCriteria();
    }

    @ModelAttribute("states")
    public BlacklistState[] states() {
        return BlacklistState.values();
    }

    @GetMapping("/search")
    public String search() {
        return "blacklist/search";
    }

    @PostMapping("/searchAction")
    public String searchAction(@Valid @ModelAttribute(SEARCH_MODEL) BlacklistSearchCriteria criteria,
                               BindingResult result, CrmSession session) {
        if (result.hasErrors()) {
            // Validačná chyba sa vykreslí hneď (bez redirectu), aby zostali chyby pri poliach – rovnako ako render po action v portlete.
            return "blacklist/search";
        }
        // Sdílená session: posledné kritériá vidí aj stará appka (a ladiaci skript show-session).
        session.setGlobalAttribute(LastSearchCriteria.KEY, new LastSearchCriteria(criteria.getFirstName(),
                criteria.getLastName(), criteria.getIdentifierValue(),
                criteria.getState() == null ? null : criteria.getState().name()));
        if (service.search(criteria).isEmpty()) {
            session.pushInfo("blacklist.search.noResult");
            return "redirect:/blacklist/search";
        }
        // PRG: legacy nastavil render parameter; tu redirect, takže F5 na výsledkoch neodošle formulár znova.
        return "redirect:/blacklist/searchresult";
    }

    @GetMapping("/searchresult")
    public String searchResult(@ModelAttribute(SEARCH_MODEL) BlacklistSearchCriteria criteria, Model model) {
        // Hľadá sa znova (rovnako ako legacy render fáza) – výsledky sa nedržia v session.
        model.addAttribute("results", service.search(criteria));
        return "blacklist/searchresult";
    }

    /** Legacy akcia „vyčistiť polia“ bola action fáza; je to len navigácia, preto GET. */
    @GetMapping("/newSearch")
    public String newSearch(SessionStatus status) {
        status.setComplete();
        return "redirect:/blacklist/search";
    }
}
