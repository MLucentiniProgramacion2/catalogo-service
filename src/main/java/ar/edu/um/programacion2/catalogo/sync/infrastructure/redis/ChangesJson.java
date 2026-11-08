package ar.edu.um.programacion2.catalogo.sync.infrastructure.redis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Contenido de {@code catedra:sync:changes:{version}} (contrato §14.5), tal como está en Redis. Envoltorios por lo
 * mismo que {@code CatedraEntityJson}: un campo ausente llega como {@code null} y el mapper lo rechaza. Package-private:
 * no sale de este paquete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChangesJson(Long version, ChangeListsJson changes) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChangeListsJson(List<Long> professionalCategories, List<Long> professionals, List<Long> weeklySchedules) {}
}
