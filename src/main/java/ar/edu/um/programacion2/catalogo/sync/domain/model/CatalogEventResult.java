package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Qué pasó con un aviso {@code CatalogUpdated} (CU-03). Todos son resultados posibles, no excepciones: en cualquiera de
 * ellos se confirma el offset, y lo que hubo que informar ya quedó registrado en el estado de sincronización.
 * <p>
 * Es {@code sealed}: el adaptador de Kafka los distingue con un {@code switch} completo.
 */
public sealed interface CatalogEventResult {
    /** La copia se puso al día. {@code localVersionBefore} es {@code null} si la base no tenía versión. */
    record Applied(Long localVersionBefore, long localVersionAfter, SyncPath path, int attempts) implements CatalogEventResult {
        public Applied {
            Objects.requireNonNull(path, "Falta el camino aplicado");
        }
    }

    /** Flujo 3a: el {@code eventId} ya estaba registrado. No se tomó la exclusión ni se aplicó nada. */
    record Duplicate(String eventId) implements CatalogEventResult {}

    /** Flujo 7a: la copia ya estaba al día. No se aplicó nada y el {@code eventId} quedó registrado. */
    record UpToDate(long localVersion, long newVersion) implements CatalogEventResult {}

    /** Flujo 8a: la sincronización falló y el {@code eventId} no se registró. */
    record Failed(SyncErrorType errorType, String description, int attempts) implements CatalogEventResult {
        public Failed {
            Objects.requireNonNull(errorType, "Falta el tipo de error");
            Objects.requireNonNull(description, "Falta la descripción del error");
        }
    }

    /** Flujo 4a: otra sincronización seguía en curso al vencer la espera. El {@code eventId} no se registró. */
    record NotExecuted() implements CatalogEventResult {}

    /** Flujo 2a: el mensaje era ilegible o le faltaba {@code eventId} o {@code newVersion}. */
    record Invalid() implements CatalogEventResult {}
}
