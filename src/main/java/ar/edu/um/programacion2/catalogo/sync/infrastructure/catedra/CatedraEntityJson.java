package ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Las tres entidades del catálogo tal como las publica la cátedra, iguales en el snapshot REST (contrato §7) y en los
 * Hashes de Redis (contrato §14.4). Los usan los dos adaptadores de salida: {@code client} y {@code redis}.
 * <p>
 * Los números y booleanos son tipos envoltorio: un campo ausente llega como {@code null} y {@link CatedraEntityMapper}
 * lo rechaza, en vez de convertirse en silencio en {@code 0} o {@code false}. Fechas, horas y día de la semana llegan
 * como texto y se parsean en el mapper. Los campos desconocidos se ignoran de forma explícita, sin depender de la
 * configuración de Jackson.
 * <p>
 * Son DTO: no salen de {@code infrastructure}.
 */
public final class CatedraEntityJson {

    private CatedraEntityJson() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryJson(Long id, String name, String description, Boolean enabled, String createdAt, String updatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfessionalJson(
        Long id,
        Long categoryId,
        String firstName,
        String lastName,
        Boolean enabled,
        String createdAt,
        String updatedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeeklyScheduleJson(
        Long id,
        Long professionalId,
        String dayOfWeek,
        String startTime,
        String endTime,
        Integer slotDurationMinutes,
        Boolean enabled,
        String createdAt,
        String updatedAt
    ) {}
}
