package ca.uhn.fhir.jpa.starter;

import java.util.HashMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import ca.uhn.fhir.jpa.provider.r4.JpaResourceProviderR4;
import ca.uhn.fhir.jpa.rp.r4.HealthcareServiceResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.LocationResourceProvider;
import ca.uhn.fhir.jpa.rp.r4.OrganizationAffiliationResourceProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

public class NearQueryInterceptor extends InterceptorAdapter {
    @Override
    public boolean incomingRequestPostProcessed(RequestDetails rd, HttpServletRequest theRequest, 
                                                HttpServletResponse theResponse) throws AuthenticationException {
        String url = rd.getCompleteUrl();
        int nearStartI = url.indexOf("near=");
        if (nearStartI != -1) {
            JpaResourceProviderR4 thisRP = getResourceProvider(rd);

            HashMap<String, String[]> oldParams = new HashMap<String, String[]>(rd.getParameters());
            HashMap<String, String[]> nearParams = new HashMap<String, String[]>();
            HashMap<String, String[]> newParams = new HashMap<String, String[]>();

            for (String key : oldParams.keySet()) {
                if (key.indexOf("near") > -1) {
                    nearParams.put(key, oldParams.get(key));
                } else {
                    newParams.put(key, oldParams.get(key));
                }
            }

            url = stripNearParams(url, nearParams);
            System.out.println("\n\n\n\n\n----------\n" + url + "\n----------\n\n\n\n\n");
            // @TODO add ids to url
            rd.setCompleteUrl(url);


            System.out.println("\n\n\n\n\n----------\n" + newParams + "\n----------\n\n\n\n\n");
            // @TODO add id param
            rd.setParameters(newParams);
        }
        return true;
    }

    /**
     * Iterates through the server's registeres resource providers, gets the one for the request's resource type
     * @param rd RequestDetails object for determining the correct resource provider
     * @return BaseJpaResourceProvider associated with what rd describes, null if no resource providers match
     */
    private JpaResourceProviderR4 getResourceProvider(RequestDetails rd) {
        RestfulServer server = ((RestfulServer) rd.getServer());
        String resourceName = rd.getResourceName();
        for (IResourceProvider rp : server.getResourceProviders()) {
            if ((rp instanceof OrganizationAffiliationResourceProvider 
                    && resourceName.equals("OrganizationAffiliation"))
                    || (rp instanceof HealthcareServiceResourceProvider
                    && resourceName.equals("HealthcareService"))
                    || (rp instanceof LocationResourceProvider && resourceName.equals("Location"))
                    || (rp instanceof HealthcareServiceResourceProvider
                    && resourceName.equals("HealthcareService"))) {
                return (JpaResourceProviderR4) rp;
            }
        }
        return null;
    }

    /**
     * strips the provided params out of the provided url
     * @param url String representing the url to strip params out of
     * @param params HashMap<String, String[]> describing the params to strip out of the url
     * @return String of the url with the params gone
     */
    private String stripNearParams(String url, HashMap<String, String[]> params) {
        for (String key : params.keySet()) {
            String before = url.substring(0, url.indexOf(key));
            int endIndex = before.length() + key.length() + 1 + params.get(key)[0].replace("|", "%7C").length() + 1;
            if (endIndex < url.length()){
                url = before + url.substring(endIndex);
            } else {
                url = before.substring(0, before.length() - 1);
            }
        }
        return url;
    }

    // public ArrayList<IBaseResource> getRelevantResources(IResourceProvider rp) {
    //     SearchParameterMap spm = new SearchParameterMap();
    //     spm.addInclude(new Include("HealthcareService:location"));
    //     int maxPageSize = HapiProperties.getMaximumPageSize();
    //     spm.setCount(maxPageSize);
    //     spm.setLoadSynchronous(true);

    //     SimpleBundleProvider bp = (SimpleBundleProvider) rp.getDao().search(spm);

    //     ArrayList<IBaseResource> retVal = new ArrayList<IBaseResource>();
    //     retVal.addAll(bp.getResources(0, bp.size()));
    //     return retVal;
    // }
}
