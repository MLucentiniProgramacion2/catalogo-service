package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Un horario semanal habilitado de la agenda (CU-07). Turnos arma un slot cada {@code slotDurationMinutes} desde
 * {@code startTime} mientras termine antes o justo en {@code endTime}.
 * <p>
 * Las horas van siempre como {@code HH:mm:ss}: es lo que publica la cátedra en el snapshot y en Redis (contrato §7 y
 * §14.4), no pierde los segundos, y el contrato (§4) pide aceptar {@code HH:mm} y {@code HH:mm:ss}. Va fijo con
 * {@code @JsonFormat} para no depender del valor por defecto de Jackson, que hoy da lo mismo pero agregaría fracciones de
 * segundo si las hubiera.
 */
public record AgendaScheduleResponse(
    long id,
    DayOfWeek dayOfWeek,
    @JsonFormat(pattern = "HH:mm:ss") LocalTime startTime,
    @JsonFormat(pattern = "HH:mm:ss") LocalTime endTime,
    int slotDurationMinutes
) {}
