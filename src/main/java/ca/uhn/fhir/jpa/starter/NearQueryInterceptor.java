package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.lang.Class;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.HealthcareService;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.OrganizationAffiliation;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.jpa.provider.r4.JpaResourceProviderR4;
import ca.uhn.fhir.jpa.rp.r4.HealthcareServiceResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.LocationResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.OrganizationAffiliationResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.OrganizationResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.PractitionerResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.PractitionerRoleResourceProvider;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

/**
 * Fakes support for Location.near searching (which is not supported by HAPI).
 * Only "supports" generally limited near searching for Location, Organization,
 * OrganizationAffiliation, HealthcareService, Practitioner, and 
 * PractitionerRole. Does not yet handle requests with multiple near searches. 
 * Does not yet handle requests with chains that exceed a depth of one.
 */
public class NearQueryInterceptor extends InterceptorAdapter {
    @Override
    public boolean incomingRequestPostProcessed(RequestDetails rd, HttpServletRequest req, HttpServletResponse res)
            throws AuthenticationException {
        HashMap<String, String[]> params = new HashMap<String, String[]>(rd.getParameters());
        String url = rd.getCompleteUrl();
        int nearStartI = url.indexOf("near=");
        if (nearStartI != -1) {
            JpaResourceProviderR4 requestSpecificRP = getResourceProvider(rd);

            HashMap<String, String[]> oldParams = new HashMap<String, String[]>(rd.getParameters());
            HashMap<String, String[]> nearParam = new HashMap<String, String[]>();
            HashMap<String, String[]> newParams = new HashMap<String, String[]>();

            for (String key : oldParams.keySet()) {
                if (key.indexOf("near") > -1) {
                    nearParam.put(key, oldParams.get(key));
                } else {
                    newParams.put(key, oldParams.get(key));
                }
            }

            ArrayList<String> ids = getIDParams(rd, nearParam);
            System.out.println("\n\n\n\n\n----------\nids: " + ids.size() + "\n----------\n\n\n\n\n");

            url = stripNearParams(url, nearParam);
            System.out.println("\n\n\n\n\n----------\nurl: " + url + "\n----------\n\n\n\n\n");
            // @TODO add ids to url
            rd.setCompleteUrl(url);

            System.out.println("\n\n\n\n\n----------\nparams: " + newParams + "\n----------\n\n\n\n\n");
            // @TODO add id param
            rd.setParameters(newParams);
        }
        return true;
    }

    /**
     * Iterates through the server's registered resource providers, gets the one
     * that matches resourceName
     * 
     * @param rd           RequestDetails object for retrieving the resource
     *                     provider
     * @param resourceName String name of the resource for which the provider is
     *                     needed
     * @return JpaResourceProviderR4 associated with what resourceName describes,
     *         null if no resource providers match
     */
    private JpaResourceProviderR4 getResourceProvider(RequestDetails rd, String resourceName) {
        RestfulServer server = ((RestfulServer) rd.getServer());
        for (IResourceProvider rp : server.getResourceProviders()) {
            if ((rp instanceof LocationResourceProvider && resourceName.equals("Location"))
                    || (rp instanceof HealthcareServiceResourceProvider && resourceName.equals("HealthcareService"))
                    || (rp instanceof OrganizationResourceProvider && resourceName.equals("Organization"))
                    || (rp instanceof PractitionerResourceProvider && resourceName.equals("Practitioner"))
                    || (rp instanceof PractitionerRoleResourceProvider && resourceName.equals("PractitionerRole"))
                    || (rp instanceof OrganizationAffiliationResourceProvider
                            && resourceName.equals("OrganizationAffiliation"))) {
                return (JpaResourceProviderR4) rp;
            }
        }
        return null;
    }

