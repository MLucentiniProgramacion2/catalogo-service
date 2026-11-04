package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;

/** Leer el estado de sincronización guardado: lo usan CU-01 y CU-08 para decidir y CU-09 para informarlo. */
public interface GetSyncStatusUseCase {
    SyncStatus getSyncStatus();
}
