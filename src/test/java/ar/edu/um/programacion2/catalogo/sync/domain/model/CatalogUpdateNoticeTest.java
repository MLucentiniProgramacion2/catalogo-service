package ar.edu.um.programacion2.catalogo.sync.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CatalogUpdateNoticeTest {

    private static final String EVENTO = "8b097be5-7ba5-4618-97e8-c502d3f46723";
    private static final Instant T1 = Instant.parse("2026-11-06T01:19:28Z");

    @Test
    void conservaElEventoYLaVersionAlRegistrarse() {
        assertThat(new CatalogUpdateNotice(EVENTO, 41).processedAt(T1)).isEqualTo(new ProcessedEvent(EVENTO, 41, T1));
    }

    @Test
    void noAceptaUnAvisoSinEventId() {
        assertThatNullPointerException().isThrownBy(() -> new CatalogUpdateNotice(null, 41)).withMessageContaining("eventId");
    }

    @Test
    void noAceptaUnEventIdEnBlanco() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CatalogUpdateNotice("  ", 41)).withMessageContaining("eventId");
    }
}
