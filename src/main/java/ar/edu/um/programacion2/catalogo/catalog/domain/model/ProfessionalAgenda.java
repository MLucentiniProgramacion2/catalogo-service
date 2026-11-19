package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * La agenda vigente de un profesional (CU-07): el profesional con su categoría y sus horarios semanales
 * <strong>habilitados</strong>, en un orden fijo. Es lo que turnos usa para armar y validar turnos.
 * <p>
 * Reglas:
 * <ul>
 * <li>todos los horarios son del profesional y están habilitados (los deshabilitados son bajas lógicas);</li>
 * <li>un profesional deshabilitado no tiene agenda vigente: sin horarios, aunque en la copia tenga alguno habilitado. Así
 * turnos no puede armar turnos con él aunque se olvide de mirar {@code enabled};</li>
 * <li>el orden es por día (de lunes a domingo, el orden de {@code DayOfWeek}), hora de inicio e ID. Va acá y no en la
 * consulta: la base guarda el día como texto, y un {@code ORDER BY} lo ordenaría alfabéticamente (FRIDAY antes que
 * MONDAY).</li>
 * </ul>
 * La categoría puede estar deshabilitada con el profesional habilitado: se informa y no se inventa una regla, igual que
 * en la búsqueda (DP-01). La cátedra solo rechaza un hold por {@code PROFESSIONAL_DISABLED}.
 */
public record ProfessionalAgenda(ProfessionalWithCategory professional, List<WeeklySchedule> weeklySchedules) {
    /** Día de la semana, hora de inicio e ID: el ID desempata dos horarios del mismo día y hora. */
    static final Comparator<WeeklySchedule> ORDER = Comparator.comparing(WeeklySchedule::dayOfWeek)
        .thenComparing(WeeklySchedule::startTime)
        .thenComparingLong(WeeklySchedule::id);

    public ProfessionalAgenda {
        Objects.requireNonNull(professional, "Falta el profesional");
        Objects.requireNonNull(weeklySchedules, "Faltan los horarios");
        long professionalId = professional.professional().id();
        for (WeeklySchedule schedule : weeklySchedules) {
            if (schedule.professionalId() != professionalId) {
                throw new IllegalArgumentException("El horario " + schedule.id() + " no es del profesional " + professionalId);
            }
            if (!schedule.enabled()) {
                throw new IllegalArgumentException("El horario " + schedule.id() + " está deshabilitado: no es parte de la agenda vigente");
            }
        }
        if (!professional.professional().enabled() && !weeklySchedules.isEmpty()) {
            throw new IllegalArgumentException("El profesional " + professionalId + " está deshabilitado: no tiene agenda vigente");
        }
        weeklySchedules = weeklySchedules.stream().sorted(ORDER).toList();
    }

    /** La agenda de un profesional deshabilitado: sin horarios. */
    public static ProfessionalAgenda withoutSchedules(ProfessionalWithCategory professional) {
        return new ProfessionalAgenda(professional, List.of());
    }
}
