package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;

/** CU-09: consultar el estado de sincronización. No tiene efectos y nunca falla por que la base esté vacía. */
public interface GetSyncOverviewUseCase {
    SyncOverview getSyncOverview();
}
