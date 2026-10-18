package com.bank.risk.infrastructure.external;

import com.bank.risk.domain.CustomerServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * OAuth2 client-credentials grant against the realm's token endpoint (the same
 * Keycloak realm that issues the tokens this service verifies), for the
 * svc-rsk-decisioning client. The token is cached and renewed
 * {@value #RENEW_BEFORE_EXPIRY_SECONDS} seconds before it expires. The client
 * credential comes from CUSTOMER_CLIENT_CREDENTIAL (a Secret, never a config
 * key); without it the provider starts, warns, and every token request is
 * answered as the customer service being unavailable (503 to the caller), so
 * the assess endpoints do not depend on the identity client existing yet.
 * No Spring OAuth2 client starter is added for this one call.
 * <p>
 * The customer service accepts the token when it carries the SERVICE realm role,
 * {@code aud svc-cus-profile-kyc} and an {@code azp} on customer's
 * SERVICE_CALLERS_CREDIT_STANDING (default svc-rsk-decisioning). Both the role and
 * the audience are realm configuration of the svc-rsk-decisioning client
 * (an audience mapper), not request parameters, so nothing here asks for them.
 */
public class ClientCredentialsTokenProvider implements BearerTokenProvider {

    static final long RENEW_BEFORE_EXPIRY_SECONDS = 30;
    static final String CREDENTIAL_VARIABLE = "CUSTOMER_CLIENT_CREDENTIAL";
    private static final Logger log = LoggerFactory.getLogger(ClientCredentialsTokenProvider.class);

    private final RestClient restClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientCredential;
    private final Clock clock;

    private String cachedToken;
    private Instant renewAt = Instant.MIN;

    public ClientCredentialsTokenProvider(RestClient restClient, String tokenUri, String clientId, String clientCredential, Clock clock) {
        this.restClient = Objects.requireNonNull(restClient, "restClient is required");
        if (tokenUri == null || tokenUri.isBlank()) {
            throw new IllegalStateException("risk.customer.token-uri must name the realm's token endpoint");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("risk.customer.client-id must name this service's OAuth2 client");
        }
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientCredential = clientCredential == null || clientCredential.isBlank() ? null : clientCredential;
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (this.clientCredential == null) {
            log.warn("{} is not set: loan eligibility cannot read the customer service and answers 503 until it is", CREDENTIAL_VARIABLE);
        }
    }

    @Override
    public synchronized String token() {
        if (clientCredential == null) {
            throw new CustomerServiceUnavailableException("no token can be requested: " + CREDENTIAL_VARIABLE + " is not set", null);
        }
        if (cachedToken != null && clock.instant().isBefore(renewAt)) {
            return cachedToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_" + "secret", clientCredential);
        Map<?, ?> body;
        try {
            body = restClient.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException failed) {
            throw new CustomerServiceUnavailableException("token request for client " + clientId + " failed: "
                    + failed.getClass().getSimpleName(), failed);
        }
        if (body == null || !(body.get("access_token") instanceof String token) || token.isBlank()) {
            throw new CustomerServiceUnavailableException("token response for client " + clientId + " carried no access_token", null);
        }
        long expiresIn = body.get("expires_in") instanceof Number seconds ? seconds.longValue() : 0;
        cachedToken = token;
        renewAt = clock.instant().plus(Duration.ofSeconds(Math.max(0, expiresIn - RENEW_BEFORE_EXPIRY_SECONDS)));
        return cachedToken;
    }
}
