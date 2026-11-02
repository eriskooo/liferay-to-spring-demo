package com.example.crm.blacklist;

import java.util.List;

/** Podmínka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType {
    BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL;

    /** Povolené podmienky pre dôvod; {@code null} = všetky. Pravidlo backendu – zdieľajú ho všetky implementácie portu. */
    public static List<ConditionType> allowedFor(StateReason reason) {
        if (reason == null) {
            return List.of(values());
        }
        return switch (reason) {
            case FRAUD -> List.of(BLOCK_ALL);
            case DEBT -> List.of(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            case ABUSE -> List.of(MANUAL_APPROVAL, BLOCK_ALL);
        };
    }
}
