package org.hl7.davinci.api.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
 * are not picked up by the default context. By default reuses HAPI's primary DataSource;
 * setting {@code api.datasource.url} moves the crawler tables to their own database
 * (SQLite or Postgres) so bulk crawl writes stop churning HAPI's H2 file.
 */
@Configuration
@EnableJpaRepositories(
		basePackages = "org.hl7.davinci.api.repository",
		entityManagerFactoryRef = "crawlerEntityManagerFactory",
		transactionManagerRef = "crawlerTransactionManager")
public class CrawlerPersistenceConfig {

	@Bean
	public LocalContainerEntityManagerFactoryBean crawlerEntityManagerFactory(
			DataSource dataSource, ApiProperties apiProperties) {
		LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
		emf.setDataSource(crawlerDataSource(dataSource, apiProperties.getDatasource()));
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
		// The crawler runs a small fixed set of query shapes; the default plan cache (2048) is heap waste.
		properties.put("hibernate.query.plan_cache_max_size", "256");
		properties.put("hibernate.query.plan_parameter_metadata_max_size", "32");
		emf.setJpaPropertyMap(properties);

		return emf;
	}

	public static DataSource crawlerDataSource(DataSource shared, ApiProperties.Datasource config) {
		if (config.getUrl() == null || config.getUrl().isBlank()) {
			return shared;
		}
		HikariConfig hikari = new HikariConfig();
		hikari.setPoolName("crawler-db");
		hikari.setJdbcUrl(config.getUrl());
		if (config.getUsername() != null) {
			hikari.setUsername(config.getUsername());
		}
		if (config.getPassword() != null) {
			hikari.setPassword(config.getPassword());
		}
		if (config.getUrl().startsWith("jdbc:sqlite:")) {
			hikari.addDataSourceProperty("journal_mode", "WAL");
			hikari.addDataSourceProperty("busy_timeout", "30000");
			hikari.addDataSourceProperty("synchronous", "NORMAL");
		}
		return new HikariDataSource(hikari);
	}

	@Bean
	public PlatformTransactionManager crawlerTransactionManager(
			@Qualifier("crawlerEntityManagerFactory") EntityManagerFactory crawlerEntityManagerFactory) {
		return new JpaTransactionManager(crawlerEntityManagerFactory);
	}
}
