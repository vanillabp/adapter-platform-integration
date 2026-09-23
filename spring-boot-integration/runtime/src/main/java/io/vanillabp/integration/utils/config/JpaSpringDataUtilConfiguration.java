package io.vanillabp.integration.utils.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.data.jpa.repository.JpaRepository;

import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.JpaSpringDataUtil;
import jakarta.persistence.EntityManagerFactory;

/**
 * Auto-configuration of the JPA-based {@link SpringDataUtil}. Only active if
 * <ul>
 *   <li>Spring Data JPA is on the classpath,</li>
 *   <li>exactly one (primary) {@link EntityManagerFactory} is available (so a
 *       Mongo-only application having spring-data-jpa on the classpath is skipped
 *       cleanly) and</li>
 *   <li>the application did not define its own {@link SpringDataUtil} bean.</li>
 * </ul>
 * If both JPA and MongoDB are configured, JPA wins deterministically (see
 * {@link MongoDbSpringDataUtilAutoConfiguration}).
 */
@AutoConfiguration(
    afterName = {
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration", "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
@ConditionalOnMissingBean(SpringDataUtil.class) // only in case of application did not define it's own
@ConditionalOnClass(JpaRepository.class)
@ConditionalOnSingleCandidate(EntityManagerFactory.class)
public class JpaSpringDataUtilConfiguration {

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public JpaSpringDataUtilConfiguration() {
  }

  /**
   * What VanillaBP loads and saves a workflow aggregate through where the aggregate is a
   * JPA entity. It is what makes a plain Spring Data repository enough: an application
   * which has one for its aggregate needs no
   * {@link io.vanillabp.integration.spi.AggregatePersistenceAware} bean of its own.
   *
   * @param applicationContext Where the aggregates' repositories are looked up - once per
   *          aggregate type, and cached afterwards
   * @param jpaContext Answers which entity manager manages a given type, so an application
   *          with more than one persistence unit asks the right one
   * @return The JPA implementation, which the application may replace by defining a
   *         {@link SpringDataUtil} bean itself
   */
  @Bean
  public SpringDataUtil jpaSpringDataUtil(
      final ApplicationContext applicationContext,
      final JpaContext jpaContext) {

    return new JpaSpringDataUtil(
        applicationContext, jpaContext);

  }

}
