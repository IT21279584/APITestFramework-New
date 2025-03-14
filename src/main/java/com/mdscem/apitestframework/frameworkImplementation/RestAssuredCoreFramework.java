package com.mdscem.apitestframework.frameworkImplementation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdscem.apitestframework.constants.Constant;
import com.mdscem.apitestframework.requestprocessor.assertion.AssertJExecutor;
import com.mdscem.apitestframework.fileprocessor.filereader.model.TestCase;
import com.mdscem.apitestframework.fileprocessor.filereader.model.Request;
import com.mdscem.apitestframework.requestprocessor.CoreFramework;
import com.mdscem.apitestframework.requestprocessor.authhandling.AuthenticationHandler;
import com.mdscem.apitestframework.requestprocessor.authhandling.AuthenticationHandlerFactory;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Component
public class RestAssuredCoreFramework implements CoreFramework {
    private static final Logger logger = LogManager.getLogger(RestAssuredCoreFramework.class);
    @Autowired
    private AssertJExecutor assertJValidation;
    @Autowired
    private AuthenticationHandlerFactory authenticationHandlerFactory;
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String createFrameworkTypeTestFileAndExecute(TestCase testCase) {
        RequestSpecification requestSpec = buildRequestSpecification(testCase);

        Response response = executeHttpMethod(
                HttpMethod.valueOf(testCase.getRequest().getMethod().toUpperCase()),
                requestSpec,
                testCase.getBaseUri() + testCase.getRequest().getPath()
        );

        logger.info("Response : " + response.prettyPrint());
        // Validate the response, but do not stop execution on failure
        validateResponse(testCase, response);
        return response.asString();
    }

    private RequestSpecification buildRequestSpecification(TestCase testCase) {
        Request request = testCase.getRequest();
        RequestSpecification requestSpec = RestAssured.given();

        // Add headers
        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                // Replace placeholders in header values
                requestSpec.header(header.getKey(), header.getValue());
            }
        }

        // Apply authentication using the handler
        if (testCase.getAuth() != null && !testCase.getAuth().isEmpty()) {
            String type = testCase.getAuth().get(Constant.TYPE);
            AuthenticationHandler authHandler = authenticationHandlerFactory.getAuthenticationHandler(type);
            authHandler.applyAuthentication(requestSpec, testCase.getAuth());
        }

        // Add request body
        if (request.getBody() != null && !request.getBody().isEmpty()) {
            // Replace placeholders in the body
            requestSpec.body(request.getBody());
        }

        // Log request if specified
        if (Constant.ALL.equalsIgnoreCase(request.getLog())) {
            requestSpec.log().all();
        }
        return requestSpec;
    }

    private Response executeHttpMethod(HttpMethod method, RequestSpecification requestSpec, String url) {
        switch (method) {
            case GET:
                return requestSpec.get(url);
            case POST:
                return requestSpec.post(url);
            case PUT:
                return requestSpec.put(url);
            case DELETE:
                return requestSpec.delete(url);
            case PATCH:
                return requestSpec.patch(url);
            default:
                throw new UnsupportedOperationException("Unsupported HTTP method: " + method);
        }
    }

    private void validateResponse(TestCase testCase, Response response) {
        // Validate the response status code
        Assertions.assertThat(response.getStatusCode())
                .as("Status code mismatch")
                .isEqualTo(testCase.getResponse().getStatusCode());

        if (testCase.getResponse().getHeaders() != null) {
            for (Map.Entry<String, String> entry : testCase.getResponse().getHeaders().entrySet()) {
                String expectedHeader = entry.getValue();
                String actualHeader = response.getHeader(entry.getKey());
                Assertions.assertThat(actualHeader)
                        .as("Header %s mismatch", entry.getKey())
                        .isEqualTo(expectedHeader);
            }
        }

        // Validate response cookies
        if (testCase.getResponse().getCookie() != null) {
            for (Map.Entry<String, String> entry : testCase.getResponse().getCookie().entrySet()) {
                String expectedCookie = entry.getValue();
                String actualCookie = response.getCookie(entry.getKey());
                Assertions.assertThat(actualCookie)
                        .as("Cookie %s mismatch", entry.getKey())
                        .isEqualTo(expectedCookie);            }
        }

        // Validate response body using JsonAssert if expected body is provided
        if (testCase.getResponse().getBody() != null) {
            validateResponseBody(testCase, response);
        }

        // Validate logging
        if (Constant.ALL.equalsIgnoreCase(testCase.getResponse().getLog())) {
            response.then().log().all();
        }
    }

    // Helper method to validate the response body
    private void validateResponseBody(TestCase testCase, Response response) {
        try {
            // Convert expected response body to JSON string
            String expectedBody = objectMapper.writeValueAsString(testCase.getResponse().getBody());

            // Parse both expected and actual JSON bodies
            JsonNode expectedJsonNode = objectMapper.readTree(expectedBody);
            JsonNode actualJsonNode = objectMapper.readTree(response.getBody().asString());

            // Validate only the fields mentioned in the TestCase response
            expectedJsonNode.fields().forEachRemaining(entry -> {
                String fieldName = entry.getKey();
                JsonNode expectedValue = entry.getValue();

                // Handle `check` keyword for dynamic validation
                if (expectedValue.isTextual() && expectedValue.asText().startsWith(Constant.START_DOUBLE_CURLY_BRACKET + Constant.CHECK)) {
                    // Extract the method chain for AssertJ
                    String assertJExpression = expectedValue.asText();
                    String methodChain = assertJExpression.substring(assertJExpression.indexOf(Constant.CHECK) + 5, assertJExpression.lastIndexOf(Constant.END_CURLY_BRACKET)).trim();

                    // Prepare the object to assert
                    JsonNode actualFieldValueNode = actualJsonNode.get(fieldName);
                    try {
                        validateWithAssertJ(actualFieldValueNode, methodChain);
                    } catch (Exception e) {
                        throw new AssertionError("Validation failed for field: " + fieldName, e);
                    }
                } else {
                    // Regular field validation
                    JsonNode actualValue = actualJsonNode.get(fieldName);
                    Assertions.assertThat(actualValue)
                            .as("Field %s does not match.", fieldName)
                            .isEqualTo(expectedValue);                }
            });
        } catch (Exception e) {
            throw new RuntimeException("Error during response validation.", e);
        }
    }

    private void validateWithAssertJ(JsonNode actualFieldValueNode, String methodChain) throws Exception {
        AbstractAssert<?, ?> assertion;

        if (actualFieldValueNode.isInt()) {
            assertion = Assertions.assertThat(actualFieldValueNode.asInt());
        } else if (actualFieldValueNode.isLong()) {
            assertion = Assertions.assertThat(actualFieldValueNode.asLong());
        } else if (actualFieldValueNode.isTextual()) {
            assertion = Assertions.assertThat(actualFieldValueNode.asText());
        } else if (actualFieldValueNode.isBoolean()) {
            assertion = Assertions.assertThat(actualFieldValueNode.asBoolean());
        } else if (actualFieldValueNode.isDouble()) {
            assertion = Assertions.assertThat(actualFieldValueNode.asDouble());
        } else if (actualFieldValueNode.isArray()) {
            List<JsonNode> actualValueList = new ArrayList<>();
            actualFieldValueNode.forEach(actualValueList::add);
            assertion = Assertions.assertThat(actualValueList);
        } else {
            throw new IllegalArgumentException("Unsupported type for dynamic validation.");
        }
        assertJValidation.executeAssertions(assertion, methodChain.split(Constant.SPLITTER));
    }
}