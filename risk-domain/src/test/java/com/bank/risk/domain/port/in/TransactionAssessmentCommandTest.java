package com.bank.risk.domain.port.in;

import com.bank.risk.domain.PaymentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Inputs of POST /api/v2/risk/assess: ids and amounts only; risk resolves the facts. */
class TransactionAssessmentCommandTest {

    private static TransactionAssessmentCommand command(String transactionId, String customerId, String amount, String currency,
                                                        PaymentType type, String account, String requestedBy) {
        return new TransactionAssessmentCommand(transactionId, customerId, amount == null ? null : new BigDecimal(amount),
                currency, type, account, requestedBy);
    }

    @Test
    void aCompleteCommandIsAccepted() {
        TransactionAssessmentCommand command = command("PAY-1", "CUST-1", "100.00", "USD", PaymentType.TRANSFER,
                "GB82WEST12345698765432", "svc-pay-initiation-settlement");

        assertThat(command.customerId()).isEqualTo("CUST-1");
        assertThat(command.counterpartyAccount()).isEqualTo("GB82WEST12345698765432");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "blank transactionId     | ' '    | CUST-1 | 100.00  | USD | TRANSFER | ACC-1 | svc | transactionId",
        "blank customerId        | PAY-1  | ' '    | 100.00  | USD | TRANSFER | ACC-1 | svc | customerId",
        "negative amount         | PAY-1  | CUST-1 | -1      | USD | TRANSFER | ACC-1 | svc | amount",
        "five decimals           | PAY-1  | CUST-1 | 1.23456 | USD | TRANSFER | ACC-1 | svc | amount",
        "lower-case currency     | PAY-1  | CUST-1 | 100.00  | usd | TRANSFER | ACC-1 | svc | currency",
        "blank counterparty      | PAY-1  | CUST-1 | 100.00  | USD | TRANSFER | ' '   | svc | counterpartyAccount",
        "blank requestedBy       | PAY-1  | CUST-1 | 100.00  | USD | TRANSFER | ACC-1 | ' ' | requestedBy",
    })
    void everyInputIsRequiredAndBounded(String name, String tx, String customer, String amount, String currency,
                                        PaymentType type, String account, String requestedBy, String field) {
        assertThatThrownBy(() -> command(tx, customer, amount, currency, type, account, requestedBy))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(field);
    }

    @Test
    void nullPaymentTypeAndOverlongIdsAreRefused() {
        assertThatThrownBy(() -> command("PAY-1", "CUST-1", "1", "USD", null, "ACC-1", "svc"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentType");
        assertThatThrownBy(() -> command("PAY-1", "C".repeat(65), "1", "USD", PaymentType.TRANSFER, "ACC-1", "svc"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customerId");
        assertThatThrownBy(() -> command("PAY-1", "CUST-1", "1", "USD", PaymentType.TRANSFER, "A".repeat(65), "svc"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("counterpartyAccount");
        assertThatThrownBy(() -> command("P".repeat(129), "CUST-1", "1", "USD", PaymentType.TRANSFER, "ACC-1", "svc"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("transactionId");
    }

    @Test
    void theLoanEligibilityCommandIsValidatedTheSameWay() {
        assertThat(new LoanEligibilityCommand("CUST-1", new BigDecimal("5000.00"), "AED").currency()).isEqualTo("AED");
        assertThatThrownBy(() -> new LoanEligibilityCommand(" ", new BigDecimal("1"), "AED"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("customerId");
        assertThatThrownBy(() -> new LoanEligibilityCommand("CUST-1", BigDecimal.ZERO, "AED"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestedAmount");
        assertThatThrownBy(() -> new LoanEligibilityCommand("CUST-1", new BigDecimal("1"), "ABC"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
    }
}
