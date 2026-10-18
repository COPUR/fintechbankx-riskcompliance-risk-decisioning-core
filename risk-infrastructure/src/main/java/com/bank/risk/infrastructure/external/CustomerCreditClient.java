package com.bank.risk.infrastructure.external;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.CustomerServiceUnavailableException;
import com.bank.risk.domain.port.out.CustomerCreditProfilePort;
import com.bank.risk.infrastructure.web.CorrelationIdFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Anti-corruption adapter for the customer context's credit standing,
 * {@code GET /api/v1/customers/{customerId}/credit-standing} (added for
 * risk; customerId, creditScore optional, creditLimit, usedCredit,
 * availableCredit, currency). Carries this service's
 * client-credentials token and the current interaction id. A 404 is an unknown
 * customer only with customer's own not-found body (customer
 * ApiExceptionHandler: application/json ErrorResponse with code
 * {@value #CUSTOMER_NOT_FOUND_CODE}); a bare 404, another code or another
 * shape (an unmapped route, an older customer build, an ingress or mesh 404)
 * is the customer service being unavailable, so the caller retries instead of
 * reading a terminal answer. The body must name the customer asked about. Any
 * other failure (transport, 4xx, 5xx, an unreadable body) is
 * {@link CustomerServiceUnavailableException}, never a decision.
 */
public class CustomerCreditClient implements CustomerCreditProfilePort {

    static final String CREDIT_PATH = "/api/v1/customers/{customerId}/credit-standing";
    static final String CUSTOMER_NOT_FOUND_CODE = "CUSTOMER_NOT_FOUND";

    private final RestClient restClient;
    private final BearerTokenProvider tokens;

    public CustomerCreditClient(RestClient restClient, BearerTokenProvider tokens) {
        this.restClient = Objects.requireNonNull(restClient, "restClient is required");
        this.tokens = Objects.requireNonNull(tokens, "tokens are required");
    }

    @Override
    public Optional<CustomerCreditProfile> fetch(String customerId) {
        String token = tokens.token();
        try {
            return restClient.get().uri(CREDIT_PATH, customerId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header("X-FAPI-Interaction-ID", interactionId())
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) {
                            if (isCustomerNotFound(response)) {
                                return Optional.empty();
                            }
                            throw new CustomerServiceUnavailableException("GET " + CREDIT_PATH
                                    + " answered 404 without the customer's not-found body (an unmapped route or another build)", null);
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new CustomerServiceUnavailableException(
                                    "GET " + CREDIT_PATH + " answered " + status, null);
                        }
                        CreditResponse body = response.bodyTo(CreditResponse.class);
                        return Optional.of(toProfile(body, customerId));
                    });
        } catch (RestClientException | UncheckedIOException transport) {
            throw new CustomerServiceUnavailableException("GET " + CREDIT_PATH + " failed: "
                    + transport.getClass().getSimpleName(), transport);
        }
    }

    /** Customer's ErrorResponse (code, message, interactionId, timestamp) as application/json with code CUSTOMER_NOT_FOUND; nothing else. */
    private static boolean isCustomerNotFound(ClientHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(contentType)) {
            return false;
        }
        try {
            ErrorBody body = new ObjectMapper().readValue(response.getBody(), ErrorBody.class);
            return body != null && CUSTOMER_NOT_FOUND_CODE.equals(body.code());
        } catch (IOException | RuntimeException notTheErrorShape) {
            return false;
        }
    }

    private static CustomerCreditProfile toProfile(CreditResponse body, String customerId) {
        if (body == null) {
            throw new CustomerServiceUnavailableException("GET " + CREDIT_PATH + " answered without a body", null);
        }
        if (body.customerId() == null || !body.customerId().trim().equals(customerId)) {
            throw new CustomerServiceUnavailableException("GET " + CREDIT_PATH
                    + " answered with the credit standing of another customer (or none); refusing to decide on it", null);
        }
        try {
            return new CustomerCreditProfile(customerId, body.currency(),
                    body.creditLimit(), body.usedCredit(), body.availableCredit(), body.creditScore(), null);
        } catch (IllegalArgumentException | NullPointerException notACreditPosition) {
            throw new CustomerServiceUnavailableException("GET " + CREDIT_PATH + " answered something that is not a credit position: "
                    + notACreditPosition.getMessage(), notACreditPosition);
        }
    }

    private static String interactionId() {
        String current = MDC.get(CorrelationIdFilter.MDC_KEY);
        return current == null || current.isBlank() ? UUID.randomUUID().toString() : current;
    }

    /**
     * The credit-standing response; creditScore is optional (absent for a customer without one). Fields the
     * customer context adds later are ignored, so an additive change there never makes this a 503.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreditResponse(String customerId, String currency, BigDecimal creditLimit, BigDecimal usedCredit,
                          BigDecimal availableCredit, Integer creditScore) {
    }

    /** Customer's ApiExceptionHandler.ErrorResponse, read for the not-found code only. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, String message) {
    }
}
