package com.bank.risk.domain.port.in;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskEvaluationCommandTest {

    @Test
    void shouldRejectInvalidInputs() {
        assertThatThrownBy(() -> new RiskEvaluationCommand("", new BigDecimal("10"), "AED", false, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", BigDecimal.ZERO, "AED", false, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");

        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "", false, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");

        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "AED", false, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocityScore");
    }

    @Test
    void shouldCreateCommandForValidInput() {
        assertThatCode(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("100"), "AED", true, 90))
                .doesNotThrowAnyException();
    }

    @Test
    void currencyMustBeAnUpperCaseIso4217Code() {
        for (String currency : new String[] {"aed", "AE", "AEDX", "ABC", "A1D"}) {
            assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10"), currency, false, 0))
                    .as(currency)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("currency must be an upper-case ISO 4217 code");
        }
        assertThatCode(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "USD", false, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void transactionIdFitsTheStoredColumn() {
        assertThatCode(() -> new RiskEvaluationCommand("P".repeat(128), new BigDecimal("10"), "AED", false, 0))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new RiskEvaluationCommand("P".repeat(129), new BigDecimal("10"), "AED", false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId must be at most 128 characters");
    }

    @Test
    void amountFitsNumeric19Scale4WithoutRounding() {
        assertThatCode(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("999999999999999.9999"), "AED", false, 0))
                .as("15 integer digits and 4 decimals").doesNotThrowAnyException();
        assertThatCode(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10.500000"), "AED", false, 0))
                .as("trailing zeros carry no value").doesNotThrowAnyException();
        assertThatCode(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("1E+6"), "AED", false, 0))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("1E+16"), "AED", false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 15 integer digits");
        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("1000000000000000"), "AED", false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 15 integer digits");
        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("1.23456"), "AED", false, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 4 decimals");
    }

    @Test
    void velocityScoreBelowZeroIsRejected() {
        assertThatThrownBy(() -> new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "AED", false, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("velocityScore");
    }
}
