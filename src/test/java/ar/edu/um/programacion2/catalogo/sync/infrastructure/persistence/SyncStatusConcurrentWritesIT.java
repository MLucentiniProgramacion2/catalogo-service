package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence;

import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.category;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.professional;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.schedule;
import static ar.edu.um.programacion2.catalogo.sync.SnapshotJson.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.usecases.SyncErrorRecorder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.adapter.JpaSyncStatusRepositoryAdapter;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Las dos carreras de {@code sync_status} que abre registrar un error sin tener la exclusión mutua (DP-09), contra
 * PostgreSQL real y por los caminos de producción: {@link SyncErrorRecorder} y CU-02, sobre el adaptador JPA.
 * <p>
 * Para intercalar los commits de forma determinista, {@link InterleavingSyncStatusRepository} decora al adaptador real y
 * corre un paso justo después de {@code load}. El otro commit va por {@link DriverManager}: el pool de los tests tiene
 * una sola conexión y la ocupa la transacción que se está probando.
 */
@IntegrationTest
@Import(SyncStatusConcurrentWritesIT.InterleavingConfiguration.class)
class SyncStatusConcurrentWritesIT {

    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");
    private static final String TIMEOUT = "Otra sincronización tenía la exclusión mutua";
    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private SyncErrorRecorder syncErrorRecorder;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private InterleavingSyncStatusRepository interleaving;

    @Autowired
    private PostgreSQLContainer database;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraSimulada(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterAll
    static void apagarCatedra() {
        CATEDRA.close();
    }

    @BeforeEach
    @AfterEach
    void volverAlEstadoInicial() {
        interleaving.afterNextLoad(null);
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @Test
    void elErrorRegistradoSinElLockNoPisaLaVersionQueOtraSincronizacionConfirmo() {
        versionLocal(1);

        // El error leyó la versión 1; antes de que guarde, el snapshot que tiene el lock confirma la 2.
        interleaving.afterNextLoad(() ->
            ejecutar(
                """
                update sync_status set local_version = 2, last_success_at = now(),
                    last_success_path = 'SNAPSHOT', last_success_trigger = 'MANUAL'
                where id = 1
                """
            )
        );
        syncErrorRecorder.record(SyncErrorType.LOCK_TIMEOUT, TIMEOUT);

        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(2L);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
    }

    @Test
    void elSnapshotNoBorraElErrorRegistradoMientrasCorria() {
        versionLocal(1);
        CATEDRA.respondJson(200, snapshot(2, List.of(category(1)), List.of(professional(15, 1)), List.of(schedule(21, 15))));

        // CU-02 leyó el estado sin error; antes de que confirme, otro disparador registra su LOCK_TIMEOUT.
        interleaving.afterNextLoad(() ->
            ejecutar(
                "update sync_status set last_error_at = now(), last_error_type = 'LOCK_TIMEOUT', last_error_description = '" + TIMEOUT + "' where id = 1"
            )
        );
        FullSyncResult resultado = executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL);

        assertThat(resultado).isEqualTo(new FullSyncResult.Applied(2, 1));
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(2L);
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
        assertThat(estado.getLastErrorDescription()).isEqualTo(TIMEOUT);
    }

    private void versionLocal(long version) {
        transactionTemplate.executeWithoutResult(tx -> {
            SyncStatus status = syncStatusRepository.load();
            status.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
            syncStatusRepository.save(status);
        });
    }

    private SyncStatus estado() {
        return transactionTemplate.execute(tx -> syncStatusRepository.load());
    }

    private void ejecutar(String sql) {
        try (
            Connection otra = DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            Statement statement = otra.createStatement()
        ) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo intercalar el commit", e);
        }
    }

    private static FakeCatedraServer startCatedra() {
        try {
            return FakeCatedraServer.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Solo para esta clase: reemplaza al {@link SyncStatusRepository} del contexto por el decorador del adaptador real. */
    @TestConfiguration
    static class InterleavingConfiguration {

        @Bean
        @Primary
        InterleavingSyncStatusRepository interleavingSyncStatusRepository(JpaSyncStatusRepositoryAdapter adapter) {
            return new InterleavingSyncStatusRepository(adapter);
        }
    }

    /** Delega todo en el adaptador real; después del próximo {@code load}, corre una vez el paso configurado. */
    static class InterleavingSyncStatusRepository implements SyncStatusRepository {

        private final SyncStatusRepository delegate;
        private final AtomicReference<Runnable> afterNextLoad = new AtomicReference<>();

        InterleavingSyncStatusRepository(SyncStatusRepository delegate) {
            this.delegate = delegate;
        }

        void afterNextLoad(Runnable step) {
            afterNextLoad.set(step);
        }

        @Override
        public SyncStatus load() {
            SyncStatus status = delegate.load();
            Runnable step = afterNextLoad.getAndSet(null);
            if (step != null) {
                step.run();
            }
            return status;
        }

        @Override
        public void save(SyncStatus status) {
            delegate.save(status);
        }
    }
}
