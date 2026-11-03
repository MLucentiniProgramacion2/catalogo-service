package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ListCategoriesUseCaseImplTest {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");

    @Mock
    private LocalCatalogVersionProvider versionProvider;

    @Mock
    private CatalogRepository catalogRepository;

    @InjectMocks
    private ListCategoriesUseCaseImpl useCase;

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = { true, false })
    void pasaElFiltroTalCualYDevuelveLaVersionConLasCategorias(Boolean habilitadas) {
        List<ProfessionalCategory> categorias = List.of(categoria(1), categoria(2));
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(4));
        when(catalogRepository.findCategories(habilitadas)).thenReturn(categorias);

        Versioned<List<ProfessionalCategory>> resultado = useCase.listCategories(habilitadas);

        assertThat(resultado).isEqualTo(new Versioned<>(4, categorias));
        verify(catalogRepository).findCategories(habilitadas);
    }

    @Test
    void conVersionYSinCategoriasDevuelveUnaListaVacia() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.of(1));
        when(catalogRepository.findCategories(null)).thenReturn(List.of());

        assertThat(useCase.listCategories(null)).isEqualTo(new Versioned<>(1, List.of()));
    }

    @Test
    void sinVersionAplicadaFallaSinConsultarLasCategorias() {
        when(versionProvider.findAppliedVersion()).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> useCase.listCategories(null)).isInstanceOf(CatalogNotInitializedException.class);

        verifyNoInteractions(catalogRepository);
    }

    private static ProfessionalCategory categoria(long id) {
        return new ProfessionalCategory(id, "Categoria " + id, null, true, CREADO, CREADO);
    }
}
