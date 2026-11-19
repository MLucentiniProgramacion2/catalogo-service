package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** CU-08 contra PostgreSQL real, con la fuente del snapshot falsa: el proxy transaccional y las escrituras son las reales. */
@IntegrationTest
class ManualSyncIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @Autowired
    private SyncService syncService;

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
    void elModoSnapshotDejaLaVersionYElDisparadorManualEnElEstado() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(4));
        assertThat(syncService.triggerManualSync(SyncMode.SNAPSHOT)).isEqualTo(new ManualSyncResult.Applied(null, 4, SyncPath.SNAPSHOT, 1));

        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(9));
        ManualSyncResult resultado = syncService.triggerManualSync(SyncMode.SNAPSHOT);

        assertThat(resultado).isEqualTo(new ManualSyncResult.Applied(4L, 9, SyncPath.SNAPSHOT, 1));
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(9L);
        assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.MANUAL);
        assertThat(SyncTestDatabase.professionalIds(transactionTemplate, em)).containsExactly(15L);
    }

    @Test
    void siFallaLaCopiaYLaVersionQuedanComoEstabanYElErrorRegistrado() {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(4));
        syncService.triggerManualSync(SyncMode.SNAPSHOT);
        when(catalogSnapshotProvider.fetchSnapshot()).thenThrow(new SnapshotAccessDeniedException("La cátedra respondió 401"));

        ManualSyncResult resultado = syncService.triggerManualSync(SyncMode.SNAPSHOT);

        assertThat(resultado).isInstanceOfSatisfying(ManualSyncResult.Failed.class, fallo -> {
            assertThat(fallo.localVersionBefore()).isEqualTo(4L);
            assertThat(fallo.localVersionAfter()).isEqualTo(4L);
            assertThat(fallo.errorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
        });
        SyncStatus estado = estado();
        assertThat(estado.getLocalVersion()).isEqualTo(4L);
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
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
