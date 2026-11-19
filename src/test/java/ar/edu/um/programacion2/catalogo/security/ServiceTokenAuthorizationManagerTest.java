package ar.edu.um.programacion2.catalogo.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Cada condición de la regla de los endpoints de servicio, con un reloj fijo y sin contexto (ADR 0007). */
class ServiceTokenAuthorizationManagerTest {

    private static final Instant AHORA = Instant.parse("2026-11-09T13:19:00Z");

    private final ServiceTokenAuthorizationManager regla = new ServiceTokenAuthorizationManager(Clock.fixed(AHORA, ZoneOffset.UTC));

    @Test
    void elTokenDeServicioDeTurnosPasa() {
        assertThat(regla.isServiceToken(token(jwt -> {}, "ROLE_SERVICE"))).isTrue();
    }

    @Test
    void unAudConVariosValoresQueIncluyeAlCatalogoPasa() {
        assertThat(regla.isServiceToken(token(jwt -> jwt.audience(List.of("otro-servicio", "catalogo-service")), "ROLE_SERVICE"))).isTrue();
    }

    @Test
    void cincoMinutosJustosPasa() {
        assertThat(regla.isServiceToken(token(jwt -> jwt.issuedAt(AHORA).expiresAt(AHORA.plus(Duration.ofMinutes(5))), "ROLE_SERVICE"))).isTrue();
    }

    @Test
    void unIatHastaSesentaSegundosEnElFuturoPasa() {
        // La misma tolerancia que el decoder le da a exp: los relojes de los dos servicios no están sincronizados.
        Instant iat = AHORA.plusSeconds(60);
        assertThat(regla.isServiceToken(token(jwt -> jwt.issuedAt(iat).expiresAt(iat.plusSeconds(300)), "ROLE_SERVICE"))).isTrue();
    }

    static Stream<Arguments> tokensRechazados() {
        return Stream.of(
            Arguments.of("sin aud", ajuste(jwt -> jwt.claims(c -> c.remove("aud"))), new String[] { "ROLE_SERVICE" }),
            Arguments.of("con otro aud", ajuste(jwt -> jwt.audience(List.of("turnos-service"))), new String[] { "ROLE_SERVICE" }),
            Arguments.of("sin iss", ajuste(jwt -> jwt.claims(c -> c.remove("iss"))), new String[] { "ROLE_SERVICE" }),
            Arguments.of("con otro iss", ajuste(jwt -> jwt.issuer("otro-servicio")), new String[] { "ROLE_SERVICE" }),
            Arguments.of("sin ROLE_SERVICE", ajuste(jwt -> {}), new String[] { "ROLE_OTRO" }),
            Arguments.of("sin roles", ajuste(jwt -> {}), new String[] {}),
            Arguments.of("con ROLE_SERVICE y ROLE_USER", ajuste(jwt -> {}), new String[] { "ROLE_SERVICE", "ROLE_USER" }),
            Arguments.of("con ROLE_SERVICE y ROLE_ADMIN", ajuste(jwt -> {}), new String[] { "ROLE_SERVICE", "ROLE_ADMIN" }),
            Arguments.of("sin iat", ajuste(jwt -> jwt.claims(c -> c.remove("iat"))), new String[] { "ROLE_SERVICE" }),
            Arguments.of(
                "con más de 5 min de vida",
                ajuste(jwt -> jwt.issuedAt(AHORA).expiresAt(AHORA.plus(Duration.ofMinutes(5)).plusSeconds(1))),
                new String[] { "ROLE_SERVICE" }
            ),
            Arguments.of(
                "de un año",
                ajuste(jwt -> jwt.issuedAt(AHORA).expiresAt(AHORA.plus(Duration.ofDays(365)))),
                new String[] { "ROLE_SERVICE" }
            ),
            Arguments.of(
                "con iat dentro de un año y 5 min de vida",
                ajuste(jwt -> jwt.issuedAt(AHORA.plus(Duration.ofDays(365))).expiresAt(AHORA.plus(Duration.ofDays(365)).plusSeconds(300))),
                new String[] { "ROLE_SERVICE" }
            ),
            Arguments.of(
                "con iat 61 s en el futuro",
                ajuste(jwt -> jwt.issuedAt(AHORA.plusSeconds(61)).expiresAt(AHORA.plusSeconds(61 + 300))),
                new String[] { "ROLE_SERVICE" }
            )
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensRechazados")
    void unTokenQueNoCumpleAlgunaCondicionNoPasa(String caso, Consumer<Jwt.Builder> ajuste, String[] roles) {
        assertThat(regla.isServiceToken(token(ajuste, roles))).isFalse();
    }

    @Test
    void sinAutenticarOConOtraClaseDeAutenticacionNoPasa() {
        assertThat(regla.isServiceToken(null)).isFalse();
        assertThat(regla.isServiceToken(new TestingAuthenticationToken("turnos-service", null, "ROLE_SERVICE"))).isFalse();
    }

    private static Consumer<Jwt.Builder> ajuste(Consumer<Jwt.Builder> ajuste) {
        return ajuste;
    }

    /** El token de servicio de turnos, ya decodificado, con los roles que se le pasen y el ajuste al final. */
    private static JwtAuthenticationToken token(Consumer<Jwt.Builder> ajuste, String... roles) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
            .header("alg", "HS512")
            .subject("turnos-service")
            .issuer("turnos-service")
            .audience(List.of("catalogo-service"))
            .issuedAt(AHORA)
            .expiresAt(AHORA.plusSeconds(300));
        ajuste.accept(jwt);
        return new JwtAuthenticationToken(jwt.build(), Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }
}
