<%@ include file="/WEB-INF/jsp/layout/taglibs.jspf" %>
<c:set var="titleKey" value="blacklist.create.title"/>
<%@ include file="/WEB-INF/jsp/layout/header.jspf" %>
<common:aclPageFilterTag privilegeType="BLACKLIST_MODIFY" wireframe="blacklist-create"/>
<%-- Telo stránky je 1:1 legacy create.jsp. Rozdiely: „Zrušit“ je navigácia (GET, legacy action gotoSearchAction),
     AJAX obnovu podmienok robí crm.js cez data-ajax-* (legacy onchange="Crm.refreshConditionTypes(this)")
     a krížové pravidlá formulára (@AssertTrue) majú vlastné form:errors. --%>
<portal:actionURL var="createUrl" escapeXml="false">
    <portal:param name="viewPage" value="create"/>
    <portal:param name="action" value="createAction"/>
</portal:actionURL>
<portal:renderURL var="cancelUrl"><portal:param name="render" value="search"/></portal:renderURL>
<portal:resourceURL id="refreshCreateConditionTypes" var="conditionTypesUrl" escapeXml="false"/>

<h2><common:message key="blacklist.create.title"/></h2>
<div id="crm-ajax-error"></div>
<form:form id="createForm" method="post" action="${createUrl}" modelAttribute="createForm">
    <div>
        <label for="firstName"><common:message key="blacklist.search.firstName"/></label>
        <form:input path="firstName"/> <form:errors path="firstName" cssClass="field-error"/>
    </div>
    <div>
        <label for="lastName"><common:message key="blacklist.search.lastName"/></label>
        <form:input path="lastName"/> <form:errors path="lastName" cssClass="field-error"/>
    </div>
    <div>
        <label for="birthDate"><common:message key="blacklist.create.birthDate"/></label>
        <form:input path="birthDate" type="date"/> <form:errors path="birthDate" cssClass="field-error"/>
    </div>
    <div>
        <label for="stateReason"><common:message key="blacklist.create.reason"/></label>
        <form:select path="stateReason" data-ajax-refresh="${conditionTypesUrl}" data-ajax-target="#conditionType">
            <form:option value=""><common:message key="blacklist.create.choose"/></form:option>
            <c:forEach items="${stateReasons}" var="r">
                <form:option value="${r}"><common:message key="blacklist.reason.${r}"/></form:option>
            </c:forEach>
        </form:select>
        <form:errors path="stateReason" cssClass="field-error"/>
    </div>
    <div>
        <label for="conditionType"><common:message key="blacklist.create.condition"/></label>
        <span id="conditionTypeBox"><jsp:include page="/WEB-INF/jsp/blacklist/fragments/conditionTypes.jsp"/></span>
        <form:errors path="conditionType" cssClass="field-error"/>
    </div>
    <div>
        <label for="validFrom"><common:message key="blacklist.detail.validFrom"/></label>
        <form:input path="validFrom" type="date"/> <form:errors path="validFrom" cssClass="field-error"/>
    </div>
    <div>
        <label for="validTo"><common:message key="blacklist.detail.validTo"/></label>
        <form:input path="validTo" type="date"/> <form:errors path="validTo" cssClass="field-error"/>
        <form:errors path="periodValid" cssClass="field-error"/>
    </div>
    <div>
        <label for="identifierType"><common:message key="blacklist.create.identifierType"/></label>
        <form:select path="identifierType">
            <form:option value=""><common:message key="blacklist.create.choose"/></form:option>
            <c:forEach items="${identifierTypes}" var="t">
                <form:option value="${t}"><common:message key="blacklist.identifier.${t}"/></form:option>
            </c:forEach>
        </form:select>
        <form:errors path="identifierType" cssClass="field-error"/>
    </div>
    <div>
        <label for="identifierValue"><common:message key="blacklist.create.identifierValue"/></label>
        <form:input path="identifierValue"/> <form:errors path="identifierValue" cssClass="field-error"/>
        <form:errors path="identifierValid" cssClass="field-error"/>
    </div>
    <button type="submit" class="btn btn-primary"><common:message key="blacklist.create.submit"/></button>
</form:form>
<form id="cancelForm" method="get" action="${cancelUrl}" class="crm-inline">
    <button type="submit" class="btn"><common:message key="blacklist.create.cancel"/></button>
</form>
<%@ include file="/WEB-INF/jsp/layout/footer.jspf" %>
