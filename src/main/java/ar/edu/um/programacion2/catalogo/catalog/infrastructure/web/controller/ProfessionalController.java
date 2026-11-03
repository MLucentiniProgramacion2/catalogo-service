package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalPageResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalSearchParams;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper.ProfessionalDtoMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-05:
 * {@code GET /api/professionals?categoryId=&name=&enabled=&day=&from=&to=&page=&size=}.
 * <p>
 * Lo protege la regla de {@code /api/**} de {@code SecurityConfiguration}: sin token o con uno inválido, 401; con el
 * token técnico de servicio o sin {@code ROLE_USER}/{@code ROLE_ADMIN}, 403 (DP-06).
 * <p>
 * Los parámetros llegan juntos en {@link ProfessionalSearchParams}: al ser un record y no un tipo simple, Spring lo arma
 * con los parámetros de la query que coinciden con sus campos (como si tuviera {@code @ModelAttribute}). Con
 * {@code @Valid}, cualquier valor inválido da 400 con un {@code fieldError} por parámetro, incluido uno que no se puede
 * convertir (como {@code page=abc}). Es otro camino que el de CU-06, que usa un {@code @RequestParam} suelto: ver
 * {@code ExceptionTranslator}.
 */
@RestController
@RequestMapping("/api/professionals")
@RequiredArgsConstructor
public class ProfessionalController {

    private final CatalogService catalogService;
    private final ProfessionalDtoMapper professionalDtoMapper;

    @GetMapping
    public ResponseEntity<ProfessionalPageResponse> searchProfessionals(@Valid ProfessionalSearchParams params) {
        try {
            return ResponseEntity.ok(
                professionalDtoMapper.toPageResponse(
                    catalogService.searchProfessionals(professionalDtoMapper.toCriteria(params), professionalDtoMapper.toPageQuery(params))
                )
            );
        } catch (CatalogNotInitializedException e) {
            throw CatalogErrorResponses.notInitialized(e);
        }
    }
}
