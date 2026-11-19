package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import java.util.List;

/**
 * Respuesta de {@code GET /api/professionals}: una página de profesionales y la versión local del catálogo de la que
 * sale (CU-05, paso 5). La página, el total y la versión salen de la misma lectura.
 *
 * @param page          número de página pedido, desde 0
 * @param size          tamaño de página pedido
 * @param totalElements profesionales que cumplen los filtros, en todas las páginas
 * @param totalPages    cantidad de páginas; una página fuera de rango viene vacía con estos totales
 */
public record ProfessionalPageResponse(
    long catalogVersion,
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<ProfessionalResponse> professionals
) {}
