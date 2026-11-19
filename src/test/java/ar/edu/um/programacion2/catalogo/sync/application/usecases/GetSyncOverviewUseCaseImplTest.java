package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.SyncLockHolder;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.lock.InMemorySyncLock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetSyncOverviewUseCaseImplTest {

    private static final Instant T1 = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-04T13:00:00Z");

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    @Mock
    private ExclusiveSync exclusiveSyncMock;

    private final InMemorySyncLock lock = new InMemorySyncLock(Duration.ofMillis(20));

    private GetSyncOverviewUseCaseImpl useCase() {
        return new GetSyncOverviewUseCaseImpl(new ExclusiveSync(lock, syncErrorRecorder), getSyncStatusUseCase);
    }

    @Test
    void sinDatosInformaElEstadoVacioYNadaEnCurso() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());

        SyncOverview overview = useCase().getSyncOverview();

        assertThat(overview.status().hasLocalCopy()).isFalse();
        assertThat(overview.inProgress()).isFalse();
    }

    @Test
    void informaElExitoYElErrorALaVez() {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(5, SyncTrigger.MANUAL, T1);
        estado.recordError(SyncErrorType.LOCK_TIMEOUT, "otra seguía en curso", T2);
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estado);

        SyncOverview overview = useCase().getSyncOverview();

        assertThat(overview.status().getLocalVersion()).isEqualTo(5L);
        assertThat(overview.status().getLastErrorType()).isEqualTo(SyncErrorType.LOCK_TIMEOUT);
    }

    @Test
    void conUnaSincronizacionCorriendoInformaEnCursoSinEsperarla() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());

        SyncOverview overview;
        try (SyncLockHolder otra = SyncLockHolder.hold(lock)) {
            overview = useCase().getSyncOverview();
        }

        assertThat(overview.inProgress()).isTrue();
    }

    @Test
    void miraEnCursoAntesDeLeerElEstado() {
        when(exclusiveSyncMock.isRunning()).thenReturn(false);
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());

        new GetSyncOverviewUseCaseImpl(exclusiveSyncMock, getSyncStatusUseCase).getSyncOverview();

        InOrder orden = inOrder(exclusiveSyncMock, getSyncStatusUseCase);
        orden.verify(exclusiveSyncMock).isRunning();
        orden.verify(getSyncStatusUseCase).getSyncStatus();
    }
}
