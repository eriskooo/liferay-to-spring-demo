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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Kontrakt portu nad skutočnou DB – rovnaké prípady ako InMemoryBlacklistServiceTest (parita správania). */
class JdbcBlacklistServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private static final PostgresTestDatabase DB = new PostgresTestDatabase();

    private static JdbcBlacklistService service;

    @BeforeAll
    static void startDatabase() {
        DB.start();
        service = new JdbcBlacklistService(DB.appDataSource(), CLOCK);
    }

    @AfterAll
    static void stopDatabase() {
        DB.stop();
    }

    @BeforeEach
    void resetSeed() {
        DB.resetSeed();
    }

    @Test
    @DisplayName("Seed obsahuje 8 záznamov s identifikátormi a poznámkou")
    void should_haveSeedData_when_searchingWithoutCriteria() {
        List<BlacklistRecord> all = service.search(new BlacklistSearchCriteria());

        assertThat(ids(all)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(all.get(0).getIdentifiers()).extracting(Identifier::getValue).containsExactly("800512/1234", "AB123456");
        assertThat(all.get(0).getNotes()).extracting(Note::getAuthor).containsExactly("Systém");
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
    @DisplayName("Veľké písmená s diakritikou nájdu ten istý záznam ako in-memory")
    void should_matchUppercaseDiacritics_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setFirstName("VĚRA"))))).containsExactly(2L);
    }

    @Test
    @DisplayName("Zástupné znaky LIKE sa berú ako obyčajné znaky")
    void should_treatWildcardsLiterally_when_searching() {
        assertThat(service.search(criteria(c -> c.setLastName("%")))).isEmpty();
        assertThat(service.search(criteria(c -> c.setLastName("_")))).isEmpty();
    }

    @Test
    @DisplayName("Identifikátor sa hľadá presne, bez ohľadu na veľkosť písmen")
    void should_matchIdentifierExactly_when_searching() {
        assertThat(ids(service.search(criteria(c -> c.setIdentifierValue("ab123456"))))).containsExactly(1L);
        assertThat(service.search(criteria(c -> c.setIdentifierValue("AB1234")))).isEmpty();
    }

    @Test
    @DisplayName("Hľadanie podľa stavu")
    void should_filterByState_when_stateGiven() {
        assertThat(ids(service.search(criteria(c -> c.setState(BlacklistState.INACTIVE))))).containsExactly(3L, 6L);
    }

    @Test
    @DisplayName("Neexistujúci záznam je prázdny Optional")
    void should_returnEmpty_when_recordUnknown() {
        assertThat(service.findById(999).isPresent()).isFalse();
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
    @DisplayName("Aktivácia s „platné do“ ho uloží bez zmeny")
    void should_keepValidTo_when_activatingWithValidTo() {
        service.change(3, BlacklistState.ACTIVE, LocalDate.of(2030, 1, 1));

        assertThat(service.findById(3).get().getValidTo()).isEqualTo(LocalDate.of(2030, 1, 1));
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
        assertThat(service.findById(4).get().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Z dvoch identifikátorov ide odstrániť len prvý, druhý ostane")
    void should_refuseSecond_when_removingBothIdentifiers() {
        service.removeIdentifier(5, 6);

        assertThatThrownBy(() -> service.removeIdentifier(5, 7)).isInstanceOf(BlacklistRuleException.class);
        assertThat(service.findById(5).get().getIdentifiers()).extracting(Identifier::getId).containsExactly(7L);
    }

    @Test
    @DisplayName("Identifikátor iného záznamu (podvrhnuté ID) je NotFound a nič sa nezmaže")
    void should_throwNotFound_when_identifierBelongsToOtherRecord() {
        assertThatThrownBy(() -> service.removeIdentifier(1, 3)).isInstanceOf(NotFoundException.class);
        assertThat(service.findById(2).get().getIdentifiers()).hasSize(1);
    }

    @Test
    @DisplayName("Odstránenie vráti zostávajúce identifikátory")
    void should_returnRemaining_when_identifierRemoved() {
        assertThat(service.removeIdentifier(1, 2)).extracting(Identifier::getId).containsExactly(1L);
    }

    @Test
    @DisplayName("Nový záznam dostane ďalšie ID, je aktívny a má jeden identifikátor")
    void should_assignIdsAndActivate_when_created() {
        BlacklistRecord created = service.create(new NewBlacklistRecord("Nový", "Záznam", LocalDate.of(1990, 1, 1),
                StateReason.DEBT, ConditionType.REQUIRE_DEPOSIT, LocalDate.of(2026, 10, 8), null,
                IdentifierType.ID_CARD, "ZZ123456"));

        assertThat(created.getId()).isEqualTo(9L);
        assertThat(created.getState()).isEqualTo(BlacklistState.ACTIVE);
        assertThat(created.getValidTo()).isNull();
        assertThat(created.getIdentifiers()).containsExactly(new Identifier(11L, IdentifierType.ID_CARD, "ZZ123456"));
    }

    @Test
    @DisplayName("Poznámka má čas z hodín a orezaný text")
    void should_useClockAndTrim_when_addingNote() {
        service.addNote(6, "  Overené telefonicky.  ", "Správce");

        assertThat(service.findById(6).get().getNotes())
                .containsExactly(new Note("Overené telefonicky.", "Správce", LocalDateTime.of(2026, 10, 8, 10, 15)));
    }

    @Test
    @DisplayName("Poznámka k neexistujúcemu záznamu je NotFound")
    void should_throwNotFound_when_addingNoteToUnknownRecord() {
        assertThatThrownBy(() -> service.addNote(999, "text", "Správce")).isInstanceOf(NotFoundException.class);
    }

    private static BlacklistSearchCriteria criteria(Consumer<BlacklistSearchCriteria> setup) {
        BlacklistSearchCriteria criteria = new BlacklistSearchCriteria();
        setup.accept(criteria);
        return criteria;
    }

    private static List<Long> ids(List<BlacklistRecord> records) {
        return records.stream().map(BlacklistRecord::getId).collect(Collectors.toList());
    }
    @Test
    @DisplayName("Súbežné odstránenie oboch identifikátorov: zámok riadku pustí len jedno, jeden identifikátor ostane")
    void should_keepOneIdentifier_when_bothRemovedConcurrently() throws Exception {
        // Závod sa opakuje – bez zámku (FOR UPDATE) by aspoň raz obe vlákna videli 2 identifikátory a zmazali oba.
        for (int round = 0; round < 10; round++) {
            DB.resetSeed();
            assertThat(raceRemovals()).as("úspešné odstránenia v kole %d", round).isEqualTo(1);
            assertThat(service.findById(1).get().getIdentifiers()).hasSize(1);
        }
    }

    private static int raceRemovals() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(removal(start, 1));
            Future<Boolean> second = pool.submit(removal(start, 2));
            start.countDown();
            return (first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0);
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Boolean> removal(final CountDownLatch start, final long identifierId) {
        return new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                start.await();
                try {
                    service.removeIdentifier(1, identifierId);
                    return true;
                } catch (BlacklistRuleException e) {
                    return false;
                }
            }
        };
    }

}
