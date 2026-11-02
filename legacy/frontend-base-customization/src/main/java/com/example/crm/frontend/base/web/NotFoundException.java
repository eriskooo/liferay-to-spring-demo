package com.example.crm.frontend.base.web;

/** Neexistujúci záznam alebo záznam mimo kontextu zákazníka (podvrhnuté ID) – stránka 404, nie zmena dát. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String what) {
        super("Nenalezeno: " + what);
    }
}
