package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class TriggerManualSyncUseCaseImplTest {

    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Mock
    private IncorporateChangesUseCase incorporateChangesUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));

    private TriggerManualSyncUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        useCase = new TriggerManualSyncUseCaseImpl(
            new LocalVersionReader(getSyncStatusUseCase, syncErrorRecorder),
            executeFullSyncUseCase,
            incorporateChangesUseCase,
            new ExclusiveSync(lock, syncErrorRecorder)
        );
    }

    @Test
    void elModoSnapshotAplicaConElDisparadorManualEInformaLasVersiones() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(3));
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).thenReturn(new FullSyncResult.Applied(5, 2));

        ManualSyncResult resultado = useCase.triggerManualSync(SyncMode.SNAPSHOT);

        assertThat(resultado).isEqualTo(new ManualSyncResult.Applied(3L, 5, SyncPath.SNAPSHOT, 2));
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void sinVersionLocalLaVersionAnteriorEsNula() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).thenReturn(new FullSyncResult.Applied(5, 1));

        assertThat(useCase.triggerManualSync(SyncMode.SNAPSHOT)).isEqualTo(new ManualSyncResult.Applied(null, 5, SyncPath.SNAPSHOT, 1));
    }

    @Test
    void siLaSincronizacionFallaInformaElErrorConLaVersionSinCambios() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(3));
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).thenReturn(
            new FullSyncResult.Failed(SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4)
        );

        ManualSyncResult resultado = useCase.triggerManualSync(SyncMode.SNAPSHOT);

        assertThat(resultado).isEqualTo(
            new ManualSyncResult.Failed(3L, 3L, SyncErrorType.CATEDRA_UNAVAILABLE, "La cátedra no estuvo disponible", 4)
        );
    }

    @Test
    void leeLaVersionYSincronizaConLaExclusionTomada() {
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return estadoConVersion(3);
        });
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new FullSyncResult.Applied(5, 1);
        });

        assertThat(useCase.triggerManualSync(SyncMode.SNAPSHOT)).isInstanceOf(ManualSyncResult.Applied.class);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siVenceLaEsperaNoSincronizaYLoRegistra() {
        ManualSyncResult resultado;
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            resultado = useCase.triggerManualSync(SyncMode.SNAPSHOT);
        }

        assertThat(resultado).isEqualTo(new ManualSyncResult.OtherSyncInProgress());
        verifyNoInteractions(getSyncStatusUseCase, executeFullSyncUseCase);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), contains("MANUAL"));
    }

    @Test
    void elModoNormalCorreCu03ConElDisparadorManualYLaVersionLeidaAdentroDeLaExclusion() {
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return estadoConVersion(88);
        });
        when(incorporateChangesUseCase.incorporate(SyncTrigger.MANUAL, 88L)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new IncorporationResult.Applied(88L, 89, SyncPath.INCREMENTAL, 1);
        });

        assertThat(useCase.triggerManualSync(SyncMode.NORMAL)).isEqualTo(new ManualSyncResult.Applied(88L, 89, SyncPath.INCREMENTAL, 1));
        verifyNoInteractions(executeFullSyncUseCase, syncErrorRecorder);
    }

    @Test
    void elModoNormalConLaCopiaAlDiaEsSinCambios() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(89));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.MANUAL, 89L)).thenReturn(new IncorporationResult.UpToDate(89, 89, 1));

        assertThat(useCase.triggerManualSync(SyncMode.NORMAL)).isEqualTo(new ManualSyncResult.UpToDate(89, 89, 1));
    }

    @Test
    void siElModoNormalFallaLaVersionDespuesEsLaUltimaAplicada() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estadoConVersion(88));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.MANUAL, 88L)).thenReturn(
            new IncorporationResult.Failed(88L, 89L, SyncErrorType.REDIS_UNAVAILABLE, "Redis de la cátedra no respondió", 4)
        );

        assertThat(useCase.triggerManualSync(SyncMode.NORMAL)).isEqualTo(
            new ManualSyncResult.Failed(88L, 89L, SyncErrorType.REDIS_UNAVAILABLE, "Redis de la cátedra no respondió", 4)
        );
    }

    @Test
    void elModoNormalTambienRespetaLaExclusion() {
        ManualSyncResult resultado;
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            resultado = useCase.triggerManualSync(SyncMode.NORMAL);
        }

        assertThat(resultado).isEqualTo(new ManualSyncResult.OtherSyncInProgress());
        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), contains("MANUAL"));
    }

    @Test
    void enModoNormalSiNoSePuedeLeerLaVersionNoSeIntentaNada() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("sin conexión"));

        ManualSyncResult resultado = useCase.triggerManualSync(SyncMode.NORMAL);

        assertThat(resultado).isEqualTo(
            new ManualSyncResult.Failed(null, null, SyncErrorType.DATABASE_UNAVAILABLE, LocalVersionReader.VERSION_UNREADABLE, 0)
        );
        verifyNoInteractions(incorporateChangesUseCase);
    }

    @Test
    void siNoSePuedeLeerLaVersionLoInformaComoResultadoSinIntentarElSnapshot() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("host-interno:5432 sin conexión"));

        ManualSyncResult resultado = useCase.triggerManualSync(SyncMode.SNAPSHOT);

        assertThat(resultado).isEqualTo(
            new ManualSyncResult.Failed(
                null,
                null,
                SyncErrorType.DATABASE_UNAVAILABLE,
                LocalVersionReader.VERSION_UNREADABLE,
                0
            )
        );
        verify(executeFullSyncUseCase, never()).executeFullSync(SyncTrigger.MANUAL);
        verify(syncErrorRecorder).record(SyncErrorType.DATABASE_UNAVAILABLE, LocalVersionReader.VERSION_UNREADABLE);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siNoSePuedeAbrirLaTransaccionTambienEsUnResultado() {
        // Con la base caída, Spring falla al abrir la transacción: es una TransactionException, no una DataAccessException.
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        assertThat(useCase.triggerManualSync(SyncMode.SNAPSHOT)).isInstanceOf(ManualSyncResult.Failed.class);
    }

    private static SyncStatus estadoConVersion(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
        return estado;
    }
}
