package org.skala.capstone;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(); }

    @Bean SecretKeySpec jwtKey(@Value("${capstone.jwt-secret}") String secret) {
        if (secret.length() < 32 || secret.contains("__GENERATE_"))
            throw new IllegalArgumentException("Configure JWT_SECRET with at least 32 characters");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean JwtEncoder jwtEncoder(SecretKeySpec key) {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(key));
    }

    @Bean JwtDecoder jwtDecoder(SecretKeySpec key, JdbcTemplate jdbc) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer("beauty-capstone"),
            jwt -> {
                try {
                    Integer count = jdbc.queryForObject(
                        "SELECT count(*) FROM app.sessions WHERE id=CAST(? AS uuid) AND expires_at>now()",
                        Integer.class, jwt.getId());
                    if (count != null && count == 1) return OAuth2TokenValidatorResult.success();
                } catch (Exception ignored) { }
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }
        ));
        return decoder;
    }

    @Bean @Order(1)
    SecurityFilterChain internal(HttpSecurity http) throws Exception {
        return http.securityMatcher("/internal/**", "/actuator/**", "/api/dev/**", "/api/reviews")
            .csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).build();
    }

    @Bean @Order(2)
    SecurityFilterChain application(HttpSecurity http) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> List.of(
            new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role").toUpperCase())));
        return http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/v1/users/signup", "/api/v1/users/login").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
}