    /**
     * Iterates through the server's registered resource providers, gets the
     * provider for this request's resource type
     * 
     * @param rd RequestDetails object for retrieving the resource provider
     * @return JpaResourceProviderR4 associated with what rd describes, null if no
     *         resource providers match
     */
    private JpaResourceProviderR4 getResourceProvider(RequestDetails rd) {
        return getResourceProvider(rd, rd.getResourceName());
    }

    /**
     * Strips the provided near params out of the provided url
     * 
     * @param url    String representing the url to strip params out of
     * @param params HashMap<String, String[]> describing the params to strip out of
     *               the url
     * @return String of the url with the near params gone
     */
    private String stripNearParams(String url, HashMap<String, String[]> nearParams) {
        for (String key : nearParams.keySet()) {
            String before = url.substring(0, url.indexOf(key));
            int endIndex = before.length() + key.length() + nearParams.get(key)[0].replace("|", "%7C").length() + 2;
            if (endIndex < url.length()) {
                url = before + url.substring(endIndex);
            } else {
                url = before.substring(0, before.length() - 1);
            }
        }
        return url;
    }

    private ArrayList<String> getIDParams(RequestDetails rd, HashMap<String, String[]> nearParam) {
        String searchPointer = nearParam.keySet().toArray(new String[1])[0];
        Range range = new Range(nearParam.get(searchPointer)[0].split("\\|"));
        ArrayList<String> ids = null;

        if (searchPointer.startsWith("near")) { // normal search (should be on Location resource)
            ids = new ArrayList<String>();
            for (Location loc : getLocationsInRange(getResources(getResourceProvider(rd)), range)) {
                ids.add(loc.getId());
            }

        } else {
            ArrayList<IBaseResource> resources;

            if (searchPointer.startsWith("_has")) { // reverse chain search
                String[] chainElements = searchPointer.split(":");
                if (chainElements.length != 4) return null;
                ArrayList<String> includes = new ArrayList<String>();
                includes.add(chainElements[1] + ":" + chainElements[2]);
                includes.add(chainElements[1] +":" + chainElements[3].substring(0, chainElements[3].indexOf(".near")));
                resources = getResources(getResourceProvider(rd, chainElements[1]), includes);
                ids = getReverseChainNearIDs(resources, range, chainElements, rd.getResourceName());

            } else { // chain search
                ArrayList<String> includes = new ArrayList<String>();
                includes.add(rd.getResourceName() + ":" + searchPointer.substring(0, searchPointer.indexOf(".near")));
                resources = getResources(getResourceProvider(rd), includes);
                ids = getChainNearIDs(resources, range, rd.getResourceName());
            }
        }

        return ids;
    }

    /**
     * Cases to keep in mind:
     * [base]/HealthcareService?location.near=-12.34567|12.34567||km
     * [base]/Practitioner?_has:PractitionerRole:practitioner:location.near=-12.34567|12.34567|32.6|mi
     * [base]/Organization?_has:OrganizationAffiliation:participating-organization:location.near=-12.34567|12.34567|25
     * [base]/Location?near=-12.34567|12.34567
     * Practitioner?_id=plannet-practitioner-1236044348,plannet-practitioner-1237551547,plannet-practitioner-1237955865
     * 
     * Plenty of work to be done here, this is from the HealthcareService extended operation
     * 
     * @param rp
     * @param nearParams
     * @return
     */
    private ArrayList<IBaseResource> getResources(JpaResourceProviderR4 rp, ArrayList<String> includes) {
        SearchParameterMap spm = new SearchParameterMap();
        for (String include : includes) {
            spm.addInclude(new Include(include));
        }
        
        int maxPageSize = HapiProperties.getMaximumPageSize();
        spm.setCount(maxPageSize);
        spm.setLoadSynchronous(true);

        SimpleBundleProvider bp = (SimpleBundleProvider) rp.getDao().search(spm);

        ArrayList<IBaseResource> retVal = new ArrayList<IBaseResource>();
        retVal.addAll(bp.getResources(0, bp.size()));
        return retVal;
    }

