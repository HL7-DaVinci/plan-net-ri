package ca.uhn.fhir.jpa.starter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.lang.Class;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseResource;

import ca.uhn.fhir.jpa.provider.r4.JpaResourceProviderR4;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

/**
 * Fakes support for Location near searching (which is not yet supported by
 * HAPI). Only tested with generally limited near searching on Location,
 * Organization, OrganizationAffiliation, HealthcareService, Practitioner, and
 * PractitionerRole. Does not yet handle requests with multiple near searches.
 * Does not yet handle requests with chains exceeding a depth of one.
 */
public class NearQueryInterceptor extends InterceptorAdapter {

    static List<String[]> urlTracker = new ArrayList<String[]>();

    @Override
    public boolean incomingRequestPostProcessed(RequestDetails rd, HttpServletRequest req, HttpServletResponse res)
            throws AuthenticationException {
        String originalUrl = rd.getCompleteUrl();
        int nearStartI = originalUrl.indexOf("near=");
        if (nearStartI != -1) {
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

            String[] joinedIDs = { String.join(",", getIDParams(rd, nearParam)) };
            newParams.put("_id", joinedIDs);
            rd.setParameters(newParams);

            String[] urlToTrack = { joinedIDs[0], originalUrl };
            NearQueryInterceptor.urlTracker.add(0, urlToTrack);
            int newSize = Math.min(5, NearQueryInterceptor.urlTracker.size());
            NearQueryInterceptor.urlTracker = NearQueryInterceptor.urlTracker.subList(0, newSize);
        }
        return true;
    }

    @Override
    public boolean outgoingResponse(RequestDetails req, ResponseDetails res, HttpServletRequest hReq, 
            HttpServletResponse hRes) throws AuthenticationException {
        String[] idParams = req.getParameters().get("_id");
        String originalUrl = getOriginalUrl((idParams == null) ? "" : idParams[0]);
        if (!originalUrl.isEmpty()) {
            Bundle bundle = (Bundle) res.getResponseResource();
            List<Bundle.BundleLinkComponent> bundleLinks = bundle.getLink();
            Bundle.BundleLinkComponent selfLink = null;
            for (Bundle.BundleLinkComponent link : bundleLinks) {
                if (link.getRelation().equals("self")) {
                    selfLink = link;
                }
            }
            if (selfLink != null) bundleLinks.remove(selfLink);
            Bundle.BundleLinkComponent newSelf = new Bundle.BundleLinkComponent();
            newSelf.setRelation("self");
            newSelf.setUrl(originalUrl);
            bundleLinks.add(newSelf);
            bundle.setLink(bundleLinks);
            res.setResponseResource(bundle);
        }
        return true;
    }

