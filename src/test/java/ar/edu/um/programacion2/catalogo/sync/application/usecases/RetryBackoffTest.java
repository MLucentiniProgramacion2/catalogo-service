package ar.edu.um.programacion2.catalogo.sync.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {

    private final List<Duration> esperas = new ArrayList<>();

    @Test
    void laCantidadDeReintentosEsLaCantidadDeEsperas() {
        RetryBackoff backoff = new RetryBackoff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4)), esperas::add);

        assertThat(backoff.maxRetries()).isEqualTo(3);
        assertThat(backoff.delayBeforeRetry(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(backoff.delayBeforeRetry(3)).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void esperaLoQueCorrespondeAlReintento() throws InterruptedException {
        RetryBackoff backoff = new RetryBackoff(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), esperas::add);

        backoff.pauseBeforeRetry(2);

        assertThat(esperas).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void sinEsperasNoHayReintentos() {
        assertThat(new RetryBackoff(List.of(), esperas::add).maxRetries()).isZero();
    }

    @Test
    void rechazaUnaEsperaNegativa() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetryBackoff(List.of(Duration.ofSeconds(-1)), esperas::add));
    }

    @Test
    void copiaLasEsperas() {
        List<Duration> configuradas = new ArrayList<>(List.of(Duration.ofSeconds(1)));
        RetryBackoff backoff = new RetryBackoff(configuradas, esperas::add);

        configuradas.add(Duration.ofSeconds(2));

        assertThat(backoff.maxRetries()).isEqualTo(1);
    }
}
