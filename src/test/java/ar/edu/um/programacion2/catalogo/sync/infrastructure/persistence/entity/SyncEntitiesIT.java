package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity;

import static ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity.SINGLE_ROW_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Esquema de la sincronización contra PostgreSQL real: la fila única de {@code sync_status} que crea Liquibase y la
 * tabla de eventos procesados. Igual que en {@code CatalogEntitiesIT}, cada test hace {@code flush} y {@code clear}
 * para leer de la base, y {@code @Transactional} deshace todo al final.
 */
@IntegrationTest
@Transactional
class SyncEntitiesIT {

    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final Instant T2 = Instant.parse("2026-09-10T14:00:00Z");

    @Autowired
    private EntityManager em;

    @Test
    void liquibaseCreaLaFilaDeEstadoSinDatos() {
        SyncStatusEntity estado = em.find(SyncStatusEntity.class, SINGLE_ROW_ID);

        assertThat(estado).isNotNull();
        assertThat(estado.getLocalVersion()).isNull();
        assertThat(estado.getLastSuccessAt()).isNull();
        assertThat(estado.getRemoteVersion()).isNull();
        assertThat(estado.getLastErrorAt()).isNull();
    }

    @Test
    void actualizaYLeeLaFilaDeEstado() {
        SyncStatusEntity estado = em.find(SyncStatusEntity.class, SINGLE_ROW_ID);
        estado.setLocalVersion(7L);
        estado.setLastSuccessAt(T1);
        estado.setLastSuccessPath(SyncPath.SNAPSHOT);
        estado.setLastSuccessTrigger(SyncTrigger.STARTUP);
        estado.setRemoteVersion(8L);
        estado.setRemoteVersionCheckedAt(T2);
        estado.setLastErrorAt(T2);
        estado.setLastErrorType(SyncErrorType.REDIS_UNAVAILABLE);
        estado.setLastErrorDescription("Redis no respondió");
        em.flush();
        em.clear();

        SyncStatusEntity leido = em.find(SyncStatusEntity.class, SINGLE_ROW_ID);

        assertThat(leido.getLocalVersion()).isEqualTo(7L);
        assertThat(leido.getLastSuccessAt()).isEqualTo(T1);
        assertThat(leido.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(leido.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        assertThat(leido.getRemoteVersion()).isEqualTo(8L);
        assertThat(leido.getRemoteVersionCheckedAt()).isEqualTo(T2);
        assertThat(leido.getLastErrorAt()).isEqualTo(T2);
        assertThat(leido.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        assertThat(leido.getLastErrorDescription()).isEqualTo("Redis no respondió");
    }

    @Test
    void rechazaUnaSegundaFilaDeEstado() {
        SyncStatusEntity otra = new SyncStatusEntity();
        otra.setId((short) 2);
        em.persist(otra);

        assertThatThrownBy(em::flush)
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("ck_sync_status_single_row");
    }

    @Test
    void guardaYLeeUnEventoProcesado() {
        em.persist(evento("catalog-event-7-4f3b"));
        em.flush();
        em.clear();

        ProcessedEventEntity leido = em.find(ProcessedEventEntity.class, "catalog-event-7-4f3b");

        assertThat(leido.getNewVersion()).isEqualTo(7L);
        assertThat(leido.getProcessedAt()).isEqualTo(T1);
    }

    @Test
    void rechazaRegistrarDosVecesElMismoEvento() {
        em.persist(evento("catalog-event-7-4f3b"));
        em.flush();
        em.clear();

        // Un segundo registro del mismo eventId (por ejemplo, otro hilo con el mensaje duplicado) choca con la PK.
        em.persist(evento("catalog-event-7-4f3b"));

        assertThatThrownBy(em::flush)
            .isInstanceOf(ConstraintViolationException.class)
            .extracting(e -> ((ConstraintViolationException) e).getConstraintName())
            .isEqualTo("pk_processed_event");
    }

    private static ProcessedEventEntity evento(String eventId) {
        ProcessedEventEntity evento = new ProcessedEventEntity();
        evento.setEventId(eventId);
        evento.setNewVersion(7L);
        evento.setProcessedAt(T1);
        return evento;
    }
}
