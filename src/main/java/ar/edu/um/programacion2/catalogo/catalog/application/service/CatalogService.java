package ar.edu.um.programacion2.catalogo.catalog.application.service;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.ListCategoriesUseCase;
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

    public Versioned<List<ProfessionalCategory>> listCategories(Boolean enabled) {
        return listCategoriesUseCase.listCategories(enabled);
    }
}
