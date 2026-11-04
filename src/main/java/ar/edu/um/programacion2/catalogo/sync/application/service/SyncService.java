package ar.edu.um.programacion2.catalogo.sync.application.service;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.InitializeCatalogUseCase;
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
    private final GetSyncStatusUseCase getSyncStatusUseCase;

    public void initializeCatalog() {
        initializeCatalogUseCase.initializeCatalog();
    }

    public SyncStatus getSyncStatus() {
        return getSyncStatusUseCase.getSyncStatus();
    }
}
