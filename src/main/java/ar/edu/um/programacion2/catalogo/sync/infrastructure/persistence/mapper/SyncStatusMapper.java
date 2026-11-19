package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.mapper;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity;
import org.springframework.stereotype.Component;

/**
 * Traduce entre la fila de {@code sync_status} y {@link SyncStatus}.
 * <p>
 * {@code SyncStatus} no tiene setters: se reconstruye con su constructor, que valida que cada grupo de campos esté
 * completo o vacío. Si la base tuviera un grupo a medias, la lectura falla en vez de inventar un estado.
 */
@Component
public class SyncStatusMapper {

    public SyncStatus toDomain(SyncStatusEntity entity) {
        return new SyncStatus(
            entity.getLocalVersion(),
            entity.getLastSuccessAt(),
            entity.getLastSuccessPath(),
            entity.getLastSuccessTrigger(),
            entity.getRemoteVersion(),
            entity.getRemoteVersionCheckedAt(),
            entity.getLastErrorAt(),
            entity.getLastErrorType(),
            entity.getLastErrorDescription()
        );
    }

    /** Copia los nueve campos sobre una entidad nueva con el ID de la única fila. */
    public SyncStatusEntity toEntity(SyncStatus status) {
        SyncStatusEntity entity = new SyncStatusEntity();
        entity.setId(SyncStatusEntity.SINGLE_ROW_ID);
        entity.setLocalVersion(status.getLocalVersion());
        entity.setLastSuccessAt(status.getLastSuccessAt());
        entity.setLastSuccessPath(status.getLastSuccessPath());
        entity.setLastSuccessTrigger(status.getLastSuccessTrigger());
        entity.setRemoteVersion(status.getRemoteVersion());
        entity.setRemoteVersionCheckedAt(status.getRemoteVersionCheckedAt());
        entity.setLastErrorAt(status.getLastErrorAt());
        entity.setLastErrorType(status.getLastErrorType());
        entity.setLastErrorDescription(status.getLastErrorDescription());
        return entity;
    }
}
