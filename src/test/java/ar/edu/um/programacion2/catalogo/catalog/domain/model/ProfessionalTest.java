package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfessionalTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    @Test
    void conservaLosDatosDeLaCatedra() {
        Professional profesional = new Professional(15, 1, "Ana", "Gomez", true, CREADO, ACTUALIZADO);

        assertThat(profesional.id()).isEqualTo(15);
        assertThat(profesional.categoryId()).isEqualTo(1);
        assertThat(profesional.firstName()).isEqualTo("Ana");
        assertThat(profesional.lastName()).isEqualTo("Gomez");
        assertThat(profesional.enabled()).isTrue();
        assertThat(profesional.createdAt()).isEqualTo(CREADO);
        assertThat(profesional.updatedAt()).isEqualTo(ACTUALIZADO);
    }

    @Test
    void aceptaUnProfesionalDeshabilitadoPorqueEsUnaBajaLogica() {
        assertThat(new Professional(15, 1, "Ana", "Gomez", false, CREADO, ACTUALIZADO).enabled()).isFalse();
    }

    @Test
    void rechazaFirstNameNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new Professional(15, 1, null, "Gomez", true, CREADO, ACTUALIZADO))
            .withMessageContaining("firstName");
    }

    @Test
    void rechazaLastNameNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new Professional(15, 1, "Ana", null, true, CREADO, ACTUALIZADO))
            .withMessageContaining("lastName");
    }

    @Test
    void rechazaCreatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new Professional(15, 1, "Ana", "Gomez", true, null, ACTUALIZADO))
            .withMessageContaining("createdAt");
    }

    @Test
    void rechazaUpdatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new Professional(15, 1, "Ana", "Gomez", true, CREADO, null))
            .withMessageContaining("updatedAt");
    }
}
