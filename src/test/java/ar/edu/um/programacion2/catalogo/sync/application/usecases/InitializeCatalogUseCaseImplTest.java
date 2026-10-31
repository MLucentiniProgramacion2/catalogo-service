package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InitializeCatalogUseCaseImplTest {

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private ExecuteFullSyncUseCase executeFullSyncUseCase;

    @InjectMocks
    private InitializeCatalogUseCaseImpl useCase;

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
            new FullSyncResult.Failed(SyncErrorType.INVALID_CREDENTIALS, "La cátedra rechazó la credencial técnica", 1)
        );

        assertThatCode(useCase::initializeCatalog).doesNotThrowAnyException();
    }
}
