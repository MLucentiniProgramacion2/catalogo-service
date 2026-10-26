package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Categoría profesional de la copia local, tal como la publica la cátedra (contrato §7 y §14.4).
 * <p>
 * Solo se exige lo que el contrato garantiza: si el modelo rechazara un dato real de la cátedra, fallaría el snapshot
 * entero. {@code description} puede ser nula. Las bajas son lógicas ({@code enabled = false}).
 */
public record ProfessionalCategory(long id, String name, String description, boolean enabled, Instant createdAt, Instant updatedAt) {
    public ProfessionalCategory {
        Objects.requireNonNull(name, "La categoría " + id + " no tiene nombre");
        Objects.requireNonNull(createdAt, "La categoría " + id + " no tiene createdAt");
        Objects.requireNonNull(updatedAt, "La categoría " + id + " no tiene updatedAt");
    }
}
