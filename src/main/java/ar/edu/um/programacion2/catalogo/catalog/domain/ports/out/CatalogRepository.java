package ar.edu.um.programacion2.catalogo.catalog.domain.ports.out;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.util.List;

/**
 * Copia local del catálogo: la reemplaza CU-02 (paso 5) y la consultan CU-05, CU-06 y CU-07. El dominio no sabe que
 * detrás hay JPA: lo implementa un adaptador de {@code infrastructure.persistence}.
 * <p>
 * Recibe las tres colecciones sueltas y no un {@code CatalogSnapshot}: el snapshot es de {@code sync}, y {@code catalog}
 * no depende de {@code sync}.
 */
public interface CatalogRepository {
    /**
     * Reemplaza la copia local por las tres colecciones: lo que no viene deja de existir localmente y lo deshabilitado
     * se guarda igual ({@code enabled = false} es la baja lógica de la cátedra).
     * <p>
     * Se suma a la transacción de quien llama y falla si no hay una abierta: el reemplazo y la versión aplicada se
     * confirman o se deshacen juntos. Hasta que esa transacción se confirma, las demás siguen viendo la copia anterior
     * (DP-03).
     * <p>
     * Supone listas coherentes: que cada profesional apunte a una categoría de la lista y cada horario a un profesional
     * de la lista lo verifica el caso de uso antes de llamar (CU-02, paso 3). Si igual llega una referencia rota, la
     * base la rechaza. Cualquier falla sale como excepción no chequeada y deja la transacción marcada para deshacerse.
     */
    void replaceAll(List<ProfessionalCategory> categories, List<Professional> professionals, List<WeeklySchedule> weeklySchedules);

    /**
     * Categorías de la copia local, ordenadas por ID (CU-06). Con {@code enabled} nulo vienen todas, incluidas las
     * deshabilitadas; con un valor, solo las de ese estado. El filtro y el orden los resuelve la base.
     * <p>
     * Por ID y no por nombre: el ID lo fija la cátedra y no cambia, y el orden por texto depende de la collation de la
     * base (tildes y mayúsculas). Si hace falta, la app ordena por nombre para mostrar.
     * <p>
     * Se suma a la transacción de quien llama y falla si no hay una abierta: quien informa la versión local la tiene que
     * leer en la misma transacción.
     */
    List<ProfessionalCategory> findCategories(Boolean enabled);
}
