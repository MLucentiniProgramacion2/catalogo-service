package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Aviso de que la cátedra publicó una versión del catálogo (CU-03), con lo único que usa la sincronización del
 * {@code CatalogUpdated} de Kafka (contrato §15.3): el aviso no trae datos, y los datos salen de Redis o del snapshot.
 * <p>
 * No es un {@link ProcessedEvent} porque todavía no se procesó: la fecha de procesamiento se toma recién al registrarlo,
 * adentro de la transacción, y entre medio puede haber minutos de espera de la exclusión mutua.
 */
public record CatalogUpdateNotice(String eventId, long newVersion) {
    public CatalogUpdateNotice {
        Objects.requireNonNull(eventId, "El aviso no tiene eventId");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("El eventId no puede estar en blanco");
        }
    }

    /** El evento tal como se registra al confirmarse. */
    public ProcessedEvent processedAt(Instant processedAt) {
        return new ProcessedEvent(eventId, newVersion, processedAt);
    }
}
