package ar.edu.um.programacion2.catalogo.catalog.application.exception;

/**
 * La copia local todavía no tiene una versión aplicada: nunca se confirmó un snapshot (DP-03, flujo 3a de CU-05, CU-06
 * y CU-07). Es lo que pasa, por ejemplo, si el snapshot del arranque falla: el servicio queda levantado y sin datos.
 * <p>
 * El criterio es la versión y no "cero filas": un catálogo aplicado puede no tener categorías, y eso es una respuesta
 * válida.
 * <p>
 * El adaptador web la traduce a {@code 503} con un {@code code} estable. El mensaje llega al cliente como
 * {@code detail}: no puede llevar datos internos.
 */
public class CatalogNotInitializedException extends RuntimeException {

    public CatalogNotInitializedException() {
        super("El catálogo todavía no está disponible: no se aplicó ninguna versión");
    }
}
