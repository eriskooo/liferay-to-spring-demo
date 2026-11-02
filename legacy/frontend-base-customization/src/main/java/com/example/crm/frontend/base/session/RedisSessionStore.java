package com.example.crm.frontend.base.session;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Sdílená session v Redise: hash {@code crm:session:{SSO_ID}}, hodnoty v JSON. Kľúčom je ID session SSO portálu –
 * cookie {@code SSO_ID} majú obe appky, takže netreba vlastnú cookie (a portlet ju ani nevie nastaviť v render fáze).
 */
public class RedisSessionStore implements SessionStore {

    static final String KEY_PREFIX = "crm:session:";
    static final int TTL_SECONDS = 1800;

    private static final Log LOG = LogFactory.getLog(RedisSessionStore.class);

    private final JedisPool pool;
    private final RequestAwareManager requests;
    private final SharedKeys keys;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public RedisSessionStore(JedisPool pool, RequestAwareManager requests, SharedKeys keys) {
        this.pool = pool;
        this.requests = requests;
        this.keys = keys;
    }

    @Override
    public Object get(String key) {
        String hash = hash();
        JavaType type = keys.typeOf(key);
        if (hash == null || type == null) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            String json = jedis.hget(hash, key);
            jedis.expire(hash, TTL_SECONDS);
            return json == null ? null : mapper.readValue(json, type);
        } catch (IOException e) {
            throw new IllegalStateException("Neplatný JSON v zdieľanej session pre kľúč " + key, e);
        }
    }

    @Override
    public void put(String key, Object value) {
        String hash = hash();
        if (hash == null) {
            return;
        }
        if (!keys.isShared(key)) {
            LOG.debug("Kľúč " + key + " sa nezdieľa, do Redisu sa nezapisuje");
            return;
        }
        if (value == null) {
            remove(key);
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hset(hash, key, mapper.writeValueAsString(value));
            jedis.expire(hash, TTL_SECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("Hodnotu kľúča " + key + " nejde zapísať ako JSON", e);
        }
    }

    @Override
    public void remove(String key) {
        String hash = hash();
        if (hash == null || !keys.isShared(key)) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.hdel(hash, key);
        }
    }

    @Override
    public void clear() {
        String hash = hash();
        if (hash == null) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(hash);
        }
    }

    private String hash() {
        String ssoId = requests.getSsoSessionId();
        return ssoId == null ? null : KEY_PREFIX + ssoId;
    }
}
