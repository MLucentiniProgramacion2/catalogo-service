package ar.edu.um.programacion2.catalogo.sync.domain.model;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot completo del catálogo publicado por la cátedra (contrato §7): las tres colecciones, incluidas las entidades
 * deshabilitadas, y la versión a la que corresponden.
 * <p>
 * Las listas se copian y quedan inmutables: quien recibe el snapshot no puede cambiarlo, ni quien lo armó puede
 * cambiarlo después. Una colección vacía es válida; una ausente no. No se verifica que las referencias entre
 * colecciones existan: eso lo hace CU-02 antes de tocar la base.
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
}
