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
 * a pasar contra la cátedra real, a escala. Los avisos viejos van "al día", el primero más nuevo que la copia pide un
 * snapshot, y como ese snapshot ya trae la última versión, lo que sigue también va "al día".
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
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void elHistorialPideUnSoloSnapshotYElRestoVaAlDia() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(12));
        assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).isEqualTo(new FullSyncResult.Applied(12, 1));
        for (long version = 11; version <= 15; version++) {
            KafkaTestBroker.publishCatalogUpdated(TOPIC, UUID.randomUUID().toString(), version);
        }
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(15));

        registry.getListenerContainers().forEach(MessageListenerContainer::start);
        await()
            .atMost(Duration.ofSeconds(30))
            .until(() -> Long.valueOf(5).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));

        // Una vez para preparar la copia en la 12 y otra por el aviso de la 13.
        verify(catalogSnapshotProvider, times(2)).fetchSnapshot();
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).hasSize(5);
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLocalVersion()).isEqualTo(15L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
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
