package covia.venue.api.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class InvokeRequest {
	String operation;
	Object input;
}
