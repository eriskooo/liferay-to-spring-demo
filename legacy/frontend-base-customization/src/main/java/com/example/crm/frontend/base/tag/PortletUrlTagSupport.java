package com.example.crm.frontend.base.tag;

import com.liferay.portal.kernel.portlet.LiferayPortletURL;
import com.liferay.portal.kernel.util.JavaConstants;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.portlet.BaseURL;
import javax.portlet.MimeResponse;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.TagSupport;
import org.springframework.web.util.HtmlUtils;

/**
 * Spoločný základ {@code portal:*URL}. Toto je skrytá Liferay závislosť z analýzy: JSP vyzerajú „čisto“,
 * ale tag pretypuje URL na {@link LiferayPortletURL} (kvôli rozšíreniam mimo Portlet API). Pri migrácii sa tag
 * reimplementuje s rovnakým rozhraním nad servletovými URL – JSP ostanú bez zmeny.
 */
public abstract class PortletUrlTagSupport extends TagSupport {

    private static final long serialVersionUID = 1L;

    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private String var;
    private boolean escapeXml = true;
    private boolean copyCurrentRenderParameters;

    public void setVar(String var) {
        this.var = var;
    }

    public void setEscapeXml(boolean escapeXml) {
        this.escapeXml = escapeXml;
    }

    public void setCopyCurrentRenderParameters(boolean copyCurrentRenderParameters) {
        this.copyCurrentRenderParameters = copyCurrentRenderParameters;
    }

    void addParameter(String name, String value) {
        List<String> values = parameters.get(name);
        if (values == null) {
            values = new ArrayList<>();
            parameters.put(name, values);
        }
        values.add(value);
    }

    @Override
    public int doStartTag() {
        parameters.clear();
        return EVAL_BODY_INCLUDE;
    }

    @Override
    public int doEndTag() throws JspException {
        Object response = pageContext.getRequest().getAttribute(JavaConstants.JAVAX_PORTLET_RESPONSE);
        if (!(response instanceof MimeResponse)) {
            throw new JspException("<" + tagName() + "> sa dá použiť len v JSP portletu (chýba javax.portlet.response)");
        }
        BaseURL url = createUrl((MimeResponse) response);
        if (!(url instanceof LiferayPortletURL)) {
            throw new JspException("<" + tagName() + "> vyžaduje Liferay (LiferayPortletURL), portál vrátil "
                    + url.getClass().getName());
        }
        ((LiferayPortletURL) url).setCopyCurrentRenderParameters(copyCurrentRenderParameters);
        for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
            url.setParameter(parameter.getKey(), parameter.getValue().toArray(new String[0]));
        }
        String value = escapeXml ? HtmlUtils.htmlEscape(url.toString(), "UTF-8") : url.toString();
        if (var != null) {
            pageContext.setAttribute(var, value);
            return EVAL_PAGE;
        }
        try {
            pageContext.getOut().write(value);
        } catch (IOException e) {
            throw new JspException(e);
        }
        return EVAL_PAGE;
    }

    /** Vytvorí URL danej fázy portletu. */
    protected abstract BaseURL createUrl(MimeResponse response);

    /** Jméno tagu do chybových hlášok. */
    protected abstract String tagName();
}
