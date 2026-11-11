package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Versión local leída a través del puerto de {@code catalog}, contra PostgreSQL real. El estado se prepara con el puerto
 * de {@code sync} ({@link SyncStatusRepository}), que es como lo escribe CU-02.
 */
@IntegrationTest
class JpaLocalCatalogVersionAdapterIT {

    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");

    @Autowired
    private LocalCatalogVersionProvider versionProvider;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

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
    void sinSnapshotConfirmadoNoHayVersion() {
        assertThat(versionLeida()).isEmpty();
    }

    @Test
    void unErrorSinSnapshotSigueSinVersion() {
        // Es el caso del arranque fallido (#23): el servicio queda levantado, con el error registrado y sin datos.
        guardar(estado -> estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "La cátedra no respondió", T1));

        assertThat(versionLeida()).isEmpty();
    }

    @Test
    void devuelveLaVersionAplicada() {
        guardar(estado -> estado.recordSnapshotApplied(7, SyncTrigger.STARTUP, T1));

        assertThat(versionLeida()).isEqualTo(OptionalLong.of(7));
    }

    @Test
    void sinTransaccionFallaAntesDeLeer() {
        assertThatThrownBy(versionProvider::findAppliedVersion).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void fallaSiFaltaLaFila() {
        transactionTemplate.executeWithoutResult(tx -> {
            tx.setRollbackOnly(); // El rollback devuelve la fila que se borra acá.
            em.createNativeQuery("delete from sync_status").executeUpdate();

            assertThatThrownBy(versionProvider::findAppliedVersion)
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("No existe la fila 1 de sync_status");
        });
    }

    private OptionalLong versionLeida() {
        return transactionTemplate.execute(tx -> versionProvider.findAppliedVersion());
    }

    private void guardar(Consumer<SyncStatus> cambio) {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus estado = syncStatusRepository.load();
            cambio.accept(estado);
            syncStatusRepository.save(estado);
        });
    }
}
