package com.stockrush.gateway.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter
    ) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/internal/ping").permitAll()
                .requestMatchers("/api/admin/**", "/api/read-model/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.GET, "/api/orders/**", "/api/read-model/orders").hasRole("CUSTOMER")
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
            )
            .build();
    }

    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new StockRushJwtGrantedAuthoritiesConverter());
        return converter;
    }

    private static final class StockRushJwtGrantedAuthoritiesConverter
        implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        public Collection<GrantedAuthority> convert(Jwt source) {
            Set<String> roles = new LinkedHashSet<>();
            roles.addAll(source.getClaimAsStringList("roles") == null ? Set.of() : source.getClaimAsStringList("roles"));
            roles.addAll(realmRoles(source));
            roles.addAll(resourceRoles(source));
            return roles.stream()
                .map(SecurityConfig.StockRushJwtGrantedAuthoritiesConverter::toAuthority)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static Collection<String> realmRoles(Jwt source) {
            Map<String, Object> realmAccess = source.getClaimAsMap("realm_access");
            if (realmAccess == null) {
                return Set.of();
            }
            return stringCollection(realmAccess.get("roles"));
        }

        private static Collection<String> resourceRoles(Jwt source) {
            Map<String, Object> resourceAccess = source.getClaimAsMap("resource_access");
            if (resourceAccess == null) {
                return Set.of();
            }

            return resourceAccess.values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(clientAccess -> clientAccess.get("roles"))
                .map(SecurityConfig.StockRushJwtGrantedAuthoritiesConverter::stringCollection)
                .flatMap(Collection::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static Collection<String> stringCollection(Object value) {
            if (!(value instanceof Collection<?> collection)) {
                return Set.of();
            }
            return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(role -> !role.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        private static String toAuthority(String role) {
            String normalized = role.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("ROLE_")) {
                return normalized;
            }
            return "ROLE_" + normalized;
        }
    }
}
