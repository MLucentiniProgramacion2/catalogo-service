package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

/**
 * La categoría del profesional en la agenda (CU-07). Puede estar deshabilitada con el profesional habilitado: se
 * informa, y la agenda va igual (la cátedra solo rechaza un hold por el profesional deshabilitado).
 */
public record AgendaCategoryResponse(long id, String name, boolean enabled) {}
