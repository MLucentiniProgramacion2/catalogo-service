package ar.edu.um.programacion2.catalogo.sync.application.service;

import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada de la sincronización: los adaptadores de entrada (arranque, y después Kafka, temporizador y REST) llaman acá y
 * nunca a los casos de uso ni a los adaptadores de salida.
 */
@Service
@RequiredArgsConstructor
public class SyncService {

    private final ExecuteFullSyncUseCase executeFullSyncUseCase;

    public FullSyncResult executeFullSync(SyncTrigger trigger) {
        return executeFullSyncUseCase.executeFullSync(trigger);
    }
}