    private ArrayList<IBaseResource> getResources(JpaResourceProviderR4 rp) {
        return getResources(rp, new ArrayList<String>());
    }

    private ArrayList<Location> getLocationsInRange(ArrayList<IBaseResource> resources, Range range) {
        ArrayList<Location> locations = new ArrayList<Location>();
        for (IBaseResource res : resources) {
            if (res instanceof Location && range.encompasses((Location) res)) locations.add((Location) res);
        }
        return locations;
    }

    private ArrayList<String> getChainNearIDs(ArrayList<IBaseResource> resources, Range range, String type) {
        ArrayList<Location> locationsInRange = getLocationsInRange(resources, range);
        ArrayList<String> ids = new ArrayList<String>();
        System.out.println("\n\n\n\n\n----------\nchain type: " + type + "\n----------\n\n\n\n\n");

        for (IBaseResource res : resources) {
            List<Reference> locationRefs;
            String thisID;
            try { // kinda gross code but no clear alternative, working with a list of lightly defined IBaseResources
                Class resClass = res.getClass();
                if (!resClass.getName().endsWith(type)) continue;
                Method getLocation = resClass.getMethod("getLocation", null);
                locationRefs = (List<Reference>) getLocation.invoke(res, null);
                Method getId = resClass.getMethod("getId", null);
                thisID = (String) getId.invoke(res, null);
            } catch (Exception e) {
                locationRefs = null;
                thisID = "";
                continue;
            }

            boolean inRange = false;
            for (Reference ref : locationRefs) {
                for (Location loc : locationsInRange) {
                    String locID = loc.getId().substring(0, loc.getId().indexOf("/", loc.getId().indexOf("/") + 1));
                    inRange = ref.getReference().equals(locID);
                    if (inRange) break;
                }
                if (inRange) break;
            }
            if (inRange) ids.add(thisID);
        }

        return ids;
    }

    private ArrayList<String> getReverseChainNearIDs(ArrayList<IBaseResource> resources, Range range, 
                                                    String[] chain, String type) {
        ArrayList<String> aggregatorIDs = getChainNearIDs(resources, range, chain[1]);
        System.out.println("\n\n\n\n\n----------\nids in range: " + aggregatorIDs.size() + "\n----------\n\n\n\n\n");
        ArrayList<String> ids = new ArrayList<String>();

        System.out.println("\n\n\n\n\n----------\nresources: " + resources.size() + "\n----------\n\n\n\n\n");
        for (IBaseResource res : resources) {

            String thisID;
            List<Reference> refs;
            try { // also kinda uncomfortable
                Class resClass = res.getClass();
                if (!resClass.getName().endsWith(chain[1])) continue;
                Method getId = resClass.getMethod("getId", null);
                thisID = (String) getId.invoke(res, null);
                if (!aggregatorIDs.contains(thisID)) continue;
                Method getType = resClass.getMethod("get" + type, null);
                if (getType.getReturnType().getName().endsWith("Reference")) {
                    refs = new ArrayList<Reference>();
                    refs.add((Reference) getType.invoke(res, null));
                } else {
                    refs = (List<Reference>) getType.invoke(res, null);
                }
            } catch (Exception e) {
                thisID = "";
                refs = null;
                continue;
            }

            boolean inAggregator = false;
            for (IBaseResource innerRes : resources) {
                if (!(innerRes instanceof IAnyResource) || !innerRes.getClass().getName().endsWith(type)) continue;
                String innerResID = ((IAnyResource) innerRes).getId();
                innerResID = innerResID.substring(0, innerResID.indexOf("/", innerResID.indexOf("/") + 1)); 
                for (Reference ref : refs) {
                    inAggregator = ref.getReference().contains(innerResID);
                    if (inAggregator) break;
                }
                if (inAggregator) break;
            }
            if (inAggregator) ids.add(thisID);
        }

        return ids;
    }
}
