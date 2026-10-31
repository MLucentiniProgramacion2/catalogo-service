package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.FullSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;

/**
 * CU-02: reemplazar la copia local por el snapshot de la cátedra y guardar su versión, en una sola transacción.
 * <p>
 * Abre sus propias transacciones: hay que llamarlo <strong>sin</strong> una transacción abierta, porque el pedido a la
 * cátedra no puede quedar dentro de una. El resultado, bueno o malo, queda registrado en el estado de sincronización.
 */
public interface ExecuteFullSyncUseCase {
    /**
     * Ejecuta la sincronización completa, con los reintentos de DP-08.
     *
     * @param trigger qué la disparó; queda registrado junto con la versión aplicada.
     * @return si se aplicó o por qué no. Una falla de la sincronización no sale como excepción.
     */
    FullSyncResult executeFullSync(SyncTrigger trigger);
}
