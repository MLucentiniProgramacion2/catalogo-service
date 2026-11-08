package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import ar.edu.um.programacion2.catalogo.sync.SyncWorstCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SyncPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void laEsperaPorDefectoCubreElPeorCasoDeCu03ConRedis() {
        SyncWorstCase peor = new SyncWorstCase();

        // Las piezas, para que un cambio de configuración se vea en cuál: Redis 5 + 3 × 3 s, Hikari 30 s, CU-02 297 s.
        assertThat(peor.redisAttempt()).isEqualTo(Duration.ofSeconds(14));
        assertThat(peor.hikari()).isEqualTo(Duration.ofSeconds(30));
        assertThat(peor.fullSync()).isEqualTo(Duration.ofSeconds(297));
        assertThat(peor.incorporateChanges()).isEqualTo(Duration.ofSeconds(618));
        assertThat(new SyncProperties().getLock().getWaitTimeout()).isEqualTo(Duration.ofMinutes(12)).isGreaterThan(peor.incorporateChanges());
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
