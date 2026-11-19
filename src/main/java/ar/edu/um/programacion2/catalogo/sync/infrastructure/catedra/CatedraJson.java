package ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * El {@link JsonMapper} con el que se leen los datos de la cátedra (snapshot REST y Redis).
 * <p>
 * Es propio y no el de Spring: el resultado no depende de la configuración global de Jackson ({@code spring.jackson.*}).
 * Uno solo para los dos adaptadores, para que las reglas no se separen.
 * <p>
 * {@code ACCEPT_FLOAT_AS_INT} va apagado, como en el mensaje de Kafka (ADR 0005): prendido, Jackson trunca {@code 7.5} a
 * {@code 7} en silencio, y un ID o una versión con decimales se aplicaría como si fuera otro. Los IDs, las versiones y
 * {@code slotDurationMinutes} son enteros en el contrato.
 */
public final class CatedraJson {

    public static final JsonMapper MAPPER = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    private CatedraJson() {}
}
