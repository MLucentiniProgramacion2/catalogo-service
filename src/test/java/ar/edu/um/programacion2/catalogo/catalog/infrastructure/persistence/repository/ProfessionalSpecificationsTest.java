package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProfessionalSpecificationsTest {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = { "ana|ana", "50%|50\\%", "a_b|a\\_b", "a\\b|a\\\\b", "\\%|\\\\\\%", "%_%|\\%\\_\\%", "Ángel Núñez|Ángel Núñez" }
    )
    void escapaLosComodinesYElPropioEscape(String texto, String escapado) {
        assertThat(ProfessionalSpecifications.escapeLike(texto)).isEqualTo(escapado);
    }
}
