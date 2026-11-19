package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;

/**
 * Estado de sincronización guardado (CU-02, CU-09). Hay uno solo por servicio y existe desde la instalación: el puerto
 * lo lee y lo reemplaza, nunca decide crearlo.
 * <p>
 * Las dos operaciones se suman a la transacción de quien las llama y fallan si no hay una abierta: así el caso de uso
 * puede guardar la versión aplicada en la misma transacción que los datos (CU-02, paso 6). Una falla de la base sale
 * como excepción no chequeada y deja esa transacción marcada para deshacerse.
 * <p>
 * Un cambio se hace leyendo y guardando en la <strong>misma</strong> transacción. No es solo prolijidad: los errores
 * pueden registrarse mientras otra sincronización confirma una versión (DP-09), y el adaptador solo garantiza que no se
 * pisen si {@code load} y {@code save} comparten la transacción.
 * <p>
 * Que falte la fila es un error de instalación, no un estado posible: las dos operaciones fallan con una excepción no
 * chequeada que no corresponde reintentar, y la fila nunca se crea en silencio.
 */
public interface SyncStatusRepository {
    /** Lee el estado guardado. */
    SyncStatus load();

    /** Guarda el estado completo, reemplazando el anterior. */
    void save(SyncStatus status);
}
