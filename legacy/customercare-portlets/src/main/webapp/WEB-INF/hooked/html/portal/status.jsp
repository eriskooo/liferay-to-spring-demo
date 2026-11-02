<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%-- Hook custom-jsp-dir: portálová stránka chýb (neexistujúca stránka, chýbajúce oprávnenie). Po migrácii ju
     nahradí chybová stránka Spring Boot – portál už nebude. --%>
<div class="crm-portal-error" id="crmPortalError">
    <h2>Stránka není dostupná</h2>
    <p>Požadovaná stránka neexistuje nebo k ní nemáte přístup.</p>
    <p><a href="/web/crm/blacklist">Zpět do CRM</a></p>
</div>
