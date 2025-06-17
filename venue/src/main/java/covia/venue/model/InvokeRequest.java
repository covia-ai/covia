package covia.venue.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class InvokeRequest {
	String operation;
	Object input;
}