    /**
     * Checks NearQueryInterceptor.urlTracker for a string matching the provided _id
     * query param string. If found, returns the original url associated with the
     * _id param string and removes that url from NearQueryInterceptor.urlTracker
     * 
     * @param responseIDs String to match existing _id param value string
     * @return String url of the original request associated with the provided _id
     *         param string
     */
    private String getOriginalUrl(String responseIDs) {
        String originalUrl = "";
        for (String[] trackedInfo : NearQueryInterceptor.urlTracker) {
            if (responseIDs.equals(trackedInfo[0])) {
                originalUrl = trackedInfo[1];
                NearQueryInterceptor.urlTracker.remove(trackedInfo);
                break;
            }
        }
        return originalUrl;
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
            if (rp.getClass().getName().endsWith(resourceName + "ResourceProvider")) {
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
     * Gets the IDs of the resources that fall within the range specified by
     * nearParam
     * 
     * @param rd        RequestDetails to describe the http request this is working
     *                  form
     * @param nearParam The near query string, with the field as the key for the
     *                  associated value
     * @return An ArrayList<String> populated with the relevent resource IDs within
     *         the specified range
     */
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
                includes.add(chainElements[1] + ":" + chainElements[3].substring(0, chainElements[3].indexOf(".near")));
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
     * Gets every resource of rp's type, plus any specified by the includes
     * ArrayList<String>
     * 
     * @param rp       JpaResourceProviderR4 runs the search for all desired
     *                 resources
     * @param includes ArrayList<String> indicates what resources to include that
     *                 are linked to rp's inherent type
     * @return ArrayList<IBaseResource> of the requested resources
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

    /**
     * Gets every resource of rp's type
     * 
     * @param rp JpaResourceProviderR4 runs the search for all resources of this
     *           type
     * @return ArrayList<IBaseResource> of every instance of the resource specified
     *         by rp's type
     */
    private ArrayList<IBaseResource> getResources(JpaResourceProviderR4 rp) {
        return getResources(rp, new ArrayList<String>());
    }

    /**
     * Sifts through resources for every Location. Deletes each Location from
     * resources and returns the Locations that fall in the provided range
     * 
     * @param resources ArrayList<IBaseResource> to remove Locations from while
     *                  pulling out the desirable ones
     * @param range     Range describing the area a Location must fall in to be
     *                  returned
     * @return ArrayList<Location> of every Location from resources encompassed by
     *         range
     */
    private ArrayList<Location> getLocationsInRange(ArrayList<IBaseResource> resources, Range range) {
        ArrayList<Location> locations = new ArrayList<Location>();
        for (int i = 0; i < resources.size(); i++) {
            IBaseResource res = resources.get(i);
            if (res instanceof Location) {
                if (range.encompasses((Location) res)) {
                    locations.add((Location) res);
                }
                resources.remove(i--);
            }
        }
        return locations;
    }

    /**
     * Gets the IDs of every IBaseResource: from resources, of the type indicated by
     * type, and within range. Removes IBaseResources from resources (all Locations
     * and every instance of type that falls outside the range)
     * 
     * @param resources ArrayList<IBaseResource> to get appropriate IDs from. Also
     *                  gets filtered to improve future searches.
     * @param range     Range describing the area a resources associated Location
     *                  must be encompassed by to be returned
     * @param type      String name of the desired resource (e.g.
     *                  "HealthcareService" indicates the
     *                  org.hl7.fhir.r4.model.HealthcareService object)
     * @return ArrayList<String> of every ID from the resources of type within range
     */
    private ArrayList<String> getChainNearIDs(ArrayList<IBaseResource> resources, Range range, String type) {
        ArrayList<Location> locationsInRange = getLocationsInRange(resources, range);
        ArrayList<String> ids = new ArrayList<String>();

        for (int i = 0; i < resources.size(); i++) {
            IBaseResource res = resources.get(i);
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
            if (inRange) {
                ids.add(thisID);
            } else {
                resources.remove(i--);
            }
        }

        return ids;
    }

    /**
     * Gets the IDs of every IBaseResource: from resources, of the type indicated by
     * type, and that points to a chain[1] which points to a Location that falls
     * within range. Removes IBaseResources from resources (all Locations and every
     * instance of chain[1] that falls outside the range)
     * 
     * @param resources ArrayList<IBaseResource> to get appropriate IDs from. Also
     *                  gets filtered to improve future searches.
     * @param range     Range describing the area a resources associated Location
     *                  must be encompassed by to be returned
     * @param chain     The near parameter query string value after being split on
     *                  '|'. Length should be 2..4
     * @param type      String name of the desired resource (e.g.
     *                  "HealthcareService" indicates the
     *                  org.hl7.fhir.r4.model.HealthcareService object)
     * @return ArrayList<String> of every ID from the resources of type within range
     */
    private ArrayList<String> getReverseChainNearIDs(ArrayList<IBaseResource> resources, Range range, String[] chain,
            String type) {
        ArrayList<String> aggregatorIDs = getChainNearIDs(resources, range, chain[1]);
        ArrayList<String> ids = new ArrayList<String>();

        for (IBaseResource res : resources) {

            List<Reference> refs;
            try { // also kinda uncomfortable
                Class resClass = res.getClass();
                if (!resClass.getName().endsWith(chain[1])) continue;
                Method getId = resClass.getMethod("getId", null);
                if (!aggregatorIDs.contains((String) getId.invoke(res, null))) continue;
                Method getType = resClass.getMethod("get" + type, null);
                if (getType.getReturnType().getName().endsWith("Reference")) {
                    refs = new ArrayList<Reference>();
                    refs.add((Reference) getType.invoke(res, null));
                } else {
                    refs = (List<Reference>) getType.invoke(res, null);
                }
            } catch (Exception e) {
                refs = null;
                continue;
            }

            String innerResID = "";
            boolean inAggregator = false;
            for (IBaseResource innerRes : resources) {
                if (!(innerRes instanceof IAnyResource) || !innerRes.getClass().getName().endsWith(type)) continue;
                innerResID = ((IAnyResource) innerRes).getId();
                innerResID = innerResID.substring(0, innerResID.indexOf("/", innerResID.indexOf("/") + 1));
                for (Reference ref : refs) {
                    inAggregator = ref.getReference().contains(innerResID);
                    if (inAggregator) break;
                }
                if (inAggregator) break;
            }
            if (inAggregator) ids.add(innerResID);
        }

        return ids;
    }
}
