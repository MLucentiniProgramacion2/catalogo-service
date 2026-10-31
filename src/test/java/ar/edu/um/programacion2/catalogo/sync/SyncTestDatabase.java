package ar.edu.um.programacion2.catalogo.sync;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Limpieza y lectura directa de las tablas de la copia local y de {@code sync_status} para los tests de integración de
 * la sincronización. Con SQL y no con los adaptadores: la preparación y la verificación no pueden depender del código
 * que se prueba.
 */
public final class SyncTestDatabase {

    private SyncTestDatabase() {}

    /** Deja la base como recién instalada: copia vacía y la fila de {@code sync_status} sin datos. */
    public static void reset(TransactionTemplate transactionTemplate, EntityManager em) {
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("delete from weekly_schedule").executeUpdate();
            em.createNativeQuery("delete from professional").executeUpdate();
            em.createNativeQuery("delete from professional_category").executeUpdate();
            em
                .createNativeQuery(
                    """
                    update sync_status set
                        local_version = null, last_success_at = null, last_success_path = null, last_success_trigger = null,
                        remote_version = null, remote_version_checked_at = null,
                        last_error_at = null, last_error_type = null, last_error_description = null
                    where id = 1
                    """
                )
                .executeUpdate();
        });
    }

    public static List<Long> categoryIds(TransactionTemplate transactionTemplate, EntityManager em) {
        return ids(transactionTemplate, em, "select id from professional_category order by id");
    }

    public static List<Long> professionalIds(TransactionTemplate transactionTemplate, EntityManager em) {
        return ids(transactionTemplate, em, "select id from professional order by id");
    }

    public static List<Long> scheduleIds(TransactionTemplate transactionTemplate, EntityManager em) {
        return ids(transactionTemplate, em, "select id from weekly_schedule order by id");
    }

    private static List<Long> ids(TransactionTemplate transactionTemplate, EntityManager em, String sql) {
        return transactionTemplate.execute(tx ->
            em.createNativeQuery(sql, Long.class).getResultList().stream().map(Long.class::cast).toList()
        );
    }
}
