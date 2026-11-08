package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.TriggerManualSyncUseCase;
import java.util.Objects;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

/**
 * CU-08, con el disparador {@link SyncTrigger#MANUAL} y dentro de la exclusión mutua (DP-09) en los dos modos:
 * <ul>
 * <li>{@code NORMAL} corre CU-03 ({@link IncorporateChangesUseCase}): incremental, snapshot o sin cambios, según Redis;</li>
 * <li>{@code SNAPSHOT} corre CU-02 aunque la copia esté al día.</li>
 * </ul>
 * La versión "antes" se lee adentro de la exclusión: leída afuera, otra sincronización podría cambiarla durante la
 * espera. "Después" sale del resultado: en modo normal, si CU-04 alcanzó a aplicar algunas versiones antes de fallar,
 * es la última aplicada; en modo snapshot, si falló, CU-02 garantiza que no cambió.
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
    private final IncorporateChangesUseCase incorporateChangesUseCase;
    private final ExclusiveSync exclusiveSync;
    private final SyncErrorRecorder syncErrorRecorder;

    @Override
    public ManualSyncResult triggerManualSync(SyncMode mode) {
        Objects.requireNonNull(mode, "Falta el modo de la sincronización");
        return switch (mode) {
            case NORMAL -> exclusiveSync
                .run(SyncTrigger.MANUAL, () -> withVersionBefore(this::incorporate))
                .orElseGet(ManualSyncResult.OtherSyncInProgress::new);
            case SNAPSHOT -> exclusiveSync
                .run(SyncTrigger.MANUAL, () -> withVersionBefore(this::forceSnapshot))
                .orElseGet(ManualSyncResult.OtherSyncInProgress::new);
        };
    }

    /** Modo normal: CU-03 con el disparador {@link SyncTrigger#MANUAL}, que elige el camino con Redis. */
    private ManualSyncResult incorporate(Long before) {
        return switch (incorporateChangesUseCase.incorporate(SyncTrigger.MANUAL, before)) {
            case IncorporationResult.Applied applied -> new ManualSyncResult.Applied(
                before,
                applied.localVersionAfter(),
                applied.path(),
                applied.attempts()
            );
            case IncorporationResult.UpToDate upToDate -> new ManualSyncResult.UpToDate(
                upToDate.localVersion(),
                upToDate.remoteVersion(),
                upToDate.attempts()
            );
            case IncorporationResult.Failed failed -> new ManualSyncResult.Failed(
                before,
                failed.localVersionAfter(),
                failed.errorType(),
                failed.description(),
                failed.attempts()
            );
        };
    }

    private ManualSyncResult forceSnapshot(Long versionBefore) {
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

    /**
     * Lee la versión "antes", adentro de la exclusión, y corre el modo con ella ({@code null} si no hay copia). Si la base
     * falla, no se intenta nada: se informa como resultado, igual que las fallas de CU-02 (CU-08, flujo 3b).
     */
    private ManualSyncResult withVersionBefore(Function<Long, ManualSyncResult> mode) {
        Long before;
        try {
            before = getSyncStatusUseCase.getSyncStatus().getLocalVersion();
        } catch (DataAccessException | TransactionException e) {
            LOG.error("Sincronización manual: {}", VERSION_UNREADABLE, e);
            syncErrorRecorder.record(SyncErrorType.DATABASE_UNAVAILABLE, VERSION_UNREADABLE);
            return new ManualSyncResult.Failed(null, null, SyncErrorType.DATABASE_UNAVAILABLE, VERSION_UNREADABLE, 0);
        }
        return mode.apply(before);
    }
}
