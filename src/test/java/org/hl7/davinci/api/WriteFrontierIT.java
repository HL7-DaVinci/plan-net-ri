package org.hl7.davinci.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.hl7.davinci.publish.feed.WriteFrontier;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full server and exercises {@link WriteFrontier} through the real JPA storage hooks:
 * a slow in-flight write pins the frontier below its own stamp, a write executed through the
 * system dao (the $import / SystemRequestDetails route) is observed by the registry, and a
 * rolled-back transaction leaves no pin behind.
 */
@ActiveProfiles("test")
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		classes = {Application.class, NicknameServiceConfig.class, RepositoryConfig.class},
		properties = {
			"spring.datasource.url=jdbc:h2:mem:writefrontierit",
			"api.enabled=false",
			"publish.enabled=false",
			"publish.frontier-grace-ms=250",
			"spring.ai.mcp.server.enabled=false",
			"hapi.fhir.fhir_version=r4",
			"spring.main.allow-bean-definition-overriding=true",
			"management.health.elasticsearch.enabled=false",
			"spring.jpa.properties.hibernate.search.backend.directory.type=local-heap"
		})
class WriteFrontierIT {

	@Autowired
	private WriteFrontier writeFrontier;

	@Autowired
	private DaoRegistry daoRegistry;

	@Autowired
	private IFhirSystemDao<Bundle, Meta> systemDao;

	@Autowired
	private IInterceptorService interceptorService;

	private SlowOrganizationBlocker registeredBlocker;

	@AfterEach
	void unregisterBlocker() {
		if (registeredBlocker != null) {
			interceptorService.unregisterInterceptor(registeredBlocker);
			registeredBlocker = null;
		}
	}

	@Test
	void slowWriteInFlightPinsTheFrontierBelowItsStamp() throws Exception {
		CountDownLatch enteredLatch = new CountDownLatch(1);
		CountDownLatch releaseLatch = new CountDownLatch(1);
		registeredBlocker = new SlowOrganizationBlocker(enteredLatch, releaseLatch);
		interceptorService.registerInterceptor(registeredBlocker);

		Organization slow = new Organization();
		slow.setName("SLOW");
		AtomicReference<Throwable> writerFailure = new AtomicReference<>();
		Thread writer = new Thread(() -> {
			try {
				daoRegistry.getResourceDao(Organization.class).create(slow, new SystemRequestDetails());
			} catch (Throwable t) {
				writerFailure.set(t);
			}
		});
		writer.start();

		assertTrue(enteredLatch.await(10, TimeUnit.SECONDS), "the blocking hook should have been entered");
		Awaitility.await("the slow write to register a pin")
				.atMost(Duration.ofSeconds(5))
				.until(() -> writeFrontier.inFlightCount() > 0);

		long nowAfterPinVisible = System.currentTimeMillis();
		assertTrue(
				writeFrontier.frontierMillis() < nowAfterPinVisible,
				"the frontier must sit below the in-flight write's stamp");

		// Grace is 250ms; sleeping well past it shows the frontier is held by the pin itself,
		// not merely lagging within the grace window.
		Thread.sleep(500);
		assertTrue(
				writeFrontier.frontierMillis() < nowAfterPinVisible,
				"the frontier must stay pinned below the in-flight write regardless of how long we wait");

		releaseLatch.countDown();
		writer.join(TimeUnit.SECONDS.toMillis(10));
		assertFalse(writer.isAlive(), "the writer thread should have completed");
		assertNull(writerFailure.get(), "the slow create should not have thrown");

		Awaitility.await("the frontier to advance past the completed write's stamp")
				.atMost(Duration.ofSeconds(5))
				.until(() -> writeFrontier.frontierMillis() > nowAfterPinVisible);
	}

	@Test
	void systemRouteTransactionIsObservedByTheRegistry() {
		long before = writeFrontier.totalRegistrations();

		Bundle transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		Organization organization = new Organization();
		organization.setName("WriteFrontierIT-system-route-" + UUID.randomUUID());
		transactionBundle
				.addEntry()
				.setResource(organization)
				.getRequest()
				.setMethod(Bundle.HTTPVerb.POST)
				.setUrl("Organization");

		systemDao.transaction(new SystemRequestDetails(), transactionBundle);

		assertTrue(
				writeFrontier.totalRegistrations() > before,
				"a transaction executed through the system dao should register a pin, proving storage "
						+ "hooks fire for SystemRequestDetails writes");
	}

	@Test
	void rollbackLeavesNoPinBehind() {
		Bundle transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		Organization invalid = new Organization();
		invalid.setName("WriteFrontierIT-rollback-" + UUID.randomUUID());
		invalid.setPartOf(new Reference("Organization/does-not-exist-" + UUID.randomUUID()));
		transactionBundle
				.addEntry()
				.setResource(invalid)
				.getRequest()
				.setMethod(Bundle.HTTPVerb.POST)
				.setUrl("Organization");

		try {
			systemDao.transaction(new SystemRequestDetails(), transactionBundle);
			fail("a transaction referencing a nonexistent Organization should have been rejected");
		} catch (BaseServerResponseException expected) {
			// referential integrity violation; the transaction must roll back cleanly
		}

		Awaitility.await("no pin left behind after the rolled-back transaction")
				.atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertEquals(0, writeFrontier.inFlightCount()));
	}

	/**
	 * Ordered to run after {@link WriteFrontier}'s own PRESTORAGE hooks (default order 0) for the same
	 * pointcut, so the pin is already registered by the time this blocks.
	 */
	@Interceptor(order = 10)
	private static class SlowOrganizationBlocker {
		private final CountDownLatch enteredLatch;
		private final CountDownLatch releaseLatch;

		SlowOrganizationBlocker(CountDownLatch theEnteredLatch, CountDownLatch theReleaseLatch) {
			enteredLatch = theEnteredLatch;
			releaseLatch = theReleaseLatch;
		}

		@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
		public void block(IBaseResource theResource) {
			if (theResource instanceof Organization organization && "SLOW".equals(organization.getName())) {
				enteredLatch.countDown();
				try {
					releaseLatch.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}
}
