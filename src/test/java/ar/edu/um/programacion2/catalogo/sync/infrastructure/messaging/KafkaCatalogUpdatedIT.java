package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.KafkaTestBroker;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-03 de punta a punta: Kafka real con Testcontainers, PostgreSQL real y el contenedor del listener de verdad. Lo único
 * falso es la fuente del snapshot.
 * <p>
 * <strong>Sin esperas a ciegas.</strong> Después de publicar, se espera a que el grupo confirme el offset de ese mensaje,
 * leído del broker con {@code Admin}: es la señal de que ya se procesó y se persistió. Recién ahí se verifica.
 * <p>
 * <strong>Las propiedades {@code spring.kafka.*} de abajo son una trampa a propósito.</strong> Si alguna se filtrara al
 * consumidor, los tests fallarían: el broker sería uno que no existe y el grupo sería otro. {@code fail-fast} haría fallar
 * el arranque si {@code KafkaAdmin} intentara conectarse para crear un topic.
 * <p>
 * Sin {@code @DirtiesContext}, a propósito: cerrar el contexto destruye el contenedor estático de PostgreSQL y deja sin base
 * a los contextos en caché. No hace falta: al cambiar de contexto, Spring pausa este y el listener se detiene.
 */
@IntegrationTest
@TestPropertySource(
    properties = {
        "catedra.kafka.listener-enabled=true",
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.consumer.group-id=grupo-que-no-se-usa",
        "spring.kafka.consumer.auto-offset-reset=latest",
        "spring.kafka.consumer.max-poll-records=500",
        "spring.kafka.listener.ack-mode=batch",
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.fail-fast=true",
    }
)
class KafkaCatalogUpdatedIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final String TOPIC = KafkaTestBroker.createTopic();
    private static final String GRUPO = "alumnos-de-prueba-catalogo";
    private static final Duration MAX_ESPERA = Duration.ofSeconds(30);

    /** Mensajes publicados en el topic desde que arrancó la clase: el offset que tiene que quedar confirmado. */
    private static long publicados;

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @MockitoSpyBean
    private ProcessedEventRepository processedEventRepository;

    @MockitoSpyBean
    private SyncStatusRepository syncStatusRepository;

    @MockitoSpyBean
    private SyncService syncService;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private ApplicationContext context;

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
    void unAvisoActualizaLaVersionYDejaElDisparadorKafka() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        String evento = eventoNuevo();

        publicarAviso(evento, 41);
        esperarQueSeConfirmeTodo();

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(41L);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(eventos()).containsExactly(evento);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
    }

    @Test
    void elMismoEventoDosVecesPideUnSoloSnapshot() {
        // Snapshot anterior a lo que anuncia el aviso: la versión local queda en 7 y el aviso dice 9. La segunda entrega
        // pasaría la comparación de versiones, así que solo la frena la primera barrera.
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(7));
        String evento = eventoNuevo();

        publicarAviso(evento, 9);
        publicarAviso(evento, 9);
        esperarQueSeConfirmeTodo();

        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
        assertThat(eventos()).containsExactly(evento);
        assertThat(estado().getLocalVersion()).isEqualTo(7L);
    }

    @Test
    void conLaCopiaAlDiaNoPideSnapshotYRegistraElEvento() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        String primero = eventoNuevo();
        String segundo = eventoNuevo();

        publicarAviso(primero, 41);
        publicarAviso(segundo, 40);
        esperarQueSeConfirmeTodo();

        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
        assertThat(eventos()).containsExactlyInAnyOrder(primero, segundo);
        assertThat(estado().getLocalVersion()).isEqualTo(41L);
    }

    @Test
    void losMensajesRotosSeDescartanYElSiguienteSeProcesa() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        String evento = eventoNuevo();

        publicar("esto no es json".getBytes(StandardCharsets.UTF_8));
        publicar(new byte[] { (byte) 0xff, (byte) 0xfe, 0 });
        publicar(new byte[0]);
        publicar(null);
        publicar("{\"eventId\":\"sin-version\"}".getBytes(StandardCharsets.UTF_8));
        publicarAviso(evento, 41);
        esperarQueSeConfirmeTodo();

        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
        assertThat(eventos()).containsExactly(evento);
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(41L);
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.INVALID_EVENT);
        assertThat(estado.getLastErrorDescription()).doesNotContain("esto no es json", "Exception", "tools.jackson");
    }

    @Test
    void siCu02FallaSeConfirmaElOffsetSinReintentarDeMas() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenThrow(new SnapshotSourceUnavailableException("La cátedra no respondió"));

        publicarAviso(eventoNuevo(), 41);
        esperarQueSeConfirmeTodo();

        // Los 4 intentos de DP-08 adentro de CU-02. Si la falla saliera del listener, cada entrega repetiría los 4.
        verify(catalogSnapshotProvider, times(4)).fetchSnapshot();
        assertThat(eventos()).isEmpty();
        assertThat(estado().getLastErrorType()).isEqualTo(SyncErrorType.CATEDRA_UNAVAILABLE);
    }

    @Test
    void siLaBaseFallaDosVecesEnLaBarreraSeReintentaYElAvisoSeProcesa() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        doThrow(baseCaida(), baseCaida()).doCallRealMethod().when(espia(processedEventRepository)).exists(any());
        String evento = eventoNuevo();

        publicarAviso(evento, 41);
        esperarQueSeConfirmeTodo();

        verify(espia(processedEventRepository), times(3)).exists(evento);
        assertThat(eventos()).containsExactly(evento);
        assertThat(estado().getLocalVersion()).isEqualTo(41L);
    }

    @Test
    void siLaBaseFallaSiempreSeAgotanLosReintentosQuedaElErrorYSeConfirmaElOffset() {
        doThrow(baseCaida()).when(espia(processedEventRepository)).exists(any());
        String evento = eventoNuevo();

        publicarAviso(evento, 41);
        esperarQueSeConfirmeTodo();

        // 1 entrega + los 3 reintentos de sync.retry.delays.
        verify(espia(processedEventRepository), times(4)).exists(evento);
        verify(catalogSnapshotProvider, never()).fetchSnapshot();
        assertThat(eventos()).isEmpty();
        assertThat(estado().getLastErrorType()).isEqualTo(SyncErrorType.EVENT_PROCESSING_FAILED);
    }

    @Test
    void siTampocoSePuedeRegistrarElErrorElOffsetSeConfirmaIgual() {
        doThrow(baseCaida()).when(espia(processedEventRepository)).exists(any());
        doThrow(baseCaida()).when(espia(syncStatusRepository)).load();

        publicarAviso(eventoNuevo(), 41);
        esperarQueSeConfirmeTodo();

        verify(espia(syncStatusRepository)).load();
    }

    @Test
    void siElRecovererNoPuedeRegistrarNadaElOffsetSeConfirmaIgual() {
        doThrow(baseCaida()).when(espia(processedEventRepository)).exists(any());
        doThrow(baseCaida()).when(syncService).reportCatalogEventFailure();

        publicarAviso(eventoNuevo(), 41);
        esperarQueSeConfirmeTodo();

        verify(syncService, times(1)).reportCatalogEventFailure();
    }

    @Test
    void laAutoconfiguracionNoDejaOtroConsumidorNiCreaTopics() {
        assertThat(context.getBeansOfType(KafkaListenerContainerFactory.class)).hasSize(1).containsKey("kafkaListenerContainerFactory");
        assertThat(context.getBeansOfType(ConsumerFactory.class)).hasSize(1).containsKey("catalogConsumerFactory");
        assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
        assertThat(registry.getListenerContainers()).hasSize(1);

        MessageListenerContainer contenedor = registry.getListenerContainers().iterator().next();
        assertThat(contenedor.isRunning()).isTrue();
        assertThat(contenedor.getGroupId()).isEqualTo(GRUPO);
        assertThat(contenedor.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);

        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        publicarAviso(eventoNuevo(), 41);
        esperarQueSeConfirmeTodo();
        assertThat(KafkaTestBroker.groups()).contains(GRUPO).doesNotContain("grupo-que-no-se-usa");
        assertThat(KafkaTestBroker.topics()).contains(TOPIC).allMatch(topic -> topic.startsWith("catedra.catalog.test-"));
    }

    private static String eventoNuevo() {
        return UUID.randomUUID().toString();
    }

    private static void publicarAviso(String evento, long version) {
        KafkaTestBroker.publishCatalogUpdated(TOPIC, evento, version);
        publicados++;
    }

    private static void publicar(byte[] valor) {
        KafkaTestBroker.publish(TOPIC, "clave", valor);
        publicados++;
    }

    private static void esperarQueSeConfirmeTodo() {
        await().atMost(MAX_ESPERA).until(() -> Long.valueOf(publicados).equals(KafkaTestBroker.committedOffset(GRUPO, TOPIC)));
    }

    /**
     * El espía queda adentro del proxy transaccional del adaptador. Stubbear sobre el proxy llamaría al método sin
     * transacción, y {@code MANDATORY} lo cortaría antes de que Mockito lo vea.
     */
    private static <T> T espia(T adaptador) {
        return AopTestUtils.getUltimateTargetObject(adaptador);
    }

    private static DataAccessResourceFailureException baseCaida() {
        return new DataAccessResourceFailureException("La base no responde");
    }

    private List<String> eventos() {
        return SyncTestDatabase.processedEventIds(transactionTemplate, em);
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
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
