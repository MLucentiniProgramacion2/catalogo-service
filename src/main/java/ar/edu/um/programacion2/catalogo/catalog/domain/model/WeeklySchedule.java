package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Horario semanal de atención de un profesional, tal como lo publica la cátedra (contrato §7 y §14.4).
 * <p>
 * Las horas son locales de atención, sin zona. {@code dayOfWeek} usa {@link DayOfWeek}, cuyos valores son exactamente los
 * que admite el contrato ({@code MONDAY} a {@code SUNDAY}).
 * <p>
 * No se exige {@code startTime < endTime} ni {@code slotDurationMinutes > 0}: el contrato no lo garantiza y rechazarlo
 * haría fallar el snapshot entero. Quien use los horarios tiene que tolerar una franja así.
 */
public record WeeklySchedule(
    long id,
    long professionalId,
    DayOfWeek dayOfWeek,
    LocalTime startTime,
    LocalTime endTime,
    int slotDurationMinutes,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
    public WeeklySchedule {
        Objects.requireNonNull(dayOfWeek, "El horario " + id + " no tiene dayOfWeek");
        Objects.requireNonNull(startTime, "El horario " + id + " no tiene startTime");
        Objects.requireNonNull(endTime, "El horario " + id + " no tiene endTime");
        Objects.requireNonNull(createdAt, "El horario " + id + " no tiene createdAt");
        Objects.requireNonNull(updatedAt, "El horario " + id + " no tiene updatedAt");
    }
}
