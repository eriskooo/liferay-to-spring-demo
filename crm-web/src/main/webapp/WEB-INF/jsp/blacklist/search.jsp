<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.search.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_VIEW" wireframe="blacklist-search"/>
<%-- Telo stránky je 1:1 legacy search.jsp; odlišnosti sú len tam, kde sa zmenil kontrakt controllera (komentované). --%>
<portal:actionURL var="searchUrl" escapeXml="false"><portal:param name="action" value="searchAction"/></portal:actionURL>
<%-- Legacy „Vymazat“ a „Nový záznam“ boli action fázy; po migrácii sú to len navigácie (GET), tlačidlá ostávajú. --%>
<portal:renderURL var="clearUrl"><portal:param name="render" value="newSearch"/></portal:renderURL>
<portal:renderURL var="gotoCreateUrl"><portal:param name="render" value="create"/></portal:renderURL>

<h2><common:message key="blacklist.search.title"/></h2>
<form:form id="searchForm" method="post" action="${searchUrl}" modelAttribute="searchCriteria">
    <form:errors path="*" cssClass="field-error" element="div"/>
    <div>
        <label for="firstName"><common:message key="blacklist.search.firstName"/></label>
        <form:input path="firstName"/>
    </div>
    <div>
        <label for="lastName"><common:message key="blacklist.search.lastName"/></label>
        <form:input path="lastName"/>
    </div>
    <div>
        <label for="identifierValue"><common:message key="blacklist.search.identifier"/></label>
        <form:input path="identifierValue"/>
    </div>
    <div>
        <label for="state"><common:message key="blacklist.search.state"/></label>
        <form:select path="state">
            <form:option value=""><common:message key="blacklist.search.anyState"/></form:option>
            <c:forEach items="${states}" var="s">
                <form:option value="${s}"><common:message key="blacklist.state.${s}"/></form:option>
            </c:forEach>
        </form:select>
    </div>
    <button type="submit" class="btn btn-primary"><common:message key="blacklist.search.submit"/></button>
</form:form>
<form id="clearForm" method="get" action="${clearUrl}" class="crm-inline">
    <button type="submit" class="btn"><common:message key="blacklist.search.clear"/></button>
</form>
<common:aclFilter privilegeType="BLACKLIST_MODIFY">
    <form id="gotoCreateForm" method="get" action="${gotoCreateUrl}" class="crm-inline">
        <button type="submit" class="btn"><common:message key="blacklist.search.create"/></button>
    </form>
</common:aclFilter>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
