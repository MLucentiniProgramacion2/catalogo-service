package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Cuerpo de {@code GET /api/synchronization/snapshot} (contrato §7), tal como llega por la red.
 * <p>
 * Los números y booleanos son tipos envoltorio: un campo ausente llega como {@code null} y el mapper lo rechaza, en
 * vez de convertirse en silencio en {@code 0} o {@code false}. Fechas, horas y día de la semana llegan como texto y
 * se parsean en el mapper. Los campos desconocidos se ignoran de forma explícita, sin depender de la configuración
 * de Jackson. Es package-private: los DTO no salen de este paquete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SnapshotResponse(
    Long snapshotVersion,
    String generatedAt,
    List<CategoryResponse> professionalCategories,
    List<ProfessionalResponse> professionals,
    List<WeeklyScheduleResponse> weeklySchedules
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CategoryResponse(Long id, String name, String description, Boolean enabled, String createdAt, String updatedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProfessionalResponse(
        Long id,
        Long categoryId,
        String firstName,
        String lastName,
        Boolean enabled,
        String createdAt,
        String updatedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WeeklyScheduleResponse(
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

    /** Error REST en {@code application/problem+json} (contrato §13). Solo interesa {@code code}, nunca {@code detail}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemResponse(String code) {}
}
