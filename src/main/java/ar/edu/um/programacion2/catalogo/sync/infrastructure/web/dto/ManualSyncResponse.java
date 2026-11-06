package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;

/**
 * Respuesta {@code 200} de {@code POST /api/admin/sync} (CU-08, paso 4). {@code localVersionBefore} es {@code null} si la
 * base no tenía versión ("sin datos"). Por ahora {@code path} es siempre {@code SNAPSHOT}: el incremental y "sin cambios"
 * llegan con CU-04.
 */
public record ManualSyncResponse(SyncMode mode, Long localVersionBefore, long localVersionAfter, SyncPath path, int attempts) {}
