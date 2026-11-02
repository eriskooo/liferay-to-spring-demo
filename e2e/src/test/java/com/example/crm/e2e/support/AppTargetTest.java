package com.example.crm.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AppTargetTest {

    @Test
    @DisplayName("Legacy ide na Liferay priamo, migrated cez nginx; každý má svoj log")
    void should_resolveEntryAndLog_when_targetSelected() {
        AppTarget legacy = AppTarget.of(config("legacy"));
        AppTarget migrated = AppTarget.of(config("migrated"));

        assertThat(legacy.entryUrl()).isEqualTo("http://localhost:8080/web/crm/blacklist");
        assertThat(legacy.appLog()).isEqualTo(Path.of("logs", "liferay", "customercare-portlet.log"));
        assertThat(migrated.entryUrl()).isEqualTo("http://localhost:8000/crm/blacklist/search");
        assertThat(migrated.appLog()).isEqualTo(Path.of("logs", "crm-web", "crm-web.log"));
    }

    @Test
    @DisplayName("Neznámy cieľ je chyba s nápovedou")
    void should_fail_when_targetUnknown() {
        assertThatThrownBy(() -> AppTarget.of(config("unknown"))).hasMessageContaining("legacy|migrated");
    }

    private static E2eConfig config(String target) {
        return new E2eConfig(target, "http://localhost:8000/sso/login", "http://localhost:8080/web/crm/blacklist",
                "http://localhost:8000/crm/blacklist/search", "jdbc:postgresql://localhost:5433/crm", "postgres",
                "postgres", Path.of("logs"), "chrome", false, Path.of("seed.sql"));
    }
}
