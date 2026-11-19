package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;

/**
 * CU-08: un administrador fuerza una sincronización, sin esperar a Kafka ni a la comprobación periódica.
 * <p>
 * Es sincrónico: vuelve cuando la sincronización terminó, así que puede tardar minutos. Hay que llamarlo sin una
 * transacción abierta (DP-09).
 */
public interface TriggerManualSyncUseCase {
    /** @return el resultado, bueno o malo. Que la sincronización falle no sale como excepción. */
    ManualSyncResult triggerManualSync(SyncMode mode);
}
