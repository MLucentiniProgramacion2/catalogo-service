package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

/**
 * Un profesional tal como lo ve la app en la búsqueda (CU-05, paso 5: "con su categoría y estado"): sin
 * {@code createdAt} ni {@code updatedAt}, que son datos de la cátedra para la sincronización.
 */
public record ProfessionalResponse(long id, String firstName, String lastName, boolean enabled, CategorySummaryResponse category) {}
