package com.bank.risk.infrastructure.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which services may ask for and read risk decisions. The SERVICE realm role
 * is held by every client-credentials client in the realm, so on its own it
 * would let any service claim transaction ids here; the token's authorized
 * party (azp, the calling client id) must also be on this list. Used from @PreAuthorize as
 * {@code @serviceCallers.allowed(authentication)}; a second instance,
 * {@code @eligibilityCallers}, lists the loan services that may ask for loan
 * eligibility. Both are beans of {@link SecurityConfiguration}.
 */
public class ServiceCallerPolicy {

    private final Set<String> allowedClients;

    /** A null, blank or comma-only list admits no service: the lists fail closed. */
    public ServiceCallerPolicy(String allowedClients) {
        this.allowedClients = Arrays.stream((allowedClients == null ? "" : allowedClients).split(","))
            .map(String::trim)
            .filter(client -> !client.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allowed(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Object azp = token.getToken().getClaims().get("azp");
        return azp != null && allowedClients.contains(azp.toString());
    }
}
