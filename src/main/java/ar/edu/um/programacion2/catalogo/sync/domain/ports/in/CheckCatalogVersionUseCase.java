package ar.edu.um.programacion2.catalogo.sync.domain.ports.in;

/**
 * CU-03 con el disparador "comprobación periódica" (DP-04): comparar la versión local con la publicada en Redis y
 * aplicar lo que falte. Cubre un aviso de Kafka perdido sin esperar al próximo aviso ni a un reinicio.
 * <p>
 * No espera la exclusión mutua: si hay otra sincronización en curso o esperando, esta pasada se saltea.
 */
public interface CheckCatalogVersionUseCase {
    void checkCatalogVersion();
}
