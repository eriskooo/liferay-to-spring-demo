package com.example.crm.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/** Skutočný Redis (Testcontainers) – rovnaké príkazy, aké vidí stará appka cez Jedis. */
class RedisSharedSessionStoreTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisSharedSessionStore store;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisSharedSessionStore(redis);
    }

    @AfterAll
    static void stopRedis() {
        factory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("Zápis ide do hashu crm:session:{SSO_ID} s TTL 30 minút")
    void should_writeHashWithTtl_when_put() {
        store.putJson("s-1", "APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Petr Vzorka\"}");

        assertThat(redis.opsForHash().get("crm:session:s-1", "APP_LAST_VIEWED_RECORD"))
                .isEqualTo("{\"id\":1,\"fullName\":\"Petr Vzorka\"}");
        assertThat(redis.getExpire("crm:session:s-1")).isBetween(1700L, 1800L);
        assertThat(store.getJson("s-1", "APP_LAST_VIEWED_RECORD")).isPresent();
    }

    @Test
    @DisplayName("Odstránenie poľa a flag staré aplikace")
    void should_removeFieldAndReadLegacyFlag_when_asked() {
        store.putJson("s-2", "APP_MESSAGES", "[]");
        store.remove("s-2", "APP_MESSAGES");
        redis.opsForValue().set("crm:flags:session-read-source", "REDIS");

        assertThat(store.getJson("s-2", "APP_MESSAGES")).isEmpty();
        assertThat(store.legacyReadSource()).contains("REDIS");
    }
}
