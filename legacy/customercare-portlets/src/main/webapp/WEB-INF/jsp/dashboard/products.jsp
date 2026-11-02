<%@ include file="/WEB-INF/jsp/init.jspf" %>
<h2><common:message key="dashboard.title"/> &ndash; <c:out value="${customer.displayName}"/></h2>
<c:choose>
    <c:when test="${empty products}"><p><common:message key="dashboard.products.empty"/></p></c:when>
    <c:otherwise>
        <table id="products" class="table table-striped table-bordered">
            <thead>
            <tr>
                <th><common:message key="dashboard.products.name"/></th>
                <th><common:message key="dashboard.products.type"/></th>
                <th><common:message key="dashboard.products.description"/></th>
                <th></th>
            </tr>
            </thead>
            <tbody>
            <c:forEach items="${products}" var="p">
                <portal:renderURL var="deactivateUrl">
                    <portal:param name="page" value="tariffDeactivation"/>
                    <portal:param name="productId" value="${p.id}"/>
                </portal:renderURL>
                <tr>
                    <td><c:out value="${p.name}"/></td>
                    <td><common:message key="dashboard.type.${p.type}"/></td>
                    <td><c:out value="${p.description}"/></td>
                    <td><a id="deactivate-${p.id}" href="${deactivateUrl}"><common:message key="dashboard.products.deactivate"/></a></td>
                </tr>
            </c:forEach>
            </tbody>
        </table>
    </c:otherwise>
</c:choose>
