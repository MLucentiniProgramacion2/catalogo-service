package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.KafkaTestBroker;
import ar.edu.um.programacion2.catalogo.sync.RedisTestServer;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-03 con Redis, de punta a punta: Kafka, Redis con la ACL de la cátedra y PostgreSQL reales, y el listener de verdad.
 * Lo único falso es la fuente del snapshot. Como en {@code KafkaCatalogUpdatedIT}, la señal de que el aviso se procesó
 * es el offset confirmado, leído del broker.
 */
@IntegrationTest
@TestPropertySource(properties = "catedra.kafka.listener-enabled=true")
class KafkaIncrementalSyncIT {

    private static final Instant CREADO = Instant.parse("2026-09-01T03:00:00Z");
    private static final String TOPIC = KafkaTestBroker.createTopic();
    private static final String GRUPO = "alumnos-de-prueba-incremental-catalogo";

    /** Avisos publicados desde que arrancó la clase: el offset que tiene que quedar confirmado. */
    private static long publicados;

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void kafkaYRedisDePrueba(DynamicPropertyRegistry properties) {
        properties.add("catedra.kafka.bootstrap-servers", KafkaTestBroker::bootstrapServers);
        properties.add("catedra.kafka.catalog-topic", () -> TOPIC);
        properties.add("catedra.kafka.consumer-group", () -> GRUPO);
        properties.add("catedra.redis.host", RedisTestServer::host);
        properties.add("catedra.redis.port", RedisTestServer::port);
        properties.add("catedra.redis.username", () -> RedisTestServer.USERNAME);
        properties.add("catedra.redis.password", () -> RedisTestServer.PASSWORD);
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
        RedisTestServer.flushAll();
    }

    @Test
    void unAvisoConLaCopiaUnaVersionAtrasVaPorIncrementalRegistraElEventoYConfirmaElOffset() {
        copiaEnLaVersion(88);
        redisPublica(89, 1);
        String evento = UUID.randomUUID().toString();

        publicarAviso(evento, 89);

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(89L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        assertThat(estado.getRemoteVersion()).isEqualTo(89L);
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(evento);
        assertThat(enabledDel201()).isEqualTo("false");
        // Solo el snapshot que preparó la copia: el aviso no pidió ninguno.
        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
    }

    @Test
    void conLaCopiaFueraDeLaVentanaVaPorSnapshot() {
        // Contrato §18.2: local 3, Redis en 7 con la más vieja en 4.
        copiaEnLaVersion(3);
        redisPublica(7, 4);
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(7, false));
        String evento = UUID.randomUUID().toString();

        publicarAviso(evento, 7);

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(7L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getRemoteVersion()).isEqualTo(7L);
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(evento);
        verify(catalogSnapshotProvider, times(2)).fetchSnapshot();
    }

    private void copiaEnLaVersion(long version) {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(version, true));
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).isEqualTo(new FullSyncResult.Applied(version, 1));
    }

    /** Redis con los cambios de la ventana, cada uno del profesional 201, que en el Hash está deshabilitado. */
    private static void redisPublica(long current, long oldest) {
        var admin = RedisTestServer.admin();
        admin.hset("catedra:sync:metadata", Map.of("currentVersion", Long.toString(current), "oldestAvailableVersion", Long.toString(oldest)));
        for (long v = oldest; v <= current; v++) {
            admin.set(
                "catedra:sync:changes:" + v,
                "{\"version\":" + v + ",\"changes\":{\"professionalCategories\":[],\"professionals\":[201],\"weeklySchedules\":[]}}"
            );
        }
        admin.hset(
            "catedra:sync:professionals",
            "201",
            "{\"id\":201,\"categoryId\":101,\"firstName\":\"Ana\",\"lastName\":\"Torres\",\"enabled\":false,\"createdAt\":\"2026-09-01T03:00:00Z\",\"updatedAt\":\"2026-11-08T01:19:34.423296Z\"}"
        );
    }

    private static void publicarAviso(String evento, long version) {
        KafkaTestBroker.publishCatalogUpdated(TOPIC, evento, version);
        publicados++;
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> Long.valueOf(publicados).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private String enabledDel201() {
        return transactionTemplate.execute(tx ->
            (String) em.createNativeQuery("select enabled::text from professional where id = 201", String.class).getSingleResult()
        );
    }

    private static CatalogSnapshot snapshot(long version, boolean habilitado) {
        return new CatalogSnapshot(
            version,
            CREADO,
            List.of(new ProfessionalCategory(101, "Clinica", null, true, CREADO, CREADO)),
            List.of(new Professional(201, 101, "Ana", "Torres", habilitado, CREADO, CREADO)),
            List.<WeeklySchedule>of()
        );
    }
}
