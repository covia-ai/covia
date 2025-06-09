package covia.venue.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class InvokeResult {
	String id;
	String status;
	Object outputs;
}
