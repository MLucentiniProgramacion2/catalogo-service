package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

/**
 * CU-01: dejar la copia local utilizable y al día al arrancar. Corre CU-03 con el disparador "arranque": sin versión local
 * hace un snapshot (CU-02); con versión, aplica lo que falte según la versión publicada en Redis.
 */
public interface InitializeCatalogUseCase {
    void initializeCatalog();
}
