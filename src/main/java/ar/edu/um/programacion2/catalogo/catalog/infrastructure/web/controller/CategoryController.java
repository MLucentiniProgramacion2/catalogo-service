package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategoryListResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper.CategoryDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-06: {@code GET /api/categories?enabled={true|false}}.
 * <p>
 * Lo protege la regla de {@code /api/**} de {@code SecurityConfiguration}: sin token o con uno inválido, 401; con el
 * token técnico de servicio, 403 (DP-06).
 * <p>
 * {@code enabled} es {@code Boolean} y no {@code boolean} para que ausente signifique "todas". La conversión es la de
 * Spring: acepta {@code true}, {@code false}, {@code on}, {@code off}, {@code yes}, {@code no}, {@code 1} y {@code 0},
 * sin distinguir mayúsculas, y un valor vacío cuenta como ausente; cualquier otro da 400 en
 * {@code application/problem+json} ({@code ExceptionTranslator}).
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CatalogService catalogService;
    private final CategoryDtoMapper categoryDtoMapper;

    @GetMapping
    public ResponseEntity<CategoryListResponse> listCategories(@RequestParam(required = false) Boolean enabled) {
        try {
            return ResponseEntity.ok(categoryDtoMapper.toListResponse(catalogService.listCategories(enabled)));
        } catch (CatalogNotInitializedException e) {
            throw CatalogErrorResponses.notInitialized(e);
        }
    }
}
