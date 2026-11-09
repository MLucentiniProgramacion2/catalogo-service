package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.web;

import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.client.ClienteRestEjemplo;

/** Viola la regla: un controller que llama al cliente REST de la cátedra en vez de llamar al service. */
public class WebQueUsaClient {

    public String responder(ClienteRestEjemplo cliente) {
        return cliente.pedir("/api/synchronization/snapshot");
    }
}
