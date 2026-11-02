package com.example.crm.web.tag;

import jakarta.servlet.jsp.JspException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortletUrlTagTest {

    private final TagTestSupport page = new TagTestSupport();

    @Test
    void actionUrlUsesActionParamAsPathAndDropsViewPage() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");

        render(tag, "viewPage", "search", "action", "searchAction", "x", "1");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/searchAction?x=1");
    }

    @Test
    void actionUrlFallsBackToNameAttribute() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");
        tag.setName("createAction");

        render(tag);

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/createAction");
    }

    @Test
    void renderUrlPrefersRenderOverViewPage() throws Exception {
        RenderUrlTag tag = new RenderUrlTag();
        tag.setVar("url");

        render(tag, "viewPage", "detail", "render", "detail", "detailId", "5");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/detail?detailId=5");
    }

    @Test
    void resourceUrlUsesIdUnderResourcePath() throws Exception {
        ResourceUrlTag tag = new ResourceUrlTag();
        tag.setVar("url");
        tag.setId("removeIdentAction");

        render(tag, "detailId", "1", "identId", "2");

        assertThat(page.pageContext.getAttribute("url"))
                .isEqualTo("/app/blacklist/resource/removeIdentAction?detailId=1&identId=2");
    }

    @Test
    void queryValuesAreUtf8Encoded() throws Exception {
        ActionUrlTag tag = new ActionUrlTag();
        tag.setVar("url");

        render(tag, "action", "a", "q", "Jan Novák&co");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/a?q=Jan+Nov%C3%A1k%26co");
    }

    @Test
    void writesEscapedUrlWhenNoVar() throws Exception {
        render(new ActionUrlTag(), "action", "a", "p1", "1", "p2", "2");

        assertThat(page.output()).isEqualTo("/app/blacklist/a?p1=1&amp;p2=2");
    }

    @Test
    void missingSegmentFailsLoudly() {
        assertThatThrownBy(() -> render(new RenderUrlTag(), "detailId", "5"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("render");
    }

    @Test
    void unsafeSegmentIsRejected() {
        assertThatThrownBy(() -> render(new ActionUrlTag(), "action", "../admin"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("../admin");
    }

    @Test
    void missingBasePathFailsLoudly() {
        page.request.removeAttribute("crm.portlet.basePath");

        assertThatThrownBy(() -> render(new ActionUrlTag(), "action", "a"))
                .isInstanceOf(JspException.class)
                .hasMessageContaining("@LegacyPortlet");
    }

    @Test
    void paramOutsideUrlTagFails() {
        UrlParamTag param = new UrlParamTag();
        param.setPageContext(page.pageContext);
        param.setName("a");
        param.setValue("b");

        assertThatThrownBy(param::doStartTag).isInstanceOf(JspException.class);
    }

    @Test
    void reusedTagInstanceDoesNotLeakParameters() throws Exception {
        // Jasper tag handlery poolinguje a používa opakovane (napr. v c:forEach).
        RenderUrlTag tag = new RenderUrlTag();
        tag.setVar("url");
        render(tag, "render", "detail", "detailId", "1");
        render(tag, "render", "detail", "detailId", "2");

        assertThat(page.pageContext.getAttribute("url")).isEqualTo("/app/blacklist/detail?detailId=2");
    }

    private void render(PortletUrlTag tag, String... nameValuePairs) throws JspException {
        tag.setPageContext(page.pageContext);
        tag.doStartTag();
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            UrlParamTag param = new UrlParamTag();
            param.setPageContext(page.pageContext);
            param.setParent(tag);
            param.setName(nameValuePairs[i]);
            param.setValue(nameValuePairs[i + 1]);
            param.doStartTag();
            param.doEndTag();
        }
        tag.doEndTag();
    }
}
