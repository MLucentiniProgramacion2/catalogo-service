package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import java.time.Instant;

/**
 * Respuesta de {@code GET /api/sync/status} (CU-09).
 * <p>
 * "Sin datos" es {@code localVersion} en {@code null}. Cada grupo es {@code null} entero si no hay datos, igual que la
 * regla "todos o ninguno" de {@code SyncStatus}: la app no tiene que considerar un grupo a medias.
 *
 * @param localVersion versión aplicada, o {@code null} si nunca se sincronizó
 * @param inProgress si hay una sincronización corriendo ahora
 * @param lastSuccess última sincronización exitosa
 * @param remote última versión publicada conocida, de la última lectura de metadata; no se consulta Redis en cada pedido
 * @param lastError último error, aunque después haya habido un éxito: cada uno trae su fecha, y {@code resolved} dice si
 *        después hubo un éxito o una lectura de la metadata de Redis (ver {@code SyncStatus.isLastErrorResolved})
 */
public record SyncStatusResponse(Long localVersion, boolean inProgress, LastSuccess lastSuccess, Remote remote, LastError lastError) {
    public record LastSuccess(Instant at, SyncPath path, SyncTrigger trigger) {}

    public record Remote(long version, Instant checkedAt) {}

    public record LastError(Instant at, SyncErrorType type, String description, boolean resolved) {}
}
