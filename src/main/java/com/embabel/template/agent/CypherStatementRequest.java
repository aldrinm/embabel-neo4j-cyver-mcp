package com.embabel.template.agent;


import java.util.Map;

public record CypherStatementRequest(String cypher, Map<String, Object> params) {
}
