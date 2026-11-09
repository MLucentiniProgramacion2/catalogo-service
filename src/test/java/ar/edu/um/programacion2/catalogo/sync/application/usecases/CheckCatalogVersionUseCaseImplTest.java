package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
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

/** La comprobación periódica (DP-04): CU-03 con PERIODIC, sin esperar la exclusión. */
@ExtendWith(MockitoExtension.class)
class CheckCatalogVersionUseCaseImplTest {

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private IncorporateChangesUseCase incorporateChangesUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    /** Espera larga: si la periódica la usara, el test con la exclusión ocupada quedaría parado 30 s. */
    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofSeconds(30));

    private CheckCatalogVersionUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        useCase = new CheckCatalogVersionUseCaseImpl(
            new LocalVersionReader(getSyncStatusUseCase, syncErrorRecorder),
            incorporateChangesUseCase,
            new ExclusiveSync(lock, syncErrorRecorder)
        );
    }

    @Test
    void correCu03ConElDisparadorPeriodicoYLaVersionLocal() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion(12));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.PERIODIC, 12L)).thenReturn(
            new IncorporationResult.Applied(12L, 13, SyncPath.INCREMENTAL, 1)
        );

        useCase.checkCatalogVersion();

        verify(incorporateChangesUseCase).incorporate(SyncTrigger.PERIODIC, 12L);
    }

    @Test
    void sinVersionLocalCorreCu03SinVersionQueHaceElSnapshot() {
        // Recupera un arranque que no pudo armar la primera copia (CU-01, flujo 2a).
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(incorporateChangesUseCase.incorporate(SyncTrigger.PERIODIC, null)).thenReturn(
            new IncorporationResult.Applied(null, 12, SyncPath.SNAPSHOT, 1)
        );

        useCase.checkCatalogVersion();

        verify(incorporateChangesUseCase).incorporate(SyncTrigger.PERIODIC, null);
    }

    @Test
    void leeLaVersionYSincronizaConLaExclusionTomada() {
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return conVersion(12);
        });
        when(incorporateChangesUseCase.incorporate(SyncTrigger.PERIODIC, 12L)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new IncorporationResult.UpToDate(12, 12, 1);
        });

        useCase.checkCatalogVersion();

        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void conOtraSincronizacionEnCursoSeSalteaSinEsperarNiRegistrarNada() {
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            useCase.checkCatalogVersion();
        }

        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase, syncErrorRecorder);
    }

    @Test
    void conLaBaseCaidaNoIntentaNadaYRegistraElError() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThatCode(useCase::checkCatalogVersion).doesNotThrowAnyException();

        verifyNoInteractions(incorporateChangesUseCase);
        verify(syncErrorRecorder).record(SyncErrorType.DATABASE_UNAVAILABLE, LocalVersionReader.VERSION_UNREADABLE);
    }

    @Test
    void siCu03FallaTerminaSinExcepcion() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion(12));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.PERIODIC, 12L)).thenReturn(
            new IncorporationResult.Failed(12L, 12L, SyncErrorType.REDIS_UNAVAILABLE, "Redis de la cátedra no respondió", 4)
        );

        assertThatCode(useCase::checkCatalogVersion).doesNotThrowAnyException();
    }

    private static SyncStatus conVersion(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.MANUAL, Instant.parse("2026-10-30T12:00:00Z"));
        return estado;
    }
}
