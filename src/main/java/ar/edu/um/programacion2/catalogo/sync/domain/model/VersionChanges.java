package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * Los IDs que cambiaron en una versión publicada (contrato §14.5): no trae las entidades, solo cuáles hay que volver a
 * leer en su estado vigente.
 * <p>
 * {@code version} es la que dice el propio contenido. CU-04 verifica que coincida con la versión pedida: si no, el
 * estado no se puede verificar. Los conjuntos se copian y quedan inmutables; un ID repetido en la lista cuenta una vez.
 */
public record VersionChanges(long version, Set<Long> categoryIds, Set<Long> professionalIds, Set<Long> scheduleIds) {
    public VersionChanges {
        categoryIds = Set.copyOf(Objects.requireNonNull(categoryIds, "La versión " + version + " no tiene professionalCategories"));
        professionalIds = Set.copyOf(Objects.requireNonNull(professionalIds, "La versión " + version + " no tiene professionals"));
        scheduleIds = Set.copyOf(Objects.requireNonNull(scheduleIds, "La versión " + version + " no tiene weeklySchedules"));
    }

    /** Una versión sin cambios es válida: solo avanza la versión local. */
    public boolean isEmpty() {
        return categoryIds.isEmpty() && professionalIds.isEmpty() && scheduleIds.isEmpty();
    }
}
