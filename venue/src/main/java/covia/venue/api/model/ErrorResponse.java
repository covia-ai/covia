package covia.venue.api.model;

import io.javalin.openapi.OpenApiByFields;

/** OpenAPI shape of the REST error body: RFC 9457 problem details (see ApiErrors). */
@OpenApiByFields
public class ErrorResponse {
	String title;
	int status;
	String detail;
	String error;
}
