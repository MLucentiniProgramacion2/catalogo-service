package ar.edu.um.programacion2.catalogo.sync.domain.model;

/**
 * Tipo del último error de sincronización (CU-09). En la base se guarda como texto sin restricción, así que agregar un
 * tipo no requiere una migración.
 */
public enum SyncErrorType {
    REDIS_UNAVAILABLE,
    SNAPSHOT_FAILED,
    INVALID_CREDENTIALS,
    LOCK_TIMEOUT,
}
