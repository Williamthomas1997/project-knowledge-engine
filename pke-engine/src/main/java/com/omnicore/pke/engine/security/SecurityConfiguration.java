package com.omnicore.pke.engine.security;

import com.omnicore.pke.engine.graph.AuditStore;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * REST, MCP and actuator security (ADR-017): an API key in {@code Authorization: Bearer <key>} (or
 * {@code X-API-Key}), roles per endpoint, and an audit event for every mutating {@code /api} call. Stateless: no
 * sessions, no CSRF (no browser clients, no cookies).
 * <ul>
 *   <li>READER: every GET, plus the read-only POSTs (search, context, templates, log explanation, validation);</li>
 *   <li>SCHEMA_ADMIN: schema registration, restore and data migrations;</li>
 *   <li>CONTRIBUTOR: every other mutating {@code /api} call;</li>
 *   <li>{@code /mcp}: READER; tools that change something check CONTRIBUTOR themselves;</li>
 *   <li>{@code /actuator/health}: open, details only for READER; other actuator endpoints READER.</li>
 * </ul>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    static final String[] READ_ONLY_POSTS = {"/api/retrieval/search", "/api/context", "/api/retrieval/templates/*/run",
            "/api/logs/explain", "/api/schema/validate"};
    static final String[] SCHEMA_ADMIN_POSTS = {"/api/history/restore", "/api/schema/migrations",
            "/api/schema/entity-types", "/api/schema/relationship-types"};

    @Bean
    SecurityFilterChain pkeSecurity(HttpSecurity http, ApiKeyService keys, AuditStore audit) throws Exception {
        http.csrf(c -> c.disable())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyFilter(keys), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new AuditFilter(audit), ApiKeyFilter.class)
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole(Role.READER.name())
                        .requestMatchers(HttpMethod.POST, SCHEMA_ADMIN_POSTS).hasRole(Role.SCHEMA_ADMIN.name())
                        .requestMatchers(HttpMethod.POST, READ_ONLY_POSTS).hasRole(Role.READER.name())
                        .requestMatchers(HttpMethod.GET, "/api/**").hasRole(Role.READER.name())
                        .requestMatchers("/api/**").hasRole(Role.CONTRIBUTOR.name())
                        .requestMatchers("/mcp", "/mcp/**").hasRole(Role.READER.name())
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** Authenticates the API key, if one is sent. A missing or unknown key leaves the request anonymous (401 later). */
    static final class ApiKeyFilter extends OncePerRequestFilter {

        private final ApiKeyService keys;

        ApiKeyFilter(ApiKeyService keys) {
            this.keys = keys;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            keyOf(request).flatMap(keys::authenticate).ifPresent(caller -> {
                List<SimpleGrantedAuthority> authorities = caller.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
                SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null, authorities));
                SecurityContextHolder.getContextHolderStrategy().setContext(context);
            });
            chain.doFilter(request, response);
        }

        static Optional<String> keyOf(HttpServletRequest request) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Optional.of(authorization.substring(7).strip());
            }
            return Optional.ofNullable(request.getHeader("X-API-Key")).map(String::strip);
        }
    }

    /** One {@code :AuditEvent} per mutating {@code /api} call, with the caller and the final status. */
    static final class AuditFilter extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(AuditFilter.class);
        private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

        private final AuditStore audit;

        AuditFilter(AuditStore audit) {
            this.audit = audit;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return READS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String outcome = null;
            try {
                chain.doFilter(request, response);
                outcome = String.valueOf(response.getStatus());
            } catch (IOException | ServletException | RuntimeException e) {
                outcome = "ERROR";
                throw e;
            } finally {
                Optional<Caller> caller = Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                        .map(a -> a.getPrincipal() instanceof Caller c ? c : null);
                try {
                    audit.record(caller.map(Caller::actor).orElse("anonymous"),
                            caller.map(c -> c.roles().stream().map(Role::name).sorted().toList()).orElse(List.of()),
                            "REST", request.getMethod() + " " + request.getRequestURI(), outcome);
                } catch (RuntimeException e) {
                    log.warn("Could not record the audit event for {} {}: {}", request.getMethod(),
                            request.getRequestURI(), e.getMessage());
                }
            }
        }
    }
}
