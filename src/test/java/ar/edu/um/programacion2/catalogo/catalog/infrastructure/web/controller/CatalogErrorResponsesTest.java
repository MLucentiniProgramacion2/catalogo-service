package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class CatalogErrorResponsesTest {

    @Test
    void elRetryAfterVaEnSegundosEnterosRedondeadoParaArriba() {
        assertThat(CatalogErrorResponses.seconds(Duration.ofMinutes(5))).isEqualTo(300);
        assertThat(CatalogErrorResponses.seconds(Duration.ofMillis(60_001))).isEqualTo(61);
    }

    @Test
    void sinPlazoNoVaElHeader() {
        var respuesta = CatalogErrorResponses.notInitialized(new CatalogNotInitializedException(Optional.empty()));

        assertThat(respuesta.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }

    @Test
    void conPlazoVaElHeader() {
        var respuesta = CatalogErrorResponses.notInitialized(new CatalogNotInitializedException(Optional.of(Duration.ofMinutes(5))));

        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
    }
}
