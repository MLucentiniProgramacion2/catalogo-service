package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.mapper;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto.ManualSyncResponse;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto.SyncStatusResponse;
import org.springframework.stereotype.Component;

/** Traduce los resultados de la sincronización a los DTO de la API. */
@Component
public class SyncDtoMapper {

    public ManualSyncResponse toResponse(SyncMode mode, ManualSyncResult.Applied applied) {
        return new ManualSyncResponse(
            mode,
            applied.localVersionBefore(),
            applied.localVersionAfter(),
            applied.path(),
            applied.attempts()
        );
    }

    public SyncStatusResponse toResponse(SyncOverview overview) {
        SyncStatus status = overview.status();
        return new SyncStatusResponse(
            status.getLocalVersion(),
            overview.inProgress(),
            status.getLastSuccessAt() == null
                ? null
                : new SyncStatusResponse.LastSuccess(status.getLastSuccessAt(), status.getLastSuccessPath(), status.getLastSuccessTrigger()),
            status.getRemoteVersion() == null
                ? null
                : new SyncStatusResponse.Remote(status.getRemoteVersion(), status.getRemoteVersionCheckedAt()),
            status.getLastErrorAt() == null
                ? null
                : new SyncStatusResponse.LastError(status.getLastErrorAt(), status.getLastErrorType(), status.getLastErrorDescription())
        );
    }
}
