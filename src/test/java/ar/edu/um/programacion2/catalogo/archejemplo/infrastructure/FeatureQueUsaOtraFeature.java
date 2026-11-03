package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;

/** Depende de otra feature ({@code sync}): la detecta {@code HexagonalArchitectureTest.featureNoDependeDe}. */
public class FeatureQueUsaOtraFeature {

    public boolean tieneCopiaLocal(SyncStatus estado) {
        return estado.hasLocalCopy();
    }
}
