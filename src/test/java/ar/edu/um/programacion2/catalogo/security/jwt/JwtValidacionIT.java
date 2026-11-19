package ar.edu.um.programacion2.catalogo.security.jwt;

import static ar.edu.um.programacion2.catalogo.security.AuthoritiesConstants.USER;
import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.*;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Validación del JWT de usuario final emitido por turnos-service (ADR 0003).
 * <p>
 * Complementa a {@link TokenAuthenticationIT}, que ya cubre la firma con otra clave y el token
 * vencido: acá se prueban un usuario común ({@code ROLE_USER}) y la ausencia de token.
 * Usa {@link TestAuthenticationResource} ({@code GET /api/authenticate}), que existe solo en tests.
 */
@AutoConfigureMockMvc
@AuthenticationIntegrationTest
class JwtValidacionIT {

    private static final String ENDPOINT_PROTEGIDO = "/api/authenticate";

    @Autowired
    private MockMvc mvc;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @Test
    void tokenValidoConRoleUserPasaLaSeguridad() throws Exception {
        String token = createValidTokenForUserWithAuthorities(jwtKey, "usuario", List.of(USER));

        mvc
            .perform(MockMvcRequestBuilders.get(ENDPOINT_PROTEGIDO).header(AUTHORIZATION, BEARER + token))
            .andExpect(status().isNoContent());
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(ENDPOINT_PROTEGIDO)).andExpect(status().isUnauthorized());
    }

    @Test
    void unTokenDeUsuarioSinExpResponde401() throws Exception {
        // El validador por defecto lo dejaba pasar: un token sin exp no vence nunca (ADR 0007).
        String token = createSignedToken(jwtKey, "usuario", claims -> claims.claim(AUTHORITIES_CLAIM, USER).claims(c -> c.remove("exp")));

        mvc
            .perform(MockMvcRequestBuilders.get(ENDPOINT_PROTEGIDO).header(AUTHORIZATION, BEARER + token))
            .andExpect(status().isUnauthorized());
    }
}
