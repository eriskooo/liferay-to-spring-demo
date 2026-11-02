package com.example.crm.frontend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.crm.frontend.backend.deactivation.CustomerProduct;
import com.example.crm.frontend.backend.deactivation.DeactivationRequest;
import com.example.crm.frontend.backend.deactivation.DeactivationType;
import com.example.crm.frontend.backend.deactivation.InMemoryCustomerProductService;
import com.example.crm.frontend.backend.deactivation.InMemoryDeactivationService;
import com.example.crm.frontend.base.acl.OperatorAcl;
import com.example.crm.frontend.base.session.SessionManagerCustom;
import com.example.crm.frontend.base.web.NotFoundException;
import com.example.sso.api.PortalCustomer;
import com.example.sso.api.PortalOperator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.portlet.MockActionResponse;
import org.springframework.mock.web.portlet.MockResourceResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.support.SimpleSessionStatus;

class DashboardControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);

    private final InMemoryCustomerProductService products = new InMemoryCustomerProductService();
    private final InMemoryDeactivationService deactivations = new InMemoryDeactivationService(CLOCK);
    private final SessionManagerCustom sessionManager = mock(SessionManagerCustom.class);
    private final DashboardController controller =
            new DashboardController(products, deactivations, CLOCK, new ObjectMapper());
    private final MockActionResponse response = new MockActionResponse();

    @BeforeEach
    void wire() {
        controller.setSessionManager(sessionManager);
        controller.setOperatorAcl(mock(OperatorAcl.class));
        when(sessionManager.getCustomer()).thenReturn(new PortalCustomer("C-1001", "Jan Vzorový"));
        when(sessionManager.getOperator()).thenReturn(new PortalOperator("spravca", "Správce"));
    }

    @Test
    @DisplayName("Prehľad ukáže produkty zákazníka zo SSO session")
    @SuppressWarnings("unchecked")
    void should_listProductsOfSsoCustomer_when_rendered() {
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.products(model)).isEqualTo("dashboard/products");
        assertThat((List<CustomerProduct>) model.get("products")).extracting(CustomerProduct::getId)
                .containsExactly("T-1", "T-2", "P-1");
    }

    @Test
    @DisplayName("Bez zákazníka v SSO session je dashboard 404")
    void should_throwNotFound_when_noCustomer() {
        when(sessionManager.getCustomer()).thenReturn(null);

        assertThatThrownBy(() -> controller.products(new ExtendedModelMap())).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Pasca z PoC: @ModelAttribute metóda dodá formulár s predvolenými hodnotami")
    void should_provideDefaultForm_when_modelAttributeMethodCalled() {
        DeactivationForm form = controller.form();

        assertThat(form.getType()).isEqualTo(DeactivationType.CUSTOMER_DEACTIVATION);
        assertThat(form.getNoticeDate()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("Formulár zrušenia: produkt, ID produktu vo formulári a najskorší dátum")
    void should_renderFormForProduct_when_pageTariffDeactivation() {
        ExtendedModelMap model = new ExtendedModelMap();
        DeactivationForm form = controller.form();

        assertThat(controller.deactivationForm("T-1", form, model)).isEqualTo("dashboard/deactivation/form");
        assertThat(((CustomerProduct) model.get("product")).getName()).isEqualTo("Tarif Neomezený");
        assertThat(form.getProductId()).isEqualTo("T-1");
        assertThat(model.get("earliestDate")).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    @DisplayName("Produkt iného zákazníka (podvrhnuté ID) je 404")
    void should_throwNotFound_when_productOfOtherCustomer() {
        assertThatThrownBy(() -> controller.deactivationForm("T-3", controller.form(), new ExtendedModelMap()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Legacy pasca @SessionAttributes: pri ďalšom produkte ostane typ z predchádzajúceho formulára")
    void should_keepStaleValues_when_formReusedFromSession() {
        DeactivationForm sessionForm = controller.form();
        controller.deactivationForm("T-1", sessionForm, new ExtendedModelMap());
        sessionForm.setType(DeactivationType.CUSTOMER_POSTPRE);

        controller.deactivationForm("T-2", sessionForm, new ExtendedModelMap());

        assertThat(sessionForm.getProductId()).isEqualTo("T-2");
        assertThat(sessionForm.getType()).isEqualTo(DeactivationType.CUSTOMER_POSTPRE);
    }

    @Test
    @DisplayName("Resource vráti JSON s najskorším dátumom")
    void should_returnJson_when_earliestDateRequested() throws Exception {
        MockResourceResponse resource = new MockResourceResponse();

        controller.earliestDate(DeactivationType.CUSTOMER_DEACTIVATION, LocalDate.of(2026, 10, 8), resource);

        assertThat(resource.getContentType()).startsWith("application/json");
        assertThat(resource.getContentAsString()).isEqualTo("{\"earliestDate\":\"2026-11-07\"}");
    }

    @Test
    @DisplayName("Platná žiadosť: uloží sa, formulár sa ukončí, render na potvrdenie")
    void should_submitAndShowDone_when_formValid() {
        DeactivationForm form = form("T-1", DeactivationType.CUSTOMER_POSTPRE, "2026-10-08", "2026-10-09");
        SimpleSessionStatus status = new SimpleSessionStatus();

        controller.submit(form, bindingResult(form), status, response);

        assertThat(deactivations.find(1).get().getOperator()).isEqualTo("Správce");
        assertThat(status.isComplete()).isTrue();
        verify(sessionManager).pushInfo("dashboard.deactivation.submitted");
        assertThat(response.getRenderParameter("page")).isEqualTo("tariffDeactivationDone");
        assertThat(response.getRenderParameter("requestId")).isEqualTo("1");
    }

    @Test
    @DisplayName("Príliš skorý dátum účinnosti: chyba pri poli a späť na formulár produktu")
    void should_rejectEarlyDate_when_submitting() {
        DeactivationForm form = form("T-1", DeactivationType.CUSTOMER_DEACTIVATION, "2026-10-08", "2026-10-09");
        BindingResult errors = bindingResult(form);

        controller.submit(form, errors, new SimpleSessionStatus(), response);

        assertThat(errors.getFieldError("effectiveDate").getCode()).isEqualTo("validation.effectiveTooEarly");
        assertThat(errors.getFieldError("effectiveDate").getArguments()).containsExactly("2026-11-07");
        assertThat(response.getRenderParameter("page")).isEqualTo("tariffDeactivation");
        assertThat(response.getRenderParameter("productId")).isEqualTo("T-1");
        assertThat(deactivations.find(1)).isNotPresent();
    }

    @Test
    @DisplayName("Datum podání v budúcnosti a chýbajúci typ sú chyby")
    void should_rejectFutureNoticeAndMissingType_when_submitting() {
        DeactivationForm form = form("T-1", null, "2026-10-09", "2026-12-31");
        BindingResult errors = bindingResult(form);

        controller.submit(form, errors, new SimpleSessionStatus(), response);

        assertThat(errors.getFieldError("noticeDate").getCode()).isEqualTo("validation.noticeInFuture");
        assertThat(errors.getFieldError("type").getCode()).isEqualTo("validation.required");
    }

    @Test
    @DisplayName("Potvrdenie ukáže žiadosť; žiadosť iného zákazníka je 404")
    void should_showOwnRequestOnly_when_doneRendered() {
        DeactivationRequest own = deactivations.submit(products.find("C-1001", "T-1").get(),
                DeactivationType.TRANSFER_TO_CUSTOMER, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), "Správce");
        DeactivationRequest foreign = deactivations.submit(products.find("C-1002", "T-3").get(),
                DeactivationType.TRANSFER_TO_CUSTOMER, LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), "Správce");
        ExtendedModelMap model = new ExtendedModelMap();

        assertThat(controller.done(own.getId(), model)).isEqualTo("dashboard/deactivation/done");
        assertThat(model.get("requestNumber")).isEqualTo(String.valueOf(own.getId()));
        assertThatThrownBy(() -> controller.done(foreign.getId(), new ExtendedModelMap()))
                .isInstanceOf(NotFoundException.class);
    }

    private static DeactivationForm form(String productId, DeactivationType type, String notice, String effective) {
        DeactivationForm form = new DeactivationForm();
        form.setProductId(productId);
        form.setType(type);
        form.setNoticeDate(LocalDate.parse(notice));
        form.setEffectiveDate(LocalDate.parse(effective));
        return form;
    }

    private static BindingResult bindingResult(DeactivationForm form) {
        return new BeanPropertyBindingResult(form, DashboardController.FORM);
    }
}
