package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Estado de sincronización contra PostgreSQL real, a través del puerto.
 * <p>
 * La clase no es {@code @Transactional}: el adaptador exige que quien llama abra la transacción, y hay que probar
 * también qué pasa sin ella. Cada test abre las suyas con {@link TransactionTemplate} y lee en una transacción aparte,
 * para leer lo que quedó en la base. Como se commitea de verdad, antes y después de cada test la fila vuelve al estado
 * inicial con SQL, no con el adaptador: la limpieza no puede depender del código que se prueba.
 */
@IntegrationTest
class JpaSyncStatusRepositoryAdapterIT {

    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final Instant T2 = Instant.parse("2026-09-10T14:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-10T14:05:00Z");

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        transactionTemplate.executeWithoutResult(tx ->
            em
                .createNativeQuery(
                    """
                    update sync_status set
                        local_version = null, last_success_at = null, last_success_path = null, last_success_trigger = null,
                        remote_version = null, remote_version_checked_at = null,
                        last_error_at = null, last_error_type = null, last_error_description = null
                    where id = 1
                    """
                )
                .executeUpdate()
        );
    }

    @Test
    void leeElEstadoInicialSinDatos() {
        SyncStatus leido = transactionTemplate.execute(tx -> syncStatusRepository.load());

        assertThat(leido).usingRecursiveComparison().isEqualTo(SyncStatus.initial());
    }

    @Test
    void guardaYLeeElEstadoCompleto() {
        SyncStatus estado = new SyncStatus(
            7L,
            T1,
            SyncPath.SNAPSHOT,
            SyncTrigger.STARTUP,
            8L,
            T2,
            T3,
            SyncErrorType.REDIS_UNAVAILABLE,
            "Redis no respondió"
        );

        transactionTemplate.executeWithoutResult(tx -> syncStatusRepository.save(estado));
        SyncStatus leido = transactionTemplate.execute(tx -> syncStatusRepository.load());

        assertThat(leido).usingRecursiveComparison().isEqualTo(estado);
    }

    @Test
    void guardarUnEstadoActualizadoReemplazaElAnterior() {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "La cátedra no respondió", T1);
            syncStatusRepository.save(estado);
        });
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            estado.recordSnapshotApplied(7, SyncTrigger.MANUAL, T2);
            syncStatusRepository.save(estado);
        });

        SyncStatus leido = transactionTemplate.execute(tx -> syncStatusRepository.load());

        assertThat(leido.getLocalVersion()).isEqualTo(7L);
        assertThat(leido.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        // Un éxito no borra el último error: CU-09 informa los dos.
        assertThat(leido.getLastErrorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
        assertThat(leido.getLastErrorAt()).isEqualTo(T1);
    }

    @Test
    void fallaSiFaltaLaFilaYNoLaCrea() {
        transactionTemplate.executeWithoutResult(tx -> {
            tx.setRollbackOnly(); // El rollback devuelve la fila que se borra acá.
            em.createNativeQuery("delete from sync_status").executeUpdate();

            // @Repository traduce la IllegalStateException del adaptador; el mensaje y la causa se conservan.
            assertThatThrownBy(syncStatusRepository::load)
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No existe la fila 1 de sync_status");
            assertThatThrownBy(() -> syncStatusRepository.save(SyncStatus.initial()))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("No existe la fila 1 de sync_status");
            em.flush();
            Number filas = (Number) em.createNativeQuery("select count(*) from sync_status").getSingleResult();
            assertThat(filas.longValue()).isZero();
        });
    }

    @Test
    void leerSinTransaccionFalla() {
        assertThatThrownBy(syncStatusRepository::load).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void guardarSinTransaccionFallaYNoCambiaNada() {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(7, SyncTrigger.MANUAL, T1);

        assertThatThrownBy(() -> syncStatusRepository.save(estado)).isInstanceOf(IllegalTransactionStateException.class);

        SyncStatus leido = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(leido.hasLocalCopy()).isFalse();
    }
}
