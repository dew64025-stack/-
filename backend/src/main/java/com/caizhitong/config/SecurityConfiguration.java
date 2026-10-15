package com.caizhitong.config;

import com.caizhitong.common.JsonSecurityErrors;
import com.caizhitong.common.SensitiveDataCrypto;
import com.caizhitong.common.idempotency.IdempotencyFilter;
import com.caizhitong.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import tools.jackson.databind.json.JsonMapper;

@Configuration @EnableMethodSecurity
public class SecurityConfiguration {
    @Bean @Order(0) public SecurityFilterChain uploadSecurity(HttpSecurity http, JsonSecurityErrors errors,
            com.caizhitong.material.files.UploadService uploads,JsonMapper mapper) throws Exception {
        return http.securityMatcher(request -> RoutePolicy.binaryUpload(request.getMethod(),request.getRequestURI()))
                .csrf(config -> config.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterBefore(new com.caizhitong.material.files.UploadCredentialFilter(uploads,new FilterErrors(mapper)),AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication,context) ->
                        new AuthorizationDecision(authentication.get().getPrincipal() instanceof com.caizhitong.material.files.UploadClaim)))
                .build();
    }
    // Task 5（负责人A）：/jobs/{jobId} 收据按文档第 5 节同时支持学生所有者与同校园管理员。
    // 与 adminSecurity 同为 @Order(1)：两者 matcher 互不相交（/admin/** 与 /jobs/**），谁先匹配都只会命中一条。
    @Bean @Order(1) public SecurityFilterChain jobSecurity(HttpSecurity http, JsonSecurityErrors errors,
            SessionTokenService sessions, JsonMapper mapper, SensitiveDataCrypto crypto) throws Exception {
        return http.securityMatcher(request -> request.getRequestURI().matches("/api/v1/jobs/[0-9]+"))
                .csrf(config -> config.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterBefore(new BearerSessionFilter(sessions, new SessionKind[]{SessionKind.STUDENT, SessionKind.ADMIN},
                        new FilterErrors(mapper)), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().access((authentication, context) -> {
                    Object principal = authentication.get().getPrincipal();
                    return new AuthorizationDecision(principal instanceof Actor actor && "active".equals(actor.status()));
                }))
                .build();
    }
    @Bean @Order(1) public SecurityFilterChain adminSecurity(HttpSecurity http, JsonSecurityErrors errors,
            SessionTokenService sessions, JsonMapper mapper, SensitiveDataCrypto crypto) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setCookieName("ADMIN-XSRF-TOKEN"); csrf.setHeaderName("X-ADMIN-CSRF"); csrf.setCookiePath("/api/v1/admin");
        csrf.setCookieCustomizer(cookie -> cookie.secure(true).httpOnly(true).sameSite("Strict"));
        http.securityMatcher("/api/v1/admin/**")
                .csrf(config -> config.csrfTokenRepository(csrf))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/auth/csrf","/api/v1/admin/auth/login","/api/v1/admin/auth/mfa/verify","/api/v1/admin/auth/token/refresh").permitAll()
                        .anyRequest().access((authentication, context) -> {
                            Object principal = authentication.get().getPrincipal();
                            return new AuthorizationDecision(principal instanceof Actor actor && actor.kind() == SessionKind.ADMIN && "active".equals(actor.status()));
                        }));
        return shared(http, errors, sessions, SessionKind.ADMIN, mapper, crypto).build();
    }
    @Bean @Order(2) public SecurityFilterChain studentSecurity(HttpSecurity http, JsonSecurityErrors errors,
            SessionTokenService sessions, JsonMapper mapper, SensitiveDataCrypto crypto) throws Exception {
        http.securityMatcher("/api/v1/**")
                .csrf(config -> config.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(request -> RoutePolicy.binaryUpload(request.getMethod(), request.getRequestURI())).denyAll()
                        .requestMatchers(request -> RoutePolicy.publicRequest(request.getMethod(), request.getRequestURI())).permitAll()
                        .anyRequest().access((authentication, context) -> {
                            Object principal = authentication.get().getPrincipal();
                            var request = context.getRequest();
                            return new AuthorizationDecision(principal instanceof Actor actor && actor.kind() == SessionKind.STUDENT
                                    && ("active".equals(actor.status()) || RoutePolicy.ownRights(request.getMethod(), request.getRequestURI())));
                        }));
        return shared(http, errors, sessions, SessionKind.STUDENT, mapper, crypto).build();
    }
    @Bean @Order(3) public SecurityFilterChain securityFilterChain(HttpSecurity http, JsonSecurityErrors errors) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .build();
    }
    private HttpSecurity shared(HttpSecurity http, JsonSecurityErrors errors, SessionTokenService sessions, SessionKind kind,
            JsonMapper mapper, SensitiveDataCrypto crypto) {
        var filterErrors = new FilterErrors(mapper);
        return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterBefore(new BearerSessionFilter(sessions, kind, filterErrors), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new IdempotencyFilter(mapper, crypto, filterErrors), AuthorizationFilter.class);
    }
}
