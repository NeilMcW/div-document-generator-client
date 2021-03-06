package uk.gov.hmcts.reform.divorce.documentgenerator.functionaltest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.internal.util.reflection.Whitebox;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.divorce.documentgenerator.DocumentGeneratorApplication;
import uk.gov.hmcts.reform.divorce.documentgenerator.domain.request.GenerateDocumentRequest;
import uk.gov.hmcts.reform.divorce.documentgenerator.domain.response.FileUploadResponse;
import uk.gov.hmcts.reform.divorce.documentgenerator.domain.response.GeneratedDocumentInfo;
import uk.gov.hmcts.reform.divorce.documentgenerator.service.TemplateManagementService;
import uk.gov.hmcts.reform.divorce.documentgenerator.service.impl.DocumentManagementServiceImpl;
import uk.gov.hmcts.reform.divorce.documentgenerator.service.impl.PDFGenerationServiceImpl;

import java.text.SimpleDateFormat;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = DocumentGeneratorApplication.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PropertySource(value = "classpath:application.yml")
@TestPropertySource(properties = {"endpoints.health.time-to-live=0",
        "service-auth-provider.service.stub.enabled=false",
        "evidence-management-api.service.stub.enabled=false"})
@AutoConfigureMockMvc
public class DocumentGenerateAndStoreE2ETest {
    private static final String API_URL = "/version/1/generatePDF";
    private static final String CURRENT_DATE_KEY = "current_date";
    private static final String DATE_FORMAT = "yyyy-MM-dd'T'hh:mm:ss.SSS";

    @Autowired
    private MockMvc webClient;

    @Value("${service.pdf-service.uri}")
    private String pdfServiceUri;

    @Value("${service.evidence-management-client-api.uri}")
    private String emClientAPIUri;

    @MockBean
    private AuthTokenGenerator serviceTokenGenerator;

    @Autowired
    private PDFGenerationServiceImpl pdfGenerationService;

    @Autowired
    private TemplateManagementService templateManagementService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private DocumentManagementServiceImpl documentManagementService;

    private MockRestServiceServer mockRestServiceServer;

