package ar.edu.um.programacion2.catalogo.sync.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PeriodicCatalogRetryHintTest {

    private final SyncProperties properties = new SyncProperties();

    @Test
    void conLaPeriodicaPrendidaElPlazoEsSuIntervalo() {
        properties.getPeriodic().setInterval(Duration.ofMinutes(7));

        assertThat(new PeriodicCatalogRetryHint(properties).retryAfter()).contains(Duration.ofMinutes(7));
    }

    @Test
    void conLaPeriodicaApagadaNoHayPlazo() {
        properties.getPeriodic().setEnabled(false);

        assertThat(new PeriodicCatalogRetryHint(properties).retryAfter()).isEmpty();
    }
}
