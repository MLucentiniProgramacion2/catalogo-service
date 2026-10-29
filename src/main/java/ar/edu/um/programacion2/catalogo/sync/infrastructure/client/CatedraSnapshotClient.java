package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.CatalogSnapshotProvider;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.InvalidSnapshotException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotAccessDeniedException;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SnapshotSourceUnavailableException;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.SnapshotResponse.ProblemResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adaptador de salida: obtiene el snapshot con {@code GET /api/synchronization/snapshot} (contrato §7).
 * <p>
 * Hace un solo intento; los reintentos son del caso de uso (DP-08). La falla se clasifica por status HTTP, nunca por
 * el texto de {@code detail} (contrato §13):
 * <ul>
 * <li>timeout, error de conexión, 408, 429 o 5xx → {@link SnapshotSourceUnavailableException} (transitoria);</li>
 * <li>401 o 403 → {@link SnapshotAccessDeniedException};</li>
 * <li>cualquier otro status o un cuerpo que no respeta el contrato → {@link InvalidSnapshotException}.</li>
 * </ul>
 * El cuerpo se lee completo dentro de {@code exchange}: así un corte de red a mitad del cuerpo llega como
 * {@link ResourceAccessException} (no disponible) y nunca se confunde con un JSON mal formado (inválida).
 */
@Component
@RequiredArgsConstructor
public class CatedraSnapshotClient implements CatalogSnapshotProvider {

    static final String SNAPSHOT_PATH = "/api/synchronization/snapshot";

    private static final Logger LOG = LoggerFactory.getLogger(CatedraSnapshotClient.class);

    /** Lo que se lee del cuerpo de un error para buscar el {@code code}: alcanza para un problem+json. */
    private static final int MAX_ERROR_BODY_BYTES = 8 * 1024;

    /** Mapper propio: el resultado no depende de la configuración global de Jackson ({@code spring.jackson.*}). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient catedraRestClient;

    @Override
    public CatalogSnapshot fetchSnapshot() {
        byte[] body;
        try {
            body = catedraRestClient.get().uri(SNAPSHOT_PATH).exchange((request, response) -> readSnapshotBody(response));
        } catch (ResourceAccessException e) {
            // La causa directa es la IOException del cliente (ConnectException, HttpTimeoutException...). El mensaje
            // de ResourceAccessException no se copia porque lleva la URL.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SnapshotSourceUnavailableException(
                "La cátedra no respondió al pedir el snapshot (" + cause.getClass().getSimpleName() + ")",
                e
            );
        }
        CatalogSnapshot snapshot = CatedraSnapshotMapper.toDomain(parse(body));
        LOG.debug(
            "Snapshot {} de la cátedra: {} categorías, {} profesionales, {} horarios",
            snapshot.snapshotVersion(),
            snapshot.professionalCategories().size(),
            snapshot.professionals().size(),
            snapshot.weeklySchedules().size()
        );
        return snapshot;
    }

    private static byte[] readSnapshotBody(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status == 200) {
            return response.getBody().readAllBytes();
        }
        String description = "La cátedra respondió " + status + " " + errorCode(response) + " al pedir el snapshot";
        if (status == 401 || status == 403) {
            throw new SnapshotAccessDeniedException(description);
        }
        if (status == 408 || status == 429 || response.getStatusCode().is5xxServerError()) {
            throw new SnapshotSourceUnavailableException(description);
        }
        throw new InvalidSnapshotException(description + " (status inesperado)");
    }

    /** El {@code code} del problem+json, si viene. Los 401 y 403 de Spring Security pueden no traerlo (§13). */
    private static String errorCode(ClientHttpResponse response) {
        try {
            byte[] body = response.getBody().readNBytes(MAX_ERROR_BODY_BYTES);
            ProblemResponse problem = JSON.readValue(body, ProblemResponse.class);
            if (problem != null && problem.code() != null) {
                return "(code=" + problem.code() + ")";
            }
        } catch (IOException | JacksonException e) {
            // Sin cuerpo legible no hay code: el status alcanza para clasificar la falla.
        }
        return "(sin code)";
    }

    private static SnapshotResponse parse(byte[] body) {
        try {
            SnapshotResponse response = JSON.readValue(body, SnapshotResponse.class);
            if (response == null) {
                throw new InvalidSnapshotException("Snapshot inválido: el cuerpo es null");
            }
            return response;
        } catch (JacksonException e) {
            throw new InvalidSnapshotException("Snapshot inválido: el cuerpo no es el JSON esperado (" + e.getOriginalMessage() + ")", e);
        }
    }
}
