package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.mapper;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto.ManualSyncResponse;
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
}
