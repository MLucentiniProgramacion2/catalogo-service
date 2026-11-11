package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.util.Objects;

/**
 * Un profesional junto con su categoría: lo que devuelve la búsqueda (CU-05, paso 5: "con su categoría y estado").
 * <p>
 * Es un modelo de lectura: {@link Professional} solo tiene el ID de la categoría, y la respuesta necesita su nombre y
 * estado. Reusa los dos records del catálogo en vez de copiar sus campos, así que conserva sus validaciones. La
 * categoría puede estar deshabilitada aunque el profesional no: se informa su estado y la app decide qué mostrar.
 */
public record ProfessionalWithCategory(Professional professional, ProfessionalCategory category) {
    public ProfessionalWithCategory {
        Objects.requireNonNull(professional, "Falta el profesional");
        Objects.requireNonNull(category, "Falta la categoría del profesional " + professional.id());
        if (professional.categoryId() != category.id()) {
            throw new IllegalArgumentException(
                "El profesional " + professional.id() + " es de la categoría " + professional.categoryId() + ", no de la " + category.id()
            );
        }
    }
}
