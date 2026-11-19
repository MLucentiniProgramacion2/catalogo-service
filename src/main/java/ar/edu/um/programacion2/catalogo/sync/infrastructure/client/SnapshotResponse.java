package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.CategoryJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.ProfessionalJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.WeeklyScheduleJson;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Cuerpo de {@code GET /api/synchronization/snapshot} (contrato §7), tal como llega por la red.
 * <p>
 * Las entidades son las de {@code CatedraEntityJson}, compartidas con Redis (contrato §14.4). {@code snapshotVersion}
 * es un tipo envoltorio por lo mismo que los campos de esas entidades: ausente llega como {@code null} y el mapper lo
 * rechaza. Los campos desconocidos se ignoran de forma explícita. Es package-private: el DTO no sale de este paquete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SnapshotResponse(
    Long snapshotVersion,
    String generatedAt,
    List<CategoryJson> professionalCategories,
    List<ProfessionalJson> professionals,
    List<WeeklyScheduleJson> weeklySchedules
) {
    /** Error REST en {@code application/problem+json} (contrato §13). Solo interesa {@code code}, nunca {@code detail}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProblemResponse(String code) {}
}
