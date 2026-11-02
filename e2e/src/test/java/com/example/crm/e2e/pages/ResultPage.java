package com.example.crm.e2e.pages;

import com.microsoft.playwright.Page;
import java.util.List;

/** Výsledky vyhledávání. */
public final class ResultPage {

    private static final String DETAIL_PREFIX = "detail-";

    private final Page page;

    public ResultPage(Page page) {
        this.page = page;
        page.locator("#resultCount").waitFor();
    }

    /** ID nájdených záznamov v poradí zobrazenia. */
    public List<Long> recordIds() {
        return page.locator("a[id^='" + DETAIL_PREFIX + "']").all().stream()
                .map(link -> Long.parseLong(link.getAttribute("id").substring(DETAIL_PREFIX.length())))
                .toList();
    }

    /** Otvorí detail kliknutím – portlet URL nie sú stabilné, používateľ aj tak kliká. */
    public DetailPage openDetail(long id) {
        page.locator("#" + DETAIL_PREFIX + id).click();
        return new DetailPage(page);
    }
}
