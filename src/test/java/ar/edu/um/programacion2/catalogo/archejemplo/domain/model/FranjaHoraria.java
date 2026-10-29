package ar.edu.um.programacion2.catalogo.archejemplo.domain.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

public record FranjaHoraria(DayOfWeek dia, LocalTime desde, LocalTime hasta) {
    public FranjaHoraria {
        if (!desde.isBefore(hasta)) {
            throw new IllegalArgumentException("La franja debe terminar después de empezar: " + desde + "-" + hasta);
        }
    }

    public boolean contiene(DayOfWeek dia, LocalTime hora) {
        return this.dia == dia && !hora.isBefore(desde) && hora.isBefore(hasta);
    }
}
