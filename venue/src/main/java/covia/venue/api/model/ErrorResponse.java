package covia.venue.api.model;

import io.javalin.openapi.OpenApiByFields;

@OpenApiByFields
public class ErrorResponse {
	String error;
	Object data;
}
