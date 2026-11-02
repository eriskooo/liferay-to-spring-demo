package com.example.crm.session;

import java.util.Optional;

/** Sdílená session oboch appiek (Redis hash crm:session:{SSO_ID}); hodnoty sú JSON reťazce. */
public interface SharedSessionStore {

    Optional<String> getJson(String ssoId, String key);

    void putJson(String ssoId, String key, String json);

    void remove(String ssoId, String key);

    /** Flag zdroja čítania starej appky (crm:flags:session-read-source) – nová appka ho len zobrazuje. */
    Optional<String> legacyReadSource();
}
