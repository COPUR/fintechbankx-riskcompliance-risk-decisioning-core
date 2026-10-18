package com.bank.risk.infrastructure.web;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapping of database integrity violations. The real lost race is proven
 * end to end by RiskOutboxIT; here each kind of violation is mapped.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void theUniqueTransactionIdConstraintIsARetryableConflict() {
        var cause = new ConstraintViolationException("duplicate key", new SQLException("23505"),
            "uq_risk_assessment_transaction");

        var response = handler.dataIntegrity(new DataIntegrityViolationException("could not execute statement", cause));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("DUPLICATE_REQUEST");
    }

    @Test
    void theConstraintNameInTheDriverMessageIsRecognisedToo() {
        var response = handler.dataIntegrity(new DataIntegrityViolationException("insert failed",
            new SQLException("ERROR: duplicate key value violates unique constraint \"uq_risk_assessment_transaction\"")));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void anyOtherIntegrityViolationIsAServerErrorWithoutDetails() {
        var cause = new ConstraintViolationException("check failed", new SQLException("23514"), "ck_risk_assessment_score");

        var response = handler.dataIntegrity(new DataIntegrityViolationException("could not execute statement", cause));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().message()).doesNotContain("ck_risk_assessment_score");
    }

    @Test
    void aViolationWithoutAnyCauseIsNotTreatedAsADuplicate() {
        assertThat(ApiExceptionHandler.violates(new DataIntegrityViolationException(null), "uq_risk_assessment_transaction"))
            .isFalse();
    }

    /** Fail closed, retryable: no decision of record is made without a fresh high-risk country list. */
    @Test
    void anUnavailableRiskSourceIsA503NamingTheSource() {
        var response = handler.riskSourceUnavailable(new com.bank.risk.domain.RiskSourceUnavailableException(
            "high-risk country list", "the list is empty"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("RISK_SOURCE_UNAVAILABLE");
        assertThat(response.getBody().message()).contains("high-risk country list").contains("empty");
        assertThat(response.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void anUnreachableCustomerServiceIsA503WithoutItsDetails() {
        var response = handler.customerServiceUnavailable(new com.bank.risk.domain.CustomerServiceUnavailableException(
            "GET /api/v1/customers/CUST-1/credit answered 502", null));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().code()).isEqualTo("CUSTOMER_SERVICE_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain("502");
    }
}
