package com.example.crm.blacklist;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Port na backend blacklistu. V legacy je za ním databázová služba backendu v tom istom JVM;
 * demo má in-memory implementáciu. Reálna migrácia sem pripojí adaptér na existujúci backend – controllery sa nemenia.
 */
public interface BlacklistService {

    List<BlacklistRecord> search(BlacklistSearchCriteria criteria);

    Optional<BlacklistRecord> findById(long id);

    BlacklistRecord create(NewBlacklistRecord data);

    /** Změna stavu a platnosti. Deaktivácia bez „platné do“ doplní dnešný dátum. */
    void change(long id, BlacklistState state, LocalDate validTo);

    void addNote(long id, String text, String author);

    /** Vráti zostávajúce identifikátory; posledný sa odstrániť nedá ({@link BlacklistRuleException}). */
    List<Identifier> removeIdentifier(long recordId, long identifierId);

    /** Povolené podmienky pre dôvod; {@code null} = všetky. */
    List<ConditionType> allowedConditionTypes(StateReason reason);
}
