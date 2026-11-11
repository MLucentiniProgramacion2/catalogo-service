package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.repository;

import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaSyncStatusRepository extends JpaRepository<SyncStatusEntity, Short> {}
