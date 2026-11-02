package com.example.crm.frontend.backend.deactivation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Vymyslené produkty demo zákazníkov (ID zákazníkov zodpovedajú SSO mocku). */
public class InMemoryCustomerProductService implements CustomerProductService {

    private final List<CustomerProduct> products = Arrays.asList(
            new CustomerProduct("T-1", "C-1001", "Tarif Neomezený", ProductType.TARIFF, "Neomezená data, volání a SMS"),
            new CustomerProduct("T-2", "C-1001", "Tarif Standard", ProductType.TARIFF, "5 GB dat, 100 minut"),
            new CustomerProduct("P-1", "C-1001", "+420 600 000 001", ProductType.PRODUCT, "Mobilní číslo – Tarif Neomezený"),
            new CustomerProduct("T-3", "C-1002", "Firemní tarif", ProductType.TARIFF, "Sdílená data pro firmu"),
            new CustomerProduct("P-2", "C-1002", "+420 600 000 002", ProductType.PRODUCT, "Mobilní číslo – Firemní tarif"));

    @Override
    public List<CustomerProduct> productsOf(String customerId) {
        return products.stream().filter(p -> p.getCustomerId().equals(customerId)).collect(Collectors.toList());
    }

    @Override
    public Optional<CustomerProduct> find(String customerId, String productId) {
        return productsOf(customerId).stream().filter(p -> p.getId().equals(productId)).findFirst();
    }
}
