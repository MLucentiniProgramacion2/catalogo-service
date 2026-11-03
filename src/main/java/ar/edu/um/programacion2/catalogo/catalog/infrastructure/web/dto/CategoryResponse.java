package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

/**
 * Una categoría tal como la ve la app (CU-06, paso 4): sin {@code createdAt} ni {@code updatedAt}, que son datos de la
 * cátedra para la sincronización. {@code description} puede ser {@code null}.
 */
public record CategoryResponse(long id, String name, String description, boolean enabled) {}
