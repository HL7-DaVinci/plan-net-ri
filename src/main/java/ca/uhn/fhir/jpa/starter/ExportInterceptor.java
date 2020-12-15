package ca.uhn.fhir.jpa.starter;
import ca.uhn.fhir.rest.server.interceptor.*;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import java.util.*;
import java.io.*;
import ca.uhn.fhir.jpa.starter.TaskHandler;
import ca.uhn.fhir.jpa.starter.PatientFinder;

import ca.uhn.fhir.jpa.starter.JSONWrapper;

public class ExportInterceptor extends InterceptorAdapter {

   /**
    * Override the incomingRequestPostProcessed method, which is called
    * for each incoming request before any processing is done
    */
   @Override
   public boolean incomingRequestPreProcessed(HttpServletRequest theRequest, HttpServletResponse theResponse) {
     String endPoint = theRequest.getRequestURL().substring(theRequest.getRequestURL().lastIndexOf("/")+1);
     if (endPoint.equals("$export")) {
         try {
            handleExport(theResponse);
         } catch (Exception e) {System.out.println("Exception: " + e.getMessage());}
         return false;
     }
      return true;
   }
   public void handleExport(HttpServletResponse theResponse) throws IOException {
       theResponse.setStatus(200);
       PrintWriter out = theResponse.getWriter();
       theResponse.setContentType("application/json");
       theResponse.setCharacterEncoding("UTF-8");
       String exportString = "";
       out.print(exportString);
       out.flush();
   }

}
