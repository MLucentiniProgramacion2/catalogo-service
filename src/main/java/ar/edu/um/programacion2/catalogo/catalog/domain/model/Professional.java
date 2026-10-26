package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Profesional de la copia local, tal como lo publica la cátedra (contrato §7 y §14.4).
 * <p>
 * Referencia a su categoría por ID: que la categoría exista lo verifica la sincronización (CU-02 y CU-04) y, en la
 * base, la clave foránea. Las bajas son lógicas ({@code enabled = false}).
 */
public record Professional(
    long id,
    long categoryId,
    String firstName,
    String lastName,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
    public Professional {
        Objects.requireNonNull(firstName, "El profesional " + id + " no tiene firstName");
        Objects.requireNonNull(lastName, "El profesional " + id + " no tiene lastName");
        Objects.requireNonNull(createdAt, "El profesional " + id + " no tiene createdAt");
        Objects.requireNonNull(updatedAt, "El profesional " + id + " no tiene updatedAt");
    }
}
