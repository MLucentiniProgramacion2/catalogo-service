package ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.CategoryJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.ProfessionalJson;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra.CatedraEntityJson.WeeklyScheduleJson;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;

/**
 * Las reglas de formato de los datos de la cátedra, en un solo lugar: las usan el snapshot (contrato §7) y Redis
 * (contrato §14), que publican las mismas entidades.
 * <p>
 * Valida solo lo que garantiza el contrato: campos presentes, instantes ISO-8601 UTC, horas {@code HH:mm} o
 * {@code HH:mm:ss} y {@code dayOfWeek} de {@code MONDAY} a {@code SUNDAY}. Un dato que no cumple, incluido el que
 * rechacen los constructores de los records del dominio, sale como {@link IllegalArgumentException} o
 * {@link DateTimeException} (o {@link NullPointerException}, de esos constructores). Cada adaptador la traduce a la
 * excepción de su puerto: este mapper no conoce a ninguno.
 */
public final class CatedraEntityMapper {

    private CatedraEntityMapper() {}

    public static ProfessionalCategory category(CategoryJson dto) {
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

    public static Professional professional(ProfessionalJson dto) {
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

    public static WeeklySchedule schedule(WeeklyScheduleJson dto) {
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

    /** @throws IllegalArgumentException si {@code value} es {@code null}: "{owner} no tiene {field}". */
    public static <T> T required(T value, String owner, String field) {
        if (value == null) {
            throw new IllegalArgumentException(owner + " no tiene " + field);
        }
        return value;
    }

    /**
     * Instante ISO-8601 (contrato §4: en UTC, como {@code 2026-09-10T14:30:00Z}). Acepta 0 a 9 decimales: en el Redis real
     * aparecen 0, 6 y 9. Si llegara con otro offset, {@link Instant#parse} lo convierte a UTC: es el mismo instante, así
     * que no se rechaza.
     */
    public static Instant instant(String value, String owner, String field) {
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
