package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.ExecuteFullSyncUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.InitializeCatalogUseCase;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CU-01: al arrancar, si la base no tiene versión local, ejecuta CU-02 con el disparador {@link SyncTrigger#STARTUP}.
 * <p>
 * CU-01 incluye a CU-02, así que depende de su puerto de entrada, no de su implementación. El resultado de CU-02 (bueno
 * o malo) ya queda registrado en el estado de sincronización y en el log; acá no hay nada más que hacer con él.
 * <p>
 * <strong>No puede ser {@code @Transactional}</strong>: CU-02 tiene {@code Propagation.NEVER} y fallaría sin pedir el
 * snapshot, porque el pedido a la cátedra no puede quedar dentro de una transacción. La lectura del estado tiene su
 * propia transacción de solo lectura, en {@link GetSyncStatusUseCaseImpl}, que termina antes de llamar a CU-02.
 */
@Component
@RequiredArgsConstructor
public class InitializeCatalogUseCaseImpl implements InitializeCatalogUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(InitializeCatalogUseCaseImpl.class);

    private final GetSyncStatusUseCase getSyncStatusUseCase;
    private final ExecuteFullSyncUseCase executeFullSyncUseCase;

    @Override
    public void initializeCatalog() {
        SyncStatus status = getSyncStatusUseCase.getSyncStatus();
        if (!status.hasLocalCopy()) {
            LOG.info("Arranque: no hay versión local del catálogo; se sincroniza con un snapshot (CU-02)");
            executeFullSyncUseCase.executeFullSync(SyncTrigger.STARTUP);
            return;
        }
        // Con versión local, CU-01 tiene que ejecutar CU-03 (comparar con Redis y aplicar lo que falte), que llega con
        // el issue de la sincronización incremental. Mientras tanto se sirve la copia guardada (DP-03).
        LOG.info(
            "Arranque: versión local del catálogo {}; la comparación con la versión publicada (CU-03) todavía no está implementada",
            status.getLocalVersion()
        );
    }
}
