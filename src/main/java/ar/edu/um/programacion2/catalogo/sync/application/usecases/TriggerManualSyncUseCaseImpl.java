package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.TriggerManualSyncUseCase;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * CU-08. El modo {@code SNAPSHOT} corre CU-02 con el disparador {@link SyncTrigger#MANUAL} dentro de la exclusión mutua
 * (DP-09). El modo {@code NORMAL} necesita CU-03, que todavía no existe: se responde que no está disponible, sin caer en
 * un snapshot, sin tomar la exclusión y sin registrar un error. El issue de CU-03 lo conecta acá.
 * <p>
 * La versión "antes" se lee adentro de la exclusión: leída afuera, otra sincronización podría cambiarla durante la
 * espera. "Después" no hace falta leerla: si se aplicó es la del snapshot, y si falló CU-02 garantiza que no cambió.
 * <p>
 * <strong>No puede ser {@code @Transactional}</strong>, por lo mismo que CU-01: ver {@link ExclusiveSync}.
 */
@Component
@RequiredArgsConstructor
public class TriggerManualSyncUseCaseImpl implements TriggerManualSyncUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(TriggerManualSyncUseCaseImpl.class);

    static final String VERSION_UNREADABLE = "No se pudo leer la versión local en la base de datos: no se intentó la sincronización";

    private final GetSyncStatusUseCase getSyncStatusUseCase;
    private final ExecuteFullSyncUseCase executeFullSyncUseCase;
    private final ExclusiveSync exclusiveSync;
    private final SyncErrorRecorder syncErrorRecorder;

    @Override
    public ManualSyncResult triggerManualSync(SyncMode mode) {
        Objects.requireNonNull(mode, "Falta el modo de la sincronización");
        return switch (mode) {
            case NORMAL -> new ManualSyncResult.ModeNotAvailable(mode);
            case SNAPSHOT -> exclusiveSync
                .run(SyncTrigger.MANUAL, this::forceSnapshot)
                .orElseGet(ManualSyncResult.OtherSyncInProgress::new);
        };
    }

    private ManualSyncResult forceSnapshot() {
        Long versionBefore;
        try {
            versionBefore = getSyncStatusUseCase.getSyncStatus().getLocalVersion();
        } catch (DataAccessException | TransactionException e) {
            // CU-08, flujo 3b: una falla de la base se informa como resultado, igual que las de CU-02.
            LOG.error("Sincronización manual: {}", VERSION_UNREADABLE, e);
            syncErrorRecorder.record(SyncErrorType.DATABASE_UNAVAILABLE, VERSION_UNREADABLE);
            return new ManualSyncResult.Failed(null, null, SyncErrorType.DATABASE_UNAVAILABLE, VERSION_UNREADABLE, 0);
        }
        return switch (executeFullSyncUseCase.executeFullSync(SyncTrigger.MANUAL)) {
            case FullSyncResult.Applied applied -> new ManualSyncResult.Applied(
                versionBefore,
                applied.snapshotVersion(),
                SyncPath.SNAPSHOT,
                applied.attempts()
            );
            case FullSyncResult.Failed failed -> new ManualSyncResult.Failed(
                versionBefore,
                versionBefore,
                failed.errorType(),
                failed.description(),
                failed.attempts()
            );
        };
    }
}
