package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createServiceToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El 503 con la base vacía (DP-03) con la comprobación periódica prendida: lleva {@code Retry-After} con su intervalo en
 * segundos, porque es la que vuelve a intentar el snapshot (DP-04). Sale igual en todas las consultas sobre la copia:
 * CU-05, CU-06 y la agenda de CU-07.
 * <p>
 * Con la periódica apagada (la configuración de {@code src/test}) no va el header: lo verifican los 503 de
 * {@code CategoryControllerIT} y {@code ProfessionalControllerIT}. La tarea queda registrada con 7 min y no corre
 * durante el test.
 */
@AutoConfigureMockMvc
@IntegrationTest
@TestPropertySource(properties = { "sync.periodic.enabled=true", "sync.periodic.interval=7m" })
class CatalogNotInitializedRetryAfterIT {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    @AfterEach
    void baseVacia() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/categories", "/api/professionals" })
    void conLaBaseVaciaResponde503ConElIntervaloDeLaPeriodicaComoRetryAfter(String ruta) throws Exception {
        String token = createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, "ROLE_USER"));

        esperar503ConRetryAfter(ruta, token);
    }

    @Test
    void laAgendaDeTurnosRespondeElMismo503() throws Exception {
        esperar503ConRetryAfter("/api/service/professionals/15/agenda", createServiceToken(jwtKey));
    }

    private void esperar503ConRetryAfter(String ruta, String token) throws Exception {
        mvc
            .perform(get(ruta).header(AUTHORIZATION, BEARER + token))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Retry-After", "420"))
            .andExpect(jsonPath("$.code").value(CatalogErrorResponses.CATALOG_NOT_INITIALIZED))
            .andExpect(jsonPath("$.detail").value("El catálogo todavía no está disponible: no se aplicó ninguna versión"));
    }
}
