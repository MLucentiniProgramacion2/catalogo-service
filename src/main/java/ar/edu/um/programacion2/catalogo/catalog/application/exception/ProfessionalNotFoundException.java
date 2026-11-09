package ar.edu.um.programacion2.catalogo.catalog.application.exception;

/**
 * El profesional pedido no está en la copia local (CU-07, flujo 4a). El adaptador web la traduce a {@code 404} con un
 * {@code code} estable. El mensaje llega al cliente como {@code detail}: es un texto fijo.
 */
public class ProfessionalNotFoundException extends RuntimeException {

    public ProfessionalNotFoundException() {
        super("El profesional no está en el catálogo");
    }
}
