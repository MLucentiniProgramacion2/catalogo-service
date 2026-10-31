package ar.edu.um.programacion2.catalogo.sync.domain.model;

/**
 * Tipo del último error de sincronización (CU-09). En la base se guarda como texto sin restricción, así que agregar un
 * tipo no requiere una migración.
 */
public enum SyncErrorType {
    /** Redis de la cátedra no respondió después de los reintentos (CU-03 y CU-04). */
    REDIS_UNAVAILABLE,

    /**
     * El snapshot no se pudo aplicar por un problema que reintentar no arregla: la respuesta no respeta el contrato, tiene
     * referencias inexistentes, la base local lo rechazó o hubo un error inesperado (CU-02).
     */
    SNAPSHOT_FAILED,

    /** La cátedra rechazó la credencial técnica (401 o 403). No se reintenta: hay que revisar la configuración. */
    INVALID_CREDENTIALS,

    /** Otra sincronización tenía tomada la exclusión mutua y se superó la espera máxima (DP-09). */
    LOCK_TIMEOUT,

    /**
     * La API REST de la cátedra no respondió o informó una falla transitoria (timeout, conexión, 408, 429, 5xx) en todos
     * los intentos del snapshot (CU-02, flujo 1a). La copia local no cambió.
     */
    CATEDRA_UNAVAILABLE,

    /**
     * La base de datos local falló de forma transitoria (sin conexión, conexión cortada, timeout, bloqueo) en todos los
     * intentos (CU-02, flujo 5a). La transacción se deshizo: la copia y la versión anteriores quedaron intactas.
     */
    DATABASE_UNAVAILABLE,
}
