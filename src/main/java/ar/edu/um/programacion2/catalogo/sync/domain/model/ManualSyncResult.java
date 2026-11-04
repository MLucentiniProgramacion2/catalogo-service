package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Resultado de una sincronización manual (CU-08). Todos los casos son resultados posibles, no excepciones: la falla ya
 * quedó registrada en el estado de sincronización y el adaptador REST elige la respuesta con un {@code switch} completo.
 * <p>
 * Las versiones son {@code Long} porque una base que nunca sincronizó no tiene versión local ({@code null}).
 */
public sealed interface ManualSyncResult {
    /** La sincronización se aplicó. */
    record Applied(Long localVersionBefore, long localVersionAfter, SyncPath path, int attempts) implements ManualSyncResult {
        public Applied {
            Objects.requireNonNull(path, "Falta el camino aplicado");
        }
    }

    /**
     * La sincronización falló y la copia quedó como estaba. Si la versión ni siquiera se pudo leer, las dos versiones son
     * {@code null} y el tipo de error lo aclara; {@code attempts} es 0 porque no se llegó a intentar.
     */
    record Failed(Long localVersionBefore, Long localVersionAfter, SyncErrorType errorType, String description, int attempts)
        implements ManualSyncResult {
        public Failed {
            Objects.requireNonNull(errorType, "Falta el tipo de error");
            Objects.requireNonNull(description, "Falta la descripción del error");
        }
    }

    /** No se ejecutó: otra sincronización seguía en curso al vencer la espera máxima (DP-09). Ya quedó registrado. */
    record OtherSyncInProgress() implements ManualSyncResult {}

    /** El modo pedido todavía no está disponible. No se intentó nada ni se registró un error: no falló ninguna sincronización. */
    record ModeNotAvailable(SyncMode mode) implements ManualSyncResult {
        public ModeNotAvailable {
            Objects.requireNonNull(mode, "Falta el modo");
        }
    }
}
