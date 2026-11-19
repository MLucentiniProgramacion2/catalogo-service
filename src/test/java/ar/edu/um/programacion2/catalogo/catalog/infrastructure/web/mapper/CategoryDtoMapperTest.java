package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategoryListResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategoryResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryDtoMapperTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant ACTUALIZADO = Instant.parse("2026-09-10T13:40:00Z");

    private final CategoryDtoMapper mapper = new CategoryDtoMapper();

    @Test
    void copiaLosCamposDeLaAppSinLasFechasDeLaCatedra() {
        ProfessionalCategory categoria = new ProfessionalCategory(1, "Clinica medica", "Atencion clinica general", false, CREADO, ACTUALIZADO);

        assertThat(mapper.toResponse(categoria)).isEqualTo(new CategoryResponse(1, "Clinica medica", "Atencion clinica general", false));
    }

    @Test
    void conservaUnaDescripcionNula() {
        ProfessionalCategory categoria = new ProfessionalCategory(2, "Pediatria", null, true, CREADO, ACTUALIZADO);

        assertThat(mapper.toResponse(categoria).description()).isNull();
    }

    @Test
    void laListaLlevaLaVersionYConservaElOrden() {
        Versioned<List<ProfessionalCategory>> leido = new Versioned<>(
            4,
            List.of(
                new ProfessionalCategory(2, "Pediatria", null, true, CREADO, ACTUALIZADO),
                new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, ACTUALIZADO)
            )
        );

        CategoryListResponse respuesta = mapper.toListResponse(leido);

        assertThat(respuesta.catalogVersion()).isEqualTo(4);
        assertThat(respuesta.categories()).extracting(CategoryResponse::id).containsExactly(2L, 1L);
    }

    @Test
    void unaListaVaciaSigueLlevandoLaVersion() {
        assertThat(mapper.toListResponse(new Versioned<>(1, List.of()))).isEqualTo(new CategoryListResponse(1, List.of()));
    }
}
