package org.wso2.ei.dataservice.integration.test.jira.issues;

import org.json.JSONArray;
import org.json.JSONObject;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.carbon.automation.test.utils.http.client.HttpResponse;
import org.wso2.ei.dataservice.integration.test.DSSIntegrationTest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class DuplicateParameterInQueryTest extends DSSIntegrationTest {

    private final String serviceName = "DuplicateParameterInQuery";
    private String serviceEndPoint;

    @BeforeClass(alwaysRun = true)
    public void serviceDeployment() throws Exception {

        super.init();
        List<File> sqlFileLis = new ArrayList<File>();
        sqlFileLis.add(selectSqlFile("Birds.sql"));
        deployService(serviceName, createArtifact(
                getResourceLocation() + File.separator + "samples" + File.separator + "dbs" + File.separator + "rdbms"
                        + File.separator + "DuplicateParameterInQueryDB.dbs", sqlFileLis));

        serviceEndPoint = getServiceUrlHttp(serviceName) + "/";
    }


    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        deleteService(serviceName);
        cleanup();
    }

    @Test(groups = { "wso2.dss" }, description = "Check whether the null received successfully", alwaysRun = true)
    public void duplicateParameterInQueryTestCase() throws Exception {
        HttpResponse response = this.getHttpResponse(serviceEndPoint + "test?name=Bird1", "application/json");
        JSONObject result = new JSONObject(response.getData());
        assertNotNull(result, "Response is null");
        //Response JSON object will be {"Birds":{"Bird":[{"weight":"30","color":null,"name":"Bird1"}]}}
    }

    /**
     * This method will "Accept" header Types "application/json", etc..
     *
     * @param endpoint    service endpoint
     * @param contentType header type
     * @return HttpResponse
     * @throws Exception
     */
    private HttpResponse getHttpResponse(String endpoint, String contentType) throws Exception {

        if (endpoint.startsWith("http://")) {
            String urlStr = endpoint;
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", contentType);
            conn.setRequestProperty("charset", "UTF-8");
            conn.setReadTimeout(10000);
            conn.connect();
            // Get the response
            StringBuilder sb = new StringBuilder();
            BufferedReader rd = null;
            try {
                rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = rd.readLine()) != null) {
                    sb.append(line);
                }
            } catch (FileNotFoundException ignored) {
            } finally {
                if (rd != null) {
                    rd.close();
                }
            }
            return new HttpResponse(sb.toString(), conn.getResponseCode());
        }
        return null;
    }
}
