package com.stockrush.promotion.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = CorrelationIds.resolve(request.getHeader(CorrelationIds.HEADER_NAME));
        response.setHeader(CorrelationIds.HEADER_NAME, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(new CorrelationHeaderRequest(request, correlationId), response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean isCorrelationHeader(String name) {
        return CorrelationIds.HEADER_NAME.equalsIgnoreCase(name);
    }

    private static final class CorrelationHeaderRequest extends HttpServletRequestWrapper {

        private final String correlationId;

        private CorrelationHeaderRequest(HttpServletRequest request, String correlationId) {
            super(request);
            this.correlationId = correlationId;
        }

        @Override
        public String getHeader(String name) {
            if (isCorrelationHeader(name)) {
                return correlationId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (isCorrelationHeader(name)) {
                return Collections.enumeration(List.of(correlationId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> headerNames = new LinkedHashSet<>();
            Enumeration<String> names = super.getHeaderNames();
            while (names.hasMoreElements()) {
                String headerName = names.nextElement();
                headerNames.add(isCorrelationHeader(headerName) ? CorrelationIds.HEADER_NAME : headerName);
            }
            headerNames.add(CorrelationIds.HEADER_NAME);
            return Collections.enumeration(headerNames);
        }
    }
}
