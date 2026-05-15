package com.stockrush.gateway.api;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;

final class TrustedIdentityHeaders {

    static final String SUBJECT = "X-StockRush-Subject";
    static final String EMAIL = "X-StockRush-Email";
    static final String ROLES = "X-StockRush-Roles";
    static final String OPERATOR = "X-Operator-Id";

    private TrustedIdentityHeaders() {
    }

    static HttpHeaders customer(HttpHeaders source, Jwt jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(source);
        headers.remove(SUBJECT);
        headers.remove(EMAIL);
        headers.remove(ROLES);
        headers.remove(OPERATOR);

        headers.set(SUBJECT, jwt.getSubject());
        String email = jwt.getClaimAsString("email");
        if (email != null && !email.isBlank()) {
            headers.set(EMAIL, email.trim());
        }
        headers.set(ROLES, roles(jwt));
        return headers;
    }

    private static String roles(Jwt jwt) {
        return roleValues(jwt).stream()
            .map(role -> role.toUpperCase(Locale.ROOT))
            .collect(Collectors.joining(","));
    }

    private static Set<String> roleValues(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return Set.of();
        }
        Object roles = realmAccess.get("roles");
        if (!(roles instanceof Collection<?> collection)) {
            return Set.of();
        }
        return collection.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .map(String::trim)
            .filter(role -> !role.isBlank())
            .collect(Collectors.toSet());
    }
}
