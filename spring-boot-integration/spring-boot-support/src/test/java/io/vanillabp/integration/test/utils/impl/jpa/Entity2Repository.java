package io.vanillabp.integration.test.utils.impl.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface Entity2Repository extends JpaRepository<Entity2, Integer> {
}
