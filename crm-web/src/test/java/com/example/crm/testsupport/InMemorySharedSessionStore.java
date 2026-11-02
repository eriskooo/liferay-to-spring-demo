package com.example.crm.testsupport;

import com.example.crm.session.SharedSessionStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Sdílená session v pamäti pre testy – rovnaký kontrakt ako Redis hash crm:session:{SSO_ID}. */
public class InMemorySharedSessionStore implements SharedSessionStore {

    private final Map<String, Map<String, String>> data = new ConcurrentHashMap<>();
    private volatile String legacyFlag;
    private volatile boolean failing;

    public Map<String, Map<String, String>> data() {
        return data;
    }

    /** Simuluje výpadok Redisu – každé volanie hodí výnimku. */
    public void failing(boolean failing) {
        this.failing = failing;
    }

    public void legacyFlag(String value) {
        this.legacyFlag = value;
    }

    @Override
    public Optional<String> getJson(String ssoId, String key) {
        check();
        return Optional.ofNullable(data.getOrDefault(ssoId, Map.of()).get(key));
    }

    @Override
    public void putJson(String ssoId, String key, String json) {
        check();
        data.computeIfAbsent(ssoId, id -> new ConcurrentHashMap<>()).put(key, json);
    }

    @Override
    public void remove(String ssoId, String key) {
        check();
        data.getOrDefault(ssoId, new ConcurrentHashMap<>()).remove(key);
    }

    @Override
    public Optional<String> legacyReadSource() {
        check();
        return Optional.ofNullable(legacyFlag);
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("Redis je nedostupný (test)");
        }
    }
}
