package com.example.crm.blacklist;

import com.example.crm.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Mení len záznamy 1 (ident. 2), 5 a 6 – viď tabuľka v Tasku 6. */
@SpringBootTest
@AutoConfigureMockMvc
class BlacklistDetailWebTest {

    private static final String AJAX = "X-Requested-With";

    @Autowired
    MockMvc mvc;

    @Test
    @org.junit.jupiter.api.DisplayName("Detail zapíše posledný záznam do zdieľanej session")
    void should_shareLastViewed_when_detailOpened() throws Exception {
        org.springframework.mock.web.MockHttpSession session = TestSessions.reader();
        String ssoId = ((com.example.crm.session.SsoIdentity) session.getAttribute(
                com.example.crm.testsupport.TestInfrastructure.TEST_IDENTITY)).ssoSessionId();

        mvc.perform(get("/blacklist/detail").param("detailId", "2").session(session)).andExpect(status().isOk());

        assertThat(com.example.crm.testsupport.TestInfrastructure.STORE.data().get(ssoId))
                .containsEntry("APP_LAST_VIEWED_RECORD", "{\"id\":2,\"fullName\":\"Věra Příkladová\"}");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Odstránenie identifikátora cez GET je 405 (mutácia len cez POST)")
    void should_reject405_when_removeIdentifierViaGet() throws Exception {
        mvc.perform(get("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "2")
                        .session(TestSessions.admin()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Autowired
    BlacklistService service;

    @Test
    void detailShowsRecordAndForms() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "2").session(TestSessions.reader()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/detail"))
                .andExpect(model().attributeExists("record", "identifiers", "changeForm", "noteForm", "states"))
                .andExpect(model().attribute("detailId", 2L));
    }

    @Test
    void unknownDetailIs404() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "999").session(TestSessions.reader()))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonNumericDetailIdIs400() throws Exception {
        mvc.perform(get("/blacklist/detail").param("detailId", "abc").session(TestSessions.reader()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readerCannotChange() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").param("state", "INACTIVE")
                        .session(TestSessions.reader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDeactivatesWithRedirectBackToDetail() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").param("state", "INACTIVE")
                        .param("validTo", "").session(TestSessions.admin()))
                .andExpect(redirectedUrl("/blacklist/detail?detailId=5"));

        assertThat(service.findById(5).orElseThrow().getState()).isEqualTo(BlacklistState.INACTIVE);
        assertThat(service.findById(5).orElseThrow().getValidTo()).isNotNull();
    }

    @Test
    void changeWithoutStateRerendersDetailWithError() throws Exception {
        mvc.perform(post("/blacklist/changeAction").param("detailId", "5").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/detail"))
                .andExpect(model().attributeHasFieldErrors("changeForm", "state"));
    }

    @Test
    void addNoteStoresAuthorFromSession() throws Exception {
        mvc.perform(post("/blacklist/addNoteAction").param("detailId", "6").param("text", "Overené telefonicky.")
                        .session(TestSessions.admin()))
                .andExpect(redirectedUrl("/blacklist/detail?detailId=6"));

        assertThat(service.findById(6).orElseThrow().getNotes())
                .anySatisfy(note -> {
                    assertThat(note.text()).isEqualTo("Overené telefonicky.");
                    assertThat(note.author()).isEqualTo("Správce");
                });
    }

    @Test
    void blankNoteIsRejected() throws Exception {
        mvc.perform(post("/blacklist/addNoteAction").param("detailId", "6").param("text", "   ")
                        .session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("noteForm", "text"));
    }

    @Test
    void ajaxRemoveIdentifierReturnsFragment() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "2")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("blacklist/fragments/identifiers"))
                .andExpect(model().attribute("identifiers", hasSize(1)));
    }

    @Test
    void removingLastIdentifierShowsErrorInFragment() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "4").param("identId", "5")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("errorKey", "blacklist.detail.lastIdentifier"))
                .andExpect(model().attribute("identifiers", hasSize(1)));
    }

    @Test
    void removeForeignIdentifierIs404() throws Exception {
        mvc.perform(post("/blacklist/resource/removeIdentAction").param("detailId", "1").param("identId", "3")
                        .header(AJAX, "XMLHttpRequest").session(TestSessions.admin()))
                .andExpect(status().isNotFound());
        assertThat(service.findById(2).orElseThrow().getIdentifiers()).hasSize(1);
    }
}
