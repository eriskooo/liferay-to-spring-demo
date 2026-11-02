package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlacklistCreateFormValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validFormHasNoViolations() {
        assertThat(violations(validForm())).isEmpty();
    }

    @Test
    void emptyFormReportsRequiredFields() {
        assertThat(violations(new BlacklistCreateForm())).contains(
                "firstName", "lastName", "birthDate", "stateReason", "conditionType", "validFrom",
                "identifierType", "identifierValue");
    }

    @Test
    void validToBeforeValidFromIsInvalidPeriod() {
        BlacklistCreateForm form = validForm();
        form.setValidTo(form.getValidFrom().minusDays(1));

        assertThat(violations(form)).containsExactly("periodValid");
    }

    @Test
    void identifierMustMatchTypeFormat() {
        BlacklistCreateForm form = validForm();
        form.setIdentifierType(IdentifierType.BIRTH_CODE);
        form.setIdentifierValue("abc");

        assertThat(violations(form)).containsExactly("identifierValid");
    }

    @Test
    void birthDateMustBeInPast() {
        BlacklistCreateForm form = validForm();
        form.setBirthDate(LocalDate.now().plusDays(1));

        assertThat(violations(form)).containsExactly("birthDate");
    }

    private Set<String> violations(BlacklistCreateForm form) {
        return validator.validate(form).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    static BlacklistCreateForm validForm() {
        BlacklistCreateForm form = new BlacklistCreateForm();
        form.setFirstName("Nový");
        form.setLastName("Záznam");
        form.setBirthDate(LocalDate.of(1990, 1, 1));
        form.setStateReason(StateReason.DEBT);
        form.setConditionType(ConditionType.REQUIRE_DEPOSIT);
        form.setValidFrom(LocalDate.of(2026, 1, 1));
        form.setIdentifierType(IdentifierType.COMPANY_REG_NO);
        form.setIdentifierValue("87654321");
        return form;
    }
    @Test
    @org.junit.jupiter.api.DisplayName("Jméno a příjmení nad 100 znakov (dĺžka stĺpca v DB) sú chyba poľa, nie 500")
    void should_rejectNamesLongerThanColumn_when_validating() {
        BlacklistCreateForm form = validForm();
        form.setFirstName("x".repeat(101));
        form.setLastName("y".repeat(101));

        assertThat(violations(form)).containsExactlyInAnyOrder("firstName", "lastName");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Jméno presne na hranici 100 znakov je platné")
    void should_acceptNamesAtColumnLength_when_validating() {
        BlacklistCreateForm form = validForm();
        form.setFirstName("x".repeat(100));

        assertThat(violations(form)).isEmpty();
    }

}
