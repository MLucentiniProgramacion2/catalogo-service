package ar.edu.um.programacion2.catalogo.sync.domain.ports.out;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Exclusión mutua de la sincronización (DP-09): una sola sincronización a la vez en el servicio.
 * <p>
 * Es un puerto porque "una sola instancia" es un supuesto de despliegue, no una regla del dominio: con varias instancias
 * cambiaría solo el adaptador. La acción se pasa por parámetro para que tomar y liberar queden en un solo lugar: quien
 * llama no puede olvidarse de liberar.
 */
public interface SyncLock {
    /**
     * Espera a que no haya otra sincronización, hasta el máximo configurado, y corre la acción en este mismo hilo. Se
     * libera al terminar, también si la acción lanza una excepción, que sale tal cual.
     * <p>
     * Un hilo que ya la tiene entra directamente: una sincronización puede incluir a otra (CU-01 incluye a CU-03) sin
     * bloquearse a sí misma, y siguen sin correr dos a la vez porque el hilo las ejecuta una después de la otra.
     *
     * @param action lo que se ejecuta con la exclusión tomada; no puede devolver {@code null}.
     * @return el resultado de la acción, o vacío si se superó la espera y la acción no corrió.
     * @throws InterruptedException si interrumpen el hilo mientras espera; la acción no corrió.
     */
    <T> Optional<T> runExclusively(Supplier<T> action) throws InterruptedException;

    /** Si hay una sincronización corriendo ahora (CU-09). No toma la exclusión ni espera. */
    boolean isHeld();
}
