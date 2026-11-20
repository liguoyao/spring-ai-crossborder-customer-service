package com.omnimerchant.config;

import com.omnimerchant.agent.a2a.A2aIdentity;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Configuration
public class A2aSecurityConfig {
    @Bean
    @Order(1)
    SecurityFilterChain a2aSecurity(HttpSecurity http, A2aIdentity identity) throws Exception {
        return http.securityMatcher("/api/a2a/**").csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("A2A_SERVICE"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> response.sendError(401)))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
                    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                        A2aIdentity.Scope scope;
                        try {
                            var segments = request.getRequestURI().split("/");
                            if (segments.length < 4) throw new SecurityException("Missing agent");
                            scope = identity.verify(request.getHeader("Authorization"), segments[3]);
                        } catch (Exception invalid) { response.sendError(401, "A2A service identity required"); return; }
                        request.setAttribute("a2a.scope", scope);
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(new UsernamePasswordAuthenticationToken(scope, null, List.of(new SimpleGrantedAuthority("A2A_SERVICE"))));
                        SecurityContextHolder.setContext(context);
                        try { chain.doFilter(request, response); }
                        finally { SecurityContextHolder.clearContext(); }
                    }
                }, UsernamePasswordAuthenticationFilter.class).build();
    }
}
