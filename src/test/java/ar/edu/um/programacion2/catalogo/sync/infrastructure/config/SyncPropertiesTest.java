package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void laEsperaPorDefectoCubreElPeorCasoDeLaSincronizacionCompleta() {
        // 4 × (5 s + 30 s + 30 s) + 7 s de reintentos + 30 s para registrar el error (DP-08 y DP-09).
        Duration peorCaso = Duration.ofSeconds(4 * (5 + 30 + 30) + 7 + 30);

        assertThat(new SyncProperties().getLock().getWaitTimeout()).isEqualTo(Duration.ofMinutes(6)).isGreaterThan(peorCaso);
    }

    @ParameterizedTest
    @ValueSource(longs = { 0, -1 })
    void laEsperaTieneQueSerPositiva(long milisegundos) {
        SyncProperties properties = new SyncProperties();
        properties.getLock().setWaitTimeout(Duration.ofMillis(milisegundos));

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void laEsperaEsObligatoria() {
        SyncProperties properties = new SyncProperties();
        properties.getLock().setWaitTimeout(null);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void elPlazoDelLoteIncrementalEsDeUnMinutoPorDefecto() {
        assertThat(new SyncProperties().getIncremental().getTimeBudget()).isEqualTo(Duration.ofSeconds(60));
    }

    @ParameterizedTest
    @ValueSource(longs = { 0, -1 })
    void elPlazoDelLoteIncrementalTieneQueSerPositivo(long milisegundos) {
        SyncProperties properties = new SyncProperties();
        properties.getIncremental().setTimeBudget(Duration.ofMillis(milisegundos));

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}
