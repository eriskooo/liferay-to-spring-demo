package com.example.crm.frontend.blacklist;

import java.io.Serializable;

/** Naposledy otevřený záznam blacklistu – hlavička ho ukazuje na každej stránke (aj v novej appke po migrácii). */
public class LastViewedRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Kľúč vo fasáde (v úložisku {@code APP_LAST_VIEWED_RECORD}). */
    public static final String KEY = "LAST_VIEWED_RECORD";

    private long id;
    private String fullName;

    public LastViewedRecord() {
    }

    public LastViewedRecord(long id, String fullName) {
        this.id = id;
        this.fullName = fullName;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
