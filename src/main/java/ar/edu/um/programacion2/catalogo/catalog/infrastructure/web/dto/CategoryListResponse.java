package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import java.util.List;

/**
 * Respuesta de {@code GET /api/categories}: las categorías y la versión local del catálogo a la que corresponden
 * (CU-06, paso 4). Es un objeto y no una lista suelta para que la versión viaje en el cuerpo, como en CU-05.
 * <p>
 * Sin paginación: son pocas (8 en el catálogo de la cátedra) y el selector de la app las necesita todas.
 */
public record CategoryListResponse(long catalogVersion, List<CategoryResponse> categories) {}
