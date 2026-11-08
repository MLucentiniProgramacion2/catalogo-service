package ar.edu.um.programacion2.catalogo.catalog.domain.ports.out;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Copia local del catálogo: la reemplaza CU-02 (paso 5), la actualiza CU-04 (paso 6) y la consultan CU-05, CU-06 y
 * CU-07. El dominio no sabe que
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
     * Crea o actualiza cada entidad con el estado recibido, sin tocar las demás (CU-04, paso 6): las de una versión
     * incremental. Lo deshabilitado se guarda igual ({@code enabled = false} es la baja lógica). Guardar dos veces el
     * mismo estado deja el mismo resultado.
     * <p>
     * Se guardan en orden (categorías, profesionales, horarios) y se suma a la transacción de quien llama, que falla si
     * no hay una abierta: las entidades y la versión aplicada se confirman o se deshacen juntas.
     * <p>
     * Supone referencias que existen, en la copia o en estas mismas listas: lo verifica el caso de uso antes, con
     * {@link #findExistingCategoryIds} y {@link #findExistingProfessionalIds} (CU-04, flujo 4a). Si igual llega una rota,
     * la base la rechaza. Cualquier falla sale como excepción no chequeada y deja la transacción marcada para deshacerse.
     */
    void saveAll(List<ProfessionalCategory> categories, List<Professional> professionals, List<WeeklySchedule> weeklySchedules);

    /**
     * Cuáles de esas categorías existen en la copia local, deshabilitadas incluidas. Se suma a la transacción de quien
     * llama: la verificación y el guardado tienen que ver la misma copia.
     */
    Set<Long> findExistingCategoryIds(Collection<Long> ids);

    /** Cuáles de esos profesionales existen en la copia local, deshabilitados incluidos. Igual que {@link #findExistingCategoryIds}. */
    Set<Long> findExistingProfessionalIds(Collection<Long> ids);

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

    /**
     * Profesionales de la copia local que cumplen los filtros, con su categoría, ordenados por ID y paginados (CU-05).
     * Filtra, ordena y pagina la base, no la memoria.
     * <p>
     * Un filtro ausente ({@code null}) no restringe; {@code enabled} siempre filtra. El nombre se busca como texto: los
     * comodines del {@code LIKE} ({@code %} y {@code _}) que escriba el usuario cuentan como letras. La disponibilidad sigue
     * las reglas de {@code AvailabilityFilter}, y un profesional aparece una sola vez aunque tenga varios horarios que la
     * cumplan. Por ID por la misma
     * razón que {@link #findCategories}: es único, así que el orden es total y estable entre páginas.
     * <p>
     * La página y el total salen de la misma transacción. Se suma a la de quien llama y falla si no hay una abierta: la
     * versión local se tiene que leer en la misma.
     */
    ResultPage<ProfessionalWithCategory> searchProfessionals(ProfessionalSearchCriteria criteria, PageQuery pageQuery);
}
