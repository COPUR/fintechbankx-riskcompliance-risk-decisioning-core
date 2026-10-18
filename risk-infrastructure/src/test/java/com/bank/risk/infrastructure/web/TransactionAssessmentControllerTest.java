package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.CounterpartyCountry;
import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.RiskSourceUnavailableException;
import com.bank.risk.domain.TransactionAlreadyAssessedException;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import com.bank.risk.domain.port.in.TransactionAssessmentUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/v2/risk/assess: ids and amounts in, risk's own facts out. */
class TransactionAssessmentControllerTest {

    private static final String BODY = """
        {"transactionId":"PAY-V2-1","customerId":"CUST-1","amount":12000.00,"currency":"USD","paymentType":"TRANSFER","counterpartyAccount":"IR580540105180021273113007"}
        """;

    private TransactionAssessmentUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(TransactionAssessmentUseCase.class);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("service-account-payments")
                .claim("azp", "svc-pay-initiation-settlement").build();
        mockMvc = MockMvcBuilders.standaloneSetup(new TransactionAssessmentController(useCase))
                .setControllerAdvice(new ApiExceptionHandler())
                .defaultRequest(get("/").principal(new JwtAuthenticationToken(jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_SERVICE")), "service-account-payments")))
                .build();
    }

    private static RiskAssessment decided(TransactionAssessmentCommand command) {
        return RiskAssessment.resolve(command, CounterpartyCountry.of("IR"), true, 40, 85, RiskDecision.BLOCK,
                List.of("HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "MEDIUM_VELOCITY"), "rsk-policy-v4");
    }

    @Test
    void theCallerSendsIdsAndAmountsOnlyAndGetsRisksResolvedDecision() throws Exception {
        when(useCase.assess(any(TransactionAssessmentCommand.class))).thenAnswer(i -> decided(i.getArgument(0)));

        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("PAY-V2-1"))
                .andExpect(jsonPath("$.customerId").value("CUST-1"))
                .andExpect(jsonPath("$.counterpartyCountry").value("IR"))
                .andExpect(jsonPath("$.decision").value("BLOCK"))
                .andExpect(jsonPath("$.attestationSource").value("RISK_RESOLVED"))
                .andExpect(jsonPath("$.attestedBy").value("svc-pay-initiation-settlement"))
                .andExpect(jsonPath("$.ruleSetVersion").value("rsk-policy-v4"))
                .andExpect(jsonPath("$.reasons[1]").value("HIGH_RISK_COUNTRY"));

        ArgumentCaptor<TransactionAssessmentCommand> command = ArgumentCaptor.forClass(TransactionAssessmentCommand.class);
        verify(useCase).assess(command.capture());
        assertThat(command.getValue().customerId()).isEqualTo("CUST-1");
        assertThat(command.getValue().counterpartyAccount()).isEqualTo("IR580540105180021273113007");
        assertThat(command.getValue().amount()).isEqualByComparingTo(new BigDecimal("12000.00"));
        assertThat(command.getValue().paymentType()).isEqualTo(PaymentType.TRANSFER);
        assertThat(command.getValue().requestedBy()).isEqualTo("svc-pay-initiation-settlement");
    }

    /** The caller must not state the facts any more: a body carrying them is refused, not silently ignored. */
    @Test
    void callerStatedFactsAreRefused() throws Exception {
        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"paymentType\"", "\"highRiskCountry\":false,\"paymentType\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("highRiskCountry")));
        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"paymentType\"", "\"velocityScore\":0,\"paymentType\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("velocityScore")));
        verifyNoInteractions(useCase);
    }

    @Test
    void anEmptyOrStaleHighRiskCountryListIsA503WithAStableCode() throws Exception {
        when(useCase.assess(any())).thenThrow(new RiskSourceUnavailableException("high-risk country list",
                "the list is empty: load it with scripts/ops/load-high-risk-countries.sh"));

        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RISK_SOURCE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(containsString("high-risk country list")));
    }

    @Test
    void aReusedTransactionIdWithOtherInputsIsAConflict() throws Exception {
        when(useCase.assess(any())).thenThrow(new TransactionAlreadyAssessedException("PAY-V2-1"));

        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "customerId omitted          | {\"transactionId\":\"PAY-1\",\"amount\":10,\"currency\":\"USD\",\"paymentType\":\"TRANSFER\",\"counterpartyAccount\":\"ACC-1\"}                     | customerId",
        "customerId null             | {\"transactionId\":\"PAY-1\",\"customerId\":null,\"amount\":10,\"currency\":\"USD\",\"paymentType\":\"TRANSFER\",\"counterpartyAccount\":\"ACC-1\"} | customerId",
        "counterpartyAccount omitted | {\"transactionId\":\"PAY-1\",\"customerId\":\"CUST-1\",\"amount\":10,\"currency\":\"USD\",\"paymentType\":\"TRANSFER\"}                      | counterpartyAccount",
        "paymentType unknown         | {\"transactionId\":\"PAY-1\",\"customerId\":\"CUST-1\",\"amount\":10,\"currency\":\"USD\",\"paymentType\":\"CASH\",\"counterpartyAccount\":\"ACC-1\"} | paymentType",
        "paymentType omitted         | {\"transactionId\":\"PAY-1\",\"customerId\":\"CUST-1\",\"amount\":10,\"currency\":\"USD\",\"counterpartyAccount\":\"ACC-1\"}                      | paymentType",
        "negative amount             | {\"transactionId\":\"PAY-1\",\"customerId\":\"CUST-1\",\"amount\":-1,\"currency\":\"USD\",\"paymentType\":\"TRANSFER\",\"counterpartyAccount\":\"ACC-1\"} | amount",
    })
    void anIncompleteRequestIsA400(String name, String body, String field) throws Exception {
        mockMvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString(field)));
        verifyNoInteractions(useCase);
    }
}
