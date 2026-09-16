package com.example.demo.stored;

import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.*;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean ReactiveAuthenticationManager sessionAuthenticationManager(AccountService accounts) {
        return authentication ->
            Mono.fromCallable(() -> accounts.authenticate(authentication.getCredentials().toString()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(user -> new UsernamePasswordAuthenticationToken(user, authentication.getCredentials(),
                    List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));
    }

    @Bean SecurityWebFilterChain security(ServerHttpSecurity http, ReactiveAuthenticationManager manager) {
        var filter = new AuthenticationWebFilter(manager);
        filter.setServerAuthenticationConverter(exchange -> {
            String header = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (header == null) return Mono.empty();
            if (!header.startsWith("Bearer ")) return Mono.error(new BadCredentialsException("Bearer required"));
            return Mono.just(new UsernamePasswordAuthenticationToken("session",header.substring(7)));
        });
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        filter.setAuthenticationFailureHandler((exchange,error) -> {
            exchange.getExchange().getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getExchange().getResponse().setComplete();
        });
        return http
            // Only explicit bearer headers authenticate; browser cookies and
            // HTTP Basic cannot authenticate these API endpoints.
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .logout(ServerHttpSecurity.LogoutSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(auth -> auth
                .pathMatchers("/auth/login", "/test-ai/**").permitAll()
                .pathMatchers("/api/admin/**").hasRole("ADMIN")
                .pathMatchers("/auth/**", "/internal/invoice-assistant/**", "/api/invoices/**").authenticated()
                .anyExchange().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((exchange,error)-> { exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED); return exchange.getResponse().setComplete(); })
                .accessDeniedHandler((exchange,error)-> { exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN); return exchange.getResponse().setComplete(); }))
            .addFilterAt(filter,SecurityWebFiltersOrder.AUTHENTICATION).build();
    }
}

