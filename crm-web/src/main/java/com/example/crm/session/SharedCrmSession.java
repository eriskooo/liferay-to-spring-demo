package com.example.crm.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JavaType;

/**
 * Fasáda pre jeden request. Nová appka nemá „starú“ session, zdieľané kľúče preto číta vždy z Redisu
 * (flag staré aplikace ju netýka). Výpadok Redisu request nezhodí – zaloguje sa a fasáda sa správa ako prázdna.
 */
final class SharedCrmSession implements CrmSession {

    private static final Logger LOG = LoggerFactory.getLogger(SharedCrmSession.class);

    private final HttpServletRequest request;
    private final IdentityResolver identities;
    private final SharedSessionStore store;
    private final SharedSessionKeys keys;

    SharedCrmSession(HttpServletRequest request, IdentityResolver identities, SharedSessionStore store,
                     SharedSessionKeys keys) {
        this.request = request;
        this.identities = identities;
        this.store = store;
        this.keys = keys;
    }

    @Override
    public Optional<OperatorUser> getOperator() {
        return identities.resolve(request).map(SsoIdentity::operator);
    }

    @Override
    public Optional<CustomerContext> getCustomer() {
        return identities.resolve(request).map(SsoIdentity::customer);
    }

    @Override
    public void setGlobalAttribute(String key, Serializable value) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        if (keys.typeOf(fullKey).isPresent()) {
            writeShared(fullKey, value);
        } else {
            request.getSession(true).setAttribute(fullKey, value);
        }
    }

    @Override
    public <T> Optional<T> getGlobalAttribute(String key, Class<T> type) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        Object value = keys.typeOf(fullKey).isPresent() ? readShared(fullKey).orElse(null) : readLocal(fullKey);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public void removeGlobalAttribute(String key) {
        String fullKey = SharedSessionKeys.PREFIX + key;
        if (keys.typeOf(fullKey).isPresent()) {
            ssoId().ifPresent(id -> guard(() -> store.remove(id, fullKey)));
        } else {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.removeAttribute(fullKey);
            }
        }
    }

    @Override
    public void pushInfo(String messageKey) {
        push(new UiMessage(UiMessage.Level.INFO, messageKey));
    }

    @Override
    public void pushError(String messageKey) {
        push(new UiMessage(UiMessage.Level.ERROR, messageKey));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<UiMessage> popMessages() {
        List<UiMessage> messages = (List<UiMessage>) readShared(SharedSessionKeys.MESSAGES).orElse(List.of());
        if (!messages.isEmpty()) {
            ssoId().ifPresent(id -> guard(() -> store.remove(id, SharedSessionKeys.MESSAGES)));
        }
        return List.copyOf(messages);
    }

    @SuppressWarnings("unchecked")
    private void push(UiMessage message) {
        List<UiMessage> messages =
                new ArrayList<>((List<UiMessage>) readShared(SharedSessionKeys.MESSAGES).orElse(List.of()));
        messages.add(message);
        // Vždy celý nový zoznam – rovnako ako stará appka (žiadna úprava „na mieste“).
        writeShared(SharedSessionKeys.MESSAGES, messages);
    }

    private Optional<Object> readShared(String fullKey) {
        Optional<String> id = ssoId();
        JavaType type = keys.typeOf(fullKey).orElseThrow();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        try {
            return store.getJson(id.get(), fullKey).map(json -> keys.fromJson(json, type));
        } catch (RuntimeException e) {
            LOG.error("Čítanie {} zo zdieľanej session zlyhalo", fullKey, e);
            return Optional.empty();
        }
    }

    private void writeShared(String fullKey, Object value) {
        ssoId().ifPresent(id -> guard(() -> store.putJson(id, fullKey, keys.toJson(value))));
    }

    private Object readLocal(String fullKey) {
        HttpSession session = request.getSession(false);
        return session == null ? null : session.getAttribute(fullKey);
    }

    private Optional<String> ssoId() {
        return identities.resolve(request).map(SsoIdentity::ssoSessionId);
    }

    private static void guard(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.error("Zápis do zdieľanej session zlyhal", e);
        }
    }
}
