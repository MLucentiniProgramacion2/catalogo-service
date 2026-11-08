package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncorporationResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;

/**
 * CU-03, pasos 6 a 10, para cualquier disparador: leer la metadata de Redis, registrar la versión remota y elegir entre
 * el incremental (CU-04) y el snapshot (CU-02). Lo usan el aviso de Kafka y CU-08 en modo normal; después, el arranque y
 * la comprobación periódica.
 * <p>
 * Lo que es propio de cada disparador queda afuera: las barreras del aviso, la exclusión mutua (DP-09) y la lectura de
 * la versión local, que el disparador hace <strong>adentro</strong> de la exclusión. Hay que llamarlo con la exclusión
 * tomada y sin una transacción abierta.
 */
public interface IncorporateChangesUseCase {
    /** @param localVersion la versión local leída con la exclusión tomada; {@code null} si no hay copia. */
    IncorporationResult incorporate(SyncTrigger trigger, Long localVersion);

    /**
     * Igual, y además el {@code eventId} del aviso queda registrado en la transacción que confirma el resultado: la de la
     * última versión incremental, la del snapshot, o la corta del flujo 7a. Si falla, no queda (DP-07).
     */
    IncorporationResult incorporate(SyncTrigger trigger, Long localVersion, CatalogUpdateNotice notice);
}
