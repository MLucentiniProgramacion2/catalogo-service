package ar.edu.um.programacion2.catalogo.catalog.application.service;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.GetProfessionalAgendaUseCase;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.ListCategoriesUseCase;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.SearchProfessionalsUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Fachada del catálogo: los adaptadores de entrada (los controllers de CU-05, CU-06 y CU-07) llaman acá y nunca a los
 * casos de uso ni a los adaptadores de salida.
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private final ListCategoriesUseCase listCategoriesUseCase;
    private final SearchProfessionalsUseCase searchProfessionalsUseCase;
    private final GetProfessionalAgendaUseCase getProfessionalAgendaUseCase;

    public Versioned<List<ProfessionalCategory>> listCategories(Boolean enabled) {
        return listCategoriesUseCase.listCategories(enabled);
    }

    public Versioned<ResultPage<ProfessionalWithCategory>> searchProfessionals(ProfessionalSearchCriteria criteria, PageQuery pageQuery) {
        return searchProfessionalsUseCase.searchProfessionals(criteria, pageQuery);
    }

    public Versioned<ProfessionalAgenda> getProfessionalAgenda(long professionalId) {
        return getProfessionalAgendaUseCase.getProfessionalAgenda(professionalId);
    }
}
