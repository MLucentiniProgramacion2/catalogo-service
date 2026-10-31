package ar.edu.um.programacion2.catalogo.sync.domain.model;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Snapshot completo del catálogo publicado por la cátedra (contrato §7): las tres colecciones, incluidas las entidades
 * deshabilitadas, y la versión a la que corresponden.
 * <p>
 * Las listas se copian y quedan inmutables: quien recibe el snapshot no puede cambiarlo, ni quien lo armó puede
 * cambiarlo después. Una colección vacía es válida; una ausente no.
 * <p>
 * El constructor no exige que las referencias entre colecciones existan: {@link #brokenReferences()} informa las que
 * faltan y CU-02 decide qué hacer (no aplicar nada y registrar el error) antes de tocar la base.
 */
public record CatalogSnapshot(
    long snapshotVersion,
    Instant generatedAt,
    List<ProfessionalCategory> professionalCategories,
    List<Professional> professionals,
    List<WeeklySchedule> weeklySchedules
) {
    public CatalogSnapshot {
        Objects.requireNonNull(generatedAt, "El snapshot " + snapshotVersion + " no tiene generatedAt");
        professionalCategories = List.copyOf(
            Objects.requireNonNull(professionalCategories, "El snapshot " + snapshotVersion + " no tiene professionalCategories")
        );
        professionals = List.copyOf(Objects.requireNonNull(professionals, "El snapshot " + snapshotVersion + " no tiene professionals"));
        weeklySchedules = List.copyOf(
            Objects.requireNonNull(weeklySchedules, "El snapshot " + snapshotVersion + " no tiene weeklySchedules")
        );
    }

    /**
     * Referencias que apuntan a una entidad que no está en el snapshot (CU-02, paso 3): profesionales cuya categoría no
     * figura y horarios cuyo profesional no figura. Las entidades deshabilitadas cuentan como presentes: una baja
     * lógica sigue existiendo.
     *
     * @return una descripción por referencia rota, en el orden de las colecciones; vacía si el snapshot es coherente.
     */
    public List<String> brokenReferences() {
        Set<Long> categoryIds = professionalCategories.stream().map(ProfessionalCategory::id).collect(Collectors.toSet());
        Set<Long> professionalIds = professionals.stream().map(Professional::id).collect(Collectors.toSet());
        Stream<String> brokenProfessionals = professionals
            .stream()
            .filter(professional -> !categoryIds.contains(professional.categoryId()))
            .map(professional ->
                "el profesional " +
                professional.id() +
                " apunta a la categoría " +
                professional.categoryId() +
                ", que no está en el snapshot"
            );
        Stream<String> brokenSchedules = weeklySchedules
            .stream()
            .filter(schedule -> !professionalIds.contains(schedule.professionalId()))
            .map(schedule ->
                "el horario " + schedule.id() + " apunta al profesional " + schedule.professionalId() + ", que no está en el snapshot"
            );
        return Stream.concat(brokenProfessionals, brokenSchedules).toList();
    }
}