    @Before
    public void before() {
        mockRestServiceServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    public void givenTemplateNameIsNull_whenGenerateAndStoreDocument_thenReturnHttp400() throws Exception {
        final String template = null;
        final Map<String, Object> values = Collections.emptyMap();

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void givenTemplateNameIsBlank_whenGenerateAndStoreDocument_thenReturnHttp400() throws Exception {
        final String template = "  ";
        final Map<String, Object> values = Collections.emptyMap();

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void givenTemplateNotFound_whenGenerateAndStoreDocument_thenReturnHttp400() throws Exception {
        final String template = "nonExistingTemplate";
        final Map<String, Object> values = Collections.emptyMap();

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    public void givenCouldNotConnectToAuthService_whenGenerateAndStoreDocument_thenReturnHttp503() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        when(serviceTokenGenerator.generate()).thenThrow(new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    public void givenAuthServiceReturnAuthenticationError_whenGenerateAndStoreDocument_thenReturnHttp401() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        when(serviceTokenGenerator.generate()).thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void givenObjectMapperThrowsException_whenGenerateAndStoreDocument_thenReturnHttp500() throws Exception {
        final ObjectMapper objectMapper = mock(ObjectMapper.class);

        ReflectionTestUtils.setField(pdfGenerationService, "objectMapper", objectMapper);

        final String template = "testtemplate";
        final Map<String, Object> values = new HashMap<>();
        values.put("someKey", "someValue");
        final String securityToken = "securityToken";
        final Instant instant = Instant.now();
        mockAndSetClock(instant);

        final Map<String, Object> valuesWithDate = new HashMap<>(values);
        valuesWithDate.put(CURRENT_DATE_KEY, new SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
                .format(Date.from(instant)));

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        final GenerateDocumentRequest requestToPDFService =
                new GenerateDocumentRequest(new String(templateManagementService.getTemplateByName(template)),
                        valuesWithDate);

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);
        when(objectMapper.writeValueAsString(requestToPDFService)).thenThrow(mock(JsonProcessingException.class));

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenCouldNotConnectToPDFService_whenGenerateAndStoreDocument_thenReturnHttp503() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.BAD_REQUEST, new byte[]{1});

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenPDFServiceReturnNon2xxStatus_whenGenerateAndStoreDocument_thenReturnSameStatus() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.INTERNAL_SERVER_ERROR, new byte[]{1});

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenPDFServiceReturnNull_whenGenerateAndStoreDocument_thenReturn400() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.OK, null);

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenCouldNotConnectToEMClientAPI_whenGenerateAndStoreDocument_thenReturn503() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.OK, new byte[]{1});
        mockEMClientAPI(HttpStatus.BAD_REQUEST, null);

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenEMClientAPIReturnNon2xxStatus_whenGenerateAndStoreDocument_thenReturnSameStatus() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.OK, new byte[]{1});
        mockEMClientAPI(HttpStatus.UNAUTHORIZED, null);

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenEMClientAPIReturnDataContainsNon200Status_whenGenerateAndStoreDocument_thenReturn500() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final FileUploadResponse fileUploadResponse = new FileUploadResponse(HttpStatus.INTERNAL_SERVER_ERROR);

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        mockPDFService(HttpStatus.OK, new byte[]{1});
        mockEMClientAPI(HttpStatus.OK, Collections.singletonList(fileUploadResponse));

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError());

        mockRestServiceServer.verify();
    }

    @Test
    public void givenAllGoesWell_whenGenerateAndStoreDocument_thenReturn() throws Exception {
        final String template = "testtemplate";
        final Map<String, Object> values = Collections.emptyMap();
        final String securityToken = "securityToken";

        final GenerateDocumentRequest generateDocumentRequest = new GenerateDocumentRequest(template, values);

        final String fileURL = "fileURL";
        final String mimeType = "mimeType";
        final String createdOn = "createdOn";
        final String createdBy = "createdBy";

        final FileUploadResponse fileUploadResponse = new FileUploadResponse(HttpStatus.OK);
        fileUploadResponse.setFileUrl(fileURL);
        fileUploadResponse.setMimeType(mimeType);
        fileUploadResponse.setCreatedOn(createdOn);
        fileUploadResponse.setCreatedBy(createdBy);

        final GeneratedDocumentInfo generatedDocumentInfo = new GeneratedDocumentInfo();
        generatedDocumentInfo.setUrl(fileURL);
        generatedDocumentInfo.setMimeType(mimeType);
        generatedDocumentInfo.setCreatedOn(createdOn);

        mockPDFService(HttpStatus.OK, new byte[]{1});
        mockEMClientAPI(HttpStatus.OK, Collections.singletonList(fileUploadResponse));

        when(serviceTokenGenerator.generate()).thenReturn(securityToken);

        MvcResult result = webClient.perform(post(API_URL)
                .content(ObjectMapperTestUtil.convertObjectToJsonString(generateDocumentRequest))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(ObjectMapperTestUtil.convertObjectToJsonString(generatedDocumentInfo),
                result.getResponse().getContentAsString());

        mockRestServiceServer.verify();
    }

    private void mockAndSetClock(Instant instant) {
        final Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(instant);

        Whitebox.setInternalState(documentManagementService, "clock", clock);
    }

    private void mockPDFService(HttpStatus expectedResponse, byte[] body) {
        mockRestServiceServer.expect(once(), requestTo(pdfServiceUri)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(expectedResponse)
                        .body(ObjectMapperTestUtil.convertObjectToJsonBytes(body))
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private void mockEMClientAPI(HttpStatus expectedResponse, List<FileUploadResponse> fileUploadResponse) {
        mockRestServiceServer.expect(once(), requestTo(emClientAPIUri)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(expectedResponse)
                        .body(ObjectMapperTestUtil.convertObjectToJsonString(fileUploadResponse))
                        .contentType(MediaType.APPLICATION_JSON));
    }
}