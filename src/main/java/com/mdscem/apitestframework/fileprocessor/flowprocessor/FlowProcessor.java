package com.mdscem.apitestframework.fileprocessor.flowprocessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mdscem.apitestframework.constants.Constant;
import com.mdscem.apitestframework.constants.DirectoryPaths;
import com.mdscem.apitestframework.context.*;
import com.mdscem.apitestframework.fileprocessor.filereader.FlowContentReader;
import com.mdscem.apitestframework.fileprocessor.filereader.model.TestCase;
import com.mdscem.apitestframework.fileprocessor.validator.TestCaseReplacer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Component responsible for processing flow definitions,
 * replacing placeholders, and generating complete test cases.
 */
@Component
public class FlowProcessor {
    private static final Logger logger = LogManager.getLogger(FlowProcessor.class);
    @Autowired
    private FlowContentReader flowContentReader;
    @Autowired
    private FlowContext flowContext;
    @Autowired
    private TestCaseReplacer testCaseReplacer;
    @Autowired
    private TestCaseRepository flowRepository;
    @Autowired
    private TestCaseRepository testCaseRepository;
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Processes flow files from the specified directory and generates complete test cases.
     *
     * @throws IOException if there is an issue reading files or processing test cases.
     *
     */

    public FlowContext flowProcess() throws IOException {
        // Directory containing flow definitions
        Path flowPathDir = Paths.get(DirectoryPaths.FLOWS_DIRECTORY);
        // Retrieve a list of flow file paths
        List<Path> flowPaths = flowContentReader.getFlowFilesFromDirectory(flowPathDir);

        for (Path flowPath : flowPaths) {
            try {
                Flow flow = new Flow();
                String flowFileName;
                ArrayList<TestCase> flowContentTestCaseList = new ArrayList<>();
                Set<String> uniqueTestCaseNames = new HashSet<>();

                // Get the file name of the current flow file
                flowFileName = String.valueOf(flowPath.getFileName());
                // Read the flow content into a list of JsonNodes
                List<JsonNode> flowContentList = flowContentReader.getFlowContentAsJsonNodes(flowPath);

                for (JsonNode flowTestCase : flowContentList) {

                    // Extract the test case name
                    String testCaseName = flowTestCase.get(Constant.TESTCASE).get(Constant.TESTCASE_NAME).asText();
                    // Check for duplicate test case names
                    if (!uniqueTestCaseNames.add(testCaseName)) {
                        throw new IllegalArgumentException("Duplicate test case name '" + testCaseName
                                + "' found in flow file: " + flowFileName);
                    }

                    TestCase testCase = flowContentReader.readTestCase(testCaseName);
                    testCaseRepository.save(testCaseName, testCase);
                    TestCase completeTestCase = testCaseReplacer.replaceTestCaseWithFlowData(testCase, flowTestCase);
                    flowContentTestCaseList.add(completeTestCase);
                }

                // Update the flow object with content and test cases
                flow.setFlowContentList(flowContentList);
                flow.setTestCaseArrayList(flowContentTestCaseList);

                // Add the flow to the context map using the file name as the key
                flowRepository.save(flowFileName, flow);

            } catch (Exception e) {
                // Handle exceptions and log the stack trace for troubleshooting
                logger.error("Error processing flow: " + flowPath, e);
            }
        }

        // Log the resulting data for debugging and validation
        logger.debug("{}", logger.isDebugEnabled() ? processFlows() : "");
        logger.debug("FlowObjectMap data: {}", objectMapper.writeValueAsString(flowContext.getFlowMap()));
        logger.debug("TestCaseMap data: {}", objectMapper.writeValueAsString(flowContext.getTestCaseMap()));
        return flowContext;
    }

    public Object processFlows() {
        // Iterate through each flow
        for (Map.Entry<String, Flow> flowEntry : flowContext.getFlowMap().entrySet()) {
            Flow flow = flowEntry.getValue();
            System.out.println("Processing flow: " + flowEntry.getKey());
        }
        return null;
    }
}