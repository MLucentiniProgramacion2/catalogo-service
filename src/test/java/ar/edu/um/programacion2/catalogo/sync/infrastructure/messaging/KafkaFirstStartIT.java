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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El primer arranque del grupo con {@code earliest}, con el historial ya publicado y una copia local atrasada: lo que va
 * a pasar contra la cátedra real, a escala (allá, la copia en la 56 y Redis en la 89). Los avisos viejos van "al día",
 * el primero más nuevo que la copia aplica por incremental <strong>todas</strong> las versiones hasta la que publica
 * Redis, y lo que sigue también va "al día". Kafka, Redis (con la ACL de la cátedra) y PostgreSQL son reales.
 * <p>
 * El listener empieza apagado y se prende a mano: el historial y la copia local tienen que estar listos antes.
 */
@IntegrationTest
class KafkaFirstStartIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final String TOPIC = KafkaTestBroker.createTopic();
    private static final String GRUPO = "alumnos-de-prueba-primer-arranque";

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void brokerDePrueba(DynamicPropertyRegistry properties) {
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
    void elPrimerAvisoNuevoAplicaTodoPorIncrementalYElRestoVaAlDia() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(12));
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).isEqualTo(new FullSyncResult.Applied(12, 1));
        // Redis en la 15, con la ventana desde la 1: cada versión cambia al profesional 15, que termina deshabilitado.
        RedisTestServer.admin().hset("catedra:sync:metadata", Map.of("currentVersion", "15", "oldestAvailableVersion", "1"));
        for (long version = 1; version <= 15; version++) {
            RedisTestServer.admin().set(
                "catedra:sync:changes:" + version,
                "{\"version\":" + version + ",\"changes\":{\"professionalCategories\":[],\"professionals\":[15],\"weeklySchedules\":[]}}"
            );
        }
        RedisTestServer.admin().hset(
            "catedra:sync:professionals",
            "15",
            "{\"id\":15,\"categoryId\":1,\"firstName\":\"Ana\",\"lastName\":\"Gomez\",\"enabled\":false,\"createdAt\":\"2026-07-01T12:00:00Z\",\"updatedAt\":\"2026-11-08T01:19:34.443824516Z\"}"
        );
        for (long version = 11; version <= 15; version++) {
            KafkaTestBroker.publishCatalogUpdated(TOPIC, UUID.randomUUID().toString(), version);
        }

        registry.getListenerContainers().forEach(MessageListenerContainer::start);
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> Long.valueOf(5).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));

        // Solo el snapshot para preparar la copia en la 12: el aviso de la 13 aplicó la 13, la 14 y la 15 por incremental.
        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).hasSize(5);
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLocalVersion()).isEqualTo(15L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        assertThat(estado.getRemoteVersion()).isEqualTo(15L);
        String habilitado = transactionTemplate.execute(tx ->
            (String) em.createNativeQuery("select enabled::text from professional where id = 15", String.class).getSingleResult()
        );
        assertThat(habilitado).isEqualTo("false");
    }

    private static CatalogSnapshot snapshot(long version) {
        LocalTime nueve = LocalTime.of(9, 0);
        return new CatalogSnapshot(
            version,
            CREADO,
            List.of(new ProfessionalCategory(1L, "Categoria", null, true, CREADO, CREADO)),
            List.of(new Professional(15L, 1L, "Ana", "Gomez", true, CREADO, CREADO)),
            List.of(new WeeklySchedule(21L, 15L, DayOfWeek.MONDAY, nueve, nueve.plusHours(3), 30, true, CREADO, CREADO))
        );
    }
}
