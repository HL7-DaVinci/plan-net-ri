package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.hl7.fhir.r4.hapi.rest.server.ServerCapabilityStatementProvider;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementSoftwareComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;

import ca.uhn.fhir.rest.api.server.RequestDetails;

public class Metadata extends ServerCapabilityStatementProvider {

    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {

        CapabilityStatement c = super.getServerConformance(request, requestDetails);
        c.setTitle("Da Vinci Payer Data exchange Plan Network Reference Implementation");
        c.setStatus(PublicationStatus.DRAFT);
        c.setExperimental(true);
        c.setPublisher("Da Vinci");

        Calendar calendar = Calendar.getInstance();
        calendar.set(2019, 8, 5, 0, 0, 0);
        c.setDate(calendar.getTime());

        CapabilityStatementSoftwareComponent software = new CapabilityStatementSoftwareComponent();
        software.setName("https://github.com/HL7-DaVinci/plan-net-ri");
        c.setSoftware(software);

        c.addImplementationGuide("https://build.fhir.org/ig/HL7/davinci-pdex-plan-net/index.html");
        c.addImplementationGuide("https://wiki.hl7.org/Da_Vinci_PDex-plan-net_FHIR_IG_Proposal");

        updateRestComponents(c.getRest());
        return c;
    }

    private void updateRestComponents(
        List<CapabilityStatementRestComponent> originalRests
    ) {
        for(CapabilityStatementRestComponent rest : originalRests) {
            rest.setMode(RestfulCapabilityMode.SERVER);
            List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
            for(CapabilityStatementRestResourceComponent resource : resources) {
                List<ResourceInteractionComponent> interactions = new ArrayList<>();
                interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYINSTANCE));
                interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.HISTORYTYPE));
                interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.READ));
                interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.SEARCHTYPE));
                interactions.add(new ResourceInteractionComponent().setCode(TypeRestfulInteraction.VREAD));
                resource.setInteraction(interactions);

                switch(resource.getType()) {
                    case "Endpoint":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-Endpoint");
                        break;
                    case "HealthcareService":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-HealthcareService");
                        break;
                    case "InsurancePlan":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-InsurancePlan");
                        break;
                    case "Location":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-Location");
                        break;
                    case "Organization":
                        resource.getSupportedProfile().add(new CanonicalType("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-Organization"));
                        resource.getSupportedProfile().add(new CanonicalType("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-Network"));
                        break;
                    case "OrganizationAffiliation":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-OrganizationAffiliation");
                        break;
                    case "Practitioner":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-Practitioner");
                        break;
                    case "PractitionerRole":
                        resource.setProfile("http://hl7.org/fhir/us/davinci-pdex-plan-net/StructureDefinition/plannet-PractitionerRole");
                        break;
                }
            }
        }
    }
}

