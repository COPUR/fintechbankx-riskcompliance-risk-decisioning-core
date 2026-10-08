package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.TransactionAlreadyAssessedException;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RiskControllerTest {

    private RiskAssessmentUseCase service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(RiskAssessmentUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RiskController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .defaultRequest(get("/").principal(serviceToken()))
                .build();
    }

    private static JwtAuthenticationToken serviceToken() {
        return token("service-account-payments", "svc-pay-initiation-settlement", "ROLE_SERVICE");
    }

    private static JwtAuthenticationToken token(String subject, String azp, String role) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject);
        if (azp != null) {
            jwt.claim("azp", azp);
        }
        return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority(role)), subject);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "service client is attested by its azp | service-account-payments | svc-pay-initiation-settlement | ROLE_SERVICE | svc-pay-initiation-settlement",
        "staff member is attested by subject   | staff-42                 | fintechbankx-web              | ROLE_BANKER  | staff-42"
    })
    void theCallerWhoStatedTheRiskFactsIsPassedInTheCommandAndReturned(String name, String subject, String azp,
                                                                     String role, String attestedBy) throws Exception {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("TX-7", new BigDecimal("10"), "USD",
                false, 0, attestedBy), 0, RiskDecision.ALLOW, List.of());
        when(service.assess(any(RiskEvaluationCommand.class))).thenReturn(assessment);

        mockMvc.perform(post("/api/v1/risk/assess")
                        .principal(token(subject, azp, role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-7","amount":10,"currency":"USD","highRiskCountry":false,"velocityScore":0}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attestationSource").value("CALLER_ATTESTED"))
                .andExpect(jsonPath("$.attestedBy").value(attestedBy));

        ArgumentCaptor<RiskEvaluationCommand> command = ArgumentCaptor.forClass(RiskEvaluationCommand.class);
        org.mockito.Mockito.verify(service).assess(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().attestedBy()).isEqualTo(attestedBy);
    }

    @Test
    void shouldAssessRisk() throws Exception {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("200"), "AED", false, 0, "svc-pay-initiation-settlement"), 60, RiskDecision.REVIEW, List.of("HIGH_AMOUNT"));
        when(service.assess(any(RiskEvaluationCommand.class))).thenReturn(assessment);

        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-1","amount":200,"currency":"AED","highRiskCountry":false,"velocityScore":45}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("TX-1"))
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void shouldReturnAssessmentByTransactionId() throws Exception {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("TX-2", new BigDecimal("100"), "AED", false, 0, "svc-pay-initiation-settlement"), 10, RiskDecision.ALLOW, List.of("COMPLIANT"));
        when(service.findByTransactionId("TX-2")).thenReturn(Optional.of(assessment));
        when(service.findByTransactionId("TX-404")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/risk/assessments/TX-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("TX-2"));

        mockMvc.perform(get("/api/v1/risk/assessments/TX-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
    }

    @Test
    void reusedTransactionIdWithADifferentAmountIsAConflict() throws Exception {
        when(service.assess(any(RiskEvaluationCommand.class)))
                .thenThrow(new TransactionAlreadyAssessedException("TX-3"));

        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-3","amount":201,"currency":"AED","highRiskCountry":false,"velocityScore":0}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
    }

    @Test
    void invalidRequestsAreA400WithAStableCode() throws Exception {
        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-4","amount":-1,"currency":"AED","highRiskCountry":false,"velocityScore":0}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("amount must be positive"));

        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    /** Caller-supplied risk facts fail closed: a missing fact is a 400, never the low-risk false or 0. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "highRiskCountry omitted  | {\"transactionId\":\"TX-6\",\"amount\":9000.00,\"currency\":\"USD\",\"velocityScore\":0}                          | highRiskCountry",
        "highRiskCountry null     | {\"transactionId\":\"TX-6\",\"amount\":9000.00,\"currency\":\"USD\",\"highRiskCountry\":null,\"velocityScore\":0}  | highRiskCountry",
        "velocityScore omitted    | {\"transactionId\":\"TX-6\",\"amount\":9000.00,\"currency\":\"USD\",\"highRiskCountry\":false}                       | velocityScore",
        "velocityScore null       | {\"transactionId\":\"TX-6\",\"amount\":9000.00,\"currency\":\"USD\",\"highRiskCountry\":false,\"velocityScore\":null} | velocityScore"
    })
    void anOmittedOrNullRiskFactIsA400NotALowRiskDefault(String name, String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field)));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "lower-case currency      | {\"transactionId\":\"TX-5\",\"amount\":10.00,\"currency\":\"aed\",\"highRiskCountry\":false,\"velocityScore\":0}   | currency",
        "unknown currency         | {\"transactionId\":\"TX-5\",\"amount\":10.00,\"currency\":\"ABC\",\"highRiskCountry\":false,\"velocityScore\":0}   | currency",
        "129-character id         | {\"transactionId\":\"ID129\",\"amount\":10.00,\"currency\":\"AED\",\"highRiskCountry\":false,\"velocityScore\":0}  | transactionId",
        "16 integer digits        | {\"transactionId\":\"TX-5\",\"amount\":1e16,\"currency\":\"AED\",\"highRiskCountry\":false,\"velocityScore\":0}    | amount",
        "five decimals            | {\"transactionId\":\"TX-5\",\"amount\":1.23456,\"currency\":\"AED\",\"highRiskCountry\":false,\"velocityScore\":0} | amount",
        "velocity out of range    | {\"transactionId\":\"TX-5\",\"amount\":10.00,\"currency\":\"AED\",\"highRiskCountry\":false,\"velocityScore\":-1}  | velocityScore"
    })
    void inputsTheDecisionOfRecordCannotHoldAreA400(String name, String body, String field) throws Exception {
        mockMvc.perform(post("/api/v1/risk/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("ID129", "P".repeat(129))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(field)));
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
