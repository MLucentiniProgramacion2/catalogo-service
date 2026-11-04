package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.sync.domain.model.ManualSyncResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;

/**
 * Traduce los resultados no exitosos de CU-08 a errores HTTP, con el mismo mecanismo que {@code CatalogErrorResponses}:
 * un {@link ErrorResponseException} con un {@link ProblemDetailWithCause}, que el {@code ExceptionTranslator} conserva y
 * completa sin depender de esta feature. La app decide por {@code status} y {@code code}, nunca por {@code detail}
 * (contrato §13).
 */
final class SyncErrorResponses {

    /** La sincronización se intentó y falló; {@code errorType} dice por qué. */
    static final String SYNC_FAILED = "SYNC_FAILED";

    /** Otra sincronización seguía en curso al vencer la espera máxima (DP-09). */
    static final String SYNC_IN_PROGRESS = "SYNC_IN_PROGRESS";

    /** El modo pedido todavía no está implementado (modo normal, hasta CU-03). */
    static final String SYNC_MODE_NOT_AVAILABLE = "SYNC_MODE_NOT_AVAILABLE";

    private static final String CODE = "code";

    private SyncErrorResponses() {}

    /**
     * {@code 502}: el pedido era válido, pero la sincronización no se pudo completar. Un {@code 200} haría ver como éxito
     * una falla a cualquier cliente que mire solo el status. Un solo status por {@code code}: es inexacto cuando la que
     * falla es la base local, y {@code errorType} lo aclara. {@code detail} es la descripción ya registrada, que por
     * contrato no lleva datos sensibles.
     */
    static ErrorResponseException failed(ManualSyncResult.Failed failed) {
        ProblemDetailWithCause problem = ProblemDetailWithCauseBuilder.instance()
            .withStatus(HttpStatus.BAD_GATEWAY.value())
            .withDetail(failed.description())
            .withProperty(CODE, SYNC_FAILED)
            .withProperty("errorType", failed.errorType().name())
            .withProperty("localVersionBefore", failed.localVersionBefore())
            .withProperty("localVersionAfter", failed.localVersionAfter())
            .withProperty("attempts", failed.attempts())
            .build();
        return new ErrorResponseException(HttpStatus.BAD_GATEWAY, problem, null);
    }

    /** {@code 409}: conflicto con el estado actual. Se puede volver a pedir cuando termine la que está corriendo. */
    static ErrorResponseException inProgress() {
        ProblemDetailWithCause problem = ProblemDetailWithCauseBuilder.instance()
            .withStatus(HttpStatus.CONFLICT.value())
            .withDetail("No se ejecutó: hay otra sincronización en curso y se superó la espera máxima")
            .withProperty(CODE, SYNC_IN_PROGRESS)
            .build();
        return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    /** {@code 501}: la operación existe en el contrato pero todavía no está implementada. No es un error del cliente. */
    static ErrorResponseException modeNotAvailable(ManualSyncResult.ModeNotAvailable notAvailable) {
        ProblemDetailWithCause problem = ProblemDetailWithCauseBuilder.instance()
            .withStatus(HttpStatus.NOT_IMPLEMENTED.value())
            .withDetail("El modo " + notAvailable.mode() + " todavía no está disponible")
            .withProperty(CODE, SYNC_MODE_NOT_AVAILABLE)
            .build();
        return new ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, problem, null);
    }
}
