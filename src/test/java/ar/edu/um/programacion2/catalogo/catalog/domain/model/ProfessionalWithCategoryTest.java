package ar.edu.um.programacion2.catalogo.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProfessionalWithCategoryTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Professional ANA = new Professional(15, 1, "Ana", "Gomez", true, CREADO, CREADO);

    @Test
    void juntaAlProfesionalConSuCategoria() {
        ProfessionalCategory clinica = new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, CREADO);

        ProfessionalWithCategory resultado = new ProfessionalWithCategory(ANA, clinica);

        assertThat(resultado.professional()).isEqualTo(ANA);
        assertThat(resultado.category()).isEqualTo(clinica);
    }

    @Test
    void aceptaUnaCategoriaDeshabilitadaConUnProfesionalHabilitado() {
        ProfessionalCategory deshabilitada = new ProfessionalCategory(1, "Clinica medica", null, false, CREADO, CREADO);

        assertThat(new ProfessionalWithCategory(ANA, deshabilitada).category().enabled()).isFalse();
    }

    @Test
    void rechazaUnaCategoriaQueNoEsLaDelProfesional() {
        ProfessionalCategory otra = new ProfessionalCategory(2, "Pediatria", null, true, CREADO, CREADO);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> new ProfessionalWithCategory(ANA, otra))
            .withMessage("El profesional 15 es de la categoría 1, no de la 2");
    }

    @Test
    void rechazaNulos() {
        ProfessionalCategory clinica = new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, CREADO);

        assertThatNullPointerException().isThrownBy(() -> new ProfessionalWithCategory(null, clinica));
        assertThatNullPointerException().isThrownBy(() -> new ProfessionalWithCategory(ANA, null)).withMessageContaining("15");
    }
}
