package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.CheckCatalogVersionUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * CU-03 con el disparador {@link SyncTrigger#PERIODIC} (DP-04). Es el mismo flujo que el del aviso de Kafka, sin las
 * barreras del aviso: no trae {@code eventId}, así que su barrera de idempotencia es la comparación de versiones de CU-03.
 * <p>
 * <strong>Sin esperar la exclusión</strong> ({@link ExclusiveSync#runIfFree}): si hay otra sincronización en curso o
 * esperando, esa ya va a comparar versiones; esta pasada se saltea sin registrar nada y la próxima vuelve a intentar. La
 * versión se lee adentro de la exclusión, con {@link LocalVersionReader}. Sin versión local, CU-03 hace el snapshot: así
 * se recupera un arranque que no pudo armar la primera copia (CU-01, flujo 2a).
 * <p>
 * Igual que CU-01 y CU-08, <strong>no puede ser {@code @Transactional}</strong>: ver {@link ExclusiveSync}.
 */
@Component
@RequiredArgsConstructor
public class CheckCatalogVersionUseCaseImpl implements CheckCatalogVersionUseCase {

    private final LocalVersionReader localVersionReader;
    private final IncorporateChangesUseCase incorporateChangesUseCase;
    private final ExclusiveSync exclusiveSync;

    @Override
    public void checkCatalogVersion() {
        exclusiveSync.runIfFree(SyncTrigger.PERIODIC, () ->
            localVersionReader.withLocalVersion(
                SyncTrigger.PERIODIC,
                localVersion -> {
                    incorporateChangesUseCase.incorporate(SyncTrigger.PERIODIC, localVersion);
                    return true;
                },
                () -> false
            )
        );
    }
}
