package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.command.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void shouldAssessRisk() throws Exception {
        RiskAssessment assessment = RiskAssessment.create("TX-1", new BigDecimal("200"), "AED", 60, RiskDecision.REVIEW, List.of("HIGH_AMOUNT"));
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
        RiskAssessment assessment = RiskAssessment.create("TX-2", new BigDecimal("100"), "AED", 10, RiskDecision.ALLOW, List.of("COMPLIANT"));
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
                .thenThrow(new com.bank.risk.application.TransactionAlreadyAssessedException("TX-3"));

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

    @Test
    void concurrentDuplicateIsAConflict() {
        var response = new ApiExceptionHandler().duplicate(
                new org.springframework.dao.DataIntegrityViolationException("uq_risk_assessment_transaction"));

        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(409);
        org.assertj.core.api.Assertions.assertThat(response.getBody().code()).isEqualTo("DUPLICATE_REQUEST");
    }
}
