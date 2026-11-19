package ar.edu.um.programacion2.catalogo.config;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.JWT_ALGORITHM;

import ar.edu.um.programacion2.catalogo.management.SecurityMetersService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class SecurityJwtConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityJwtConfiguration.class);

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Bean
    public JwtDecoder jwtDecoder(SecurityMetersService metersService) {
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(getSecretKey()).macAlgorithm(JWT_ALGORITHM).build();
        jwtDecoder.setJwtValidator(validators());
        return token -> {
            try {
                return jwtDecoder.decode(token);
            } catch (Exception e) {
                if (e.getMessage().contains("Invalid signature")) {
                    metersService.trackTokenInvalidSignature();
                } else if (e.getMessage().contains("Jwt expired at") || e.getMessage().contains(EXPIRY_REQUIRED)) {
                    metersService.trackTokenExpired();
                } else if (
                    e.getMessage().contains("Invalid JWT serialization") ||
                    e.getMessage().contains("Malformed token") ||
                    e.getMessage().contains("Invalid unsecured/JWS/JWE")
                ) {
                    metersService.trackTokenMalformed();
                } else {
                    LOG.error("Unknown JWT error {}", e.getMessage());
                }
                throw e;
            }
        };
    }

    /** Texto del error del validador de {@code exp}: lo usa el contador de tokens vencidos. */
    static final String EXPIRY_REQUIRED = "The exp claim is not valid";

    /**
     * Los validadores por defecto (vigencia con 60 s de tolerancia, {@code JwtValidators.createDefault()}) más uno que
     * <strong>exige {@code exp}</strong>: el validador por defecto deja pasar un token sin {@code exp} (medido en el
     * #35), que no vencería nunca. El enunciado (§9) pide validar la vigencia de todo token, y un token sin {@code exp}
     * no tiene vigencia que validar: responde {@code 401} como uno vencido. Vale para los tokens de usuario y para el de
     * servicio (ADR 0007).
     * <p>
     * No se validan acá {@code aud} ni {@code iss}: son reglas de autorización del endpoint de agenda (DP-06), que dan
     * {@code 403}. Validadas en el decoder darían {@code 401} y rechazarían los tokens de usuario, que no los traen.
     */
    static OAuth2TokenValidator<Jwt> validators() {
        return new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull)
        );
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey()));
    }

    private SecretKey getSecretKey() {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }
}
