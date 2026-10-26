package ar.edu.um.programacion2.catalogo.sync.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessedEventTest {

    private static final Instant PROCESADO = Instant.parse("2026-09-10T13:45:02Z");

    @Test
    void conservaElEventIdLaVersionAnunciadaYLaFecha() {
        ProcessedEvent evento = new ProcessedEvent("catalog-event-7-4f3b", 7, PROCESADO);

        assertThat(evento.eventId()).isEqualTo("catalog-event-7-4f3b");
        assertThat(evento.newVersion()).isEqualTo(7);
        assertThat(evento.processedAt()).isEqualTo(PROCESADO);
    }

    @Test
    void dosRegistrosDelMismoEventoSonIguales() {
        assertThat(new ProcessedEvent("catalog-event-7-4f3b", 7, PROCESADO)).isEqualTo(
            new ProcessedEvent("catalog-event-7-4f3b", 7, PROCESADO)
        );
    }

    @Test
    void rechazaEventIdNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ProcessedEvent(null, 7, PROCESADO))
            .withMessageContaining("eventId");
    }

    @Test
    void rechazaEventIdEnBlanco() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProcessedEvent("  ", 7, PROCESADO))
            .withMessageContaining("eventId");
    }

    @Test
    void rechazaFechaDeProcesamientoNula() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ProcessedEvent("catalog-event-7-4f3b", 7, null))
            .withMessageContaining("fecha de procesamiento");
    }
}
