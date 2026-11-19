package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ProcessedEvent;

/**
 * Eventos {@code CatalogUpdated} ya procesados (DP-07). Las dos operaciones se suman a la transacción de quien las llama
 * y fallan si no hay una abierta: así el {@code eventId} puede confirmarse o deshacerse junto con los datos (CU-03, paso 9).
 */
public interface ProcessedEventRepository {
    /** Primera barrera de idempotencia (CU-03, paso 3): si el evento ya se procesó. */
    boolean exists(String eventId);

    /**
     * Registra el evento si no estaba. Si ya estaba, no cambia nada y la transacción de quien llama sigue viva: un dato de
     * registro repetido nunca puede deshacer un snapshot entero.
     */
    void registerIfAbsent(ProcessedEvent event);
}
