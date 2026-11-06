package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogEventResult;
import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogUpdateNotice;

/**
 * CU-03 con el disparador Kafka: incorporar el cambio que anuncia un {@code CatalogUpdated} sin aplicarlo dos veces.
 * <p>
 * Lo que se espera que falle vuelve como {@link CatalogEventResult}. Una excepción significa algo inesperado (por ejemplo,
 * la base caída en una barrera): el adaptador la reintenta de forma acotada y, si se agotan, llama a
 * {@link #reportUnexpectedFailure()}.
 */
public interface HandleCatalogUpdatedUseCase {
    /** Procesa un aviso ya validado. Hay que llamarlo sin una transacción abierta: espera la exclusión mutua. */
    CatalogEventResult handle(CatalogUpdateNotice notice);

    /** Flujo 2a: registra que se descartó un mensaje ilegible o incompleto. */
    CatalogEventResult rejectInvalid();

    /** Registra que un aviso se descartó después de agotar los reintentos. Nunca lanza una excepción. */
    void reportUnexpectedFailure();
}
