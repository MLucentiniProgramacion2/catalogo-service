package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** DP-09 con el contexto real: el bean, el proxy transaccional y PostgreSQL. La espera es la corta de {@code src/test}. */
@IntegrationTest
class ExclusiveSyncIT {

    @Autowired
    private ExclusiveSync exclusiveSync;

    @Autowired
    private SyncService syncService;

    @Autowired
    private SyncLock syncLock;

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
    void laEsperaVencidaQuedaEnElEstadoDeSincronizacion() {
        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            assertThat(exclusiveSync.isRunning()).isTrue();
            assertThat(exclusiveSync.run(SyncTrigger.MANUAL, () -> "no tiene que correr")).isEmpty();
        }

        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
        assertThat(estado.getLastErrorDescription()).contains("MANUAL");
        assertThat(estado.hasLocalCopy()).isFalse();
        assertThat(exclusiveSync.isRunning()).isFalse();
    }

    @Test
    void elArranquePasaPorLaExclusion() {
        // Con el lock retenido por otro hilo. Sin la exclusión, CU-01 iría a la cátedra (acá, el puerto 1, donde no escucha
        // nadie) y el error registrado sería CATEDRA_UNAVAILABLE.
        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            syncService.initializeCatalog();
        }

        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
        assertThat(estado.getLastErrorDescription()).contains("STARTUP");
        assertThat(estado.hasLocalCopy()).isFalse();
    }

    @Test
    void unAvisoQueVenceLaEsperaDejaLockTimeoutYNoRegistraElEvento() {
        CatalogEventResult resultado;
        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            resultado = syncService.handleCatalogUpdated(new CatalogUpdateNotice("8b097be5-7ba5-4618-97e8-c502d3f46723", 41));
        }

        assertThat(resultado).isEqualTo(new CatalogEventResult.NotExecuted());
        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
        assertThat(estado.getLastErrorDescription()).contains("KAFKA");
        assertThat(estado.hasLocalCopy()).isFalse();
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).isEmpty();
    }

    @Test
    void noSePuedeLlamarDentroDeUnaTransaccion() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(tx -> exclusiveSync.run(SyncTrigger.MANUAL, () -> true)))
            .isInstanceOf(IllegalTransactionStateException.class);
    }
}
