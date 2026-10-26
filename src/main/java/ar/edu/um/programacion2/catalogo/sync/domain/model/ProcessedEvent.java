package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Evento {@code CatalogUpdated} ya procesado (contrato §15.3, DP-07). Es la primera barrera de idempotencia: si un
 * mensaje duplicado trae un {@code eventId} registrado, no se aplica nada.
 * <p>
 * {@code newVersion} es la versión que anunciaba el evento; la versión que efectivamente quedó aplicada está en
 * {@link SyncStatus}.
 */
public record ProcessedEvent(String eventId, long newVersion, Instant processedAt) {
    public ProcessedEvent {
        Objects.requireNonNull(eventId, "El evento no tiene eventId");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("El eventId no puede estar en blanco");
        }
        Objects.requireNonNull(processedAt, "El evento " + eventId + " no tiene fecha de procesamiento");
    }
}
