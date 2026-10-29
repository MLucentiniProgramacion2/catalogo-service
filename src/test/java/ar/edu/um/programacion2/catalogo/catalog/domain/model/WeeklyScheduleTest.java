package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class WeeklyScheduleTest {

    private static final LocalTime NUEVE = LocalTime.of(9, 0);
    private static final LocalTime DOCE = LocalTime.of(12, 0);
    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    @Test
    void conservaLosDatosDeLaCatedra() {
        WeeklySchedule horario = new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, DOCE, 30, true, CREADO, ACTUALIZADO);

        assertThat(horario.id()).isEqualTo(21);
        assertThat(horario.professionalId()).isEqualTo(15);
        assertThat(horario.dayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(horario.startTime()).isEqualTo(NUEVE);
        assertThat(horario.endTime()).isEqualTo(DOCE);
        assertThat(horario.slotDurationMinutes()).isEqualTo(30);
        assertThat(horario.enabled()).isTrue();
        assertThat(horario.createdAt()).isEqualTo(CREADO);
        assertThat(horario.updatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void aceptaUnHorarioDeshabilitadoPorqueEsUnaBajaLogica() {
        assertThat(new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, DOCE, 30, false, CREADO, ACTUALIZADO).enabled()).isFalse();
    }

    @Test
    void aceptaUnaFranjaQueNoTerminaDespuesDeEmpezarPorqueElContratoNoLoGarantiza() {
        WeeklySchedule horario = new WeeklySchedule(
            21,
            15,
            DayOfWeek.FRIDAY,
            LocalTime.of(22, 0),
            LocalTime.of(2, 0),
            30,
            true,
            CREADO,
            ACTUALIZADO
        );

        assertThat(horario.startTime()).isAfter(horario.endTime());
    }

    @Test
    void aceptaUnaDuracionDeTurnoNoPositivaPorqueElContratoNoLoGarantiza() {
        assertThat(new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, DOCE, 0, true, CREADO, ACTUALIZADO).slotDurationMinutes()).isZero();
    }

    @Test
    void rechazaDayOfWeekNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WeeklySchedule(21, 15, null, NUEVE, DOCE, 30, true, CREADO, ACTUALIZADO))
            .withMessageContaining("dayOfWeek");
    }

    @Test
    void rechazaStartTimeNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WeeklySchedule(21, 15, DayOfWeek.MONDAY, null, DOCE, 30, true, CREADO, ACTUALIZADO))
            .withMessageContaining("startTime");
    }

    @Test
    void rechazaEndTimeNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, null, 30, true, CREADO, ACTUALIZADO))
            .withMessageContaining("endTime");
    }

    @Test
    void rechazaCreatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, DOCE, 30, true, null, ACTUALIZADO))
            .withMessageContaining("createdAt");
    }

    @Test
    void rechazaUpdatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new WeeklySchedule(21, 15, DayOfWeek.MONDAY, NUEVE, DOCE, 30, true, CREADO, null))
            .withMessageContaining("updatedAt");
    }
}
