<%@ include file="/WEB-INF/jsp/init.jspf" %>
<portal:renderURL var="newSearchUrl"><portal:param name="render" value="search"/></portal:renderURL>

<h2><common:message key="blacklist.result.title"/></h2>
<p id="resultCount"><common:message key="blacklist.result.count"><common:msgarg value="${fn:length(records)}"/></common:message></p>
<table class="table table-striped table-bordered">
    <thead>
    <tr>
        <th><common:message key="blacklist.result.name"/></th>
        <th><common:message key="blacklist.result.birthDate"/></th>
        <th><common:message key="blacklist.result.state"/></th>
        <th><common:message key="blacklist.result.validity"/></th>
    </tr>
    </thead>
    <tbody>
    <c:forEach items="${records}" var="r">
        <portal:renderURL var="detailUrl">
            <portal:param name="viewPage" value="detail"/>
            <portal:param name="detailId" value="${r.id}"/>
        </portal:renderURL>
        <tr>
            <td><a id="detail-${r.id}" href="${detailUrl}"><c:out value="${r.fullName}"/></a></td>
            <td>${r.birthDate}</td>
            <td><common:message key="blacklist.state.${r.state}"/></td>
            <td>${r.validFrom} &ndash;
                <c:choose>
                    <c:when test="${empty r.validTo}"><common:message key="blacklist.detail.unlimited"/></c:when>
                    <c:otherwise>${r.validTo}</c:otherwise>
                </c:choose>
            </td>
        </tr>
    </c:forEach>
    </tbody>
</table>
<a href="${newSearchUrl}"><common:message key="blacklist.result.newSearch"/></a>
