package com.bank.risk.application;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.CustomerNotFoundException;
import com.bank.risk.domain.CustomerServiceUnavailableException;
import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.LoanEligibilityDecision;
import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import com.bank.risk.domain.service.LoanEligibilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** POST /api/v1/risk/loan-eligibility: read the credit profile through the out-port, apply risk's rule. */
@ExtendWith(MockitoExtension.class)
class LoanEligibilityServiceTest {

    @Mock private CustomerCreditProfilePort customers;

    private LoanEligibilityService service() {
        return new LoanEligibilityService(customers, new LoanEligibilityPolicy());
    }

    @Test
    void decidesFromTheCustomersCreditProfile() {
        when(customers.fetch("CUST-UNIT-006")).thenReturn(Optional.of(new CustomerCreditProfile("CUST-UNIT-006", "AED",
                new BigDecimal("10000.00"), new BigDecimal("0.00"), new BigDecimal("10000.00"), 700, null)));

        assertThat(service().check(new LoanEligibilityCommand("CUST-UNIT-006", new BigDecimal("5000.00"), "AED")))
            .isEqualTo(new LoanEligibility(LoanEligibilityDecision.ELIGIBLE, LoanEligibilityPolicy.WITHIN_AVAILABLE_CREDIT));
        assertThat(service().check(new LoanEligibilityCommand("CUST-UNIT-006", new BigDecimal("12000.00"), "AED")))
            .isEqualTo(new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, LoanEligibilityPolicy.INSUFFICIENT_AVAILABLE_CREDIT));
    }

    @Test
    void anUnknownCustomerIsNotFound() {
        when(customers.fetch("CUST-404")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().check(new LoanEligibilityCommand("CUST-404", new BigDecimal("1.00"), "AED")))
            .isInstanceOf(CustomerNotFoundException.class)
            .hasMessageContaining("CUST-404");
    }

    @Test
    void anUnreachableCustomerServiceIsReportedNotDecided() {
        when(customers.fetch("CUST-1")).thenThrow(new CustomerServiceUnavailableException("connection refused", null));

        assertThatThrownBy(() -> service().check(new LoanEligibilityCommand("CUST-1", new BigDecimal("1.00"), "AED")))
            .isInstanceOf(CustomerServiceUnavailableException.class);
    }
}
