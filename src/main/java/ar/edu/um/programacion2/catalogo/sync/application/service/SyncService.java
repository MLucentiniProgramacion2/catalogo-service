package ar.edu.um.programacion2.catalogo.sync.application.service;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncOverviewUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.InitializeCatalogUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.TriggerManualSyncUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada de la sincronización: los adaptadores de entrada (arranque, y después Kafka, temporizador y REST) llaman acá y
 * nunca a los casos de uso ni a los adaptadores de salida.
 * <p>
 * No expone CU-02: es un caso incluido que exige tener la exclusión mutua (DP-09), y desde acá se podría llamar sin ella.
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final InitializeCatalogUseCase initializeCatalogUseCase;
    private final GetSyncOverviewUseCase getSyncOverviewUseCase;
    private final TriggerManualSyncUseCase triggerManualSyncUseCase;

    public void initializeCatalog() {
        initializeCatalogUseCase.initializeCatalog();
    }

    public ManualSyncResult triggerManualSync(SyncMode mode) {
        return triggerManualSyncUseCase.triggerManualSync(mode);
    }

    public SyncOverview getSyncOverview() {
        return getSyncOverviewUseCase.getSyncOverview();
    }
}
