package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategorySummaryResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalPageResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalSearchParams;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfessionalDtoMapperTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    private final ProfessionalDtoMapper mapper = new ProfessionalDtoMapper();

    @Test
    void sinParametrosElCriterioTomaLosHabilitadosYLaPrimeraPaginaDe20() {
        ProfessionalSearchParams params = new ProfessionalSearchParams(null, null, null, null, null);

        assertThat(mapper.toCriteria(params)).isEqualTo(new ProfessionalSearchCriteria(null, null, true));
        assertThat(mapper.toPageQuery(params)).isEqualTo(new PageQuery(0, 20));
    }

    @Test
    void conParametrosLosPasaAlDominio() {
        ProfessionalSearchParams params = new ProfessionalSearchParams(3L, " ana ", false, 2, 5);

        assertThat(mapper.toCriteria(params)).isEqualTo(new ProfessionalSearchCriteria(3L, "ana", false));
        assertThat(mapper.toPageQuery(params)).isEqualTo(new PageQuery(2, 5));
    }

    @Test
    void armaLaRespuestaConLaVersionLaPaginaYLaCategoria() {
        ProfessionalWithCategory ana = new ProfessionalWithCategory(
            new Professional(15, 1, "Ana", "Gomez", true, CREADO, CREADO),
            new ProfessionalCategory(1, "Clinica medica", "Atencion clinica general", false, CREADO, CREADO)
        );

        ProfessionalPageResponse respuesta = mapper.toPageResponse(new Versioned<>(4, new ResultPage<>(List.of(ana), 1, 2, 3)));

        assertThat(respuesta).isEqualTo(
            new ProfessionalPageResponse(
                4,
                1,
                2,
                3,
                2,
                List.of(new ProfessionalResponse(15, "Ana", "Gomez", true, new CategorySummaryResponse(1, "Clinica medica", false)))
            )
        );
    }
}
