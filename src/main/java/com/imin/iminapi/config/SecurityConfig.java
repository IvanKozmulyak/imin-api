package com.imin.iminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imin.iminapi.security.ApiError;
import com.imin.iminapi.security.BearerTokenAuthFilter;
import com.imin.iminapi.security.ErrorCode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Standalone mapper for auth-error responses — avoids circular dependency with Jackson auto-config. */
    private static final ObjectMapper AUTH_MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   BearerTokenAuthFilter bearerFilter) throws Exception {
        http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Public reads for legacy poster pipeline
                        .requestMatchers("/", "/index.html", "/images/**").permitAll()
                        .requestMatchers("/api/events/**").permitAll()
                        .requestMatchers("/api/posters/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // V1 auth endpoints are public — login etc.
                        .requestMatchers("/api/v1/auth/signup",
                                         "/api/v1/auth/login",
                                         "/api/v1/auth/logout").permitAll()
                        // Everything else under /api/v1 requires a session
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint((req, resp, ex) -> {
                            ErrorCode errorCode = (ErrorCode) req.getAttribute("imin.authErrorCode");
                            if (errorCode == null) errorCode = ErrorCode.AUTH_MISSING;
                            String message = errorCode == ErrorCode.AUTH_TOKEN_EXPIRED
                                    ? "Authentication token has expired" : "Authentication required";
                            resp.setStatus(401);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.setCharacterEncoding("UTF-8");
                            AUTH_MAPPER.writeValue(resp.getWriter(), ApiError.of(errorCode, message));
                            resp.getWriter().flush();
                        })
                        .accessDeniedHandler((req, resp, ex) -> {
                            resp.setStatus(403);
                            resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            resp.setCharacterEncoding("UTF-8");
                            AUTH_MAPPER.writeValue(resp.getWriter(),
                                    ApiError.of(ErrorCode.FORBIDDEN, "Access denied"));
                            resp.getWriter().flush();
                        })
                );
        return http.build();
    }
}
