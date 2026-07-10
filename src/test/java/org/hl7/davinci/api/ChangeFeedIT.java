package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import java.time.Duration;
import java.util.Map;
import org.hl7.davinci.publish.feed.ChangeEntry;
import org.hl7.davinci.publish.feed.ChangeFeedReader;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full server and exercises {@link ChangeFeedReader} against real HFJ_RES_VER history:
 * winner selection by version, delete detection, and window boundary inclusion/exclusion.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class, NicknameServiceConfig.class, RepositoryConfig.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:changefeedit",
			"api.enabled=false",
			"publish.enabled=false",
			"spring.ai.mcp.server.enabled=false",
			"hapi.fhir.fhir_version=r4",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class ChangeFeedIT {

	@Autowired
	private ChangeFeedReader changeFeedReader;

	@Autowired
	private DaoRegistry daoRegistry;

	@Autowired
	private IFhirSystemDao<Bundle, Meta> systemDao;

	@Test
	void readWindowSelectsWinnersAndRespectsWindowBoundaries() throws InterruptedException {
		SystemRequestDetails details = new SystemRequestDetails();
		IFhirResourceDao<Organization> organizationDao = daoRegistry.getResourceDao(Organization.class);
		IFhirResourceDao<Location> locationDao = daoRegistry.getResourceDao(Location.class);

		long t0 = System.currentTimeMillis() - 1;

		Organization a = new Organization();
		a.setName("ChangeFeedIT-A-v1");
		IIdType aId = organizationDao.create(a, details).getId().toUnqualifiedVersionless();
		Thread.sleep(15);

		a.setId(aId.getIdPart());
		a.setName("ChangeFeedIT-A-v2");
		organizationDao.update(a, details);
		Thread.sleep(30);
		long tMid = System.currentTimeMillis();
		Thread.sleep(30);

		a.setName("ChangeFeedIT-A-v3");
		organizationDao.update(a, details);
		Thread.sleep(15);

		Organization b = new Organization();
		b.setName("ChangeFeedIT-B");
		IIdType bId = organizationDao.create(b, details).getId().toUnqualifiedVersionless();
		Thread.sleep(15);
		organizationDao.delete(bId, details);
		Thread.sleep(15);

		Location l = new Location();
		l.setName("ChangeFeedIT-L");
		IIdType lId = locationDao.create(l, details).getId().toUnqualifiedVersionless();
		Thread.sleep(15);

		Organization c = new Organization();
		c.setName("ChangeFeedIT-C");
		IIdType cId = organizationDao.create(c, details).getId().toUnqualifiedVersionless();
		long stampOfCVersion1 =
				organizationDao.read(cId, details).getMeta().getLastUpdated().getTime();
		Thread.sleep(15);

		long until = System.currentTimeMillis();

		Map<String, ChangeEntry> organizationWindow = changeFeedReader.readWindow("Organization", t0, until);
		ChangeEntry aWinner = organizationWindow.get(aId.getIdPart());
		assertEquals(3, aWinner.versionId());
		assertFalse(aWinner.deleted());

		ChangeEntry bWinner = organizationWindow.get(bId.getIdPart());
		assertTrue(bWinner.deleted());
		assertEquals(2, bWinner.versionId());

		assertFalse(organizationWindow.containsKey(lId.getIdPart()), "a Location must not appear in the Organization feed");

		Map<String, ChangeEntry> locationWindow = changeFeedReader.readWindow("Location", t0, until);
		assertEquals(1, locationWindow.size());
		ChangeEntry lWinner = locationWindow.get(lId.getIdPart());
		assertEquals(1, lWinner.versionId());
		assertFalse(lWinner.deleted());

		Map<String, ChangeEntry> subWindow = changeFeedReader.readWindow("Organization", t0, tMid);
		ChangeEntry aSubWinner = subWindow.get(aId.getIdPart());
		assertEquals(2, aSubWinner.versionId());

		Map<String, ChangeEntry> sinceAtCsOwnStamp = changeFeedReader.readWindow("Organization", stampOfCVersion1, until);
		assertFalse(
				sinceAtCsOwnStamp.containsKey(cId.getIdPart()),
				"a version stamped exactly at the exclusive lower bound must not appear");
	}

	@Test
	void readWindowDrainsASameInstantClusterLargerThanThePageSize() {
		changeFeedReader.setPageSize(5);
		try {
			long t0 = System.currentTimeMillis() - 1;

			// One transaction bundle stamps every entry with the same TransactionDetails
			// timestamp, so 12 creates of one type form a single same-instant cluster more than
			// twice the shrunk page size.
			Bundle transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
			for (int i = 0; i < 12; i++) {
				Organization organization = new Organization();
				organization.setName("ChangeFeedIT-cluster-" + i);
				transactionBundle
						.addEntry()
						.setResource(organization)
						.getRequest()
						.setMethod(Bundle.HTTPVerb.POST)
						.setUrl("Organization");
			}
			systemDao.transaction(new SystemRequestDetails(), transactionBundle);

			long until = System.currentTimeMillis();

			Map<String, ChangeEntry> window = assertTimeoutPreemptively(
					Duration.ofSeconds(30), () -> changeFeedReader.readWindow("Organization", t0, until));

			assertEquals(12, window.size());
			assertTrue(window.values().stream().noneMatch(ChangeEntry::deleted));
		} finally {
			changeFeedReader.setPageSize(1000);
		}
	}
}
