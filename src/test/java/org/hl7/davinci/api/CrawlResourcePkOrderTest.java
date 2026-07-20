package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.hl7.davinci.api.config.ApiProperties;
import org.hl7.davinci.api.config.CrawlerPersistenceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * Guards the composite PK column order every prefix query in CrawlResourceRepository depends
 * on. Hibernate orders composite PK constraint columns alphabetically by attribute name, not by
 * declaration order; if this test fails, the index is no longer (server_id, type_id, uid) and
 * every per-server or per-type query silently becomes a full table scan.
 */
class CrawlResourcePkOrderTest {

	@Test
	void h2OrdersThePrimaryKeyServerIdTypeIdUid() throws Exception {
		HikariConfig hikari = new HikariConfig();
		hikari.setJdbcUrl("jdbc:h2:mem:pkorder-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
		try (HikariDataSource ds = new HikariDataSource(hikari)) {
			buildSchema(ds);
			assertEquals(List.of("server_id", "type_id", "uid"), primaryKeyColumnsH2(ds, "CRAWL_RESOURCE"));
		}
	}

	@Test
	void sqliteOrdersThePrimaryKeyServerIdTypeIdUid(@TempDir Path tmp) throws Exception {
		ApiProperties.Datasource config = new ApiProperties.Datasource();
		config.setUrl("jdbc:sqlite:" + tmp.resolve("pkorder.sqlite"));
		try (HikariDataSource ds = (HikariDataSource) CrawlerPersistenceConfig.crawlerDataSource(null, config)) {
			buildSchema(ds);
			assertEquals(List.of("server_id", "type_id", "uid"), primaryKeyColumnsSqlite(ds, "crawl_resource"));
		}
	}

	private static void buildSchema(DataSource ds) {
		LocalContainerEntityManagerFactoryBean bean =
				new CrawlerPersistenceConfig().crawlerEntityManagerFactory(ds, new ApiProperties());
		bean.afterPropertiesSet();
		EntityManagerFactory emf = bean.getObject();
		emf.createEntityManager().close();
		emf.close();
	}

	private static List<String> primaryKeyColumnsH2(DataSource ds, String table) throws Exception {
		try (Connection conn = ds.getConnection()) {
			DatabaseMetaData meta = conn.getMetaData();
			TreeMap<Short, String> byOrdinal = new TreeMap<>();
			try (ResultSet rs = meta.getPrimaryKeys(null, null, table)) {
				while (rs.next()) {
					byOrdinal.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME").toLowerCase());
				}
			}
			return new ArrayList<>(byOrdinal.values());
		}
	}

	private static List<String> primaryKeyColumnsSqlite(DataSource ds, String table) throws Exception {
		try (Connection conn = ds.getConnection();
				PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")");
				ResultSet rs = ps.executeQuery()) {
			TreeMap<Integer, String> byOrdinal = new TreeMap<>();
			while (rs.next()) {
				int pk = rs.getInt("pk");
				if (pk > 0) {
					byOrdinal.put(pk, rs.getString("name"));
				}
			}
			return new ArrayList<>(byOrdinal.values());
		}
	}
}
