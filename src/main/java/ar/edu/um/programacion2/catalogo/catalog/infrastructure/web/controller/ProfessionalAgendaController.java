package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.application.exception.ProfessionalNotFoundException;
import ar.edu.um.programacion2.catalogo.catalog.application.service.CatalogService;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.AgendaResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper.AgendaDtoMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-07: {@code GET /api/service/professionals/{id}/agenda}, para el servicio de turnos.
 * <p>
 * Lo protege la regla de {@code /api/service/**} de {@code SecurityConfiguration} (DP-06, ADR 0007): solo el JWT técnico
 * de turnos. Sin token o con uno inválido, 401; con uno de usuario o de servicio mal armado, 403.
 * <p>
 * Errores: {@code 404} con {@code code: PROFESSIONAL_NOT_FOUND} y {@code 503} con {@code code: CATALOG_NOT_INITIALIZED}
 * (y {@code Retry-After}), armados con {@link CatalogErrorResponses} como en CU-05 y CU-06. Un {@code id} que no es un
 * número da {@code 400} por el {@code handleTypeMismatch} de JHipster, que no repite el valor.
 * <p>
 * {@code X-User-Login} solo se usa para el log ({@link UserLoginForLog}). El {@code Authorization} no es un parámetro: no
 * puede llegar a ningún log de argumentos (el {@code LoggingAspect} de JHipster, que es de dev, además no mira los
 * paquetes de las features).
 */
@RestController
@RequestMapping("/api/service/professionals")
@RequiredArgsConstructor
public class ProfessionalAgendaController {

    private static final Logger LOG = LoggerFactory.getLogger(ProfessionalAgendaController.class);

    static final String USER_LOGIN_HEADER = "X-User-Login";

    private final CatalogService catalogService;
    private final AgendaDtoMapper agendaDtoMapper;

    @GetMapping("/{id}/agenda")
    public ResponseEntity<AgendaResponse> getProfessionalAgenda(
        @PathVariable long id,
        @RequestHeader(name = USER_LOGIN_HEADER, required = false) String userLogin
    ) {
        LOG.info("Agenda vigente del profesional {} pedida por turnos ({})", id, UserLoginForLog.describe(userLogin));
        try {
            return ResponseEntity.ok(agendaDtoMapper.toResponse(catalogService.getProfessionalAgenda(id)));
        } catch (CatalogNotInitializedException e) {
            throw CatalogErrorResponses.notInitialized(e);
        } catch (ProfessionalNotFoundException e) {
            throw CatalogErrorResponses.professionalNotFound(e);
        }
    }
}
