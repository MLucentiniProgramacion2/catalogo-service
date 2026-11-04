package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createExpiredToken;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-08 de punta a punta: JWT firmado, MockMvc y PostgreSQL real, con la fuente del snapshot falsa. */
@AutoConfigureMockMvc
@IntegrationTest
class ManualSyncControllerIT {

    private static final String RUTA = "/api/admin/sync";
    private static final String SNAPSHOT = RUTA + "?mode=SNAPSHOT";
    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");
    private static final String TIPO_PROBLEMA = "https://www.jhipster.tech/problem/problem-with-message";

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

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
    void elModoSnapshotRespondeElResultadoConLasVersiones() throws Exception {
        versionLocal(3);
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(5));

        pedir(SNAPSHOT, token("ROLE_USER ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(
                content().json(
                    """
                    {"mode": "SNAPSHOT", "localVersionBefore": 3, "localVersionAfter": 5, "path": "SNAPSHOT", "attempts": 1}
                    """,
                    JsonCompareMode.STRICT
                )
            );

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(5L);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
    }

    @Test
    void conLaBaseVaciaLaVersionAnteriorEsNula() throws Exception {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(5));

        pedir(SNAPSHOT, token("ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.localVersionBefore", nullValue()))
            .andExpect(jsonPath("$.localVersionAfter").value(5));
    }

    @Test
    void unaSincronizacionFallidaResponde502ConElTipoDeError() throws Exception {
        versionLocal(3);
        when(catalogSnapshotProvider.fetchSnapshot()).thenThrow(
            new SnapshotAccessDeniedException("La cátedra respondió 401 (sin code) al pedir el snapshot")
        );

        pedir(SNAPSHOT, token("ROLE_ADMIN"))
            .andExpect(status().isBadGateway())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.code").value(SyncErrorResponses.SYNC_FAILED))
            .andExpect(jsonPath("$.errorType").value("INVALID_CREDENTIALS"))
            .andExpect(jsonPath("$.localVersionBefore").value(3))
            .andExpect(jsonPath("$.localVersionAfter").value(3))
            .andExpect(jsonPath("$.attempts").value(1))
            .andExpect(jsonPath("$.path").value(RUTA))
            .andExpect(jsonPath("$.type").value(TIPO_PROBLEMA))
            .andExpect(jsonPath("$.detail").value(containsString("La cátedra rechazó la credencial técnica")))
            .andExpect(content().string(not(containsString("Exception"))));
    }

    @Test
    void sinVersionLocalElErrorInformaLasVersionesNulas() throws Exception {
        when(catalogSnapshotProvider.fetchSnapshot()).thenThrow(new SnapshotAccessDeniedException("La cátedra respondió 401"));

        pedir(SNAPSHOT, token("ROLE_ADMIN"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.localVersionBefore", nullValue()))
            .andExpect(jsonPath("$.localVersionAfter", nullValue()));
    }

    @Test
    void siVenceLaEsperaResponde409YQuedaRegistrado() throws Exception {
        // La sincronización en curso es de otro hilo: MockMvc corre en el del test y la exclusión es reentrante.
        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            pedir(SNAPSHOT, token("ROLE_ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(SyncErrorResponses.SYNC_IN_PROGRESS))
                .andExpect(jsonPath("$.message").value("error.http.409"))
                .andExpect(jsonPath("$.path").value(RUTA));
        }

        verifyNoInteractions(catalogSnapshotProvider);
        assertThat(estado().getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(strings = { RUTA, RUTA + "?mode=", RUTA + "?mode=NORMAL" })
    void elModoNormalEsElPorDefectoYTodaviaNoEstaDisponible(String url) throws Exception {
        pedir(url, token("ROLE_ADMIN"))
            .andExpect(status().isNotImplemented())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value(SyncErrorResponses.SYNC_MODE_NOT_AVAILABLE))
            .andExpect(jsonPath("$.detail").value("El modo NORMAL todavía no está disponible"));

        verifyNoInteractions(catalogSnapshotProvider);
        assertThat(estado().getLastErrorType()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "valorInventado", "snapshot", "normal" })
    void unModoDesconocidoResponde400SinRepetirElValor(String modo) throws Exception {
        pedir(RUTA + "?mode=" + modo, token("ROLE_ADMIN"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.message").value("error.http.400"))
            .andExpect(jsonPath("$.path").value(RUTA))
            .andExpect(jsonPath("$.detail").value("Failed to convert 'mode'"))
            // Igual que CU-06: el code queda para los 400 de validación (CU-05).
            .andExpect(jsonPath("$.code").doesNotExist())
            .andExpect(content().string(not(containsString(modo))))
            .andExpect(content().string(not(containsString("SyncMode"))));

        verifyNoInteractions(catalogSnapshotProvider);
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(post(SNAPSHOT)).andExpect(status().isUnauthorized());

        verifyNoInteractions(catalogSnapshotProvider);
    }

    @Test
    void conUnTokenVencidoResponde401() throws Exception {
        pedir(SNAPSHOT, createExpiredToken(jwtKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void unUsuarioComunRecibe403() throws Exception {
        pedir(SNAPSHOT, token("ROLE_USER")).andExpect(status().isForbidden());

        verifyNoInteractions(catalogSnapshotProvider);
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

        pedir(SNAPSHOT, tokenDeServicio).andExpect(status().isForbidden());

        verifyNoInteractions(catalogSnapshotProvider);
    }

    private ResultActions pedir(String url, String token) throws Exception {
        return mvc.perform(post(url).header(AUTHORIZATION, BEARER + token));
    }

    private String token(String roles) {
        return createSignedToken(jwtKey, "admin", claims -> claims.claim(AUTHORITIES_CLAIM, roles).claim("userId", 1));
    }

    private void versionLocal(long version) {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(estado);
        });
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private static CatalogSnapshot snapshot(long version) {
        LocalTime nueve = LocalTime.of(9, 0);
        return new CatalogSnapshot(
            version,
            T1,
            List.of(new ProfessionalCategory(1L, "Categoria", null, true, T1, T1)),
            List.of(new Professional(15L, 1L, "Ana", "Gomez", true, T1, T1)),
            List.of(new WeeklySchedule(21L, 15L, DayOfWeek.MONDAY, nueve, nueve.plusHours(3), 30, true, T1, T1))
        );
    }
}
