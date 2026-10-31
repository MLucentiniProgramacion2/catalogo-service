package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

/**
 * CU-01: dejar la copia local utilizable al arrancar. Si no hay versión local, la construye con un snapshot (CU-02).
 * <p>
 * Con versión local, CU-01 tiene que comparar contra la versión publicada (CU-03), que todavía no existe: por ahora solo
 * lo informa.
 */
public interface InitializeCatalogUseCase {
    void initializeCatalog();
}
