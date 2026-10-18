package com.bank.risk.infrastructure.web;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who stated the risk facts of a request, recorded as attested_by with the
 * decision: a SERVICE-role caller is its client id (token azp), anyone else
 * (staff) is the token subject.
 */
final class CallerAttestation {

    private CallerAttestation() {
    }

    static String attestedBy(Authentication caller) {
        if (caller == null) {
            throw new AuthenticationCredentialsNotFoundException("An authenticated caller is required");
        }
        if (caller instanceof JwtAuthenticationToken token) {
            Object azp = token.getToken().getClaims().get("azp");
            boolean service = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE".equals(authority.getAuthority()));
            if (service && azp != null && !azp.toString().isBlank()) {
                return azp.toString();
            }
            return token.getToken().getSubject();
        }
        return caller.getName();
    }
}
