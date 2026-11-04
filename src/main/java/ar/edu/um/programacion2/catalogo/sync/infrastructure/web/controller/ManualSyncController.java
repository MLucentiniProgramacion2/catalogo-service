package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.sync.application.service.SyncService;
import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncMode;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto.ManualSyncResponse;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.web.mapper.SyncDtoMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST de CU-08: {@code POST /api/admin/sync?mode={NORMAL|SNAPSHOT}}.
 * <p>
 * Lo protege la regla de {@code /api/admin/**} de {@code SecurityConfiguration}: sin token o con uno inválido, 401; sin
 * {@code ROLE_ADMIN}, o con el token técnico de servicio, 403 (DP-05 y DP-06).
 * <p>
 * Es sincrónico: CU-08 "responde con el resultado", así que el pedido dura lo que la sincronización, y en el peor caso
 * unos 11 min (6 de espera y 5 de ejecución). Si el cliente corta, la sincronización sigue y el resultado queda en CU-09.
 * <p>
 * {@code mode} es un único parámetro, validado como el de CU-06: un valor que no es del enum, incluidas las minúsculas,
 * da 400 por {@code ExceptionTranslator.handleTypeMismatch}, sin repetir el valor recibido. Sin él, o vacío, es
 * {@code NORMAL} (DP-10).
 */
@RestController
@RequestMapping("/api/admin/sync")
@RequiredArgsConstructor
public class ManualSyncController {

    private final SyncService syncService;
    private final SyncDtoMapper syncDtoMapper;

    @PostMapping
    public ResponseEntity<ManualSyncResponse> triggerManualSync(@RequestParam(defaultValue = "NORMAL") SyncMode mode) {
        return switch (syncService.triggerManualSync(mode)) {
            case ManualSyncResult.Applied applied -> ResponseEntity.ok(syncDtoMapper.toResponse(mode, applied));
            case ManualSyncResult.Failed failed -> throw SyncErrorResponses.failed(failed);
            case ManualSyncResult.OtherSyncInProgress inProgress -> throw SyncErrorResponses.inProgress();
            case ManualSyncResult.ModeNotAvailable notAvailable -> throw SyncErrorResponses.modeNotAvailable(notAvailable);
        };
    }
}
