package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

@ExtendWith(MockitoExtension.class)
class LocalVersionReaderTest {

    @Mock
    private GetSyncStatusUseCase getSyncStatusUseCase;

    @Mock
    private SyncErrorRecorder syncErrorRecorder;

    private LocalVersionReader reader;

    @BeforeEach
    void armar() {
        reader = new LocalVersionReader(getSyncStatusUseCase, syncErrorRecorder);
    }

    @Test
    void pasaLaVersionLeida() {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(12, SyncTrigger.MANUAL, Instant.parse("2026-11-01T12:00:00Z"));
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(estado);

        assertThat(reader.withLocalVersion(SyncTrigger.PERIODIC, version -> "con " + version, () -> "ilegible")).isEqualTo("con 12");
    }

    @Test
    void sinCopiaPasaNull() {
        when(getSyncStatusUseCase.getSyncStatus()).thenReturn(SyncStatus.initial());

        assertThat(reader.withLocalVersion(SyncTrigger.STARTUP, version -> "con " + version, () -> "ilegible")).isEqualTo("con null");
        verifyNoInteractions(syncErrorRecorder);
    }

    @Test
    void conLaBaseCaidaNoCorreYRegistraElTextoFijo() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new DataAccessResourceFailureException("host-interno:5432 sin conexión"));

        assertThat(reader.withLocalVersion(SyncTrigger.STARTUP, version -> "corrió", () -> "ilegible")).isEqualTo("ilegible");
        verify(syncErrorRecorder).record(SyncErrorType.DATABASE_UNAVAILABLE, LocalVersionReader.VERSION_UNREADABLE);
    }

    @Test
    void sinPoderAbrirLaTransaccionTambienEsIlegible() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager"));

        assertThat(reader.withLocalVersion(SyncTrigger.PERIODIC, version -> "corrió", () -> "ilegible")).isEqualTo("ilegible");
    }

    @Test
    void otraFallaSaleTalCual() {
        when(getSyncStatusUseCase.getSyncStatus()).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> reader.withLocalVersion(SyncTrigger.PERIODIC, version -> "corrió", () -> "ilegible")).isInstanceOf(
            IllegalStateException.class
        );
        verifyNoInteractions(syncErrorRecorder);
    }
}
