package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.IncorporateChangesUseCase;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.in.InitializeCatalogUseCase;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CU-01: al arrancar, corre CU-03 con el disparador {@link SyncTrigger#STARTUP}.
 * <p>
 * CU-01 no elige el camino: lo elige CU-03 ({@link IncorporateChangesUseCase}), que es el único lugar con la regla. Sin
 * versión local, CU-03 va directo a CU-02 sin leer Redis (la primera copia viene por REST); con versión, compara con la
 * publicada en Redis y aplica lo que falte por incremental o por snapshot. Así el arranque con la copia atrasada (el
 * servicio estuvo apagado mientras la cátedra publicaba) se recupera igual que un aviso de Kafka (DP-04). El resultado
 * ya queda registrado en el estado de sincronización y en el log: acá no hay nada más que hacer con él.
 * <p>
 * Corre con la exclusión mutua tomada (DP-09), y la versión se lee <strong>adentro</strong>, con {@link LocalVersionReader}:
 * si se leyera antes y hubiera que esperar, otra sincronización podría cambiarla mientras tanto. Si vence la espera,
 * {@link ExclusiveSync} lo registra y el arranque termina sin cambios.
 * <p>
 * <strong>No puede ser {@code @Transactional}</strong>: CU-03 y CU-02 tienen {@code Propagation.NEVER}, porque los pedidos
 * a Redis y a la cátedra no pueden quedar dentro de una transacción. La lectura de la versión tiene su propia
 * transacción de solo lectura, que termina antes de llamar a CU-03.
 */
@Component
@RequiredArgsConstructor
public class InitializeCatalogUseCaseImpl implements InitializeCatalogUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(InitializeCatalogUseCaseImpl.class);

    private final LocalVersionReader localVersionReader;
    private final IncorporateChangesUseCase incorporateChangesUseCase;
    private final ExclusiveSync exclusiveSync;

    @Override
    public void initializeCatalog() {
        exclusiveSync.run(SyncTrigger.STARTUP, () -> localVersionReader.withLocalVersion(SyncTrigger.STARTUP, this::incorporate, () -> false));
    }

    private boolean incorporate(Long localVersion) {
        if (localVersion != null) {
            LOG.info("Arranque: versión local del catálogo {}; se compara con la publicada (CU-03)", localVersion);
        }
        incorporateChangesUseCase.incorporate(SyncTrigger.STARTUP, localVersion);
        return true;
    }
}
