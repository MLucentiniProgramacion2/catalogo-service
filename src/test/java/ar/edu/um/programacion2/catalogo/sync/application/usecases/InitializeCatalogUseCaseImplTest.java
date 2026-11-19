package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

/** CU-01: siempre CU-03 con el disparador de arranque, con la versión leída adentro de la exclusión. */
@ExtendWith(MockitoExtension.class)
class InitializeCatalogUseCaseImplTest {

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private IncorporateChangesUseCase incorporateChangesUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));

    private InitializeCatalogUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        useCase = new InitializeCatalogUseCaseImpl(
            new LocalVersionReader(getSyncStatusUseCase, syncErrorRecorder),
            incorporateChangesUseCase,
            new ExclusiveSync(lock, syncErrorRecorder)
        );
    }

    @Test
    void sinVersionLocalLlamaACu03SinVersionQueVaAlSnapshot() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, null)).thenReturn(
            new IncorporationResult.Applied(null, 7, SyncPath.SNAPSHOT, 1)
        );

        useCase.initializeCatalog();

        verify(incorporateChangesUseCase).incorporate(SyncTrigger.STARTUP, null);
    }

    @Test
    void conLaVersionAtrasadaLlamaACu03ConElDisparadorDeArranqueYLaVersionLocal() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion(5));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, 5L)).thenReturn(
            new IncorporationResult.Applied(5L, 7, SyncPath.INCREMENTAL, 1)
        );

        useCase.initializeCatalog();

        verify(incorporateChangesUseCase).incorporate(SyncTrigger.STARTUP, 5L);
    }

    @Test
    void conLaCopiaAlDiaTerminaSinCambiosNiExcepcion() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion(7));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, 7L)).thenReturn(new IncorporationResult.UpToDate(7, 7, 1));

        assertThatCode(useCase::initializeCatalog).doesNotThrowAnyException();
        verify(incorporateChangesUseCase).incorporate(SyncTrigger.STARTUP, 7L);
    }

    @Test
    void siCu03FallaTerminaSinExcepcion() {
        // El error ya lo registró CU-03 (por ejemplo Redis caído, flujo 3a): el servicio sigue con la copia que tiene.
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion(5));
        when(incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, 5L)).thenReturn(
            new IncorporationResult.Failed(5L, 5L, SyncErrorType.REDIS_UNAVAILABLE, "Redis de la cátedra no respondió", 4)
        );

        assertThatCode(useCase::initializeCatalog).doesNotThrowAnyException();
    }

    @Test
    void leeLaVersionYSincronizaConLaExclusionTomada() {
        // Leer adentro evita repetir lo que otro aplicó mientras este esperaba (DP-09).
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return conVersion(5);
        });
        when(incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, 5L)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new IncorporationResult.UpToDate(5, 5, 1);
        });

        useCase.initializeCatalog();

        verify(incorporateChangesUseCase).incorporate(SyncTrigger.STARTUP, 5L);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siHayOtraSincronizacionYVenceLaEsperaTerminaSinCambiosYLoRegistra() {
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            useCase.initializeCatalog();
        }

        verifyNoInteractions(getSyncStatusUseCase, incorporateChangesUseCase);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), contains("STARTUP"));
    }

    @Test
    void conLaBaseCaidaNoIntentaNadaYRegistraElError() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("sin conexión"));

        assertThatCode(useCase::initializeCatalog).doesNotThrowAnyException();

        verifyNoInteractions(incorporateChangesUseCase);
        verify(syncErrorRecorder).record(SyncErrorType.DATABASE_UNAVAILABLE, LocalVersionReader.VERSION_UNREADABLE);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void unaFallaInesperadaLiberaLaExclusionYPropaga() {
        // La atrapa StartupSyncTrigger; la exclusión no puede quedar tomada para los demás disparadores.
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new IllegalStateException("un error de programación"));

        assertThatThrownBy(useCase::initializeCatalog).isInstanceOf(IllegalStateException.class);

        assertThat(lock.isHeld()).isFalse();
    }

    private static SyncStatus conVersion(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.MANUAL, Instant.parse("2026-10-30T12:00:00Z"));
        return estado;
    }
}
