package com.example.crm.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.crm.TestSessions;
import com.example.crm.acl.Privilege;
import com.example.crm.session.LastViewedRecord;
import com.example.crm.session.UiMessage;
import com.example.crm.testsupport.TestInfrastructure;
import com.example.testsupport.ProbeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ProbeController.class)
class WebInfrastructureTest {

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("Bez SSO identity presmeruje na SSO login s návratom na pôvodnú cestu")
    void should_redirectToSsoLogin_when_portalSessionEnded() throws Exception {
        mvc.perform(get("/probe/open").queryParam("x", "1"))
                .andExpect(redirectedUrl("/sso/login?returnUrl=%2Fprobe%2Fopen%3Fx%3D1"));
    }

    @Test
    @DisplayName("AJAX bez identity dostane 401 bez presmerovania")
    void should_return401_when_ajaxWithoutIdentity() throws Exception {
        mvc.perform(get("/probe/open").header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Interceptor zverejní base path portletu pre URL tagy")
    void should_exposeBasePath_when_loggedIn() throws Exception {
        mvc.perform(get("/probe/open").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(content().string("/probe"));
    }

    @Test
    @DisplayName("Chýbajúce privilégium vykreslí 403 (AJAX bez layoutu)")
    void should_render403_when_privilegeMissing() throws Exception {
        mvc.perform(get("/probe/modify").session(TestSessions.reader()))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403.jsp"))
                .andExpect(model().attribute("privilege", Privilege.BLACKLIST_MODIFY));
        mvc.perform(get("/probe/modify").session(TestSessions.reader()).header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/WEB-INF/jsp/error/403-fragment.jsp"));
        mvc.perform(get("/probe/modify").session(TestSessions.admin())).andExpect(content().string("ok"));
    }

    @Test
    @DisplayName("Hláška z POST sa po redirecte zobrazí v layoute raz")
    void should_showMessageOnce_when_redirected() throws Exception {
        MockHttpSession session = TestSessions.admin();

        mvc.perform(post("/probe/notify").session(session)).andExpect(redirectedUrl("/probe/page"));
        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutMessages",
                        contains(new UiMessage(UiMessage.Level.INFO, "blacklist.detail.changed"))));
        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutMessages", empty()));
    }

    @Test
    @DisplayName("Layout dostane posledný záznam zo zdieľanej session a ladiace údaje")
    void should_exposeSharedSessionInLayout_when_pageRendered() throws Exception {
        MockHttpSession session = TestSessions.admin();
        String ssoId = ((com.example.crm.session.SsoIdentity) session.getAttribute(TestInfrastructure.TEST_IDENTITY))
                .ssoSessionId();
        TestInfrastructure.STORE.putJson(ssoId, "APP_LAST_VIEWED_RECORD", "{\"id\":1,\"fullName\":\"Petr Vzorka\"}");

        mvc.perform(get("/probe/page").session(session))
                .andExpect(model().attribute("layoutLastViewed", new LastViewedRecord(1, "Petr Vzorka")))
                .andExpect(model().attribute("layoutSsoId", ssoId))
                .andExpect(model().attribute("layoutLegacyFlag", "PORTLET"));
    }

    @Test
    @DisplayName("Koreň a /blacklist presmerujú na vyhľadávanie")
    void should_redirectToSearch_when_homeOpened() throws Exception {
        mvc.perform(get("/")).andExpect(redirectedUrl("/blacklist/search"));
        mvc.perform(get("/blacklist")).andExpect(redirectedUrl("/blacklist/search"));
    }

    @Test
    @DisplayName("POST bez identity: po prihlásení návrat na stránku portletu, nie na POST URL (tá by dala 405)")
    void should_returnToPortletPage_when_postWithoutIdentity() throws Exception {
        mvc.perform(post("/probe/notify")).andExpect(redirectedUrl("/sso/login?returnUrl=%2Fprobe"));
    }

    @Test
    @DisplayName("Chybová stránka (403, 404) má v hlavičke prihláseného operátora")
    void should_showOperatorInLayout_when_errorPageRendered() throws Exception {
        mvc.perform(get("/probe/modify").session(TestSessions.reader()))
                .andExpect(status().isForbidden())
                .andExpect(model().attribute("layoutOperator", com.example.crm.testsupport.DemoIdentities.READER));
        mvc.perform(get("/blacklist/detail").param("detailId", "999").session(TestSessions.reader()))
                .andExpect(status().isNotFound())
                .andExpect(model().attribute("layoutOperator", com.example.crm.testsupport.DemoIdentities.READER));
    }
}
