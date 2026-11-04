package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createExpiredToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-09 de punta a punta: JWT firmado, MockMvc y PostgreSQL real. */
@AutoConfigureMockMvc
@IntegrationTest
class SyncStatusControllerIT {

    private static final String RUTA = "/api/sync/status";
    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-04T12:30:00Z");
    private static final Instant T3 = Instant.parse("2026-11-04T13:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SyncLock syncLock;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void sinDatosResponde200ConLaVersionNula() throws Exception {
        pedir(token("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {"localVersion": null, "inProgress": false, "lastSuccess": null, "remote": null, "lastError": null}
                    """,
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void sinDatosInformaElErrorDelIntentoInicialSin503() throws Exception {
        modificarEstado(estado -> estado.recordError(SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", T1));

        pedir(token("ROLE_USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localVersion").value((Object) null))
            .andExpect(jsonPath("$.lastError.type").value("CATEDRA_UNAVAILABLE"))
            .andExpect(jsonPath("$.lastError.description").value("La cátedra no estuvo disponible"));
    }

    @Test
    void conDatosInformaElExitoLaVersionRemotaYElErrorALaVez() throws Exception {
        modificarEstado(estado -> {
            estado.recordSnapshotApplied(12, SyncTrigger.MANUAL, T1);
            estado.recordRemoteVersion(14, T2);
            estado.recordError(SyncErrorType.LOCK_TIMEOUT, "Otra seguía en curso", T3);
        });

        pedir(token("ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    """
                    {
                      "localVersion": 12,
                      "inProgress": false,
                      "lastSuccess": {"at": "2026-11-04T12:00:00Z", "path": "SNAPSHOT", "trigger": "MANUAL"},
                      "remote": {"version": 14, "checkedAt": "2026-11-04T12:30:00Z"},
                      "lastError": {"at": "2026-11-04T13:00:00Z", "type": "LOCK_TIMEOUT", "description": "Otra seguía en curso"}
                    }
                    """,
                    JsonCompareMode.STRICT
                )
            );
    }

    @Test
    void conUnaSincronizacionCorriendoInformaEnCursoSinEsperarla() throws Exception {
        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            pedir(token("ROLE_USER")).andExpect(status().isOk()).andExpect(jsonPath("$.inProgress").value(true));
        }

        pedir(token("ROLE_USER")).andExpect(status().isOk()).andExpect(jsonPath("$.inProgress").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = { "ROLE_USER", "ROLE_ADMIN", "ROLE_USER ROLE_ADMIN" })
    void loPuedenConsultarLosUsuariosYLosAdministradores(String roles) throws Exception {
        pedir(token(roles)).andExpect(status().isOk());
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get(RUTA)).andExpect(status().isUnauthorized());
    }

    @Test
    void conUnTokenVencidoResponde401() throws Exception {
        pedir(createExpiredToken(jwtKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void elTokenDeServicioRecibe403() throws Exception {
        String tokenDeServicio = createSignedToken(jwtKey, "turnos-service", claims ->
            claims
                .claim(AUTHORITIES_CLAIM, "ROLE_SERVICE")
                .audience(List.of("catalogo-service"))
                .issuer("turnos-service")
                .expiresAt(Instant.now().plusSeconds(300))
        );

        pedir(tokenDeServicio).andExpect(status().isForbidden());
    }

    private ResultActions pedir(String token) throws Exception {
        return mvc.perform(get(RUTA).header(AUTHORIZATION, BEARER + token));
    }

    private String token(String roles) {
        return createSignedToken(jwtKey, "ana.perez", claims -> claims.claim(AUTHORITIES_CLAIM, roles).claim("userId", 7));
    }

    private void modificarEstado(Consumer<SyncStatus> cambio) {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            cambio.accept(estado);
            syncStatusRepository.save(estado);
        });
    }
}
