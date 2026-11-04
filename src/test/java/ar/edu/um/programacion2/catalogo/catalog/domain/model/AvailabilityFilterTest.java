package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.DayOfWeek;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class AvailabilityFilterTest {

    private static final LocalTime DIEZ = LocalTime.of(10, 0);
    private static final LocalTime DOCE = LocalTime.of(12, 0);

    @Test
    void aceptaUnaFranjaCompletaYAbiertaDeCadaLado() {
        assertThat(new AvailabilityFilter(DayOfWeek.MONDAY, DIEZ, DOCE).to()).isEqualTo(DOCE);
        assertThat(new AvailabilityFilter(DayOfWeek.MONDAY, DIEZ, null).to()).isNull();
        assertThat(new AvailabilityFilter(DayOfWeek.MONDAY, null, DOCE).from()).isNull();
        assertThat(AvailabilityFilter.on(DayOfWeek.SUNDAY)).isEqualTo(new AvailabilityFilter(DayOfWeek.SUNDAY, null, null));
    }

    @Test
    void aceptaUnMinutoDeFranja() {
        assertThat(new AvailabilityFilter(DayOfWeek.MONDAY, DIEZ, DIEZ.plusMinutes(1)).from()).isEqualTo(DIEZ);
    }

    @Test
    void rechazaUnaFranjaVaciaPorqueElFinNoEstaIncluido() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AvailabilityFilter(DayOfWeek.MONDAY, DIEZ, DIEZ)).withMessageContaining("anterior");
    }

    @Test
    void rechazaUnaFranjaInvertida() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AvailabilityFilter(DayOfWeek.MONDAY, DOCE, DIEZ));
    }

    @Test
    void rechazaUnaFranjaSinDia() {
        assertThatNullPointerException().isThrownBy(() -> new AvailabilityFilter(null, DIEZ, DOCE)).withMessageContaining("día");
    }

    @Test
    void elCriterioLlevaLaDisponibilidadYSinElParametroNoFiltra() {
        AvailabilityFilter lunes = AvailabilityFilter.on(DayOfWeek.MONDAY);

        assertThat(ProfessionalSearchCriteria.of(null, null, null, lunes).availability()).isEqualTo(lunes);
        assertThat(ProfessionalSearchCriteria.of(null, null, null).availability()).isNull();
    }
}
