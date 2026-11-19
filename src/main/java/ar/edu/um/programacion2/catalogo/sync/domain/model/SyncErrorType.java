package ar.edu.um.programacion2.catalogo.sync.domain.model;

/**
 * Tipo del último error de sincronización (CU-09). En la base se guarda como texto sin restricción, así que agregar un
 * tipo no requiere una migración.
 */
public enum SyncErrorType {
    /**
     * Redis de la cátedra no respondió (timeout o sin conexión) en todos los intentos (CU-03 flujo 6a, CU-04 flujos 1b y
     * 3b). Se conservan las versiones ya aplicadas completas.
     */
    REDIS_UNAVAILABLE,

    /**
     * El snapshot no se pudo aplicar por un problema que reintentar no arregla: la respuesta no respeta el contrato, tiene
     * referencias inexistentes, la base local lo rechazó o hubo un error inesperado (CU-02).
     */
    SNAPSHOT_FAILED,

    /**
     * La cátedra rechazó la credencial: la API REST con un 401 o 403 (CU-02), o Redis con {@code WRONGPASS} o
     * {@code NOPERM} (CU-03 y CU-04). La descripción dice cuál de los dos fue. No se reintenta ni se cae en un snapshot:
     * hay que revisar la configuración.
     */
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

    /**
     * Una versión incremental no se pudo aplicar por una falla de la base local que <strong>no</strong> es transitoria
     * (no está en {@code DatabaseFailures}: la base rechazó los datos o el uso), por un error inesperado, o porque se
     * interrumpió (CU-04, flujo 6a). Reintentar no sirve. Esa versión hizo rollback y se conservan las anteriores.
     */
    INCREMENTAL_FAILED,

    /** Llegó un mensaje de Kafka ilegible o sin {@code eventId} o {@code newVersion}, y se descartó (CU-03, flujo 2a). */
    INVALID_EVENT,

    /**
     * Un aviso de Kafka se descartó por un error inesperado después de agotar los reintentos (DP-08): por ejemplo, la base
     * caída en una barrera. Su {@code eventId} no quedó registrado.
     */
    EVENT_PROCESSING_FAILED,
}
