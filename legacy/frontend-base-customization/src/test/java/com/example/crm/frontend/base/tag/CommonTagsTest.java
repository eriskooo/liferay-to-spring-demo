package com.example.crm.frontend.base.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.i18n.MessageSourceCustom;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockPageContext;

class CommonTagsTest {

    private final OperatorAcl acl = mock(OperatorAcl.class);
    private final MockPageContext pageContext = TagTestSupport.pageContext(new MessageSourceCustom(), acl);

    @Test
    @DisplayName("Text z messages.properties v UTF-8")
    void should_writeUtf8Text_when_keyExists() throws JspException {
        message("test.search", null, false);

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("Hledat");
    }

    @Test
    @DisplayName("Argumenty z <common:msgarg> a escapovanie HTML v argumente")
    void should_formatAndEscapeArguments_when_msgargPresent() throws JspException {
        MessageTag tag = new MessageTag();
        tag.setPageContext(pageContext);
        tag.setKey("test.count");
        tag.doStartTag();
        MsgArgTag arg = new MsgArgTag();
        arg.setPageContext(pageContext);
        arg.setParent(tag);
        arg.setValue("<b>3</b>");
        arg.doStartTag();
        arg.doEndTag();
        tag.doEndTag();

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("Počet záznamů: &lt;b&gt;3&lt;/b&gt;");
    }

    @Test
    @DisplayName("Chýbajúci kľúč je viditeľný ako ??kľúč?? (PoC ho ticho nahradil)")
    void should_showMissingKeyVisibly_when_keyUnknown() throws JspException {
        message("missing.key", null, false);

        assertThat(TagTestSupport.content(pageContext)).isEqualTo("??missing.key??");
    }

    @Test
    @DisplayName("var uloží text do page scope a nič nevypíše; includeKey pridá kľúč")
    void should_storeIntoVarAndAppendKey_when_requested() throws JspException {
        message("test.search", "label", true);

        assertThat(pageContext.getAttribute("label")).isEqualTo("Hledat [test.search]");
        assertThat(TagTestSupport.content(pageContext)).isEmpty();
    }

    @Test
    @DisplayName("msgarg mimo <common:message> je chyba v JSP")
    void should_fail_when_msgargOutsideMessage() {
        MsgArgTag arg = new MsgArgTag();
        arg.setPageContext(pageContext);
        arg.setValue("x");

        assertThatThrownBy(arg::doStartTag).isInstanceOf(JspException.class).hasMessageContaining("common:message");
    }

    @Test
    @DisplayName("aclFilter zobrazí telo len s privilégiom")
    void should_includeBodyOnlyWithPrivilege_when_aclFilter() throws JspException {
        when(acl.hasPrivilege("BLACKLIST_MODIFY")).thenReturn(true);

        assertThat(aclFilter("BLACKLIST_MODIFY").doStartTag()).isEqualTo(Tag.EVAL_BODY_INCLUDE);
        assertThat(aclFilter("DEACTIVATION").doStartTag()).isEqualTo(Tag.SKIP_BODY);
    }

    @Test
    @DisplayName("Preklep v mene privilégia v JSP je JspException, nie tiché skrytie")
    void should_fail_when_privilegeNameUnknown() {
        when(acl.hasPrivilege("BLACKLIST_MODIFI")).thenThrow(new IllegalArgumentException("Neznáme privilégium"));

        assertThatThrownBy(() -> aclFilter("BLACKLIST_MODIFI").doStartTag()).isInstanceOf(JspException.class);
    }

    @Test
    @DisplayName("aclPageFilterTag bez privilégia vypíše 403 a zastaví zvyšok stránky")
    void should_stopPage_when_pageFilterDenied() throws JspException {
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType("DEACTIVATION");
        tag.setWireframe("true");

        tag.doStartTag();

        assertThat(tag.doEndTag()).isEqualTo(Tag.SKIP_PAGE);
        assertThat(TagTestSupport.content(pageContext)).contains("Nemáte oprávnění DEACTIVATION.");
    }

    @Test
    @DisplayName("aclPageFilterTag s privilégiom nič nevypíše a stránka pokračuje")
    void should_continuePage_when_pageFilterAllowed() throws JspException {
        when(acl.hasPrivilege("DEACTIVATION")).thenReturn(true);
        AclPageFilterTag tag = new AclPageFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType("DEACTIVATION");

        tag.doStartTag();

        assertThat(tag.doEndTag()).isEqualTo(Tag.EVAL_PAGE);
        assertThat(TagTestSupport.content(pageContext)).isEmpty();
    }

    private void message(String key, String var, boolean includeKey) throws JspException {
        MessageTag tag = new MessageTag();
        tag.setPageContext(pageContext);
        tag.setKey(key);
        tag.setVar(var);
        tag.setIncludeKey(includeKey);
        tag.doStartTag();
        tag.doEndTag();
    }

    private AclFilterTag aclFilter(String privilege) {
        AclFilterTag tag = new AclFilterTag();
        tag.setPageContext(pageContext);
        tag.setPrivilegeType(privilege);
        return tag;
    }
}
