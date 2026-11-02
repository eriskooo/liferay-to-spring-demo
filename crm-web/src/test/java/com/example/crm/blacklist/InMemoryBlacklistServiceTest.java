package com.example.crm.blacklist;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.example.crm.common.NotFoundException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);

    @Test
    void seedHasEightRecords() {
        assertThat(service.search(new BlacklistSearchCriteria())).hasSize(8);
    }

    @Test
    void lastNameSearchIsCaseInsensitiveContains() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    void firstNameSearchHandlesDiacritics() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("věra"))))).containsExactly(2L);
    }

    @Test
    void identifierSearchIsExact() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("AB123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    void stateSearch() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    void deactivationWithoutValidToUsesToday() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).orElseThrow();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void changeOfUnknownRecordIsNotFound() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void lastIdentifierCannotBeRemoved() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
    }

    @Test
    void identifierOfAnotherRecordIsNotFound() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeIdentifierReturnsRemaining() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::id).containsExactly(1L);
    }

    @Test
    void allowedConditionTypesDependOnReason() {
        assertThat(service.allowedConditionTypes(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(service.allowedConditionTypes(null)).containsExactly(ConditionType.values());
    }

    @Test
    void createAssignsIdsAndStartsActive() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getIdentifiers()).extracting(Identifier::id).containsExactly(11L);
    }

    @Test
    void noteUsesClockAndAuthor() {
        service.addNote(6, "  Overené telefonicky.  ", "Správce");

        assertThat(service.findById(6).orElseThrow().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správce", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    void identifierFormats() {
        assertThat(IdentifierType.BIRTH_CODE.isValid("800512/1234")).isTrue();
        assertThat(IdentifierType.BIRTH_CODE.isValid("abc")).isFalse();
        assertThat(IdentifierType.ID_CARD.isValid("AB123456")).isTrue();
        assertThat(IdentifierType.PASSPORT.isValid("P1234567")).isTrue();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid("1234567")).isFalse();
    }

    @Test
    void emptyCriteriaAreNotFilled() {
        BlacklistSearchCriteria blank = criteria(c -> c.setLastName("   "));
        assertThat(blank.isAnyCriterionFilled()).isFalse();
        assertThat(criteria(c -> c.setState(BlacklistState.ACTIVE)).isAnyCriterionFilled()).isTrue();
    }

    private static BlacklistSearchCriteria criteria(java.util.function.Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).toList();
    }
}
