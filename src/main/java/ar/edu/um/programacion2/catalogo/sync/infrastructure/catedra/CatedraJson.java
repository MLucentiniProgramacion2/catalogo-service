package ar.edu.um.programacion2.catalogo.sync.infrastructure.catedra;

import tools.jackson.databind.json.JsonMapper;

/**
 * El {@link JsonMapper} con el que se leen los datos de la cátedra (snapshot REST y Redis).
 * <p>
 * Es propio y no el de Spring: el resultado no depende de la configuración global de Jackson ({@code spring.jackson.*}).
 * Uno solo para los dos adaptadores, para que las reglas no se separen.
 */
public final class CatedraJson {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private CatedraJson() {}
}
