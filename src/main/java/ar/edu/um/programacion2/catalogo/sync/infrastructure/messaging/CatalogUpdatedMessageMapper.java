package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el texto de un mensaje en un {@link CatalogUpdateNotice}, o en vacío si es ilegible o le falta {@code eventId}
 * o {@code newVersion} (CU-03, flujo 2a). Es lo único que valida: no se inventan reglas que el contrato no garantiza.
 * <p>
 * Mapper propio, como en {@code CatedraSnapshotClient}: el resultado no depende de {@code spring.jackson.*}.
 * {@code ACCEPT_FLOAT_AS_INT} va apagado porque Jackson truncaría {@code 7.5} a {@code 7} en silencio: sería un aviso de
 * una versión que nadie anunció.
 */
final class CatalogUpdatedMessageMapper {

    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    private CatalogUpdatedMessageMapper() {}

    /** @param payload el valor del mensaje; {@code null} si es un tombstone. */
    static Optional<CatalogUpdateNotice> toNotice(String payload) {
        if (payload == null) {
            return Optional.empty();
        }
        CatalogUpdatedMessage message;
        try {
            message = JSON.readValue(payload, CatalogUpdatedMessage.class);
        } catch (JacksonException e) {
            // Sin la excepción: su mensaje puede traer parte del contenido y nombres de clases.
            return Optional.empty();
        }
        // El JSON "null" es legible, pero se lee como null.
        if (message == null || message.eventId() == null || message.eventId().isBlank() || message.newVersion() == null) {
            return Optional.empty();
        }
        return Optional.of(new CatalogUpdateNotice(message.eventId(), message.newVersion()));
    }
}
