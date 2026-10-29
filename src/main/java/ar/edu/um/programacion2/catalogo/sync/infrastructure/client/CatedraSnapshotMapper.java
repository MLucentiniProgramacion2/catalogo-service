package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.SnapshotResponse.CategoryResponse;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.SnapshotResponse.ProfessionalResponse;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.SnapshotResponse.WeeklyScheduleResponse;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.function.Function;

/**
 * Traduce el DTO del snapshot de la cátedra al modelo de dominio.
 * <p>
 * Valida solo el formato que garantiza el contrato: campos presentes, instantes ISO-8601 UTC, horas {@code HH:mm} o
 * {@code HH:mm:ss} y {@code dayOfWeek} de {@code MONDAY} a {@code SUNDAY}. Cualquier dato que no cumpla, incluido el
 * que rechacen los constructores de los records, termina en {@link InvalidSnapshotException}: no hay snapshot parcial.
 */
final class CatedraSnapshotMapper {

    private CatedraSnapshotMapper() {}

    static CatalogSnapshot toDomain(SnapshotResponse response) {
        try {
            long version = required(response.snapshotVersion(), "El snapshot", "snapshotVersion");
            String snapshot = "El snapshot " + version;
            return new CatalogSnapshot(
                version,
                instant(response.generatedAt(), snapshot, "generatedAt"),
                list(required(response.professionalCategories(), snapshot, "professionalCategories"), CatedraSnapshotMapper::category),
                list(required(response.professionals(), snapshot, "professionals"), CatedraSnapshotMapper::professional),
                list(required(response.weeklySchedules(), snapshot, "weeklySchedules"), CatedraSnapshotMapper::schedule)
            );
        } catch (NullPointerException | IllegalArgumentException | DateTimeException e) {
            throw new InvalidSnapshotException("Snapshot inválido: " + e.getMessage(), e);
        }
    }

    private static ProfessionalCategory category(CategoryResponse dto) {
        long id = required(dto.id(), "Una categoría", "id");
        String owner = "La categoría " + id;
        return new ProfessionalCategory(
            id,
            dto.name(),
            dto.description(),
            required(dto.enabled(), owner, "enabled"),
            instant(dto.createdAt(), owner, "createdAt"),
            instant(dto.updatedAt(), owner, "updatedAt")
        );
    }

    private static Professional professional(ProfessionalResponse dto) {
        long id = required(dto.id(), "Un profesional", "id");
        String owner = "El profesional " + id;
        return new Professional(
            id,
            required(dto.categoryId(), owner, "categoryId"),
            dto.firstName(),
            dto.lastName(),
            required(dto.enabled(), owner, "enabled"),
            instant(dto.createdAt(), owner, "createdAt"),
            instant(dto.updatedAt(), owner, "updatedAt")
        );
    }

    private static WeeklySchedule schedule(WeeklyScheduleResponse dto) {
        long id = required(dto.id(), "Un horario", "id");
        String owner = "El horario " + id;
        return new WeeklySchedule(
            id,
            required(dto.professionalId(), owner, "professionalId"),
            dayOfWeek(dto.dayOfWeek(), owner),
            time(dto.startTime(), owner, "startTime"),
            time(dto.endTime(), owner, "endTime"),
            required(dto.slotDurationMinutes(), owner, "slotDurationMinutes"),
            required(dto.enabled(), owner, "enabled"),
            instant(dto.createdAt(), owner, "createdAt"),
            instant(dto.updatedAt(), owner, "updatedAt")
        );
    }

    private static <T> T required(T value, String owner, String field) {
        if (value == null) {
            throw new IllegalArgumentException(owner + " no tiene " + field);
        }
        return value;
    }

    /** Una colección con un elemento {@code null} también es inválida. */
    private static <D, M> List<M> list(List<D> dtos, Function<D, M> mapper) {
        return dtos
            .stream()
            .map(dto -> {
                if (dto == null) {
                    throw new IllegalArgumentException("Una colección tiene un elemento nulo");
                }
                return mapper.apply(dto);
            })
            .toList();
    }

    /**
     * Instante ISO-8601 (contrato §4: en UTC, como {@code 2026-09-10T14:30:00Z}). Si llegara con otro offset,
     * {@link Instant#parse} lo convierte a UTC: es el mismo instante, así que no se rechaza.
     */
    private static Instant instant(String value, String owner, String field) {
        required(value, owner, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(owner + " tiene un " + field + " inválido: '" + value + "'", e);
        }
    }

    /** {@link LocalTime#parse} acepta {@code HH:mm} y {@code HH:mm:ss}, como pide el contrato §4. */
    private static LocalTime time(String value, String owner, String field) {
        required(value, owner, field);
        try {
            return LocalTime.parse(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(owner + " tiene un " + field + " inválido: '" + value + "'", e);
        }
    }

    private static DayOfWeek dayOfWeek(String value, String owner) {
        required(value, owner, "dayOfWeek");
        try {
            return DayOfWeek.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(owner + " tiene un dayOfWeek inválido: '" + value + "'", e);
        }
    }
}
