package com.example.crm.common;

/** Záznam neexistuje alebo nepatrí aktuálnemu kontextu. Web vrstva z neho spraví HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what) {
        super("Nenalezeno: " + what);
    }
}
