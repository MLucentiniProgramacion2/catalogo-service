package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.professional;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-01 con la copia al día: al arrancar, CU-03 con el disparador "arranque" lee la metadata, registra la versión remota
 * (CU-09 la informa) y no aplica nada. La última sincronización exitosa sigue siendo la que dejó la copia.
 * <p>
 * El estado lo prepara {@link LocalCopyBeforeStartup}: la copia en la 13 y Redis en la 13.
 */
@IntegrationTest
@Import(LocalCopyBeforeStartup.class)
@TestPropertySource(properties = { "sync.startup.enabled=true", "test.startup.local-version=13", "test.startup.redis-version=13" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupUpToDateIT {

    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraYRedisDePrueba(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
        registry.add("catedra.redis.host", RedisTestServer::host);
        registry.add("catedra.redis.port", RedisTestServer::port);
        registry.add("catedra.redis.username", () -> RedisTestServer.USERNAME);
        registry.add("catedra.redis.password", () -> RedisTestServer.PASSWORD);
    }

    @AfterAll
    void dejarLaBaseYRedisVaciosYApagarLaCatedra() {
        // Lo que dejó el arranque no puede quedar para los tests siguientes, que comparten la base y Redis.
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
        CATEDRA.close();
    }

    @Test
    void conLaCopiaAlDiaElArranqueRegistraLaVersionRemotaSinAplicarNada() {
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLocalVersion()).isEqualTo(13L);
        assertThat(estado.getRemoteVersion()).isEqualTo(13L);
        assertThat(estado.getRemoteVersionCheckedAt()).isAfter(estado.getLastSuccessAt());
        // La última sincronización exitosa es el snapshot que preparó la copia, no el arranque.
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(estado.getLastErrorType()).isNull();
        assertThat(CATEDRA.requests()).hasSize(1);
    }

    private static FakeCatedraServer startCatedra() {
        try {
            FakeCatedraServer catedra = FakeCatedraServer.start();
            catedra.respondJson(200, snapshot(13, List.of(category(1)), List.of(professional(15, 1)), List.of(schedule(21, 15))));
            return catedra;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
