package com.example.crm.frontend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.backend.blacklist.ConditionType;
import com.example.crm.frontend.backend.blacklist.IdentifierType;
import com.example.crm.frontend.backend.blacklist.InMemoryBlacklistService;
import com.example.crm.frontend.backend.blacklist.StateReason;
import com.example.crm.frontend.base.acl.AclDeniedException;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.acl.Privilege;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.sso.api.PortalOperator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

class BlacklistCreateControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final BlacklistCreateController controller = new BlacklistCreateController(service, CLOCK);
    private final MockActionResponse response = new MockActionResponse();
    private final List<String> auditLines = new java.util.ArrayList<String>();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(acl);
        controller.setAuditLog(new AuditLog(auditLines::add));
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));
    }

    @Test
    @DisplayName("Nový formulár má „platné od“ dnes a ponúka všetky podmienky")
    void should_prefillValidFromAndOfferAllConditions_when_rendered() {
        CreateForm form = controller.form();
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.create(form, model)).isEqualTo("blacklist/create");
        assertThat(form.getValidFrom()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(model.get("conditionTypes")).isEqualTo(service.allowedConditionTypes(null));
        verify(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);
    }

    @Test
    @DisplayName("Bez BLACKLIST_MODIFY sa formulár nezobrazí")
    void should_refuse_when_notPrivileged() {
        doThrow(new AclDeniedException(Privilege.BLACKLIST_MODIFY)).when(acl).checkPrivilege(Privilege.BLACKLIST_MODIFY);

        assertThatThrownBy(() -> controller.create(controller.form(), new ExtendedModelMap()))
                .isInstanceOf(AclDeniedException.class);
    }

    @Test
    @DisplayName("AJAX refresh vráti fragment s podmienkami povolenými pre dôvod")
    void should_returnAllowedConditions_when_reasonChanged() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.refreshConditionTypes(StateReason.DEBT, model)).isEqualTo("blacklist/fragments/conditionTypes");
        assertThat(model.get("conditionTypes")).isEqualTo(java.util.Arrays.asList(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL));
    }

    @Test
    @DisplayName("Platný formulár: záznam vznikne, session atribút sa ukončí, render na detail")
    void should_createAndGoToDetail_when_formValid() {
        CreateForm form = validForm();
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.createAction(form, bindingResult(form), status, response);

        assertThat(service.findById(9)).isPresent();
        assertThat(status.isComplete()).isTrue();
        verify(sessionManager).pushInfo("blacklist.create.created");
        assertThat(response.getRenderParameter("viewPage")).isEqualTo("detail");
        assertThat(response.getRenderParameter("detailId")).isEqualTo("9");
    }

    @Test
    @DisplayName("Vytvorenie záznamu zapíše audit s novým ID")
    void should_auditNewId_when_recordCreated() {
        CreateForm form = validForm();

        controller.createAction(form, bindingResult(form), new SimpleSessionStatus(), response);

        assertThat(auditLines).containsExactly("AUDIT event=blacklist.create recordId=9 operator=spravca");
    }

    @Test
    @DisplayName("Neplatný formulár audit nezapíše")
    void should_notAudit_when_formInvalid() {
        CreateForm form = new CreateForm();

        controller.createAction(form, bindingResult(form), new SimpleSessionStatus(), response);

        assertThat(auditLines).isEmpty();
    }

    @Test
    @DisplayName("Prázdny formulár: chyby pri povinných poliach, späť na formulár, nič nevznikne")
    void should_reportRequiredFields_when_formEmpty() {
        CreateForm form = new CreateForm();
        BindingResult errors = bindingResult(form);
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.createAction(form, errors, status, response);

        assertThat(fieldsWithCode(errors, "validation.required")).contains("firstName", "lastName", "birthDate",
                "stateReason", "conditionType", "validFrom", "identifierType", "identifierValue");
        assertThat(response.getRenderParameter("viewPage")).isEqualTo("create");
        assertThat(status.isComplete()).isFalse();
        assertThat(service.findById(9)).isNotPresent();
    }

    @Test
    @DisplayName("Business pravidlá: platnosť, formát identifikátora, podmienka podľa dôvodu")
    void should_reportRuleViolations_when_valuesInconsistent() {
        CreateForm form = validForm();
        form.setValidTo(LocalDate.of(2026, 1, 1));
        form.setIdentifierValue("12AB");
        form.setStateReason(StateReason.FRAUD);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        BindingResult errors = bindingResult(form);

        new BlacklistCreateValidator(service).validate(form, errors);

        assertThat(errors.getFieldError("validTo").getCode()).isEqualTo("validation.validToBeforeValidFrom");
        assertThat(errors.getFieldError("identifierValue").getCode()).isEqualTo("validation.identifierFormat");
        assertThat(errors.getFieldError("conditionType").getCode()).isEqualTo("validation.conditionNotAllowed");
    }

    @Test
    @DisplayName("Zlý dátum (chyba konverzie) nedostane navyše „povinný údaj“")
    void should_keepTypeMismatchOnly_when_dateUnparseable() {
        CreateForm form = validForm();
        form.setBirthDate(null);
        BindingResult errors = bindingResult(form);
        errors.rejectValue("birthDate", "typeMismatch");

        new BlacklistCreateValidator(service).validate(form, errors);

        assertThat(errors.getFieldErrors("birthDate")).extracting(e -> e.getCode()).containsExactly("typeMismatch");
    }

    @Test
    @DisplayName("Zrušení ukončí formulár v session a vráti vyhľadávanie")
    void should_completeAndReturnToSearch_when_cancelled() {
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.gotoSearch(status, response);

        assertThat(status.isComplete()).isTrue();
        assertThat(response.getRenderParameter("render")).isEqualTo("search");
    }

    private static CreateForm validForm() {
        CreateForm form = new CreateForm();
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

    private static BindingResult bindingResult(CreateForm form) {
        return new BeanPropertyBindingResult(form, BlacklistCreateController.FORM);
    }

    private static List<String> fieldsWithCode(BindingResult errors, String code) {
        return errors.getFieldErrors().stream().filter(e -> code.equals(e.getCode()))
                .map(e -> e.getField()).collect(Collectors.toList());
    }
    @Test
    @DisplayName("Jméno a příjmení nad 100 znakov (dĺžka stĺpca v DB) sú chyby polí, nič nevznikne")
    void should_rejectValuesLongerThanColumns_when_formValidated() {
        CreateForm form = validForm();
        form.setFirstName(repeat('x', 101));
        form.setLastName(repeat('y', 101));
        BindingResult errors = bindingResult(form);

        controller.createAction(form, errors, new SimpleSessionStatus(), response);

        assertThat(fieldsWithCode(errors, "validation.maxLength")).containsExactlyInAnyOrder("firstName", "lastName");
        assertThat(service.findById(9).isPresent()).isFalse();
        assertThat(auditLines).isEmpty();
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

}
