package com.example.crm.frontend.backend.blacklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InMemoryBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryBlacklistService service = new InMemoryBlacklistService(CLOCK);

    @Test
    @DisplayName("Seed obsahuje 8 záznamov")
    void should_haveEightRecords_when_seeded() {
        assertThat(service.search(new BlacklistSearchCriteria())).hasSize(8);
    }

    @Test
    @DisplayName("Příjmení sa hľadá ako podreťazec bez ohľadu na veľkosť písmen")
    void should_matchLastNameContainsIgnoreCase_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setLastName("vzork"))))).containsExactly(1L, 8L);
    }

    @Test
    @DisplayName("Hľadanie funguje s diakritikou")
    void should_matchDiacritics_when_searchingFirstName() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("věra"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Identifikátor sa hľadá presne")
    void should_matchIdentifierExactly_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("AB123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    @DisplayName("Hľadanie podľa stavu")
    void should_filterByState_when_stateGiven() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    @DisplayName("Deaktivácia bez „platné do“ doplní dnešok")
    void should_useToday_when_deactivatingWithoutValidTo() {
        service.change(5, BlacklistState.INACTIVE, null);

        BlacklistRecord record = service.findById(5).get();
        assertThat(record.getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(record.getValidTo()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Zmena neexistujúceho záznamu je NotFound")
    void should_throwNotFound_when_changingUnknownRecord() {
        assertThatThrownBy(() -> service.change(999, BlacklistState.ACTIVE, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Posledný identifikátor sa odstrániť nedá")
    void should_refuse_when_removingLastIdentifier() {
        assertThatThrownBy(() -> service.removeIdentifier(4, 5))
                .isInstanceOf(BlacklistRuleException.class)
                .extracting(e -> ((BlacklistRuleException) e).getMessageKey())
                .isEqualTo("blacklist.detail.lastIdentifier");
    }

    @Test
    @DisplayName("Identifikátor iného záznamu (podvrhnuté ID) je NotFound")
    void should_throwNotFound_when_identifierBelongsToOtherRecord() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Odstránenie vráti zostávajúce identifikátory")
    void should_returnRemaining_when_identifierRemoved() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("Povolené podmienky závisia od dôvodu, null = všetky")
    void should_dependOnReason_when_listingConditionTypes() {
        assertThat(service.allowedConditionTypes(StateReason.FRAUD)).containsExactly(ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(StateReason.DEBT))
                .containsExactly(ConditionType.REQUIRE_DEPOSIT, ConditionType.MANUAL_APPROVAL);
        assertThat(service.allowedConditionTypes(StateReason.ABUSE))
                .containsExactly(ConditionType.MANUAL_APPROVAL, ConditionType.BLOCK_ALL);
        assertThat(service.allowedConditionTypes(null)).containsExactly(ConditionType.values());
    }

    @Test
    @DisplayName("Nový záznam dostane ďalšie ID a je aktívny")
    void should_assignIdsAndActivate_when_created() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getIdentifiers()).extracting(Identifier::getId).containsExactly(11L);
    }

    @Test
    @DisplayName("Poznámka má čas z hodín a orezaný text")
    void should_useClockAndTrim_when_addingNote() {
        service.addNote(6, "  Overené telefonicky.  ", "Správce");

        assertThat(service.findById(6).get().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správce", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    @DisplayName("Formáty identifikátorov")
    void should_validateFormats_when_checkingIdentifiers() {
        assertThat(IdentifierType.BIRTH_CODE.isValid("800512/1234")).isTrue();
        assertThat(IdentifierType.BIRTH_CODE.isValid("abc")).isFalse();
        assertThat(IdentifierType.ID_CARD.isValid("AB123456")).isTrue();
        assertThat(IdentifierType.PASSPORT.isValid("P1234567")).isTrue();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid("1234567")).isFalse();
        assertThat(IdentifierType.COMPANY_REG_NO.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("Prázdne kritériá (aj medzery) nie sú vyplnené")
    void should_reportEmpty_when_criteriaBlank() {
        assertThat(criteria(c -> c.setLastName("   ")).isAnyCriterionFilled()).isFalse();
        assertThat(criteria(c -> c.setState(BlacklistState.ACTIVE)).isAnyCriterionFilled()).isTrue();
    }

    private static BlacklistSearchCriteria criteria(Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).collect(Collectors.toList());
    }
}
