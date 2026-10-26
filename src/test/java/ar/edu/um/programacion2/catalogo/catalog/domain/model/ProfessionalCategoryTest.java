package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfessionalCategoryTest {

    private static final Instant CREADA = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADA = Instant.parse("2026-09-10T13:40:00Z");

    @Test
    void conservaLosDatosDeLaCatedra() {
        ProfessionalCategory categoria = new ProfessionalCategory(
            1,
            "Clinica medica",
            "Atencion clinica general",
            true,
            CREADA,
            ACTUALIZADA
        );

        assertThat(categoria.id()).isEqualTo(1);
        assertThat(categoria.name()).isEqualTo("Clinica medica");
        assertThat(categoria.description()).isEqualTo("Atencion clinica general");
        assertThat(categoria.enabled()).isTrue();
        assertThat(categoria.createdAt()).isEqualTo(CREADA);
        assertThat(categoria.updatedAt()).isEqualTo(ACTUALIZADA);
    }

    @Test
    void aceptaUnaCategoriaDeshabilitadaPorqueEsUnaBajaLogica() {
        assertThat(new ProfessionalCategory(1, "Clinica medica", "x", false, CREADA, ACTUALIZADA).enabled()).isFalse();
    }

    @Test
    void aceptaDescripcionNulaPorqueElContratoNoLaGarantiza() {
        assertThat(new ProfessionalCategory(1, "Clinica medica", null, true, CREADA, ACTUALIZADA).description()).isNull();
    }

    @Test
    void aceptaNombreEnBlancoPorqueElContratoNoLoProhibe() {
        assertThat(new ProfessionalCategory(1, " ", null, true, CREADA, ACTUALIZADA).name()).isBlank();
    }

    @Test
    void rechazaNombreNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ProfessionalCategory(1, null, "x", true, CREADA, ACTUALIZADA))
            .withMessageContaining("nombre");
    }

    @Test
    void rechazaCreatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ProfessionalCategory(1, "Clinica medica", "x", true, null, ACTUALIZADA))
            .withMessageContaining("createdAt");
    }

    @Test
    void rechazaUpdatedAtNulo() {
        assertThatNullPointerException()
            .isThrownBy(() -> new ProfessionalCategory(1, "Clinica medica", "x", true, CREADA, null))
            .withMessageContaining("updatedAt");
    }
}
