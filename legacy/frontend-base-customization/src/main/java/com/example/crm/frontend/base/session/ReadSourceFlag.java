package com.example.crm.frontend.base.session;

import java.util.Locale;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Feature flag „zdroj čtení“ zo slide 12: kľúč v Redise, prepína sa bez reštartu a bez deployu
 * ({@code redis-cli SET crm:flags:session-read-source REDIS}). Ten istý kľúč bude čítať aj nová appka.
 * Pri akejkoľvek pochybnosti platí PORTLET – stará session ostáva zdrojom pravdy, kým sa Redis neoverí.
 */
public class ReadSourceFlag {

    static final String FLAG_KEY = "crm:flags:session-read-source";
    static final String CACHE_ATTRIBUTE = ReadSourceFlag.class.getName();

    private static final Log LOG = LogFactory.getLog(ReadSourceFlag.class);

    private final JedisPool pool;
    private final RequestAwareManager requests;

    public ReadSourceFlag(JedisPool pool, RequestAwareManager requests) {
        this.pool = pool;
        this.requests = requests;
    }

    /** Aktuálny zdroj čtení (v rámci requestu sa číta z Redisu raz). */
    public ReadSource current() {
        Object cached = requests.getRequestAttribute(CACHE_ATTRIBUTE);
        if (cached instanceof ReadSource) {
            return (ReadSource) cached;
        }
        ReadSource source = load();
        requests.setRequestAttribute(CACHE_ATTRIBUTE, source);
        return source;
    }

    private ReadSource load() {
        String value;
        try (Jedis jedis = pool.getResource()) {
            value = jedis.get(FLAG_KEY);
        } catch (RuntimeException e) {
            LOG.error("Flag zdroja čítania sa nedá prečítať z Redisu, číta sa zo starej session", e);
            return ReadSource.PORTLET;
        }
        if (value == null) {
            return ReadSource.PORTLET;
        }
        try {
            return ReadSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOG.warn("Neznáma hodnota flagu " + FLAG_KEY + "='" + value + "', použije sa PORTLET");
            return ReadSource.PORTLET;
        }
    }
}
