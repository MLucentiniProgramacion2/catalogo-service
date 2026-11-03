package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchProfessionalsUseCaseImplTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final PageQuery PAGINA = new PageQuery(1, 10);

    @Mock
    private LocalCatalogVersionProvider versionProvider;

    @Mock
    private CatalogRepository catalogRepository;

    @InjectMocks
    private SearchProfessionalsUseCaseImpl useCase;

    @Test
    void leePrimeroLaVersionYDevuelveLaPaginaConEsaVersion() {
        ProfessionalSearchCriteria criterio = ProfessionalSearchCriteria.of(1L, "ana", false);
        ResultPage<ProfessionalWithCategory> pagina = new ResultPage<>(List.of(anaDeClinica()), 1, 10, 11);
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(4));
        when(catalogRepository.searchProfessionals(criterio, PAGINA)).thenReturn(pagina);

        Versioned<ResultPage<ProfessionalWithCategory>> resultado = useCase.searchProfessionals(criterio, PAGINA);

        assertThat(resultado).isEqualTo(new Versioned<>(4, pagina));
        InOrder orden = inOrder(versionProvider, catalogRepository);
        orden.verify(versionProvider).findAppliedVersion();
        orden.verify(catalogRepository).searchProfessionals(criterio, PAGINA);
    }

    @Test
    void losFiltrosYLaPaginaLleganAlPuertoTalCual() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(1));
        when(catalogRepository.searchProfessionals(any(), any())).thenReturn(new ResultPage<>(List.of(), 1, 10, 0));

        useCase.searchProfessionals(ProfessionalSearchCriteria.of(7L, " ana pe ", false), PAGINA);

        ArgumentCaptor<ProfessionalSearchCriteria> criterio = ArgumentCaptor.forClass(ProfessionalSearchCriteria.class);
        verify(catalogRepository).searchProfessionals(criterio.capture(), eq(PAGINA));
        assertThat(criterio.getValue()).isEqualTo(new ProfessionalSearchCriteria(7L, "ana pe", false));
    }

    @Test
    void sinEnabledAlPuertoLeLlegaTrue() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(1));
        when(catalogRepository.searchProfessionals(any(), any())).thenReturn(new ResultPage<>(List.of(), 1, 10, 0));

        useCase.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), PAGINA);

        ArgumentCaptor<ProfessionalSearchCriteria> criterio = ArgumentCaptor.forClass(ProfessionalSearchCriteria.class);
        verify(catalogRepository).searchProfessionals(criterio.capture(), any());
        assertThat(criterio.getValue().enabled()).isTrue();
    }

    @Test
    void conVersionYSinResultadosDevuelveUnaPaginaVacia() {
        ResultPage<ProfessionalWithCategory> vacia = new ResultPage<>(List.of(), 1, 10, 0);
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(2));
        when(catalogRepository.searchProfessionals(any(), any())).thenReturn(vacia);

        assertThat(useCase.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), PAGINA)).isEqualTo(new Versioned<>(2, vacia));
    }

    @Test
    void sinVersionAplicadaFallaSinConsultarLosProfesionales() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> useCase.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), PAGINA)).isInstanceOf(
            CatalogNotInitializedException.class
        );

        verifyNoInteractions(catalogRepository);
    }

    @Test
    void rechazaNulosSinConsultarNada() {
        assertThatNullPointerException().isThrownBy(() -> useCase.searchProfessionals(null, PAGINA));
        assertThatNullPointerException().isThrownBy(() -> useCase.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), null));

        verifyNoInteractions(versionProvider, catalogRepository);
    }

    private static ProfessionalWithCategory anaDeClinica() {
        return new ProfessionalWithCategory(
            new Professional(15, 1, "Ana", "Gomez", false, CREADO, CREADO),
            new ProfessionalCategory(1, "Clinica medica", null, true, CREADO, CREADO)
        );
    }
}
