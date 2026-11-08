package ar.edu.um.programacion2.catalogo.sync.domain.model;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * El estado vigente de las entidades que cambiaron en una versión (contrato §14.4), tal como se leyó de los Hashes:
 * solo las que se encontraron.
 * <p>
 * Lo que decide CU-04 sobre ellas está acá, sin la base: si falta alguna de las listadas (flujo 3a) y qué referencias
 * apuntan afuera de la versión, que hay que buscar en la copia local (flujo 4a).
 */
public record ChangedEntities(List<ProfessionalCategory> categories, List<Professional> professionals, List<WeeklySchedule> schedules) {
    public ChangedEntities {
        categories = List.copyOf(Objects.requireNonNull(categories, "Faltan las categorías"));
        professionals = List.copyOf(Objects.requireNonNull(professionals, "Faltan los profesionales"));
        schedules = List.copyOf(Objects.requireNonNull(schedules, "Faltan los horarios"));
    }

    /**
     * Los IDs listados en {@code changes} que no se encontraron (flujo 3a), como texto para el log, en orden.
     *
     * @return vacía si se encontraron todos.
     */
    public List<String> missingFrom(VersionChanges changes) {
        List<String> missing = new ArrayList<>();
        missing(changes.categoryIds(), ids(categories, ProfessionalCategory::id)).forEach(id -> missing.add("la categoría " + id));
        missing(changes.professionalIds(), ids(professionals, Professional::id)).forEach(id -> missing.add("el profesional " + id));
        missing(changes.scheduleIds(), ids(schedules, WeeklySchedule::id)).forEach(id -> missing.add("el horario " + id));
        return missing;
    }

    /** Categorías a las que apunta algún profesional de la versión y que la versión no trae: hay que buscarlas en la copia. */
    public Set<Long> categoryIdsOutside() {
        Set<Long> own = ids(categories, ProfessionalCategory::id);
        return professionals.stream().map(Professional::categoryId).filter(id -> !own.contains(id)).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Profesionales a los que apunta algún horario de la versión y que la versión no trae: hay que buscarlos en la copia. */
    public Set<Long> professionalIdsOutside() {
        Set<Long> own = ids(professionals, Professional::id);
        return schedules.stream().map(WeeklySchedule::professionalId).filter(id -> !own.contains(id)).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Referencias que no están ni en la versión ni en la copia local (flujo 4a). Una entidad deshabilitada cuenta como
     * presente: una baja lógica sigue existiendo.
     *
     * @param localCategoryIds cuáles de {@link #categoryIdsOutside()} existen en la copia local.
     * @param localProfessionalIds cuáles de {@link #professionalIdsOutside()} existen en la copia local.
     * @return una descripción por referencia rota, en orden; vacía si no hay ninguna.
     */
    public List<String> brokenReferences(Set<Long> localCategoryIds, Set<Long> localProfessionalIds) {
        Set<Long> categoryIds = ids(categories, ProfessionalCategory::id);
        Set<Long> professionalIds = ids(professionals, Professional::id);
        List<String> broken = new ArrayList<>();
        professionals
            .stream()
            .filter(p -> !categoryIds.contains(p.categoryId()) && !localCategoryIds.contains(p.categoryId()))
            .forEach(p -> broken.add("el profesional " + p.id() + " apunta a la categoría " + p.categoryId() + ", que no existe"));
        schedules
            .stream()
            .filter(s -> !professionalIds.contains(s.professionalId()) && !localProfessionalIds.contains(s.professionalId()))
            .forEach(s -> broken.add("el horario " + s.id() + " apunta al profesional " + s.professionalId() + ", que no existe"));
        return broken;
    }

    private static Set<Long> missing(Set<Long> listed, Set<Long> found) {
        return listed.stream().filter(id -> !found.contains(id)).collect(Collectors.toCollection(TreeSet::new));
    }

    private static <T> Set<Long> ids(List<T> entities, java.util.function.ToLongFunction<T> id) {
        return entities.stream().map(e -> id.applyAsLong(e)).collect(Collectors.toSet());
    }
}
