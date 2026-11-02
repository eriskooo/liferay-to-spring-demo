package com.example.crm.frontend.backend.blacklist;

import static com.example.crm.frontend.backend.blacklist.BlacklistState.ACTIVE;
import static com.example.crm.frontend.backend.blacklist.BlacklistState.INACTIVE;
import static com.example.crm.frontend.backend.blacklist.ConditionType.BLOCK_ALL;
import static com.example.crm.frontend.backend.blacklist.ConditionType.MANUAL_APPROVAL;
import static com.example.crm.frontend.backend.blacklist.ConditionType.REQUIRE_DEPOSIT;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.BIRTH_CODE;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.COMPANY_REG_NO;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.ID_CARD;
import static com.example.crm.frontend.backend.blacklist.IdentifierType.PASSPORT;
import static com.example.crm.frontend.backend.blacklist.StateReason.ABUSE;
import static com.example.crm.frontend.backend.blacklist.StateReason.DEBT;
import static com.example.crm.frontend.backend.blacklist.StateReason.FRAUD;

import com.example.crm.frontend.base.web.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * In-memory náhrada backendu (vymyslené dáta). Metódy sú {@code synchronized} – jednoduchosť pred výkonom;
 * reálny backend rieši konzistenciu transakciou v DB.
 */
public class InMemoryBlacklistService implements BlacklistService {

    private final Clock clock;
    private final Map<Long, BlacklistRecord> records = new LinkedHashMap<>();
    private long nextRecordId = 1;
    private long nextIdentifierId = 1;

    public InMemoryBlacklistService(Clock clock) {
        this.clock = clock;
        seed();
    }

    @Override
    public synchronized List<BlacklistRecord> search(BlacklistSearchCriteria criteria) {
        return records.values().stream().filter(r -> matches(r, criteria)).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<BlacklistRecord> findById(long id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public synchronized BlacklistRecord create(NewBlacklistRecord data) {
        BlacklistRecord record = add(data.getFirstName(), data.getLastName(), data.getBirthDate(), ACTIVE,
                data.getStateReason(), data.getConditionType(), data.getValidFrom(), data.getValidTo());
        identifier(record, data.getIdentifierType(), data.getIdentifierValue());
        return record;
    }

    @Override
    public synchronized void change(long id, BlacklistState state, LocalDate validTo) {
        LocalDate effectiveValidTo = (state == INACTIVE && validTo == null) ? LocalDate.now(clock) : validTo;
        require(id).changeState(state, effectiveValidTo);
    }

    @Override
    public synchronized void addNote(long id, String text, String author) {
        require(id).addNote(new Note(text.trim(), author, LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES)));
    }

    @Override
    public synchronized List<Identifier> removeIdentifier(long recordId, long identifierId) {
        BlacklistRecord record = require(recordId);
        Identifier identifier = null;
        for (Identifier candidate : record.getIdentifiers()) {
            if (candidate.getId() == identifierId) {
                identifier = candidate;
            }
        }
        if (identifier == null) {
            // Identifikátor iného záznamu = podvrhnuté ID → 404, nie zmena cudzích dát.
            throw new NotFoundException("identifikátor " + identifierId + " záznamu " + recordId);
        }
        if (record.getIdentifiers().size() == 1) {
            throw new BlacklistRuleException("blacklist.detail.lastIdentifier");
        }
        record.removeIdentifier(identifier);
        return Collections.unmodifiableList(new ArrayList<>(record.getIdentifiers()));
    }

    @Override
    public List<ConditionType> allowedConditionTypes(StateReason reason) {
        return ConditionType.allowedFor(reason);
    }

    private BlacklistRecord require(long id) {
        BlacklistRecord record = records.get(id);
        if (record == null) {
            throw new NotFoundException("záznam blacklistu " + id);
        }
        return record;
    }

    private static boolean matches(BlacklistRecord record, BlacklistSearchCriteria criteria) {
        return containsIgnoreCase(record.getFirstName(), criteria.getFirstName())
                && containsIgnoreCase(record.getLastName(), criteria.getLastName())
                && identifierMatches(record, criteria.getIdentifierValue())
                && (criteria.getState() == null || criteria.getState() == record.getState());
    }

    private static boolean identifierMatches(BlacklistRecord record, String query) {
        if (isBlank(query)) {
            return true;
        }
        for (Identifier identifier : record.getIdentifiers()) {
            if (identifier.getValue().equalsIgnoreCase(query.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String value, String query) {
        return isBlank(query) || value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private BlacklistRecord add(String firstName, String lastName, LocalDate birthDate, BlacklistState state,
                                StateReason reason, ConditionType condition, LocalDate validFrom, LocalDate validTo) {
        BlacklistRecord record = new BlacklistRecord(nextRecordId++, firstName, lastName, birthDate, state, reason,
                condition, validFrom, validTo);
        records.put(record.getId(), record);
        return record;
    }

    private void identifier(BlacklistRecord record, IdentifierType type, String value) {
        record.addIdentifier(new Identifier(nextIdentifierId++, type, value));
    }

    private void seed() {
        BlacklistRecord r1 = add("Petr", "Vzorka", LocalDate.of(1980, 5, 12), ACTIVE, FRAUD, BLOCK_ALL,
                LocalDate.of(2024, 1, 10), null);
        identifier(r1, BIRTH_CODE, "800512/1234");
        identifier(r1, ID_CARD, "AB123456");
        r1.addNote(new Note("Opakované pokusy o objednávku na cizí doklady.", "Systém",
                LocalDateTime.of(2024, 1, 10, 9, 0)));

        BlacklistRecord r2 = add("Věra", "Příkladová", LocalDate.of(1975, 11, 3), ACTIVE, DEBT, REQUIRE_DEPOSIT,
                LocalDate.of(2023, 6, 1), LocalDate.of(2027, 6, 1));
        identifier(r2, BIRTH_CODE, "755103/2345");

        BlacklistRecord r3 = add("Jan", "Testovací", LocalDate.of(1990, 2, 20), INACTIVE, DEBT, MANUAL_APPROVAL,
                LocalDate.of(2022, 3, 15), LocalDate.of(2024, 3, 15));
        identifier(r3, ID_CARD, "CD987654");

        BlacklistRecord r4 = add("Eva", "Ukázková", LocalDate.of(1988, 7, 30), ACTIVE, ABUSE, MANUAL_APPROVAL,
                LocalDate.of(2025, 2, 1), null);
        identifier(r4, PASSPORT, "P1234567");

        BlacklistRecord r5 = add("Tomáš", "Fiktivní", LocalDate.of(1969, 12, 24), ACTIVE, FRAUD, BLOCK_ALL,
                LocalDate.of(2021, 9, 9), null);
        identifier(r5, BIRTH_CODE, "691224/3456");
        identifier(r5, PASSPORT, "X7654321");

        BlacklistRecord r6 = add("Lucie", "Demová", LocalDate.of(1995, 4, 18), INACTIVE, ABUSE, BLOCK_ALL,
                LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        identifier(r6, ID_CARD, "EF555444");

        BlacklistRecord r7 = add("Karel", "Modelový", LocalDate.of(1983, 8, 8), ACTIVE, DEBT, REQUIRE_DEPOSIT,
                LocalDate.of(2025, 10, 1), null);
        identifier(r7, COMPANY_REG_NO, "12345678");

        BlacklistRecord r8 = add("Petr", "Vzorkový", LocalDate.of(1979, 1, 1), ACTIVE, DEBT, MANUAL_APPROVAL,
                LocalDate.of(2024, 5, 5), null);
        identifier(r8, BIRTH_CODE, "790101/4567");
    }
}
