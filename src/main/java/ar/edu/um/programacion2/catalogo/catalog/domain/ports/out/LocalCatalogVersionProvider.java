package ar.edu.um.programacion2.catalogo.catalog.domain.ports.out;

import java.util.OptionalLong;

/**
 * Versión del catálogo que tiene aplicada la copia local: la que informan las consultas sobre la copia (CU-05, CU-06 y
 * CU-07) junto con los datos.
 * <p>
 * La versión la guarda la sincronización, pero {@code catalog} no depende de {@code sync} (el ciclo lo prohíbe ArchUnit,
 * {@code catalogNoDependeDeSync}): el puerto es de {@code catalog} y lo implementa un adaptador de {@code sync}.
 * <p>
 * Se suma a la transacción de quien llama y falla si no hay una abierta: la versión y los datos se tienen que leer en la
 * misma transacción para que correspondan entre sí.
 */
public interface LocalCatalogVersionProvider {
    /**
     * Versión aplicada, o vacío si nunca se confirmó un snapshot: la base local está vacía (DP-03).
     */
    OptionalLong findAppliedVersion();
}
