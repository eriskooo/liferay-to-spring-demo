package com.example.ssomock;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Pevný zoznam demo operátorov a zákazníkov (vymyslené). Privilégiá tu nie sú – ACL je vecou CRM. */
@Component
public class Directory {

    public record Operator(String login, String displayName) {
    }

    public record Customer(String customerId, String displayName) {
    }

    private static final List<Operator> OPERATORS = List.of(
            new Operator("citatel", "Čtenář"),
            new Operator("spravca", "Správce"));

    private static final List<Customer> CUSTOMERS = List.of(
            new Customer("C-1001", "Jan Vzorový"),
            new Customer("C-1002", "Firma Ukázka s.r.o."));

    /** Všetci demo operátori. */
    public List<Operator> operators() {
        return OPERATORS;
    }

    /** Všetci demo zákazníci. */
    public List<Customer> customers() {
        return CUSTOMERS;
    }

    /** Operátor podľa loginu. */
    public Optional<Operator> findOperator(String login) {
        return OPERATORS.stream().filter(o -> o.login().equals(login)).findFirst();
    }

    /** Zákazník podľa ID. */
    public Optional<Customer> findCustomer(String customerId) {
        return CUSTOMERS.stream().filter(c -> c.customerId().equals(customerId)).findFirst();
    }
}
