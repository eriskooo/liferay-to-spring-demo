package com.example.crm.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.crm.e2e.pages.CreatePage;
import com.example.crm.e2e.pages.DetailPage;
import com.example.crm.e2e.pages.ResultPage;
import com.example.crm.e2e.pages.SearchPage;
import com.example.crm.e2e.support.AuditEvent;
import com.example.crm.e2e.support.E2eExtension;
import com.example.crm.e2e.support.E2eSession;
import com.example.crm.e2e.support.IdentifierRow;
import com.example.crm.e2e.support.NoteRow;
import com.example.crm.e2e.support.Operator;
import com.example.crm.e2e.support.RecordRow;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Charakterizačné testy blacklistu: správanie starej appky (UI + DB + audit) je špecifikácia pre novú.
 * Očakávané texty sú z legacy messages.properties; odchýlka na migrated sa opravuje v crm-web, nie tu.
 */
@ExtendWith(E2eExtension.class)
class BlacklistCharacterizationIT {

    private static final Duration AUDIT_WAIT = Duration.ofSeconds(3);

    @Test
    @DisplayName("1 Čítanie: vyhľadanie podľa mena s diakritikou nájde jeden záznam a nič nezmení")
    void should_findOneRecord_when_searchingFirstNameWithDiacritics(E2eSession e2e) {
        ResultPage result = new SearchPage(e2e.page()).searchByFirstName("Věra");

        assertThat(result.recordIds()).containsExactly(2L);
        assertThat(e2e.db().recordCount()).isEqualTo(8);
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @Operator("citatel")
    @DisplayName("6 ACL: čitateľ vidí záznam, ale nemá ovládacie prvky zmien")
    void should_hideModifyControls_when_operatorIsReader(E2eSession e2e) {
        SearchPage search = new SearchPage(e2e.page());
        assertThat(search.canCreate()).isFalse();

        DetailPage detail = search.searchByLastName("Vzorka").openDetail(1);

        assertThat(detail.canModify()).isFalse();
        assertThat(e2e.db().record(1)).map(RecordRow::state).contains("ACTIVE");
        assertThat(e2e.log().auditEvents()).isEmpty();
    }
    @Test
    @DisplayName("2 Zápis: zmena stavu bez „platné do“ – hláška, DB s dnešným dátumom, audit")
    void should_deactivateWithToday_when_stateChangedWithoutValidTo(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1)
                .changeState("INACTIVE", "");

        LocalDate today = e2e.db().today();
        assertThat(detail.messages()).contains("Stav záznamu byl změněn.");
        assertThat(detail.state()).isEqualTo("Neaktivní");
        assertThat(e2e.db().record(1)).hasValueSatisfying(row -> {
            assertThat(row.state()).isEqualTo("INACTIVE");
            assertThat(row.validTo()).isEqualTo(today);
        });
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(new AuditEvent("blacklist.change",
                Map.of("recordId", "1", "operator", "spravca", "state", "INACTIVE", "validTo", today.toString())));
    }

    @Test
    @DisplayName("3 Validácia: prázdna poznámka sa neuloží a nezapíše audit")
    void should_rejectNote_when_textEmpty(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1).addNote("");

        assertThat(detail.bodyText()).contains("Poznámka nesmí být prázdná.");
        assertThat(e2e.db().notes(1)).extracting(NoteRow::author).containsExactly("Systém");
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @DisplayName("4a AJAX: odstránenie identifikátora prekreslí zoznam bez prenačítania stránky")
    void should_removeIdentifierWithoutReload_when_removedViaAjax(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByLastName("Vzorka").openDetail(1);
        detail.markPage();

        detail.removeIdentifier(2);

        PlaywrightAssertions.assertThat(detail.identifiers()).not().containsText("AB123456");
        PlaywrightAssertions.assertThat(detail.identifiers()).containsText("800512/1234");
        assertThat(detail.isSamePage()).isTrue();
        assertThat(e2e.db().identifiers(1)).extracting(IdentifierRow::id).containsExactly(1L);
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(new AuditEvent(
                "blacklist.identifier.remove", Map.of("recordId", "1", "identifierId", "2", "operator", "spravca")));
    }

    @Test
    @DisplayName("4b AJAX: posledný identifikátor nejde odstrániť – hláška vo fragmente, DB bez zmeny, bez auditu")
    void should_keepLastIdentifier_when_removingIt(E2eSession e2e) {
        DetailPage detail = new SearchPage(e2e.page()).searchByFirstName("Věra").openDetail(2);

        detail.removeIdentifier(3);

        PlaywrightAssertions.assertThat(detail.identifiers())
                .containsText("Poslední identifikátor nelze odstranit.");
        assertThat(e2e.db().identifiers(2)).extracting(IdentifierRow::id).containsExactly(3L);
        assertThat(e2e.log().auditEvents()).isEmpty();
    }

    @Test
    @DisplayName("5 Vytvorenie: AJAX podmienky podľa dôvodu, nový záznam v DB a audit")
    void should_createRecord_when_formFilled(E2eSession e2e) {
        CreatePage create = new SearchPage(e2e.page()).openCreate().selectReason("DEBT");
        PlaywrightAssertions.assertThat(create.conditionTypeOptions()).hasCount(2);

        DetailPage detail = create.submit("Nový", "Záznam", "1990-01-01", "MANUAL_APPROVAL", "ID_CARD", "ZZ123456");

        assertThat(detail.heading()).contains("Nový Záznam");
        assertThat(detail.messages()).contains("Záznam byl vytvořen.");
        assertThat(e2e.db().recordCount()).isEqualTo(9);
        assertThat(e2e.db().record(9)).map(RecordRow::state).contains("ACTIVE");
        assertThat(e2e.db().identifiers(9)).containsExactly(new IdentifierRow(11L, "ID_CARD", "ZZ123456"));
        assertThat(e2e.log().awaitAuditEvents(1, AUDIT_WAIT)).containsExactly(
                new AuditEvent("blacklist.create", Map.of("recordId", "9", "operator", "spravca")));
    }
}
