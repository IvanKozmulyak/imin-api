package com.imin.iminapi.security;

import com.imin.iminapi.model.AuthSession;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.AuthSessionRepository;
import com.imin.iminapi.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class BearerTokenAuthFilter extends OncePerRequestFilter {

    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final TokenService tokens;

    public BearerTokenAuthFilter(AuthSessionRepository sessions, UserRepository users, TokenService tokens) {
        this.sessions = sessions;
        this.users = users;
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring("Bearer ".length()).trim();
        String hash = tokens.hashOf(raw);
        Optional<AuthSession> maybe = sessions.findByTokenHashAndRevokedAtIsNull(hash);
        if (maybe.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        AuthSession s = maybe.get();
        if (s.getExpiresAt().isBefore(Instant.now())) {
            request.setAttribute("imin.authErrorCode", ErrorCode.AUTH_TOKEN_EXPIRED);
            chain.doFilter(request, response);
            return;
        }
        Optional<User> user = users.findById(s.getUserId());
        if (user.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        AuthPrincipal principal = new AuthPrincipal(user.get().getId(), user.get().getOrgId(), user.get().getRole(), s.getId());
        AbstractAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.get().getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(auth);
        chain.doFilter(request, response);
    }
}
