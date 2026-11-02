package com.example.crm.frontend.backend.deactivation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeactivationBackendTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T10:15:30Z"), ZoneOffset.UTC);
    private static final LocalDate NOTICE = LocalDate.of(2026, 10, 8);

    private final CustomerProductService products = new InMemoryCustomerProductService();
    private final DeactivationService deactivations = new InMemoryDeactivationService(CLOCK);

    @Test
    @DisplayName("Demo pravidlá: prevod hneď, zrušenie +30 dní, predplatená karta +1 deň")
    void should_computeEarliestDate_when_typeGiven() {
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.TRANSFER_TO_CUSTOMER, NOTICE)).isEqualTo(NOTICE);
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_DEACTIVATION, NOTICE))
                .isEqualTo(LocalDate.of(2026, 11, 7));
        assertThat(DeactivationRules.earliestEffectiveDate(DeactivationType.CUSTOMER_POSTPRE, NOTICE))
                .isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("Produkty patria zákazníkovi; cudzí produkt sa nenájde")
    void should_findOnlyOwnProducts_when_queried() {
        assertThat(products.productsOf("C-1001")).extracting(CustomerProduct::getId).containsExactly("T-1", "T-2", "P-1");
        assertThat(products.productsOf("C-1002")).extracting(CustomerProduct::getId).containsExactly("T-3", "P-2");
        assertThat(products.find("C-1001", "T-3")).isNotPresent();
        assertThat(products.find("C-1001", "T-1")).isPresent();
    }

    @Test
    @DisplayName("Žiadosť sa uloží s ID, názvom produktu a časom z hodín")
    void should_storeRequest_when_submitted() {
        CustomerProduct product = products.find("C-1001", "T-1").get();

        DeactivationRequest request = deactivations.submit(product, DeactivationType.CUSTOMER_POSTPRE, NOTICE,
                LocalDate.of(2026, 10, 9), "Správce");

        assertThat(request.getId()).isEqualTo(1L);
        assertThat(request.getProductName()).isEqualTo("Tarif Neomezený");
        assertThat(request.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 15));
        assertThat(deactivations.find(1L).get()).isSameAs(request);
    }

    @Test
    @DisplayName("Backend odmietne príliš skorý dátum, aj keby ho formulár prepustil")
    void should_reject_when_effectiveDateTooEarly() {
        CustomerProduct product = products.find("C-1001", "T-1").get();

        assertThatThrownBy(() -> deactivations.submit(product, DeactivationType.CUSTOMER_DEACTIVATION, NOTICE,
                LocalDate.of(2026, 10, 9), "Správce")).isInstanceOf(IllegalArgumentException.class);
    }
}
