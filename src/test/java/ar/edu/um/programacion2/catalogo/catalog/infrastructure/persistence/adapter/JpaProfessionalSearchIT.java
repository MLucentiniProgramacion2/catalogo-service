package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.JpaProfessionalRepository;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.repository.ProfessionalSpecifications;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceUnitUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La búsqueda de profesionales (CU-05) contra PostgreSQL real, a través del puerto: filtros solos y combinados, nombre,
 * paginación, orden y cantidad de sentencias.
 * <p>
 * La copia se prepara con {@code replaceAll}, como la deja CU-02, y se limpia con SQL. {@code replaceAll} inserta en el
 * orden de las listas: con IDs desordenados, una consulta sin {@code ORDER BY} los devolvería en ese orden (el de las
 * filas en disco), y los tests de orden lo detectan.
 */
@IntegrationTest
class JpaProfessionalSearchIT {

    private static final Instant CREADO = Instant.parse("2026-07-01T12:00:00Z");
    private static final PageQuery PRIMERA = new PageQuery(0, 20);

    @Autowired
    private CatalogRepository catalogRepository;

    @Autowired
    private JpaProfessionalRepository professionalRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @BeforeEach
    @AfterEach
    void vaciarLasTablas() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    // ---- enabled y categoría ----

    @Test
    void sinFiltrosDevuelveSoloLosHabilitadosOrdenadosPorId() {
        guardar(
            List.of(categoria(1, true)),
            List.of(profesional(3, 1, "Carla", "Diaz", true), profesional(1, 1, "Ana", "Perez", true), profesional(2, 1, "Bruno", "Ruiz", false))
        );

        assertThat(ids(buscar(null, null, null))).containsExactly(1L, 3L);
    }

    @Test
    void conEnabledFalseDevuelveSoloLosDeshabilitados() {
        guardar(List.of(categoria(1, true)), List.of(profesional(1, 1, "Ana", "Perez", true), profesional(2, 1, "Bruno", "Ruiz", false)));

        assertThat(ids(buscar(null, null, false))).containsExactly(2L);
        assertThat(ids(buscar(null, null, true))).containsExactly(1L);
    }

    @Test
    void porCategoriaDevuelveSoloLosDeEsaCategoria() {
        guardar(
            List.of(categoria(1, true), categoria(2, true)),
            List.of(profesional(1, 1, "Ana", "Perez", true), profesional(2, 2, "Bruno", "Ruiz", true), profesional(3, 1, "Carla", "Diaz", true))
        );

        assertThat(ids(buscar(1L, null, null))).containsExactly(1L, 3L);
        assertThat(ids(buscar(2L, null, null))).containsExactly(2L);
    }

