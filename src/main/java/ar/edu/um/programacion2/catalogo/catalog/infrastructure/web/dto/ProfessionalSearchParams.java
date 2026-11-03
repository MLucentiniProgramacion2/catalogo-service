package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;

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
 * <p>
 * {@code day} solo acepta {@code MONDAY} a {@code SUNDAY} en mayúsculas, como el contrato (la conversión de Spring a un
 * enum distingue mayúsculas). {@code from} y {@code to} usan el formato ISO de hora que configura JHipster, que acepta
 * {@code HH:mm} y {@code HH:mm:ss} (contrato §4). Las reglas que combinan parámetros van con {@code @AssertTrue}: cada
 * una aparece como un {@code fieldError} con su nombre ({@code timeRangeOrdered}, {@code timeRangeHasDay}). El dominio
 * las vuelve a exigir en {@code AvailabilityFilter}; acá están para responder 400 con el detalle del campo.
 *
 * @param page número de página, desde 0
 * @param size tamaño de página, de 1 a {@link PageQuery#MAX_SIZE}
 * @param day  día en que atiende
 * @param from inicio de la franja (solo con {@code day})
 * @param to   fin de la franja, no incluido (solo con {@code day})
 */
public record ProfessionalSearchParams(
    Long categoryId,
    @Size(max = ProfessionalSearchParams.NAME_MAX_LENGTH) String name,
    Boolean enabled,
    @Min(0) Integer page,
    @Min(1) @Max(PageQuery.MAX_SIZE) Integer size,
    DayOfWeek day,
    LocalTime from,
    LocalTime to
) {
    /** Largo máximo del texto a buscar, tal como llega. */
    public static final int NAME_MAX_LENGTH = 100;

    /** Con {@code from} y {@code to}, el inicio es anterior al fin: {@code [from, to)} con {@code from == to} está vacío. */
    @AssertTrue(message = "from must be before to")
    public boolean isTimeRangeOrdered() {
        return from == null || to == null || from.isBefore(to);
    }

    /** Una franja sin día no es un filtro válido (CU-05, 2c). */
    @AssertTrue(message = "from and to require day")
    public boolean isTimeRangeHasDay() {
        return day != null || (from == null && to == null);
    }
}
