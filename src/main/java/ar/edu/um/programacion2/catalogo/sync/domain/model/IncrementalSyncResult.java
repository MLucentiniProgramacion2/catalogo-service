package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Resultado de aplicar un lote de versiones incrementales (CU-04). Ninguno es una excepción: lo que hubo que informar ya
 * quedó registrado, y CU-03 decide qué sigue con un {@code switch} completo.
 * <p>
 * {@code localVersion} es siempre la versión local al terminar: la última aplicada completa. Cada versión es su propia
 * transacción, así que las aplicadas antes de un problema se conservan (DP-08). {@code attempts} cuenta los intentos del
 * lote: 1 más los reintentos, que comparten un solo presupuesto.
 */
public sealed interface IncrementalSyncResult {
    long localVersion();

    int attempts();

    /** Se aplicaron todas: la versión local es la objetivo. */
    record Completed(long localVersion, int attempts) implements IncrementalSyncResult {}

    /**
     * No se pudo seguir por incremental y corresponde una sincronización completa (CU-03, flujo 8b): el estado no se puede
     * verificar (flujos 3a y 4a, o un {@code changes:{v}} con otra versión), o venció el plazo del lote. No es un error:
     * el motivo quedó en el log.
     */
    record NeedsSnapshot(long localVersion, String reason, int attempts) implements IncrementalSyncResult {
        public NeedsSnapshot {
            Objects.requireNonNull(reason, "Falta el motivo");
        }
    }

    /**
     * Otro proceso cambió la versión local mientras tanto (CU-04, paso 5): se hizo rollback y se terminó sin cambios. Con
     * la exclusión mutua no debería pasar; si pasa, la verificación protege la versión igual (DP-09).
     */
    record Preempted(long localVersion, Long foundVersion, int attempts) implements IncrementalSyncResult {}

    /** Falló y no se sigue: se agotaron los reintentos o la falla no se arregla reintentando. El error quedó registrado. */
    record Failed(long localVersion, SyncErrorType errorType, String description, int attempts) implements IncrementalSyncResult {
        public Failed {
            Objects.requireNonNull(errorType, "Falta el tipo de error");
            Objects.requireNonNull(description, "Falta la descripción del error");
        }
    }
}
