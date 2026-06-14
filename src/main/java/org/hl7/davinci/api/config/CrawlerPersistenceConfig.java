package org.hl7.davinci.api.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Isolated persistence unit for the crawler entities. Required because HAPI scans only its
 * own entity packages and scopes its own repository discovery, so our entities/repositories
 * are not picked up by the default context. Reuses HAPI's primary DataSource.
 */
@Configuration
@EnableJpaRepositories(
		basePackages = "org.hl7.davinci.api.repository",
		entityManagerFactoryRef = "crawlerEntityManagerFactory",
		transactionManagerRef = "crawlerTransactionManager")
public class CrawlerPersistenceConfig {

	@Bean
	public LocalContainerEntityManagerFactoryBean crawlerEntityManagerFactory(DataSource dataSource) {
		LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
		emf.setDataSource(dataSource);
		emf.setPackagesToScan("org.hl7.davinci.api.entity");
		emf.setPersistenceUnitName("CRAWLER_PU");

		HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
		emf.setJpaVendorAdapter(vendorAdapter);

		Map<String, Object> properties = new HashMap<>();
		// Dialect auto-detected from the shared connection.
		properties.put("hibernate.hbm2ddl.auto", "update");
		properties.put("hibernate.format_sql", "false");
		properties.put("hibernate.show_sql", "false");
		// Batched/ordered DML for the large per-server upserts.
		properties.put("hibernate.jdbc.batch_size", "500");
		properties.put("hibernate.order_inserts", "true");
		properties.put("hibernate.order_updates", "true");
		emf.setJpaPropertyMap(properties);

		return emf;
	}

	@Bean
	public PlatformTransactionManager crawlerTransactionManager(
			@Qualifier("crawlerEntityManagerFactory") EntityManagerFactory crawlerEntityManagerFactory) {
		return new JpaTransactionManager(crawlerEntityManagerFactory);
	}
}
