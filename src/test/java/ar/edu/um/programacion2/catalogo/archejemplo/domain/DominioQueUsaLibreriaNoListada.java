package ar.edu.um.programacion2.catalogo.archejemplo.domain;

import org.apache.commons.lang3.StringUtils;

/** No usa ningún paquete de la lista negra: solo la lista blanca lo detecta. */
public class DominioQueUsaLibreriaNoListada {

    public boolean nombreValido(String nombre) {
        return StringUtils.isNotBlank(nombre);
    }
}
