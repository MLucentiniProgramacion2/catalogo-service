package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfessionalAgendaTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void ordenaPorDiaDeLunesADomingoDespuesPorHoraYDespuesPorId() {
        // Desordenados a propósito, y FRIDAY antes que MONDAY alfabéticamente.
        ProfessionalAgenda agenda = new ProfessionalAgenda(
            profesional(true),
            List.of(
                horario(30, DayOfWeek.FRIDAY, "09:00"),
                horario(12, DayOfWeek.MONDAY, "14:00"),
                horario(11, DayOfWeek.MONDAY, "09:00"),
                horario(10, DayOfWeek.MONDAY, "09:00"),
                horario(40, DayOfWeek.SUNDAY, "08:00")
            )
        );

        assertThat(agenda.weeklySchedules()).extracting(WeeklySchedule::id).containsExactly(10L, 11L, 12L, 30L, 40L);
    }

    @Test
    void losHorariosNoSePuedenModificar() {
        ProfessionalAgenda agenda = new ProfessionalAgenda(profesional(true), List.of(horario(10, DayOfWeek.MONDAY, "09:00")));

        assertThatThrownBy(() -> agenda.weeklySchedules().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rechazaUnHorarioDeshabilitado() {
        WeeklySchedule deshabilitado = new WeeklySchedule(10, 15, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, false, CREADO, CREADO);

        assertThatIllegalArgumentException().isThrownBy(() -> new ProfessionalAgenda(profesional(true), List.of(deshabilitado)));
    }

    @Test
    void rechazaUnHorarioDeOtroProfesional() {
        WeeklySchedule ajeno = new WeeklySchedule(10, 99, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true, CREADO, CREADO);

        assertThatIllegalArgumentException().isThrownBy(() -> new ProfessionalAgenda(profesional(true), List.of(ajeno)));
    }

    @Test
    void unProfesionalDeshabilitadoNoTieneAgendaVigente() {
        assertThatIllegalArgumentException().isThrownBy(() ->
            new ProfessionalAgenda(profesional(false), List.of(horario(10, DayOfWeek.MONDAY, "09:00")))
        );
        assertThat(ProfessionalAgenda.withoutSchedules(profesional(false)).weeklySchedules()).isEmpty();
    }

    private static ProfessionalWithCategory profesional(boolean habilitado) {
        return new ProfessionalWithCategory(
            new Professional(15, 1, "Ana", "Gomez", habilitado, CREADO, CREADO),
            new ProfessionalCategory(1, "Clínica", null, true, CREADO, CREADO)
        );
    }

    private static WeeklySchedule horario(long id, DayOfWeek dia, String desde) {
        LocalTime inicio = LocalTime.parse(desde);
        return new WeeklySchedule(id, 15, dia, inicio, inicio.plusHours(3), 30, true, CREADO, CREADO);
    }
}
