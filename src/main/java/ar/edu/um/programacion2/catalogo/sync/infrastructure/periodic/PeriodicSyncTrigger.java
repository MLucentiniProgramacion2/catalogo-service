package ar.edu.um.programacion2.catalogo.sync.infrastructure.periodic;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador de entrada de la comprobación periódica de versión (DP-04): cada pasada llama a {@link SyncService}, como el
 * listener de Kafka y el disparador de arranque. Lo programa {@link PeriodicSyncConfiguration}.
 * <p>
 * <strong>Nunca propaga.</strong> El scheduler de Spring, ante una excepción de una tarea repetida, la loguea y sigue;
 * igual se atrapa acá para que el comportamiento no dependa de eso y el log diga qué pasó. Lo esperado (Redis caído, la
 * cátedra caída) ya lo registra CU-03 en el estado de sincronización; acá solo llega lo inesperado.
 */
@Component
@RequiredArgsConstructor
public class PeriodicSyncTrigger {

    private static final Logger LOG = LoggerFactory.getLogger(PeriodicSyncTrigger.class);

    private final SyncService syncService;

    public void checkCatalogVersion() {
        try {
            syncService.checkCatalogVersion();
        } catch (RuntimeException e) {
            LOG.error("Comprobación periódica: falló de forma inesperada; se vuelve a intentar en la próxima pasada", e);
        }
    }
}
