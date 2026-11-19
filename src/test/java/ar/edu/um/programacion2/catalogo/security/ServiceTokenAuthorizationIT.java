package ar.edu.um.programacion2.catalogo.security;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createExpiredToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createServiceToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createTokenWithDifferentSignature;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La regla de los endpoints de servicio (DP-06, ADR 0007) y la de los de usuario frente al token de servicio, con el
 * contexto completo: la seguridad, el decoder y el conversor que lee los roles del claim {@code auth}, como en la
 * aplicación real.
 * <p>
 * Los endpoints de servicio se prueban contra {@link EndpointDeServicioDePrueba} ({@code GET /api/service/prueba}), que
 * existe solo en este contexto: es una clase anidada en un test, así que el escaneo de componentes no la ve
 * ({@code TestTypeExcludeFilter}), y la trae el {@code @Import}. Una clase de primer nivel con {@code @RestController}
 * en el paquete base, en cambio, entraría en todos los contextos.
 */
@AutoConfigureMockMvc
@IntegrationTest
@Import(ServiceTokenAuthorizationIT.EndpointDeServicioDePrueba.class)
class ServiceTokenAuthorizationIT {

    static final String RUTA_DE_SERVICIO = "/api/service/prueba";

    /** La agenda de CU-07, con la base vacía: si pasa la seguridad, responde 503. */
    static final String RUTA_DE_LA_AGENDA = "/api/service/professionals/15/agenda";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    void baseVacia() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void elTokenDeServicioDeTurnosPasa() throws Exception {
        pedir(get(RUTA_DE_SERVICIO), createServiceToken(jwtKey)).andExpect(status().isNoContent());
        pedir(get(RUTA_DE_LA_AGENDA), createServiceToken(jwtKey)).andExpect(status().isServiceUnavailable());
    }

    @ParameterizedTest
    @ValueSource(strings = { RUTA_DE_SERVICIO, RUTA_DE_LA_AGENDA })
    void sinTokenResponde401(String ruta) throws Exception {
        mvc.perform(get(ruta)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = { RUTA_DE_SERVICIO, RUTA_DE_LA_AGENDA })
    void conUnTokenVencidoResponde401(String ruta) throws Exception {
        pedir(get(ruta), createExpiredToken(jwtKey)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = { RUTA_DE_SERVICIO, RUTA_DE_LA_AGENDA })
    void conLaFirmaInvalidaResponde401(String ruta) throws Exception {
        pedir(get(ruta), createTokenWithDifferentSignature()).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = { RUTA_DE_SERVICIO, RUTA_DE_LA_AGENDA })
    void unTokenDeServicioSinExpResponde401(String ruta) throws Exception {
        // Sin exp no hay vigencia que validar: lo rechaza el decoder, como a uno vencido, antes de la regla.
        pedir(get(ruta), createServiceToken(jwtKey, claims -> claims.claims(c -> c.remove("exp")))).andExpect(status().isUnauthorized());
    }

    static Stream<Arguments> tokensConFirmaValidaQueNoSonDeServicio() {
        return Stream.of(
            Arguments.of("de usuario con ROLE_USER", usuario("ROLE_USER")),
            Arguments.of("de usuario con ROLE_ADMIN", usuario("ROLE_ADMIN")),
            Arguments.of("de servicio sin aud", servicio(claims -> claims.claims(c -> c.remove("aud")))),
            Arguments.of("de servicio con otro aud", servicio(claims -> claims.audience(List.of("turnos-service")))),
            Arguments.of("de servicio sin iss", servicio(claims -> claims.claims(c -> c.remove("iss")))),
            Arguments.of("de servicio con otro iss", servicio(claims -> claims.issuer("otro-servicio"))),
            Arguments.of("de servicio sin ROLE_SERVICE", servicio(claims -> claims.claims(c -> c.remove(AUTHORITIES_CLAIM)))),
            Arguments.of("de servicio con ROLE_SERVICE y ROLE_USER", servicio(claims -> claims.claim(AUTHORITIES_CLAIM, "ROLE_SERVICE ROLE_USER"))),
            Arguments.of("de servicio sin iat", servicio(claims -> claims.claims(c -> c.remove("iat")))),
            Arguments.of("de servicio con 10 min de vida", servicio(claims -> claims.expiresAt(Instant.now().plusSeconds(600))))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensConFirmaValidaQueNoSonDeServicio")
    void unTokenConFirmaValidaQueNoEsElDeServicioResponde403(String caso, Consumer<JwtClaimsSet.Builder> ajustes) throws Exception {
        pedir(get(RUTA_DE_SERVICIO), token(ajustes)).andExpect(status().isForbidden());
        pedir(get(RUTA_DE_LA_AGENDA), token(ajustes)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/categories", "/api/professionals", "/api/sync/status" })
    void elTokenDeServicioRecibe403EnLosEndpointsDeUsuario(String ruta) throws Exception {
        pedir(get(ruta), createServiceToken(jwtKey)).andExpect(status().isForbidden());
    }

    @Test
    void elTokenDeServicioRecibe403EnLaSincronizacionManual() throws Exception {
        pedir(post("/api/admin/sync"), createServiceToken(jwtKey)).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_SERVICE ROLE_USER", "ROLE_SERVICE ROLE_ADMIN" })
    void unTokenMixtoRecibe403EnLosEndpointsDeUsuarioYDeAdmin(String roles) throws Exception {
        // ROLE_SERVICE marca el token como de servicio aunque traiga también un rol de usuario (DP-06).
        String token = createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, roles));

        pedir(get("/api/categories"), token).andExpect(status().isForbidden());
        pedir(post("/api/admin/sync"), token).andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions pedir(MockHttpServletRequestBuilder pedido, String token) throws Exception {
        return mvc.perform(pedido.header(AUTHORIZATION, BEARER + token));
    }

    /** Arma el token con la clave del test: los casos parametrizados son estáticos y no la tienen. */
    private String token(Consumer<JwtClaimsSet.Builder> ajustes) {
        return createSignedToken(jwtKey, "turnos-service", ajustes);
    }

    private static Consumer<JwtClaimsSet.Builder> usuario(String roles) {
        return claims -> claims.subject("ana.perez").claim(AUTHORITIES_CLAIM, roles).claim("userId", 7);
    }

    /** El token de servicio válido (como {@code createServiceToken}) con un ajuste al final. */
    private static Consumer<JwtClaimsSet.Builder> servicio(Consumer<JwtClaimsSet.Builder> ajuste) {
        return claims -> {
            Instant ahora = Instant.now();
            claims
                .claim(AUTHORITIES_CLAIM, "ROLE_SERVICE")
                .audience(List.of("catalogo-service"))
                .issuer("turnos-service")
                .issuedAt(ahora)
                .expiresAt(ahora.plusSeconds(300));
            ajuste.accept(claims);
        };
    }

    /** Un endpoint de servicio que solo existe en este contexto: responde 204 si pasó la seguridad. */
    @RestController
    static class EndpointDeServicioDePrueba {

        @GetMapping(RUTA_DE_SERVICIO)
        ResponseEntity<Void> prueba() {
            return ResponseEntity.noContent().build();
        }
    }
}
