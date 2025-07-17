package covia.venue.api.model;

import io.javalin.openapi.OpenApiByFields;

/**
 * Result of invoking a job
 */
@OpenApiByFields
public class InvokeResult {
	String id;
	String status;
	Object output;
}
