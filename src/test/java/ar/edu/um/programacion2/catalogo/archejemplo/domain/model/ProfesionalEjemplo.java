package ar.edu.um.programacion2.catalogo.archejemplo.domain.model;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Ejercita lo que va a usar un dominio real: primitivos, arrays, enum, record, java.time, Optional, colecciones y streams. */
public class ProfesionalEjemplo {

    private final long id;
    private final String nombre;
    private final int duracionTurnoMinutos;
    private final EstadoProfesional estado;
    private final List<FranjaHoraria> franjas;
    private final Set<Long> categoriaIds;
    private final Instant actualizadoEn;

    public ProfesionalEjemplo(
        long id,
        String nombre,
        int duracionTurnoMinutos,
        EstadoProfesional estado,
        List<FranjaHoraria> franjas,
        Set<Long> categoriaIds,
        Instant actualizadoEn
    ) {
        this.id = id;
        this.nombre = nombre;
        this.duracionTurnoMinutos = duracionTurnoMinutos;
        this.estado = estado;
        this.franjas = List.copyOf(franjas);
        this.categoriaIds = Set.copyOf(categoriaIds);
        this.actualizadoEn = actualizadoEn;
    }

    public Optional<FranjaHoraria> franjaPara(DayOfWeek dia, LocalTime hora) {
        return franjas.stream().filter(franja -> franja.contiene(dia, hora)).findFirst();
    }

    public Map<DayOfWeek, List<FranjaHoraria>> franjasPorDia() {
        return franjas.stream().collect(Collectors.groupingBy(FranjaHoraria::dia));
    }

    public int[] minutosDeInicio() {
        return franjas.stream().mapToInt(franja -> franja.desde().toSecondOfDay() / 60).toArray();
    }

    public boolean atiende() {
        return estado.atiende() && !franjas.isEmpty();
    }

    public boolean perteneceA(long categoriaId) {
        return categoriaIds.contains(categoriaId);
    }

    public long getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public int getDuracionTurnoMinutos() {
        return duracionTurnoMinutos;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
