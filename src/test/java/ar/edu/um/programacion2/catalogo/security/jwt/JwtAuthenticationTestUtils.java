package ar.edu.um.programacion2.catalogo.security.jwt;

import static ar.edu.um.programacion2.catalogo.security.AuthoritiesConstants.ADMIN;
import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.JWT_ALGORITHM;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.util.Base64;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

public class JwtAuthenticationTestUtils {

    public static final String BEARER = "Bearer ";

    @Bean
    private TestAuthenticationResource authenticationResource() {
        return new TestAuthenticationResource();
    }

    @Bean
    private MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    public static String createValidToken(String jwtKey) {
        return createValidTokenForUser(jwtKey, "anonymous");
    }

    public static String createValidTokenForUser(String jwtKey, String user) {
        return createValidTokenForUserWithAuthorities(jwtKey, user, List.of(ADMIN));
    }

    public static String createValidTokenForUserWithAuthorities(String jwtKey, String user, List<String> authorities) {
        JwtEncoder encoder = jwtEncoder(jwtKey);

        var now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuedAt(now)
            .expiresAt(now.plusSeconds(60))
            .subject(user)
            .claims(customClaim -> customClaim.put(AUTHORITIES_CLAIM, authorities))
            .build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    /**
     * Firma con la clave {@code jwtKey} un token con los claims que agregue quien llama, sobre {@code sub}, {@code iat}
     * y {@code exp} (60 segundos). Sirve para armar tokens con la forma exacta que emite turnos.
     */
    public static String createSignedToken(String jwtKey, String user, Consumer<JwtClaimsSet.Builder> claims) {
        var now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder().issuedAt(now).expiresAt(now.plusSeconds(60)).subject(user);
        claims.accept(builder);

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return jwtEncoder(jwtKey).encode(JwtEncoderParameters.from(jwsHeader, builder.build())).getTokenValue();
    }

    /**
     * El JWT técnico de servicio con la forma que emite turnos (DPT-16): {@code sub} y {@code iss} {@code turnos-service},
     * {@code aud} {@code [catalogo-service]}, {@code auth} {@code ROLE_SERVICE} y 5 min de vida. {@code ajustes} corre al
     * final, para cambiar o sacar un claim ({@code claims(c -> c.remove("aud"))}).
     */
    public static String createServiceToken(String jwtKey, Consumer<JwtClaimsSet.Builder> ajustes) {
        return createSignedToken(jwtKey, "turnos-service", claims -> {
            Instant now = Instant.now();
            claims
                .claim(AUTHORITIES_CLAIM, "ROLE_SERVICE")
                .audience(List.of("catalogo-service"))
                .issuer("turnos-service")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));
            ajustes.accept(claims);
        });
    }

    /** El JWT técnico de servicio válido, sin ajustes. */
    public static String createServiceToken(String jwtKey) {
        return createServiceToken(jwtKey, claims -> {});
    }

    public static String createTokenWithDifferentSignature() {
        JwtEncoder encoder = jwtEncoder("Xfd54a45s65fds737b9aafcb3412e07ed99b267f33413274720ddbb7f6c5e64e9f14075f2d7ed041592f0b7657baf8");

        var now = Instant.now();
        var past = now.plusSeconds(60);

        JwtClaimsSet claims = JwtClaimsSet.builder().issuedAt(now).expiresAt(past).subject("anonymous").build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public static String createExpiredToken(String jwtKey) {
        JwtEncoder encoder = jwtEncoder(jwtKey);

        var now = Instant.now();
        var past = now.minusSeconds(600);

        JwtClaimsSet claims = JwtClaimsSet.builder().issuedAt(past).expiresAt(past.plusSeconds(1)).subject("anonymous").build();

        JwsHeader jwsHeader = JwsHeader.with(JWT_ALGORITHM).build();
        return encoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    public static String createInvalidToken(String jwtKey) {
        return createValidToken(jwtKey).substring(1);
    }

    public static String createSignedInvalidJwt(String jwtKey) throws Exception {
        return calculateHMAC("foo", jwtKey);
    }

    private static JwtEncoder jwtEncoder(String jwtKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(getSecretKey(jwtKey)));
    }

    private static SecretKey getSecretKey(String jwtKey) {
        byte[] keyBytes = Base64.from(jwtKey).decode();
        return new SecretKeySpec(keyBytes, 0, keyBytes.length, JWT_ALGORITHM.getName());
    }

    private static String calculateHMAC(String data, String key) throws Exception {
        SecretKeySpec secretKeySpec = new SecretKeySpec(Base64.from(key).decode(), "HmacSHA512");
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(secretKeySpec);
        return String.copyValueOf(Hex.encode(mac.doFinal(data.getBytes())));
    }
}
