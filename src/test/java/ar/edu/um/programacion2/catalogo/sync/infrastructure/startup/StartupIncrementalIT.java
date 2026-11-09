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
 * CU-01 con versión local, de punta a punta: el servicio estuvo apagado mientras la cátedra publicaba (la copia quedó en
 * la 12 y Redis va por la 13). Al arrancar, CU-03 con el disparador "arranque" aplica la 13 por incremental, sin snapshot.
 * <p>
 * El estado lo prepara {@link LocalCopyBeforeStartup}, justo antes de que corra el disparador: un snapshot de la 12 por la
 * cátedra simulada y la 13 publicada en Redis (con la ACL de la cátedra). El executor de los tests es sincrónico, así que al empezar el test el arranque ya terminó.
 */
@IntegrationTest
@Import(LocalCopyBeforeStartup.class)
@TestPropertySource(properties = { "sync.startup.enabled=true", "test.startup.local-version=12", "test.startup.redis-version=13" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StartupIncrementalIT {

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
    void conLaCopiaAtrasadaElArranqueAplicaLoQueFaltaPorIncrementalConElDisparadorDeArranque() {
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLocalVersion()).isEqualTo(13L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        assertThat(estado.getRemoteVersion()).isEqualTo(13L);
        assertThat(estado.getLastErrorType()).isNull();
        String nombre = transactionTemplate.execute(tx ->
            (String) em.createNativeQuery("select first_name from professional where id = 15", String.class).getSingleResult()
        );
        assertThat(nombre).isEqualTo("Beatriz");
        // Un solo pedido a la cátedra: el snapshot que preparó la copia en la 12. El arranque no pidió otro.
        assertThat(CATEDRA.requests()).hasSize(1);
    }

    private static FakeCatedraServer startCatedra() {
        try {
            FakeCatedraServer catedra = FakeCatedraServer.start();
            catedra.respondJson(200, snapshot(12, List.of(category(1)), List.of(professional(15, 1)), List.of(schedule(21, 15))));
            return catedra;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
