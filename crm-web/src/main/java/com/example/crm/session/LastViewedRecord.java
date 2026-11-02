package com.example.crm.session;

import java.io.Serializable;

/** Naposledy otevřený záznam blacklistu – hlavička starej aj novej appky ho ukazuje. */
public record LastViewedRecord(long id, String fullName) implements Serializable {

    /** Kľúč vo fasáde (v úložisku APP_LAST_VIEWED_RECORD). */
    public static final String KEY = "LAST_VIEWED_RECORD";
}
