package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import static ar.edu.um.programacion2.catalogo.security.SecurityUtils.AUTHORITIES_CLAIM;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.BEARER;
import static ar.edu.um.programacion2.catalogo.security.jwt.JwtAuthenticationTestUtils.createSignedToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-08 en modo normal de punta a punta: JWT firmado, MockMvc, PostgreSQL y Redis reales (con la ACL de la cátedra). La
 * fuente del snapshot es falsa y no se tiene que usar.
 */
@AutoConfigureMockMvc
@IntegrationTest
class ManualSyncNormalModeIT {

    private static final Instant T = Instant.parse("2026-09-01T03:00:00Z");

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @Value("${jhipster.security.authentication.jwt.base64-secret}")
    private String jwtKey;

    @DynamicPropertySource
    static void redisDePrueba(DynamicPropertyRegistry properties) {
        properties.add("catedra.redis.host", RedisTestServer::host);
        properties.add("catedra.redis.port", RedisTestServer::port);
        properties.add("catedra.redis.username", () -> RedisTestServer.USERNAME);
        properties.add("catedra.redis.password", () -> RedisTestServer.PASSWORD);
    }

    @BeforeEach
    void copiaEnLa88YRedisEnLa89() {
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
        transactionTemplate.executeWithoutResult(tx -> {
            catalogRepository.replaceAll(
                List.of(new ProfessionalCategory(101, "Clinica", null, true, T, T)),
                List.of(new Professional(201, 101, "Ana", "Torres", true, T, T)),
                List.<WeeklySchedule>of()
            );
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(88, SyncTrigger.STARTUP, T);
            syncStatusRepository.save(estado);
        });
        var admin = RedisTestServer.admin();
        admin.hset("catedra:sync:metadata", Map.of("currentVersion", "89", "oldestAvailableVersion", "1"));
        admin.set("catedra:sync:changes:89", "{\"version\":89,\"changes\":{\"professionalCategories\":[],\"professionals\":[201],\"weeklySchedules\":[]}}");
        admin.hset(
            "catedra:sync:professionals",
            "201",
            "{\"id\":201,\"categoryId\":101,\"firstName\":\"Ana\",\"lastName\":\"Torres\",\"enabled\":false,\"createdAt\":\"2026-09-01T03:00:00Z\",\"updatedAt\":\"2026-11-08T01:19:34.423296Z\"}"
        );
    }

    @AfterEach
    void limpiar() {
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
    }

    @Test
    void conUnaVersionPendienteResponde200PorIncremental() throws Exception {
        pedirNormal()
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    """
                    {"mode": "NORMAL", "localVersionBefore": 88, "localVersionAfter": 89, "path": "INCREMENTAL", "attempts": 1}
                    """,
                    JsonCompareMode.STRICT
                )
            );

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(89L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(estado.getRemoteVersion()).isEqualTo(89L);
        verifyNoInteractions(catalogSnapshotProvider);
    }

    @Test
    void conLaCopiaAlDiaResponde200SinCambiosYDejaLaVersionRemota() throws Exception {
        pedirNormal().andExpect(status().isOk());

        pedirNormal()
            .andExpect(status().isOk())
            .andExpect(
                content().json(
                    """
                    {"mode": "NORMAL", "localVersionBefore": 89, "localVersionAfter": 89, "path": "NONE", "attempts": 1}
                    """,
                    JsonCompareMode.STRICT
                )
            );

        assertThat(estado().getRemoteVersion()).isEqualTo(89L);
        verifyNoInteractions(catalogSnapshotProvider);
    }

    private ResultActions pedirNormal() throws Exception {
        String token = createSignedToken(jwtKey, "admin", claims -> claims.claim(AUTHORITIES_CLAIM, "ROLE_ADMIN").claim("userId", 1));
        return mvc.perform(post("/api/admin/sync").header(AUTHORIZATION, BEARER + token));
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }
}
