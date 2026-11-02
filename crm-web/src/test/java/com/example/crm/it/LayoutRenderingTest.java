package com.example.crm.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Reálny Tomcat + Jasper: chybová stránka s layoutom sa skompiluje a texty sú preložené. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LayoutRenderingTest {

    @LocalServerPort
    int port;

    @Test
    @DisplayName("Neznáma cesta vykreslí vlastnú chybovú stránku s layoutom a navigáciou do Liferay")
    void should_renderCustomErrorPage_when_pathUnknown() throws Exception {
        HttpResponse<String> response = new WebClient(port).get("/crm/neexistuje", "Accept", "text/html");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Nenalezeno").contains("/web/crm/dashboard").contains("/crm/css/crm.css");
        WebClient.assertNoMissingMessages(response.body());
    }
}
