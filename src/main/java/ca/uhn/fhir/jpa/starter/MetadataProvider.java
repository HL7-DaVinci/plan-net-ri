package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import ca.uhn.fhir.jpa.provider.r4.JpaConformanceProviderR4;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import ca.uhn.fhir.jpa.dao.DaoConfig;
import ca.uhn.fhir.jpa.dao.IFhirSystemDao;
import ca.uhn.fhir.rest.server.RestfulServer;

public class MetadataProvider extends JpaConformanceProviderR4 {
  MetadataProvider(RestfulServer theRestfulServer, IFhirSystemDao<Bundle, Meta> theSystemDao, DaoConfig theDaoConfig) {
    super(theRestfulServer, theSystemDao, theDaoConfig);
    setCache(false);
  }

  @Override
  public CapabilityStatement getServerConformance(HttpServletRequest theRequest) {
    CapabilityStatement metadata = super.getServerConformance(theRequest);
    metadata.setTitle("Da Vinci Payer Data exchange Plan Network Reference Implementation");
    metadata.setStatus(PublicationStatus.DRAFT);
    metadata.setExperimental(true);
    metadata.setPublisher("Da Vinci");

    Calendar calendar = Calendar.getInstance();
    calendar.set(2019, 8, 5, 0, 0, 0);
    metadata.setDate(calendar.getTime());

    // TODO: add once we have a github repo
    // CapabilityStatementSoftwareComponent software =
    //   new CapabilityStatementSoftwareComponent();
    // software.setName("https://github.com/HL7-DaVinci/drug-formulary");
    // metadata.setSoftware(software);

    metadata.addImplementationGuide("https://build.fhir.org/ig/HL7/davinci-pdex-plan-net/index.html");
    metadata.addImplementationGuide("https://wiki.hl7.org/Da_Vinci_PDex-plan-net_FHIR_IG_Proposal");

    updateRestComponents(metadata.getRest());
    return metadata;
  }

  private void updateRestComponents(
    List<CapabilityStatementRestComponent> originalRests
  ) {
    for(CapabilityStatementRestComponent rest : originalRests) {
      List<CapabilityStatementRestResourceComponent> resources = rest.getResource();
      for(CapabilityStatementRestResourceComponent resource : resources) {
        List<ResourceInteractionComponent> interactions = new ArrayList<ResourceInteractionComponent>();
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
