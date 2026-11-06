package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncLock;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Un aviso que llega durante otra sincronización (DP-09), con dos hilos y PostgreSQL real.
 * <p>
 * La exclusión la tiene {@link SyncLockHolder}, en un hilo propio, y el aviso corre en otro: si corriera en el mismo hilo,
 * la reentrancia lo dejaría pasar. La espera es de 30 s, para que no venza mientras el test coordina los hilos; el caso
 * de la espera vencida está en {@code ExclusiveSyncIT}.
 */
@IntegrationTest
@TestPropertySource(properties = "sync.lock.wait-timeout=30s")
class CatalogEventWaitsForSyncIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final CatalogUpdateNotice AVISO = new CatalogUpdateNotice("8b097be5-7ba5-4618-97e8-c502d3f46723", 41);
    private static final long MAX_SECONDS = 20;

    @MockitoBean
    private CatalogSnapshotProvider catalogSnapshotProvider;

    @Autowired
    private SyncService syncService;

    @Autowired
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

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
    void siLaOtraDejoLaVersionAlDiaElAvisoEsperaYNoAplicaNada() throws InterruptedException {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        AtomicReference<CatalogEventResult> resultado = new AtomicReference<>();
        Thread aviso = new Thread(() -> resultado.set(syncService.handleCatalogUpdated(AVISO)), "aviso-kafka");

        try (SyncLockHolder manual = SyncLockHolder.hold(syncLock)) {
            aviso.start();
            esperarQueEsteParado(aviso);
            assertThat(resultado.get()).as("el aviso tiene que esperar a la otra sincronización").isNull();
            // Lo que haría la manual antes de soltar la exclusión: confirmar la versión que anuncia el aviso.
            assertThat(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).isEqualTo(new FullSyncResult.Applied(41, 1));
        }
        aviso.join(TimeUnit.SECONDS.toMillis(MAX_SECONDS));

        assertThat(resultado.get()).isEqualTo(new CatalogEventResult.UpToDate(41, 41));
        verify(catalogSnapshotProvider, times(1)).fetchSnapshot();
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(AVISO.eventId());
        assertThat(estado().getLastSuccessTrigger()).as("el último éxito sigue siendo el de la manual").isEqualTo(SyncTrigger.MANUAL);
    }

    @Test
    void siLaOtraNoAplicoNadaElAvisoEsperaYDespuesSincroniza() throws InterruptedException {
        when(catalogSnapshotProvider.fetchSnapshot()).thenReturn(snapshot(41));
        AtomicReference<CatalogEventResult> resultado = new AtomicReference<>();
        Thread aviso = new Thread(() -> resultado.set(syncService.handleCatalogUpdated(AVISO)), "aviso-kafka");

        try (SyncLockHolder otra = SyncLockHolder.hold(syncLock)) {
            aviso.start();
            esperarQueEsteParado(aviso);
            verify(catalogSnapshotProvider, never()).fetchSnapshot();
        }
        aviso.join(TimeUnit.SECONDS.toMillis(MAX_SECONDS));

        assertThat(resultado.get()).isEqualTo(new CatalogEventResult.Applied(null, 41, SyncPath.SNAPSHOT, 1));
        assertThat(estado().getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        assertThat(SyncTestDatabase.processedEventIds(transactionTemplate, em)).containsExactly(AVISO.eventId());
    }

    /** {@code tryLock} con plazo deja al hilo en {@code TIMED_WAITING}: se lo espera sin dormir, como en {@code InMemorySyncLockTest}. */
    private static void esperarQueEsteParado(Thread hilo) {
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(MAX_SECONDS);
        while (hilo.getState() != Thread.State.TIMED_WAITING) {
            assertThat(System.nanoTime()).as("el aviso no llegó a la espera de la exclusión").isLessThan(limite);
            Thread.onSpinWait();
        }
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
