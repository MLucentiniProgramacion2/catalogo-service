package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Resultado de CU-03 para cualquier disparador: llevar la copia a la versión publicada por incremental (CU-04), por
 * snapshot (CU-02), o comprobar que ya estaba al día. Ninguno es una excepción: lo que hubo que informar ya quedó
 * registrado. Cada disparador lo traduce al suyo (Kafka, CU-08) con un {@code switch} completo.
 * <p>
 * {@code attempts} cuenta los intentos de toda la ejecución: 1 más los reintentos de la lectura de Redis, de CU-04 y de
 * CU-02, si corrieron.
 */
public sealed interface IncorporationResult {
    int attempts();

    /** La copia avanzó. {@code localVersionBefore} es {@code null} si la base no tenía versión. */
    record Applied(Long localVersionBefore, long localVersionAfter, SyncPath path, int attempts) implements IncorporationResult {
        public Applied {
            Objects.requireNonNull(path, "Falta el camino aplicado");
        }
    }

    /** Flujo 7a: la versión local ya era la publicada o una posterior. No se aplicó nada. */
    record UpToDate(long localVersion, long remoteVersion, int attempts) implements IncorporationResult {}

    /**
     * Falló. {@code localVersionAfter} es la última versión aplicada completa: si CU-04 alcanzó a aplicar algunas, se
     * conservan (DP-08). El error ya quedó registrado.
     */
    record Failed(Long localVersionBefore, Long localVersionAfter, SyncErrorType errorType, String description, int attempts)
        implements IncorporationResult {
        public Failed {
            Objects.requireNonNull(errorType, "Falta el tipo de error");
            Objects.requireNonNull(description, "Falta la descripción del error");
        }
    }
}
