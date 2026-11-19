package ar.edu.um.programacion2.catalogo.security.jwt;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Regla de autorización de {@code /api/**} (DP-06): pasan los tokens de usuario final con {@code ROLE_USER} o
 * {@code ROLE_ADMIN}; el token técnico de servicio y un token sin roles reciben 403.
 * <p>
 * Los tokens tienen la forma que emite turnos (ADR 0003 de turnos): {@code auth} es un texto con los roles separados por
 * espacio, no una lista. El de servicio lleva además {@code aud}, {@code iss} y 5 minutos de vigencia (DPT-16). Usa
 * {@link TestAuthenticationResource} ({@code GET /api/authenticate}), que existe solo en tests.
 */
@AutoConfigureMockMvc
@AuthenticationIntegrationTest
class AutorizacionPorRolIT {

    private static final String ENDPOINT_DE_USUARIO = "/api/authenticate";

    @Autowired
    private MockMvc mvc;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_USER", "ROLE_USER ROLE_ADMIN", "ROLE_ADMIN" })
    void unUsuarioDeTurnosPasa(String roles) throws Exception {
        String token = createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, roles).claim("userId", 7));

        pedir(token, status().isNoContent());
    }

    @Test
    void elTokenTecnicoDeServicioRecibe403() throws Exception {
        String token = createSignedToken(jwtKey, "turnos-service", claims ->
            claims
                .claim(AUTHORITIES_CLAIM, "ROLE_SERVICE")
                .audience(List.of("catalogo-service"))
                .issuer("turnos-service")
                .expiresAt(Instant.now().plusSeconds(300))
        );

        pedir(token, status().isForbidden());
    }

    @Test
    void unTokenSinRolesRecibe403() throws Exception {
        pedir(createSignedToken(jwtKey, "ana.perez", claims -> {}), status().isForbidden());
    }

    @Test
    void unTokenConUnRolDesconocidoRecibe403() throws Exception {
        pedir(createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, "ROLE_OTRO")), status().isForbidden());
    }

    private void pedir(String token, ResultMatcher esperado) throws Exception {
        mvc.perform(get(ENDPOINT_DE_USUARIO).header(AUTHORIZATION, BEARER + token)).andExpect(esperado);
    }
}
