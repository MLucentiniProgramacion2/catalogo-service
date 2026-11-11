package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncOverview;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncOverviewUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.GetSyncStatusUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CU-09. El estado se lee con {@link GetSyncStatusUseCaseImpl}, en su transacción de solo lectura. Alcanza con esa: es
 * una sola fila leída una vez, y no hay dos consultas que tengan que coincidir como en CU-05 y CU-06.
 * <p>
 * "En curso" se mira <strong>antes</strong> de leer el estado. Las dos lecturas no son atómicas, y en este orden el peor
 * desfase es informar "en curso" con la versión ya nueva. Al revés, podría decir que no hay nada en curso con la versión
 * vieja, como si la sincronización que acaba de terminar no hubiera cambiado nada.
 */
@Component
@RequiredArgsConstructor
public class GetSyncOverviewUseCaseImpl implements GetSyncOverviewUseCase {

    private final ExclusiveSync exclusiveSync;
    private final GetSyncStatusUseCase getSyncStatusUseCase;

    @Override
    public SyncOverview getSyncOverview() {
        boolean inProgress = exclusiveSync.isRunning();
        return new SyncOverview(getSyncStatusUseCase.getSyncStatus(), inProgress);
    }
}
