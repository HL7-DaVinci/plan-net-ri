package org.hl7.davinci.publish.feed;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the change feed for one resource type over a window of its HFJ_RES_VER history and
 * reduces it to a single winner per id. Winners are selected by the largest numeric versionId,
 * not timestamp, since commit order can invert version order under concurrent writes.
 */
@Component
public class ChangeFeedReader {

	private static final Logger ourLog = LoggerFactory.getLogger(ChangeFeedReader.class);
	private static final int DEFAULT_PAGE_SIZE = 1000;
	private static final int CLUSTER_WARN_THRESHOLD = 50_000;

	private final DaoRegistry daoRegistry;
	private int pageSize = DEFAULT_PAGE_SIZE;

	public ChangeFeedReader(DaoRegistry daoRegistry) {
		this.daoRegistry = daoRegistry;
	}

	/** Test seam: shrinks the page size so an IT can exercise same-instant cluster draining without a huge fixture. */
	public void setPageSize(int pageSize) {
		this.pageSize = pageSize;
	}

	/** Returns the per-id winner map for {@code type} over the window (sinceExclusiveMillis, untilInclusiveMillis]. */
	@SuppressWarnings("rawtypes")
	public Map<String, ChangeEntry> readWindow(String type, long sinceExclusiveMillis, long untilInclusiveMillis) {
		IFhirResourceDao dao = daoRegistry.getResourceDao(type);
		SystemRequestDetails details = new SystemRequestDetails();

		// HAPI's since/until bounds are inclusive; nudge sinceMillis to make the window exclusive-below.
		long sinceMillis = sinceExclusiveMillis + 1;
		Date since = new Date(sinceMillis);

		List<ChangeEntry> entries = new ArrayList<>();
		long currentUntilMillis = untilInclusiveMillis;
		long boundaryInstant = Long.MIN_VALUE;
		Set<String> seenAtBoundary = new HashSet<>();

		while (currentUntilMillis >= sinceMillis) {
			Date until = new Date(currentUntilMillis);
			IBundleProvider page = dao.history(since, until, null, details);
			List<IBaseResource> resources = page.getResources(0, pageSize);
			if (resources.isEmpty()) {
				break;
			}

			for (IBaseResource resource : resources) {
				long updatedMillis = updatedMillisOf(resource);
				if (updatedMillis == boundaryInstant) {
					String dedupeKey = resource.getIdElement().getIdPart() + "#"
							+ resource.getIdElement().getVersionIdPart();
					if (!seenAtBoundary.add(dedupeKey)) {
						continue;
					}
				}
				toChangeEntry(type, resource, updatedMillis).ifPresent(entries::add);
			}

			if (resources.size() < pageSize) {
				break;
			}

			// Results are ordered RES_UPDATED DESC, so the last row in a full page is the oldest.
			long oldestInPage = updatedMillisOf(resources.get(resources.size() - 1));
			if (oldestInPage == boundaryInstant) {
				// Every row ties on RES_UPDATED with the boundary; drain the cluster explicitly and step past it.
				drainCluster(dao, type, boundaryInstant, details, entries);
				currentUntilMillis = boundaryInstant - 1;
				boundaryInstant = Long.MIN_VALUE;
				seenAtBoundary.clear();
				continue;
			}
			boundaryInstant = oldestInPage;
			seenAtBoundary.clear();
			currentUntilMillis = oldestInPage;
		}

		return selectWinners(entries);
	}

	/**
	 * Reads every version stamped at exactly {@code instant} into {@code collector} via one query
	 * execution, since separate executions have no stable relative order within the same instant.
	 */
	@SuppressWarnings("rawtypes")
	private void drainCluster(
			IFhirResourceDao dao,
			String type,
			long instant,
			SystemRequestDetails details,
			List<ChangeEntry> collector) {
		Date at = new Date(instant);
		IBundleProvider cluster = dao.history(at, at, null, details);
		List<IBaseResource> resources;
		int n = pageSize;
		while (true) {
			resources = cluster.getResources(0, n);
			if (resources.size() < n) {
				break;
			}
			n *= 2;
		}

		if (resources.size() > CLUSTER_WARN_THRESHOLD) {
			ourLog.warn(
					"Draining same-instant history cluster of {} resources for type {} at instant {}; "
							+ "the whole cluster is held in memory for this drain",
					resources.size(),
					type,
					instant);
		}

		for (IBaseResource resource : resources) {
			toChangeEntry(type, resource, updatedMillisOf(resource)).ifPresent(collector::add);
		}
	}

	/** Pure winner reduction, kept separate from the DAO calls so it is directly testable. */
	static Map<String, ChangeEntry> selectWinners(List<ChangeEntry> entries) {
		Map<String, ChangeEntry> winners = new HashMap<>();
		for (ChangeEntry entry : entries) {
			winners.merge(
					entry.id(),
					entry,
					(existing, candidate) -> candidate.versionId() > existing.versionId() ? candidate : existing);
		}
		return winners;
	}

	private static Optional<ChangeEntry> toChangeEntry(String type, IBaseResource resource, long updatedMillis) {
		String versionPart = resource.getIdElement().getVersionIdPart();
		long versionId;
		try {
			versionId = Long.parseLong(versionPart);
		} catch (NumberFormatException | NullPointerException e) {
			ourLog.warn(
					"Skipping change feed entry with unparsable versionId: {}",
					resource.getIdElement().getValue());
			return Optional.empty();
		}
		String id = resource.getIdElement().getIdPart();
		boolean deleted = ResourceMetadataKeyEnum.DELETED_AT.get(resource) != null;
		return Optional.of(new ChangeEntry(type, id, versionId, deleted, updatedMillis));
	}

	private static long updatedMillisOf(IBaseResource resource) {
		IPrimitiveType<Date> deletedAt = ResourceMetadataKeyEnum.DELETED_AT.get(resource);
		if (deletedAt != null && deletedAt.getValue() != null) {
			return deletedAt.getValue().getTime();
		}
		return resource.getMeta().getLastUpdated().getTime();
	}
}
