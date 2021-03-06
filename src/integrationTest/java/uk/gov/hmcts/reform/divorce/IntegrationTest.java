package uk.gov.hmcts.reform.divorce;

import io.restassured.response.Response;
import net.serenitybdd.junit.spring.integration.SpringIntegrationMethodRule;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import uk.gov.hmcts.reform.authorisation.ServiceAuthorisationApi;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;

import static net.serenitybdd.rest.SerenityRest.given;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {ServiceContextConfiguration.class})
@EnableFeignClients(basePackageClasses = ServiceAuthorisationApi.class)
public abstract class IntegrationTest {

    private static final String CITIZEN_USER_NAME = "CaseWorkerTest";
    private static final String CITIZEN_USER_PASSWORD = "password";


    @Value("${divorce.document.generator.uri}")
    private String divDocumentGeneratorURI;

    @Value("${document.management.store.baseUrl}")
    private String documentManagementURL;

    @Autowired
    private AuthTokenGenerator authTokenGenerator;

    @Autowired
    private IDAMUtils idamTestSupportUtil;

    @Rule
    public SpringIntegrationMethodRule springMethodIntegration;

    private static String userToken = null;

    public IntegrationTest() {
        this.springMethodIntegration = new SpringIntegrationMethodRule();
    }

    Response readDataFromEvidenceManagement(String uri) {
        getUserToken();
        return EvidenceManagementUtil.readDataFromEvidenceManagement(uri, authTokenGenerator.generate(), "CaseWorkerTest");
    }

    Response callDivDocumentGenerator(String requestBody) {
        return given()
            .contentType("application/json")
            .header("Authorization", getUserToken())
            .body(requestBody)
            .when()
            .post(divDocumentGeneratorURI)
            .andReturn();
    }

    //this is a hack to make this work with the docker container
    String getDocumentStoreURI(String uri) {
        if (uri.contains("document-management-store:8080")) {
            return uri.replace("http://document-management-store:8080", documentManagementURL);
        }

        return uri;
    }

    private synchronized String getUserToken(){
        if(userToken == null){
            idamTestSupportUtil.createDivorceCaseworkerUserInIdam(CITIZEN_USER_NAME, CITIZEN_USER_PASSWORD);

            userToken = idamTestSupportUtil.generateUserTokenWithNoRoles(CITIZEN_USER_NAME, CITIZEN_USER_PASSWORD);
        }

        return userToken;
    }
}
