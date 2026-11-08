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
import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InitializeCatalogUseCaseImplTest {

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));

    private InitializeCatalogUseCaseImpl useCase;

    @BeforeEach
    void armarCasoDeUso() {
        useCase = new InitializeCatalogUseCaseImpl(getSyncStatusUseCase, executeFullSyncUseCase, new ExclusiveSync(lock, syncErrorRecorder));
    }

    @Test
    void sinVersionLocalEjecutaLaSincronizacionCompletaConElDisparadorDeArranque() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).thenReturn(new FullSyncResult.Applied(7, 1));

        useCase.initializeCatalog();

        verify(executeFullSyncUseCase).executeFullSync(SyncTrigger.STARTUP);
    }

    @Test
    void conVersionLocalNoLlamaALaCatedra() {
        SyncStatus conVersion = SyncStatus.initial();
        conVersion.recordSnapshotApplied(5, SyncTrigger.STARTUP, Instant.parse("2026-10-30T12:00:00Z"));
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(conVersion);

        useCase.initializeCatalog();

        verifyNoInteractions(executeFullSyncUseCase);
    }

    @Test
    void siLaSincronizacionCompletaFallaTerminaSinExcepcion() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).thenReturn(
            new FullSyncResult.Failed(SyncErrorType.INVALID_CREDENTIALS, "La API REST de la cátedra rechazó la credencial técnica", 1)
        );

        assertThatCode(useCase::initializeCatalog).doesNotThrowAnyException();
    }

    @Test
    void leeElEstadoYSincronizaConLaExclusionTomada() {
        // Leer adentro evita repetir un snapshot que otro aplicó mientras este esperaba (DP-09).
        when(getSyncStatusUseCase.getSyncStatus()).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return SyncStatus.initial();
        });
        when(executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP)).thenAnswer(invocation -> {
            assertThat(lock.isHeld()).isTrue();
            return new FullSyncResult.Applied(7, 1);
        });

        useCase.initializeCatalog();

        verify(executeFullSyncUseCase).executeFullSync(SyncTrigger.STARTUP);
        assertThat(lock.isHeld()).isFalse();
    }

    @Test
    void siHayOtraSincronizacionYVenceLaEsperaTerminaSinCambiosYLoRegistra() {
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            useCase.initializeCatalog();
        }

        verifyNoInteractions(getSyncStatusUseCase, executeFullSyncUseCase);
        verify(syncErrorRecorder).record(eq(SyncErrorType.LOCK_TIMEOUT), contains("STARTUP"));
    }

    @Test
    void siLeerElEstadoFallaLiberaLaExclusionYPropaga() {
        // La falla la atrapa StartupSyncTrigger; la exclusión no puede quedar tomada para los demás disparadores.
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new IllegalStateException("base caída"));

        assertThatThrownBy(useCase::initializeCatalog).isInstanceOf(IllegalStateException.class);

        assertThat(lock.isHeld()).isFalse();
    }
}
