package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;

/** Leer el estado de sincronización guardado: lo usa CU-01 al arrancar y lo va a informar CU-09. */
public interface GetSyncStatusUseCase {
    SyncStatus getSyncStatus();
}
