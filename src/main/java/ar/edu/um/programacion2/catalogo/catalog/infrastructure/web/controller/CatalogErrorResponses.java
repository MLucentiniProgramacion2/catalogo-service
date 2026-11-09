package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;

/**
 * Traduce las excepciones de aplicación del catálogo a errores HTTP. Los controllers de CU-05, CU-06 y CU-07 las atrapan
 * y lanzan lo que devuelve acá, como el controller del repo del profesor con {@code ResponseStatusException}.
 * <p>
 * Se usa {@link ErrorResponseException}, la clase padre de {@code ResponseStatusException}, porque permite elegir el
 * cuerpo: un {@link ProblemDetailWithCause} con el {@code code}. El {@code ExceptionTranslator} de JHipster reconoce ese
 * cuerpo, lo conserva y le agrega {@code type}, {@code title}, {@code message} y {@code path}, igual que a los demás
 * errores (contrato §13). Así el formato se arma en un solo lugar y JHipster no depende de las features. Con
 * {@code ResponseStatusException} el cuerpo es un {@code ProblemDetail} común: JHipster lo reemplaza y se pierde el
 * {@code code}.
 */
public final class CatalogErrorResponses {

    /** {@code code} del 503 con la base vacía: la app decide por status y code, nunca por {@code detail} (contrato §13). */
    public static final String CATALOG_NOT_INITIALIZED = "CATALOG_NOT_INITIALIZED";

    private static final String CODE = "code";

    private CatalogErrorResponses() {}

    /**
     * {@code 503}: la copia local todavía no tiene versión (DP-03). Lo usan los tres controllers, así que el error sale
     * igual en CU-05, CU-06 y CU-07.
     * <p>
     * Con {@code Retry-After} en segundos si la excepción trae un plazo: el intervalo de la comprobación periódica (DP-04),
     * que es la que vuelve a intentar el snapshot. Sin plazo (la periódica apagada) no se manda: un valor fijo prometería
     * algo que no se cumple. El header sobrevive al {@code ExceptionTranslator} porque {@code ResponseEntityExceptionHandler}
     * responde una {@link ErrorResponseException} con sus propios headers.
     */
    public static ErrorResponseException notInitialized(CatalogNotInitializedException e) {
        ProblemDetailWithCause problem = ProblemDetailWithCauseBuilder.instance()
            .withStatus(HttpStatus.SERVICE_UNAVAILABLE.value())
            .withDetail(e.getMessage())
            .withProperty(CODE, CATALOG_NOT_INITIALIZED)
            .build();
        ErrorResponseException response = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE, problem, e);
        e.getRetryAfter().ifPresent(retryAfter -> response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds(retryAfter))));
        return response;
    }

    /** {@code Retry-After} va en segundos enteros (RFC 9110): se redondea para arriba, para no prometer antes de tiempo. */
    static long seconds(Duration retryAfter) {
        return Math.ceilDiv(retryAfter.toMillis(), 1000);
    }
}
