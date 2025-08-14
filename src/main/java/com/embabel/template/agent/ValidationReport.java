package com.embabel.template.agent;

import com.embabel.template.agent.cyver.CyverSyntaxValidatonResult;


public record ValidationReport(
        CyverSyntaxValidatonResult syntaxResult,
        CyverSyntaxValidatonResult schemaResult,
        CyverSyntaxValidatonResult propertiesResult
        ) {
}