    @Test
    void unaCategoriaInexistenteDevuelveUnaListaVacia() {
        guardar(List.of(categoria(1, true)), List.of(profesional(1, 1, "Ana", "Perez", true)));

        ResultPage<ProfessionalWithCategory> pagina = buscar(99L, null, null);

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalElements()).isZero();
    }

    @Test
    void unProfesionalHabilitadoDeUnaCategoriaDeshabilitadaApareceConElEstadoDeLaCategoria() {
        guardar(List.of(categoria(1, false)), List.of(profesional(1, 1, "Ana", "Perez", true)));

        ProfessionalWithCategory resultado = buscar(null, null, null).items().getFirst();

        assertThat(resultado.professional().id()).isEqualTo(1);
        assertThat(resultado.category().id()).isEqualTo(1);
        assertThat(resultado.category().name()).isEqualTo("Categoria 1");
        assertThat(resultado.category().enabled()).isFalse();
    }

    // ---- nombre ----

    @Test
    void elNombreSeBuscaEnElNombreEnElApellidoYCruzandoLosDos() {
        guardarNombres(List.of(new String[] { "Ana", "Perez" }, new String[] { "Bruno", "Anaya" }, new String[] { "Carla", "Diaz" }));

        assertThat(ids(buscar(null, "ana", null))).containsExactly(1L, 2L);
        assertThat(ids(buscar(null, "diaz", null))).containsExactly(3L);
        assertThat(ids(buscar(null, "ana pe", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "o an", null))).containsExactly(2L);
    }

    @Test
    void elOrdenDeLasPalabrasImporta() {
        guardarNombres(List.<String[]>of(new String[] { "Ana", "Perez" }));

        assertThat(buscar(null, "perez ana", null).items()).isEmpty();
    }

    @Test
    void elNombreNoDistingueMayusculas() {
        guardarNombres(List.<String[]>of(new String[] { "Ana", "Perez" }));

        assertThat(ids(buscar(null, "ANA PEREZ", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "aNa", null))).containsExactly(1L);
    }

    @Test
    void lasMayusculasConTildeSeComparanConLasMinusculasConTilde() {
        // Depende del locale de la base: con en_US.utf8 (el de la imagen de los tests) lower('Á') es 'á'. Con locale C,
        // lower() solo cambiaría las letras ASCII y este test fallaría.
        guardarNombres(List.of(new String[] { "Ángel", "Núñez" }, new String[] { "angel", "Nunez" }));

        assertThat(ids(buscar(null, "ÁNGEL", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "ñ", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "NÚÑEZ", null))).containsExactly(1L);
    }

    @Test
    void lasTildesNoSeIgnoran() {
        guardarNombres(List.<String[]>of(new String[] { "Ana", "Pérez" }));

        assertThat(buscar(null, "perez", null).items()).isEmpty();
        assertThat(ids(buscar(null, "pérez", null))).containsExactly(1L);
    }

    @Test
    void elPorcentajeSeBuscaComoLetraYNoComoComodin() {
        guardarNombres(List.of(new String[] { "Ana", "50% Off" }, new String[] { "Ana", "500 Off" }));

        assertThat(ids(buscar(null, "0%", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "%", null))).containsExactly(1L);
    }

    @Test
    void elGuionBajoSeBuscaComoLetraYNoComoComodin() {
        guardarNombres(List.of(new String[] { "Ana", "a_b" }, new String[] { "Ana", "axb" }));

        assertThat(ids(buscar(null, "a_b", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "_", null))).containsExactly(1L);
    }

    @Test
    void laBarraInvertidaSeBuscaComoLetra() {
        guardarNombres(List.of(new String[] { "Ana", "a\\b" }, new String[] { "Ana", "ab" }));

        assertThat(ids(buscar(null, "\\", null))).containsExactly(1L);
        assertThat(ids(buscar(null, "a\\b", null))).containsExactly(1L);
    }

    @Test
    void losFiltrosSeCombinan() {
        guardar(
            List.of(categoria(1, true), categoria(2, true)),
            List.of(
                profesional(1, 1, "Ana", "Perez", true),
                profesional(2, 1, "Ana", "Ruiz", false),
                profesional(3, 2, "Ana", "Diaz", true),
                profesional(4, 1, "Bruno", "Perez", true)
            )
        );

        assertThat(ids(buscar(1L, "ana", null))).containsExactly(1L);
        assertThat(ids(buscar(1L, "ana", false))).containsExactly(2L);
        assertThat(ids(buscar(2L, "perez", null))).isEmpty();
    }

    // ---- paginación ----

    @Test
    void paginaPorIdConElTotalDeTodasLasPaginas() {
        guardarHabilitados(5, 3, 1, 4, 2);

        ResultPage<ProfessionalWithCategory> primera = pagina(0, 2);
        ResultPage<ProfessionalWithCategory> segunda = pagina(1, 2);
        ResultPage<ProfessionalWithCategory> ultima = pagina(2, 2);

        assertThat(ids(primera)).containsExactly(1L, 2L);
        assertThat(ids(segunda)).containsExactly(3L, 4L);
        assertThat(ids(ultima)).containsExactly(5L);
        for (ResultPage<ProfessionalWithCategory> p : List.of(primera, segunda, ultima)) {
            assertThat(p.totalElements()).isEqualTo(5);
            assertThat(p.totalPages()).isEqualTo(3);
            assertThat(p.size()).isEqualTo(2);
        }
        assertThat(segunda.number()).isEqualTo(1);
    }

    @Test
    void unaPaginaFueraDeRangoVieneVaciaConElTotalCorrecto() {
        guardarHabilitados(1, 2, 3);

        ResultPage<ProfessionalWithCategory> fuera = pagina(50, 20);

        assertThat(fuera.items()).isEmpty();
        assertThat(fuera.number()).isEqualTo(50);
        assertThat(fuera.totalElements()).isEqualTo(3);
        assertThat(fuera.totalPages()).isEqualTo(1);
    }

    @Test
    void recorrerTodasLasPaginasDevuelveCadaProfesionalUnaSolaVezEnOrden() {
        long[] desordenados = { 17, 4, 23, 1, 9, 30, 12, 2, 28, 15, 7, 21, 3 };
        guardarHabilitados(desordenados);

        List<Long> recorridos = new ArrayList<>();
        for (int numero = 0; numero < 5; numero++) {
            recorridos.addAll(ids(pagina(numero, 3)));
        }

        assertThat(recorridos).containsExactly(LongStream.of(desordenados).sorted().boxed().toArray(Long[]::new));
    }

    // ---- sentencias ----

    @Test
    void unaPaginaLlenaSonDosSentenciasAunqueCadaProfesionalTengaOtraCategoria() {
        guardarConUnaCategoriaCadaUno(25);
        Statistics estadisticas = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        estadisticas.setStatisticsEnabled(true);
        try {
            estadisticas.clear();

            ResultPage<ProfessionalWithCategory> pagina = buscar(null, null, null);

            assertThat(pagina.items()).hasSize(20);
            assertThat(pagina.totalElements()).isEqualTo(25);
            // La de datos (con la categoría por el join del @EntityGraph) y la del count: ninguna por profesional.
            assertThat(estadisticas.getPrepareStatementCount()).isEqualTo(2);
        } finally {
            estadisticas.setStatisticsEnabled(false);
        }
    }

    @Test
    void laConsultaDelRepositorioDejaLaCategoriaDeCadaProfesionalCargada() {
        guardarConUnaCategoriaCadaUno(5);
        PersistenceUnitUtil util = em.getEntityManagerFactory().getPersistenceUnitUtil();

        List<Boolean> cargadas = transactionTemplate.execute(tx -> {
            Page<ProfessionalEntity> pagina = professionalRepository.findAll(
                ProfessionalSpecifications.hasEnabled(true),
                PageRequest.of(0, 20, Sort.by("id"))
            );
            // Se pregunta antes de tocar la categoría: leer su nombre la inicializaría con otra consulta.
            return pagina.getContent().stream().map(p -> util.isLoaded(p, "category") && util.isLoaded(p.getCategory())).toList();
        });

        assertThat(cargadas).hasSize(5).containsOnly(true);
    }

    @Test
    void buscarSinTransaccionFalla() {
        assertThatThrownBy(() -> catalogRepository.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), PRIMERA)).isInstanceOf(
            IllegalTransactionStateException.class
        );
    }

    // ---- ayudantes ----

    private ResultPage<ProfessionalWithCategory> buscar(Long categoryId, String name, Boolean enabled) {
        return transactionTemplate.execute(tx ->
            catalogRepository.searchProfessionals(ProfessionalSearchCriteria.of(categoryId, name, enabled), PRIMERA)
        );
    }

    private ResultPage<ProfessionalWithCategory> pagina(int numero, int tamano) {
        return transactionTemplate.execute(tx ->
            catalogRepository.searchProfessionals(ProfessionalSearchCriteria.of(null, null, null), new PageQuery(numero, tamano))
        );
    }

    private static List<Long> ids(ResultPage<ProfessionalWithCategory> pagina) {
        return pagina.items().stream().map(r -> r.professional().id()).toList();
    }

    private void guardar(List<ProfessionalCategory> categorias, List<Professional> profesionales) {
        transactionTemplate.executeWithoutResult(tx -> catalogRepository.replaceAll(categorias, profesionales, List.of()));
    }

    /** Un profesional habilitado por nombre y apellido, con IDs 1, 2, 3... en la categoría 1. */
    private void guardarNombres(List<String[]> nombres) {
        List<Professional> profesionales = new ArrayList<>();
        for (int i = 0; i < nombres.size(); i++) {
            profesionales.add(profesional(i + 1, 1, nombres.get(i)[0], nombres.get(i)[1], true));
        }
        guardar(List.of(categoria(1, true)), profesionales);
    }

    /** Profesionales habilitados de la categoría 1, insertados en el orden recibido. */
    private void guardarHabilitados(long... ids) {
        guardar(List.of(categoria(1, true)), LongStream.of(ids).mapToObj(id -> profesional(id, 1, "Nombre", "Apellido " + id, true)).toList());
    }

    private void guardarConUnaCategoriaCadaUno(int cantidad) {
        guardar(
            LongStream.rangeClosed(1, cantidad).mapToObj(id -> categoria(id, true)).toList(),
            LongStream.rangeClosed(1, cantidad).mapToObj(id -> profesional(id, id, "Nombre", "Apellido " + id, true)).toList()
        );
    }

    private static ProfessionalCategory categoria(long id, boolean enabled) {
        return new ProfessionalCategory(id, "Categoria " + id, null, enabled, CREADO, CREADO);
    }

    private static Professional profesional(long id, long categoryId, String firstName, String lastName, boolean enabled) {
        return new Professional(id, categoryId, firstName, lastName, enabled, CREADO, CREADO);
    }
}
