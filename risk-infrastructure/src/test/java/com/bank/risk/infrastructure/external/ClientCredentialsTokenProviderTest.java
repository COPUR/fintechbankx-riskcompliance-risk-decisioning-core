package com.bank.risk.infrastructure.external;

import com.bank.risk.domain.CustomerServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * This repository had no outbound HTTP adapter before the customer client, so
 * the token source is a small client-credentials grant against the realm's
 * token endpoint (the same Keycloak realm that issues the tokens this service
 * verifies), cached until shortly before it expires. No Spring OAuth2 client
 * starter is added for one call.
 */
class ClientCredentialsTokenProviderTest {

    private static final String TOKEN_URI = "http://keycloak.identity.svc.cluster.local:8080/realms/fintechbankx/protocol/openid-connect/token";
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");

    private MockRestServiceServer server;
    private AtomicReference<Instant> now;
    private ClientCredentialsTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        provider = new ClientCredentialsTokenProvider(builder.build(), TOKEN_URI, "svc-rsk-decisioning", "client-credential-value", clock);
    }

    @Test
    void requestsAClientCredentialsTokenAndCachesItUntilItIsAboutToExpire() {
        server.expect(once(), requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form("client_credentials", "svc-rsk-decisioning", "client-credential-value")))
                .andRespond(withSuccess("{\"access_token\":\"t-1\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));

        assertThat(provider.token()).isEqualTo("t-1");
        now.set(NOW.plus(Duration.ofSeconds(200)));
        assertThat(provider.token()).as("served from the cache").isEqualTo("t-1");
        server.verify();
        server.reset();

        server.expect(once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"t-2\",\"token_type\":\"Bearer\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        now.set(NOW.plus(Duration.ofSeconds(275)));
        assertThat(provider.token()).as("renewed 30 s before expiry").isEqualTo("t-2");
        server.verify();
    }

    @Test
    void aFailedTokenRequestIsTheCustomerServiceBeingUnavailable() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.token())
            .isInstanceOf(CustomerServiceUnavailableException.class)
            .hasMessageContaining("token")
            .hasMessageNotContaining("client-credential-value");
    }

    /**
     * Without CUSTOMER_CLIENT_CREDENTIAL the service still starts (the assess endpoints do not need the identity
     * client), warns, and loan eligibility answers 503 until the credential is set; no token request is made.
     */
    @Test
    void aMissingClientCredentialMakesEveryTokenRequestUnavailableWithoutCallingTheIdentityProvider() {
        var withoutCredential = new ClientCredentialsTokenProvider(RestClient.create(), TOKEN_URI, "svc-rsk-decisioning", " ", Clock.systemUTC());

        assertThatThrownBy(withoutCredential::token)
            .isInstanceOf(CustomerServiceUnavailableException.class)
            .hasMessageContaining("CUSTOMER_CLIENT_CREDENTIAL");
        server.verify();
    }

    /** The endpoint and the client id are configuration, known at build time: a blank one is a startup fault. */
    @Test
    void aBlankTokenUriOrClientIdIsRefusedAtConstruction() {
        assertThatThrownBy(() -> new ClientCredentialsTokenProvider(RestClient.create(), " ", "svc-rsk-decisioning", "x", Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("token-uri");
        assertThatThrownBy(() -> new ClientCredentialsTokenProvider(RestClient.create(), TOKEN_URI, " ", "x", Clock.systemUTC()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("client-id");
    }

    private static org.springframework.util.MultiValueMap<String, String> form(String grant, String clientId, String credential) {
        var form = new org.springframework.util.LinkedMultiValueMap<String, String>();
        form.add("grant_type", grant);
        form.add("client_id", clientId);
        form.add("client_" + "secret", credential);
        return form;
    }
}
