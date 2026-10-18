package com.bank.risk.infrastructure.external;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.CustomerServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Anti-corruption adapter for customer's GET /api/v1/customers/{id}/credit-standing,
 * the endpoint customer adds for risk (deploy-order dependency; tested here
 * against a stub of its shape: customerId, creditScore optional 300..850,
 * absent for a customer without a score, creditLimit, usedCredit,
 * availableCredit, currency). Without a score the
 * profile has none and the policy answers INELIGIBLE with NO_CREDIT_SCORE,
 * as Customer.java:410-413 does; never ELIGIBLE by default.
 */
class CustomerCreditClientTest {

    private static final String BASE = "http://customer-profile-kyc.customer.svc.cluster.local:8080";

    private MockRestServiceServer server;
    private CustomerCreditClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CustomerCreditClient(builder.build(), () -> "token-123");
    }

    @Test
    void readsTheCreditPositionWithAServiceTokenAndTheInteractionId() {
        server.expect(once(), requestTo(BASE + "/api/v1/customers/CUST-UNIT-006/credit-standing"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer token-123"))
                .andExpect(header("X-FAPI-Interaction-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andRespond(withSuccess("""
                    {"customerId":"CUST-UNIT-006","currency":"AED","creditLimit":10000.00,"usedCredit":2500.50,"availableCredit":7499.50}
                    """, MediaType.APPLICATION_JSON));

        Optional<CustomerCreditProfile> profile = client.fetch("CUST-UNIT-006");

        assertThat(profile).isPresent();
        assertThat(profile.get().currency()).isEqualTo("AED");
        assertThat(profile.get().creditLimit()).isEqualByComparingTo("10000.00");
        assertThat(profile.get().usedCredit()).isEqualByComparingTo("2500.50");
        assertThat(profile.get().availableCredit()).isEqualByComparingTo("7499.50");
        assertThat(profile.get().creditScore()).as("absent in the response: the customer has no score").isNull();
        server.verify();
    }

    @Test
    void readsTheCreditScoreWhenCustomerSendsIt() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-7/credit-standing")).andRespond(withSuccess("""
            {"customerId":"CUST-7","creditScore":700,"creditLimit":10000.00,"usedCredit":0.00,"availableCredit":10000.00,"currency":"AED","futureField":"ignored"}
            """, MediaType.APPLICATION_JSON));

        CustomerCreditProfile profile = client.fetch("CUST-7").orElseThrow();

        assertThat(profile.creditScore()).isEqualTo(700);
        assertThat(profile.monthlyIncome()).as("not part of the credit standing").isNull();
    }

    @Test
    void anUnknownCustomerIsEmpty() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-404/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CUSTOMER_NOT_FOUND\"}"));

        assertThat(client.fetch("CUST-404")).isEmpty();
    }

    @Test
    void anyOtherFailureIsTheCustomerServiceBeingUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-1/credit-standing")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-2/credit-standing")).andRespond(withException(new SocketTimeoutException("read timed out")));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-3/credit-standing")).andRespond(withStatus(HttpStatus.FORBIDDEN));

        for (String id : new String[] {"CUST-1", "CUST-2", "CUST-3"}) {
            assertThatThrownBy(() -> client.fetch(id))
                .as(id)
                .isInstanceOf(CustomerServiceUnavailableException.class)
                .hasMessageContaining("/credit-standing")
                .hasMessageNotContaining("token-123");
        }
    }

    @Test
    void aResponseThatIsNotACreditPositionIsUnavailableToo() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-9/credit-standing"))
                .andRespond(withSuccess("{\"customerId\":\"CUST-9\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch("CUST-9")).isInstanceOf(CustomerServiceUnavailableException.class);
    }

    @Test
    void theCustomerIdIsPathEncoded() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST%2F1/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"CUSTOMER_NOT_FOUND\",\"message\":\"Customer not found\"}"));

        assertThat(client.fetch("CUST/1")).isEmpty();
    }

    /**
     * Only the customer context's own not-found problem body (ApiExceptionHandler: code CUSTOMER_NOT_FOUND) means
     * "no such customer". A bare 404 (an unmapped route, an older customer build without /credit-standing, an
     * ingress or mesh 404) is the customer service being unavailable, so the loan service retries later
     * instead of reading a terminal answer (DevSecOps B-2).
     */
    @Test
    void aBareOrForeign404IsTheCustomerServiceBeingUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-A/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-B/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"NOT_FOUND\",\"message\":\"No static resource\"}"));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-C/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML).body("<html>404 Not Found</html>"));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-D/credit-standing")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":404}"));

        for (String id : new String[] {"CUST-A", "CUST-B", "CUST-C", "CUST-D"}) {
            assertThatThrownBy(() -> client.fetch(id))
                .as(id)
                .isInstanceOf(CustomerServiceUnavailableException.class)
                .hasMessageContaining("404");
        }
    }

    /** The body must be the customer that was asked about; another id (or none) is not a position of that customer (DevSecOps S-3). */
    @Test
    void aBodyNamingAnotherCustomerIsUnavailable() {
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-7/credit-standing")).andRespond(withSuccess("""
            {"customerId":"CUST-8","creditScore":700,"creditLimit":10000.00,"usedCredit":0.00,"availableCredit":10000.00,"currency":"AED"}
            """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v1/customers/CUST-9/credit-standing")).andRespond(withSuccess("""
            {"creditScore":700,"creditLimit":10000.00,"usedCredit":0.00,"availableCredit":10000.00,"currency":"AED"}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetch("CUST-7")).isInstanceOf(CustomerServiceUnavailableException.class)
            .hasMessageContaining("another customer");
        assertThatThrownBy(() -> client.fetch("CUST-9")).isInstanceOf(CustomerServiceUnavailableException.class);
    }
}
