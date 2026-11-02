package com.example.crm.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.common.NotFoundException;
import com.example.crm.session.CrmSession;
import com.example.crm.session.OperatorUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;

/** Audit vzniká len po úspešnej zmene – controllery priamo, in-memory backend (bez zdieľaného stavu s web testami). */
class BlacklistAuditTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final List<String> lines = new ArrayList<>();
    private final AuditLog audit = new AuditLog(lines::add);
    private final CrmSession session = mock(CrmSession.class);
    private final BlacklistDetailController detail = new BlacklistDetailController(service, audit);
    private final BlacklistCreateController create = new BlacklistCreateController(service, CLOCK, audit);

    @BeforeEach
    void operator() {
        when(session.getOperator()).thenReturn(Optional.of(new OperatorUser("spravca", "Správce")));
        when(session.operatorLogin()).thenCallRealMethod();
    }

    @Test
    @DisplayName("Zmena stavu zapíše audit s platnosťou doplnenou službou")
    void should_auditEffectiveValidTo_when_stateChanged() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        form.setState(BlacklistState.INACTIVE);

        detail.change(5, form, binding(form, "changeForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly(
                "AUDIT event=blacklist.change recordId=5 operator=spravca state=INACTIVE validTo=2026-10-08");
    }

    @Test
    @DisplayName("Validačná chyba pri zmene audit nezapíše")
    void should_notAudit_when_changeInvalid() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        BindingResult result = binding(form, "changeForm");
        result.reject("invalid");

        detail.change(5, form, result, new ExtendedModelMap(), session);

        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu audit nezapíše")
    void should_notAudit_when_recordUnknown() {
        BlacklistChangeForm form = new BlacklistChangeForm();
        form.setState(BlacklistState.ACTIVE);

        assertThatThrownBy(() -> detail.change(999, form, binding(form, "changeForm"), new ExtendedModelMap(), session))
                .isInstanceOf(NotFoundException.class);
        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("Pridanie poznámky zapíše audit bez textu poznámky")
    void should_auditWithoutText_when_noteAdded() {
        NoteForm form = new NoteForm();
        form.setText("Tajný text");

        detail.addNote(6, form, binding(form, "noteForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.note recordId=6 operator=spravca");
    }

    @Test
    @DisplayName("Odstránenie identifikátora zapíše audit, odmietnutie posledného nie")
    void should_auditOnlySuccessfulRemoval_when_removingIdentifiers() {
        detail.removeIdentifier(1, 2, new ExtendedModelMap(), session);
        detail.removeIdentifier(4, 5, new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.identifier.remove recordId=1 identifierId=2 operator=spravca");
    }

    @Test
    @DisplayName("Vytvorenie záznamu zapíše audit s novým ID, neplatný formulár nie")
    void should_auditNewId_when_recordCreated() {
        BlacklistCreateForm invalid = new BlacklistCreateForm();
        BindingResult invalidResult = binding(invalid, "createForm");
        invalidResult.reject("invalid");
        create.create(invalid, invalidResult, new ExtendedModelMap(), session);

        BlacklistCreateForm form = validCreateForm();
        create.create(form, binding(form, "createForm"), new ExtendedModelMap(), session);

        assertThat(lines).containsExactly("AUDIT event=blacklist.create recordId=9 operator=spravca");
    }

    private static BlacklistCreateForm validCreateForm() {
        BlacklistCreateForm form = new BlacklistCreateForm();
        form.setFirstName("Nový");
        form.setLastName("Záznam");
        form.setBirthDate(LocalDate.of(1990, 1, 1));
        form.setStateReason(StateReason.DEBT);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        form.setValidFrom(LocalDate.of(2026, 10, 8));
        form.setIdentifierType(IdentifierType.ID_CARD);
        form.setIdentifierValue("ZZ123456");
        return form;
    }

    private static BindingResult binding(Object target, String name) {
        return new BeanPropertyBindingResult(target, name);
    }
}
