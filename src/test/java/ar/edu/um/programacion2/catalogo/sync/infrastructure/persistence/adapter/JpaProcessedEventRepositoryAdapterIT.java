package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.ProcessedEventRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.ProcessedEventEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Eventos procesados contra PostgreSQL real, a través del puerto. Sin {@code @Transactional}: el adaptador exige que la abra quien llama. */
@IntegrationTest
class JpaProcessedEventRepositoryAdapterIT {

    private static final String EVENTO = "8b097be5-7ba5-4618-97e8-c502d3f46723";
    private static final String OTRO = "2f1b6c1e-4a57-4d53-8d3e-1c7a7a5a9f10";
    private static final Instant T1 = Instant.parse("2026-11-06T01:19:28Z");
    private static final Instant T2 = Instant.parse("2026-11-06T02:19:28Z");

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void unEventoRegistradoExisteYUnoDesconocidoNo() {
        registrar(new ProcessedEvent(EVENTO, 41, T1));

        assertThat(existe(EVENTO)).isTrue();
        assertThat(existe(OTRO)).isFalse();
    }

    @Test
    void guardaLaVersionYLaFechaTalCualLlegan() {
        registrar(new ProcessedEvent(EVENTO, 41, T1));

        // Se lee por la entidad, que es como lo lee Hibernate: el INSERT es nativo y tiene que respetar la zona UTC.
        ProcessedEventEntity leido = transactionTemplate.execute(tx -> em.find(ProcessedEventEntity.class, EVENTO));
        assertThat(leido.getNewVersion()).isEqualTo(41L);
        assertThat(leido.getProcessedAt()).isEqualTo(T1);
    }

    @Test
    void registrarDosVecesNoCambiaLaFilaOriginal() {
        registrar(new ProcessedEvent(EVENTO, 41, T1));

        registrar(new ProcessedEvent(EVENTO, 99, T2));

        ProcessedEventEntity leido = transactionTemplate.execute(tx -> em.find(ProcessedEventEntity.class, EVENTO));
        assertThat(leido.getNewVersion()).isEqualTo(41L);
        assertThat(leido.getProcessedAt()).isEqualTo(T1);
    }

    @Test
    void unDuplicadoNoInvalidaLaTransaccionDeQuienLlama() {
        registrar(new ProcessedEvent(EVENTO, 41, T1));

        // Con una violación de clave primaria, PostgreSQL rechazaría todo lo que sigue y el commit se volvería rollback.
        transactionTemplate.executeWithoutResult(tx -> {
            processedEventRepository.registerIfAbsent(new ProcessedEvent(EVENTO, 41, T2));
            processedEventRepository.registerIfAbsent(new ProcessedEvent(OTRO, 42, T2));
        });

        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactlyInAnyOrder(EVENTO, OTRO);
    }

    @Test
    void siLaTransaccionSeDeshaceElEventoNoQueda() {
        transactionTemplate.executeWithoutResult(tx -> {
            processedEventRepository.registerIfAbsent(new ProcessedEvent(EVENTO, 41, T1));
            tx.setRollbackOnly();
        });

        assertThat(existe(EVENTO)).isFalse();
    }

    @Test
    void sinTransaccionFalla() {
        assertThatThrownBy(() -> processedEventRepository.exists(EVENTO)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> processedEventRepository.registerIfAbsent(new ProcessedEvent(EVENTO, 41, T1))).isInstanceOf(
            IllegalTransactionStateException.class
        );
    }

    private void registrar(ProcessedEvent evento) {
        transactionTemplate.executeWithoutResult(tx -> processedEventRepository.registerIfAbsent(evento));
    }

    private boolean existe(String eventId) {
        return transactionTemplate.execute(tx -> processedEventRepository.exists(eventId));
    }
}
