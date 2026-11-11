package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto.SyncStatusResponse;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.mapper.SyncDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-09: {@code GET /api/sync/status}.
 * <p>
 * Lo protege la regla de {@code /api/**} de {@code SecurityConfiguration}: sin token o con uno inválido, 401; con el
 * token técnico de servicio o sin {@code ROLE_USER}/{@code ROLE_ADMIN}, 403 (DP-05 y DP-06).
 * <p>
 * A diferencia de CU-05 y CU-06, nunca responde 503: con la base vacía informa "sin datos" y el error del intento
 * inicial, si lo hubo. Es justamente la consulta que explica por qué las otras dan 503.
 */
@RestController
@RequestMapping("/api/sync/status")
@RequiredArgsConstructor
public class SyncStatusController {

    private final SyncService syncService;
    private final SyncDtoMapper syncDtoMapper;

    @GetMapping
    public ResponseEntity<SyncStatusResponse> getSyncStatus() {
        return ResponseEntity.ok(syncDtoMapper.toResponse(syncService.getSyncOverview()));
    }
}
