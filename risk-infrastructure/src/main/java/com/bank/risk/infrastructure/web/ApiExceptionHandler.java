package com.bank.risk.infrastructure.web;

import com.bank.risk.domain.TransactionAlreadyAssessedException;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Maps application and domain exceptions to the ErrorResponse shape of
 * risk-context.yaml: a stable code, a message and the interaction id.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String UNIQUE_TRANSACTION_CONSTRAINT = "uq_risk_assessment_transaction";
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TransactionAlreadyAssessedException.class)
    ResponseEntity<ErrorResponse> alreadyAssessed(TransactionAlreadyAssessedException ex) {
        return error(HttpStatus.CONFLICT, "TRANSACTION_ALREADY_ASSESSED", ex.getMessage());
    }

    /**
     * Two concurrent first assessments of one transaction: the unique
     * transaction_id constraint lets one through and the other may retry to
     * read the stored decision. Any other integrity violation is a server
     * fault, not a retryable conflict, and its details stay in the log.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> dataIntegrity(DataIntegrityViolationException ex) {
        if (violates(ex, UNIQUE_TRANSACTION_CONSTRAINT)) {
            return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", "This transaction is already being assessed; retry");
        }
        log.error("Data integrity violation while assessing risk", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be processed");
    }

    static boolean violates(Throwable ex, String constraint) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = switch (ex) {
            case MethodArgumentNotValidException invalid -> invalid.getBindingResult().getAllErrors().stream()
                    .map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request");
            case HttpMessageNotReadableException unreadable -> "Malformed request body";
            default -> ex.getMessage();
        };
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
