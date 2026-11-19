package ar.edu.um.programacion2.catalogo.sync.application.service;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.CheckCatalogVersionUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncOverviewUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.HandleCatalogUpdatedUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.InitializeCatalogUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.TriggerManualSyncUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada de la sincronización: los adaptadores de entrada (arranque, Kafka, REST y la comprobación periódica) llaman acá y
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
    private final HandleCatalogUpdatedUseCase handleCatalogUpdatedUseCase;
    private final CheckCatalogVersionUseCase checkCatalogVersionUseCase;

    public void initializeCatalog() {
        initializeCatalogUseCase.initializeCatalog();
    }

    public void checkCatalogVersion() {
        checkCatalogVersionUseCase.checkCatalogVersion();
    }

    public ManualSyncResult triggerManualSync(SyncMode mode) {
        return triggerManualSyncUseCase.triggerManualSync(mode);
    }

    public SyncOverview getSyncOverview() {
        return getSyncOverviewUseCase.getSyncOverview();
    }

    public CatalogEventResult handleCatalogUpdated(CatalogUpdateNotice notice) {
        return handleCatalogUpdatedUseCase.handle(notice);
    }

    public CatalogEventResult rejectInvalidCatalogEvent() {
        return handleCatalogUpdatedUseCase.rejectInvalid();
    }

    public void reportCatalogEventFailure() {
        handleCatalogUpdatedUseCase.reportUnexpectedFailure();
    }
}
