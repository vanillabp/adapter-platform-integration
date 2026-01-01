package io.vanillabp.integration.utils.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

import io.vanillabp.integration.utils.SpringDataUtil;

@Configuration
@ConditionalOnMissingBean(SpringDataUtil.class) // only in case of application did not define it's own
@ConditionalOnClass(JpaRepository.class)
public class JpaSpringDataUtilConfiguration {

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private LocalContainerEntityManagerFactoryBean containerEntityManagerFactoryBean;

  @Autowired
  private JpaContext jpaContext;

  @Bean
  public SpringDataUtil jpaSpringDataUtil() {

    return new JpaSpringDataUtil(
        applicationContext, jpaContext, containerEntityManagerFactoryBean);

  }

}
