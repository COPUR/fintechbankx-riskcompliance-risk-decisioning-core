package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.CustomerNotFoundException;
import com.bank.risk.domain.CustomerServiceUnavailableException;
import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.LoanEligibilityDecision;
import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import com.bank.risk.domain.port.in.LoanEligibilityUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v1/risk/loan-eligibility, called by the loan service ("Risk decides"). */
class LoanEligibilityControllerTest {

    private static final String BODY = """
        {"customerId":"CUST-UNIT-006","requestedAmount":5000.00,"currency":"AED"}
        """;

    private LoanEligibilityUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(LoanEligibilityUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new LoanEligibilityController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void answersEligibleWithTheReasonAndEchoesTheQuestion() throws Exception {
        when(useCase.check(any())).thenReturn(new LoanEligibility(LoanEligibilityDecision.ELIGIBLE, "WITHIN_AVAILABLE_CREDIT"));

        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("CUST-UNIT-006"))
                .andExpect(jsonPath("$.decision").value("ELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("WITHIN_AVAILABLE_CREDIT"))
                .andExpect(jsonPath("$.requestedAmount").value(5000.00))
                .andExpect(jsonPath("$.currency").value("AED"));

        ArgumentCaptor<LoanEligibilityCommand> command = ArgumentCaptor.forClass(LoanEligibilityCommand.class);
        verify(useCase).check(command.capture());
        assertThat(command.getValue().requestedAmount()).isEqualByComparingTo(new BigDecimal("5000.00"));
    }

    @Test
    void answersIneligibleWithTheReason() throws Exception {
        when(useCase.check(any())).thenReturn(new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, "CREDIT_SCORE_BELOW_MINIMUM"));

        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("INELIGIBLE"))
                .andExpect(jsonPath("$.reason").value("CREDIT_SCORE_BELOW_MINIMUM"));
    }

    @Test
    void anUnreachableCustomerServiceIsA503() throws Exception {
        when(useCase.check(any())).thenThrow(new CustomerServiceUnavailableException("GET /api/v1/customers/CUST-UNIT-006/credit failed", null));

        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CUSTOMER_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(containsString("customer")));
    }

    @Test
    void anUnknownCustomerIsA404() throws Exception {
        when(useCase.check(any())).thenThrow(new CustomerNotFoundException("CUST-UNIT-006"));

        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void anIncompleteQuestionIsA400() throws Exception {
        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedAmount\":5000.00,\"currency\":\"AED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("customerId")));
        mockMvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"CUST-1\",\"requestedAmount\":0,\"currency\":\"AED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("requestedAmount")));
        verifyNoInteractions(useCase);
    }
}
