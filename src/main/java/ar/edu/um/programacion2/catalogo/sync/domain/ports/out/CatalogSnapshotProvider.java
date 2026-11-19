package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import ar.edu.um.programacion2.catalogo.sync.domain.model.CatalogSnapshot;

/**
 * Fuente del snapshot completo del catálogo (CU-02, pasos 1 y 2). El dominio no sabe que detrás hay una API REST: lo
 * implementa un adaptador de {@code infrastructure.client}.
 * <p>
 * Hace un solo intento. Los reintentos (DP-08) son del caso de uso, que decide según el tipo de excepción.
 */
public interface CatalogSnapshotProvider {
    /**
     * Obtiene el snapshot vigente.
     *
     * @return el snapshot con sus tres colecciones, ya validado en formato.
     * @throws SnapshotSourceUnavailableException si la fuente no respondió a tiempo, no se pudo conectar o informó
     *         una falla transitoria. Se puede reintentar.
     * @throws SnapshotAccessDeniedException si la fuente rechazó la credencial. Reintentar no sirve.
     * @throws InvalidSnapshotException si la respuesta no respeta el contrato. Reintentar no sirve.
     */
    CatalogSnapshot fetchSnapshot();
}
