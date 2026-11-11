package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.sync.SyncWorstCase;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    @ParameterizedTest
    @ValueSource(longs = { 0, 1, 59_999 })
    void elIntervaloDeLaPeriodicaNoPuedeSerMenorQueUnMinuto(long milisegundos) {
        SyncProperties properties = new SyncProperties();
        properties.getPeriodic().setInterval(Duration.ofMillis(milisegundos));

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void unMinutoEsElIntervaloMasCortoQueSeAcepta() {
        SyncProperties properties = new SyncProperties();
        properties.getPeriodic().setInterval(Duration.ofMinutes(1));

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void elIntervaloDeLaPeriodicaEsObligatorio() {
        SyncProperties properties = new SyncProperties();
        properties.getPeriodic().setInterval(null);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "5 m", "-1m", "0", "30s", "5" })
    void conUnIntervaloInvalidoODebajoDelMinimoNoArranca(String intervalo) {
        // "5" sin unidad son 5 ms: es el error de tipeo que corta el mínimo.
        new ApplicationContextRunner()
            .withUserConfiguration(SyncConfiguration.class)
            .withPropertyValues("sync.periodic.interval=" + intervalo)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void conUnIntervaloValidoArrancaConEseValor() {
        new ApplicationContextRunner()
            .withUserConfiguration(SyncConfiguration.class)
            .withPropertyValues("sync.periodic.interval=2m", "sync.periodic.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                SyncProperties properties = context.getBean(SyncProperties.class);
                assertThat(properties.getPeriodic().getInterval()).isEqualTo(Duration.ofMinutes(2));
                assertThat(properties.getPeriodic().isEnabled()).isFalse();
            });
    }
}
