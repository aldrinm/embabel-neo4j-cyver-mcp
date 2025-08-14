package com.embabel.template.agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.domain.io.UserInput;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import com.embabel.template.agent.cyver.CyverSyntaxValidatonResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.embabel.agent.api.common.PromptRunner;


import java.util.List;
import java.util.Map;
import java.util.Optional;


@Agent(description = "Answers any questions the user may have")
public class GraphAgent {

    private final List<McpSyncClient> mcpSyncClients;
    private final static String CYPHER_VALID = "CYPHER_VALID";
    private final static String CYPHER_NOT_VALID = "CYPHER_NOT_VALID";
    private final static String VALIDATE_CYPHER_NEEDED = "VERIFY_NEEDED";

    public GraphAgent(List<McpSyncClient> mcpSyncClients) {
        this.mcpSyncClients = mcpSyncClients;
    }

    @Action(
        description = "Generates a cypher statement that may be used to answer the user's query"
    )
    public CypherStatementRequest generateCypher(UserInput userInput) {
        var schema = getSchema();
        return PromptRunner
                .usingLlm()
                .createObject(String.format("""
                                Build a cypher query to answer the user's query. 
                                # User query
                                %s
                                
                                Use this database schema: 
                                # Schema
                                %s
                                
                                Return the cypher as a plain string with no markup or triple-quotes
                                """,
                        userInput.getContent(), schema).trim(),
                        CypherStatementRequest.class);
//        return new CypherStatementRequest("MATCH (n:Minifig) return count(n.fig_num)", Map.of());
    }

    private String getSchema() {
        final String mcpClientName = "mcp-neo4j-cypher";
        final String toolName = "get_neo4j_schema";
        Optional<McpSyncClient> mcpNeo4jClient = mcpSyncClients.stream()
                .filter(c -> c.getServerInfo().name().equals(mcpClientName))
                .findAny();
        if (mcpNeo4jClient.isPresent()) {
            var mcpNeo4jClientForReal = mcpNeo4jClient.get();
            Optional<McpSchema.Tool> schemaTool = mcpNeo4jClientForReal.listTools().tools().stream()
                    .filter(t -> t.name().equals(toolName))
                    .findAny();
            if (schemaTool.isPresent()) {
                ToolCallback callback = new SyncMcpToolCallback(mcpNeo4jClientForReal, schemaTool.get());
                String result = callback.call("{}");

                //parse the result string which is in json
                CyverSyntaxValidatonResult parsedResult = null;
                final ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                try {
                    List<Map<String, Object>> listResult = objectMapper.readValue(result, List.class);
                    // Assuming the first element of the list contains the actual data
                    if (!listResult.isEmpty()) {
                        Map<String, Object> dataMap = listResult.get(0);
                        String textValue = (String) dataMap.get("text");
                        //let's not bother parsing this. LLMs are suppossed to work better with JSON isn't it ? ;)
                        return textValue;
                    }
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException("Failed to parse result", ex);
                }
            } else {
                throw new RuntimeException("Could not find an appropriate tool to fetch the scheman");
            }
        } else {
            throw new RuntimeException("Could not find the mcp neo4j client");
        }

        throw new RuntimeException("Could not get the schema. No MCP tools available!");

    }

    @AchievesGoal(description = "Validated cypher with feedback")
    @Action(description = "Validates the given cypher and generates a report")
    public ValidationReport validateCypher(CypherStatementRequest cypherStatementRequest) {
        CyverSyntaxValidatonResult syntaxValidationResult = validateSyntax(cypherStatementRequest.cypher());
        CyverSyntaxValidatonResult schemaValidationResult = null;
        CyverSyntaxValidatonResult propertiesValidationResult = null;

        if (syntaxValidationResult.isValid()) {
            //only bother with these next validations is the syntax is correct
            schemaValidationResult = validateCypherWithSchemaInternal(cypherStatementRequest.cypher());
            propertiesValidationResult = validateCypherProperties(cypherStatementRequest.cypher());
        }
        return new ValidationReport(syntaxValidationResult, schemaValidationResult, propertiesValidationResult);
    }

    private CyverSyntaxValidatonResult validateSyntax(String cypherStatement) {
        return validateCypherWithTool(cypherStatement, "validate_cypher_syntax");
    }

    private CyverSyntaxValidatonResult validateCypherWithSchemaInternal(String cypherStatement) {
        return validateCypherWithTool(cypherStatement, "schema_validator");
    }

    private CyverSyntaxValidatonResult validateCypherProperties(String cypherStatement) {
        return validateCypherWithTool(cypherStatement, "validate_cypher_properties");
    }

    private CyverSyntaxValidatonResult validateCypherWithTool(String cypherStatement, String toolName) {
        Optional<McpSyncClient> cyver = mcpSyncClients.stream().filter(c -> c.getServerInfo().name().equals("cyver")).findAny();
        if (cyver.isPresent()) {
            var cyverClient = cyver.get();
            Optional<McpSchema.Tool> tool = cyverClient.listTools().tools().stream().filter(t -> t.name().equals(toolName)).findAny();
            if (tool.isPresent()) {
                ToolCallback callback = new SyncMcpToolCallback(cyverClient, tool.get());
                String result = callback.call("{\"query\": \"" + cypherStatement + "\"}");

                //parse the result string which is in json
                CyverSyntaxValidatonResult parsedResult = null;
                final ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                try {
                    List<Map<String, Object>> listResult = objectMapper.readValue(result, List.class);
                    // Assuming the first element of the list contains the actual data
                    if (!listResult.isEmpty()) {
                        Map<String, Object> dataMap = listResult.get(0);
                        String textValue = (String) dataMap.get("text");
                        return objectMapper.readValue(textValue, CyverSyntaxValidatonResult.class);
                    }
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException("Failed to parse result", ex);
                }
            } else {
                throw new RuntimeException("Could not find an appropriate tool to validate the cypher in the cyver client");
            }
        } else {
            throw new RuntimeException("Could not find the cyver client");
        }

        throw new RuntimeException("Could not validate the cypher. No MCP tools available!");
    }
}
