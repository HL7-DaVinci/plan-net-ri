package ca.uhn.fhir.jpa.starter;

import java.util.Map;
import java.util.HashMap;

import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;

public class NearQueryInterceptor extends InterceptorAdapter {
    @Override
    public void incomingRequestPreHandled(RestOperationTypeEnum theOperation, 
            ActionRequestDetails theProcessedRequest) {
        RequestDetails rd = theProcessedRequest.getRequestDetails();
        String url = rd.getCompleteUrl();
        String newUrl = url;
        int nearStartI = url.indexOf("_near");
        if (nearStartI != -1) {
            int nearEndI = -1;
            for (int i = nearStartI; i < url.length(); i++){
                if (url.charAt(i) == '&') {
                    nearEndI = i;
                    break;
                }
            }
            newUrl = url.substring(0, nearStartI);
            if (nearEndI != -1) newUrl += url.substring(nearEndI);
        }
        rd.setCompleteUrl(newUrl);

        Map<String, String[]> oldParams = rd.getParameters(); //unmodifiable
        HashMap<String, String[]> params = new HashMap<String, String[]>(oldParams);
        params.remove("_near");
        rd.setParameters(params);
    }
}
