package com.bank.risk.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which services may ask for and read risk decisions. The SERVICE realm role
 * is held by every client-credentials client in the realm, so on its own it
 * would let any service claim transaction ids here; the token's authorized
 * party (azp, the calling client id) must also be on this list. Used from @PreAuthorize as
 * {@code @serviceCallers.allowed(authentication)}.
 */
@Component("serviceCallers")
public class ServiceCallerPolicy {

    private final Set<String> allowedClients;

    public ServiceCallerPolicy(@Value("${fintechbankx.security.service-callers}") String allowedClients) {
        this.allowedClients = Arrays.stream(allowedClients.split(","))
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
