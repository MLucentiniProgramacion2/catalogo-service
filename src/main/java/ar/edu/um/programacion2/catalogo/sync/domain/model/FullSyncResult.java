package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Resultado de una sincronización completa (CU-02). Que falle es un resultado posible y ya quedó registrado en el
 * estado de sincronización, así que no se informa con una excepción.
 * <p>
 * Es {@code sealed}: quien lo reciba puede distinguir los dos casos con un {@code switch} completo.
 */
public sealed interface FullSyncResult {
    /** Cantidad de intentos que se hicieron, contando el primero. */
    int attempts();

    /** El snapshot quedó aplicado y su versión es la versión local. */
    record Applied(long snapshotVersion, int attempts) implements FullSyncResult {}

    /** No se aplicó nada. El tipo y la descripción son los que quedaron registrados (o se intentaron registrar). */
    record Failed(SyncErrorType errorType, String description, int attempts) implements FullSyncResult {
        public Failed {
            Objects.requireNonNull(errorType, "Falta el tipo de error");
            Objects.requireNonNull(description, "Falta la descripción del error");
        }
    }
}
