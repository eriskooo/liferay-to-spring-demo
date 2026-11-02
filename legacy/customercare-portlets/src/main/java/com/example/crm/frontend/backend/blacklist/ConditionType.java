package com.example.crm.frontend.backend.blacklist;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Podmínka, ktorá sa uplatní pri objednávke osoby z blacklistu. */
public enum ConditionType {
    BLOCK_ALL, REQUIRE_DEPOSIT, MANUAL_APPROVAL;

    /** Povolené podmienky pre dôvod; {@code null} = všetky. Pravidlo backendu – zdieľajú ho všetky implementácie portu. */
    public static List<ConditionType> allowedFor(StateReason reason) {
        if (reason == null) {
            return Arrays.asList(values());
        }
        switch (reason) {
            case FRAUD:
                return Collections.singletonList(BLOCK_ALL);
            case DEBT:
                return Arrays.asList(REQUIRE_DEPOSIT, MANUAL_APPROVAL);
            default:
                return Arrays.asList(MANUAL_APPROVAL, BLOCK_ALL);
        }
    }
}
