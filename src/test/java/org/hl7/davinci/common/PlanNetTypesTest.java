package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PlanNetTypesTest {

	@Test
	void idsAreTheLiteralDocumentedNumbers() {
		assertEquals(1, PlanNetTypes.idOf("Endpoint"));
		assertEquals(2, PlanNetTypes.idOf("HealthcareService"));
		assertEquals(3, PlanNetTypes.idOf("InsurancePlan"));
		assertEquals(4, PlanNetTypes.idOf("Location"));
		assertEquals(5, PlanNetTypes.idOf("Organization"));
		assertEquals(6, PlanNetTypes.idOf("OrganizationAffiliation"));
		assertEquals(7, PlanNetTypes.idOf("Practitioner"));
		assertEquals(8, PlanNetTypes.idOf("PractitionerRole"));
	}

	@Test
	void idOfAndTypeOfRoundTripForEveryType() {
		for (int id = 1; id <= 8; id++) {
			assertEquals(id, PlanNetTypes.idOf(PlanNetTypes.typeOf(id)));
		}
	}

	@Test
	void idOfRejectsAnUnknownType() {
		assertThrows(IllegalArgumentException.class, () -> PlanNetTypes.idOf("Bundle"));
	}

	@Test
	void typeOfRejectsAnUnknownId() {
		assertThrows(IllegalArgumentException.class, () -> PlanNetTypes.typeOf(0));
		assertThrows(IllegalArgumentException.class, () -> PlanNetTypes.typeOf(9));
	}
}
