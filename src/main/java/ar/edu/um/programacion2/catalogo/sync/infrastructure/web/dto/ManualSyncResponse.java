package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;

/**
 * Respuesta {@code 200} de {@code POST /api/admin/sync} (CU-08, paso 4). {@code localVersionBefore} es {@code null} si la
 * base no tenía versión ("sin datos"). {@code path} es {@code INCREMENTAL}, {@code SNAPSHOT} o {@code NONE} ("sin
 * cambios": la copia ya estaba en la versión publicada, y las dos versiones son iguales).
 */
public record ManualSyncResponse(SyncMode mode, Long localVersionBefore, long localVersionAfter, ManualSyncPath path, int attempts) {}
