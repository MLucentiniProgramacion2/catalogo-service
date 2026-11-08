package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import ar.edu.um.programacion2.catalogo.sync.domain.model.IncrementalSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.domain.model.VersionChanges;
import java.util.SortedMap;

/**
 * CU-04: aplicar en orden las versiones pendientes, con una transacción por versión. Es un caso incluido por CU-03, como
 * CU-02: hay que llamarlo con la exclusión mutua tomada y <strong>sin</strong> una transacción abierta, porque abre las
 * suyas y lee Redis afuera de ellas.
 */
public interface ApplyIncrementalSyncUseCase {
    /**
     * Aplica desde {@code localVersion + 1} hasta la última de {@code pending}, con los reintentos de DP-08 y el plazo del
     * lote.
     *
     * @param localVersion la versión local que leyó quien llama, con la exclusión tomada.
     * @param pending los cambios de cada versión pendiente, ya leídos y sin huecos (CU-03, paso 8).
     * @return el resultado, bueno o malo. Una falla no sale como excepción.
     */
    IncrementalSyncResult applyIncremental(SyncTrigger trigger, long localVersion, SortedMap<Long, VersionChanges> pending);

    /**
     * Igual que {@link #applyIncremental(SyncTrigger, long, SortedMap)}, y además registra el {@code eventId} del aviso en
     * la transacción de la <strong>última</strong> versión del lote (DP-07): si algo falla antes, no queda registrado.
     */
    IncrementalSyncResult applyIncremental(
        SyncTrigger trigger,
        long localVersion,
        SortedMap<Long, VersionChanges> pending,
        CatalogUpdateNotice notice
    );
}
