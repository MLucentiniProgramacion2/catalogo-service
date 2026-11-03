package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Parámetros de la query de {@code GET /api/professionals} (CU-05), juntos en un objeto para validarlos con
 * {@code @Valid}: un valor fuera de rango o que no se puede convertir da un 400 con un {@code fieldError} por parámetro
 * ({@code MethodArgumentNotValidException}, contrato §13).
 * <p>
 * Todos son opcionales y de tipo envoltorio: ausente es {@code null}. Los valores por defecto los aplica
 * {@code ProfessionalDtoMapper} ({@code page} y {@code size}, que son de la API) y el dominio ({@code enabled}, que es
 * una regla de negocio, DP-01). No sale de la web: el mapper lo traduce a los tipos del dominio.
 * <p>
 * {@code @Size} en {@code name} se aplica al texto recibido, antes de recortarle los espacios: protege el límite de la
 * API (enunciado §9), no es una regla del catálogo.
 *
 * @param page número de página, desde 0
 * @param size tamaño de página, de 1 a {@link PageQuery#MAX_SIZE}
 */
public record ProfessionalSearchParams(
    Long categoryId,
    @Size(max = ProfessionalSearchParams.NAME_MAX_LENGTH) String name,
    Boolean enabled,
    @Min(0) Integer page,
    @Min(1) @Max(PageQuery.MAX_SIZE) Integer size
) {
    /** Largo máximo del texto a buscar, tal como llega. */
    public static final int NAME_MAX_LENGTH = 100;
}
