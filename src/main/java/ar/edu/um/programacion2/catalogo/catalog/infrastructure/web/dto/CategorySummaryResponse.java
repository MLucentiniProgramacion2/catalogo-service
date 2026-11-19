package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

/**
 * La categoría de un profesional en la búsqueda (CU-05). Lleva {@code enabled} porque un profesional habilitado puede
 * estar en una categoría deshabilitada: aparece igual (DP-01 mira el estado del profesional) y la app decide qué mostrar.
 */
public record CategorySummaryResponse(long id, String name, boolean enabled) {}
