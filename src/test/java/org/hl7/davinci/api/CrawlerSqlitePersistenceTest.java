package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.util.Random;
import javax.sql.DataSource;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.config.CrawlerPersistenceConfig;
import org.hl7.davinci.api.entity.CrawlResource;
import org.hl7.davinci.api.entity.CrawlResourceId;
import org.hl7.davinci.api.entity.CrawlServer;
import org.hl7.davinci.common.PlanNetTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

class CrawlerSqlitePersistenceTest {

	@Test
	void sharesHapiDataSourceWhenNoUrlIsConfigured() {
		DataSource shared = new HikariDataSource();
		assertSame(shared, CrawlerPersistenceConfig.crawlerDataSource(shared, new ApiProperties.Datasource()));
	}

	@Test
	void sqliteSchemaAndLargeBodiesSurviveARestart(@TempDir Path tmp) {
		ApiProperties.Datasource config = new ApiProperties.Datasource();
		config.setUrl("jdbc:sqlite:" + tmp.resolve("crawler.sqlite"));

		try (HikariDataSource ds =
				(HikariDataSource) CrawlerPersistenceConfig.crawlerDataSource(null, config)) {
			// Larger than the 32600-byte default LONGVARBINARY cap that bit us on H2.
			byte[] body = new byte[100_000];
			new Random(42).nextBytes(body);

			EntityManagerFactory first = emf(ds);
			try {
				EntityManager em = first.createEntityManager();
				assertEquals(
						"wal",
						em.createNativeQuery("pragma journal_mode").getSingleResult(),
						"the WAL pragma must reach the SQLite connection");
				em.getTransaction().begin();
				CrawlResourceId id = new CrawlResourceId(1, PlanNetTypes.idOf("Organization"), "org-1");
				CrawlResource resource = new CrawlResource();
				resource.setId(id);
				resource.setVersionId("3");
				resource.setLastUpdated("2026-07-13T00:00:00Z");
				resource.setResourceJson(body);
				em.persist(resource);

				CrawlServer server = new CrawlServer();
				server.setServerKey("http://a.example/fhir");
				em.persist(server);
				em.getTransaction().commit();
				em.close();
			} finally {
				first.close();
			}

			// A second factory over the same file is the restart path: hbm2ddl update
			// against an already-populated SQLite schema.
			EntityManagerFactory second = emf(ds);
			try {
				EntityManager em = second.createEntityManager();
				CrawlResource loaded =
						em.find(CrawlResource.class, new CrawlResourceId(1, PlanNetTypes.idOf("Organization"), "org-1"));
				assertNotNull(loaded);
				assertEquals("3", loaded.getVersionId());
				assertArrayEquals(body, loaded.getResourceJson(), "the 100KB body must round-trip intact");

				CrawlServer loadedServer = em.createQuery(
								"select s from CrawlServer s where s.serverKey = :key", CrawlServer.class)
						.setParameter("key", "http://a.example/fhir")
						.getSingleResult();
				assertNotNull(loadedServer, "the crawl_server row must also survive the restart");
				em.close();
			} finally {
				second.close();
			}
		}
	}

	private static EntityManagerFactory emf(DataSource ds) {
		LocalContainerEntityManagerFactoryBean bean =
				new CrawlerPersistenceConfig().crawlerEntityManagerFactory(ds, new ApiProperties());
		bean.afterPropertiesSet();
		return bean.getObject();
	}
}
