package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.WeeklyScheduleEntity;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class WeeklyScheduleMapperTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");
    private static final WeeklySchedule HORARIO = new WeeklySchedule(
        21,
        15,
        DayOfWeek.MONDAY,
        LocalTime.of(9, 0),
        LocalTime.of(12, 30, 15),
        30,
        false,
        CREADO,
        ACTUALIZADO
    );

    private final WeeklyScheduleMapper mapper = new WeeklyScheduleMapper();

    @Test
    void toEntityUsaLaReferenciaRecibida() {
        ProfessionalEntity referencia = profesional(15L);

        assertThat(mapper.toEntity(HORARIO, referencia).getProfessional()).isSameAs(referencia);
    }

    @Test
    void idaYVueltaConservaTodosLosCampos() {
        assertThat(mapper.toDomain(mapper.toEntity(HORARIO, profesional(15L)))).isEqualTo(HORARIO);
    }

    @Test
    void rechazaUnaReferenciaAOtroProfesional() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> mapper.toEntity(HORARIO, profesional(16L)))
            .withMessage("La referencia al profesional 16 no coincide con el profesional 15 del horario 21");
    }

    @Test
    void unaDuracionNulaEnLaBaseFallaEnVezDeLeerseComoCero() {
        WeeklyScheduleEntity entidad = mapper.toEntity(HORARIO, profesional(15L));
        entidad.setSlotDurationMinutes(null);

        assertThatNullPointerException()
            .isThrownBy(() -> mapper.toDomain(entidad))
            .withMessage("El horario 21 tiene slotDurationMinutes nulo en la base");
    }

    @Test
    void unEnabledNuloEnLaBaseFallaEnVezDeLeerseComoFalse() {
        WeeklyScheduleEntity entidad = mapper.toEntity(HORARIO, profesional(15L));
        entidad.setEnabled(null);

        assertThatNullPointerException()
            .isThrownBy(() -> mapper.toDomain(entidad))
            .withMessage("El horario 21 tiene enabled nulo en la base");
    }

    private static ProfessionalEntity profesional(Long id) {
        ProfessionalEntity profesional = new ProfessionalEntity();
        profesional.setId(id);
        return profesional;
    }
}
