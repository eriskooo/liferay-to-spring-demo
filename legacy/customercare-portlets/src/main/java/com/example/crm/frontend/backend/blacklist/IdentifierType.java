package com.example.crm.frontend.backend.blacklist;

import java.util.regex.Pattern;

/** Typ identifikátoru osoby a jeho formát (zjednodušené vzory pre demo). */
public enum IdentifierType {
    BIRTH_CODE("\\d{6}/?\\d{3,4}"),
    ID_CARD("[A-Z]{2}\\d{6}"),
    PASSPORT("[A-Z]\\d{7}"),
    COMPANY_REG_NO("\\d{8}");

    private final Pattern format;

    IdentifierType(String regex) {
        this.format = Pattern.compile(regex);
    }

    /** Či hodnota zodpovedá formátu typu. */
    public boolean isValid(String value) {
        return value != null && format.matcher(value).matches();
    }
}
