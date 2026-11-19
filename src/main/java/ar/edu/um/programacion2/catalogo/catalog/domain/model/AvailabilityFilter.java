package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Filtro de disponibilidad de la búsqueda (CU-05, paso 4): el profesional tiene al menos un horario semanal
 * <strong>habilitado</strong> de ese día que se superpone con la franja pedida, o cualquiera de ese día si no se indica
 * franja. "Disponibilidad" es que <strong>atiende</strong> según sus horarios; no mira ocupaciones ni turnos (DP-01).
 * <p>
 * Los horarios y la franja son intervalos semiabiertos, {@code [inicio, fin)}: el fin no está incluido. Se superponen
 * si {@code start < to} y {@code end > from}. Así, un horario que termina a las 12:00 no se superpone con una franja
 * que empieza a las 12:00.
 * <p>
 * La franja puede quedar abierta de un lado ({@code from} o {@code to} nulos): sin {@code from}, la condición
 * {@code end > from} no se aplica, y lo mismo con {@code to}. Si vienen las dos, {@code from} tiene que ser anterior a
 * {@code to}: con {@code from == to} la franja {@code [from, to)} estaría vacía y no se superpondría con nada.
 * <p>
 * Un horario con {@code startTime >= endTime} (el contrato no lo impide, ver {@link WeeklySchedule}) es el intervalo
 * vacío {@code [start, end)}: no se superpone con ninguna franja y, sin franja, no cubre ninguna hora del día. Por eso
 * no cuenta nunca. La regla es la misma con franja y sin ella, y no depende de los turnos: un horario con
 * {@code slotDurationMinutes <= 0} sí cuenta.
 *
 * @param day  día de la semana; obligatorio (una franja sin día no es un filtro válido)
 * @param from inicio de la franja, o {@code null} para no acotarla por abajo
 * @param to   fin de la franja (no incluido), o {@code null} para no acotarla por arriba
 */
public record AvailabilityFilter(DayOfWeek day, LocalTime from, LocalTime to) {
    public AvailabilityFilter {
        Objects.requireNonNull(day, "La franja horaria necesita un día");
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("El inicio de la franja (" + from + ") tiene que ser anterior al fin (" + to + ")");
        }
    }

    /** Todo el día: cualquier horario habilitado (y no vacío) de ese día. */
    public static AvailabilityFilter on(DayOfWeek day) {
        return new AvailabilityFilter(day, null, null);
    }
}
