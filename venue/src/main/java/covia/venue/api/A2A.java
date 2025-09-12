package covia.venue.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.api.ContentTypes;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.MapEntry;
import convex.core.data.Maps;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.core.util.Utils;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.server.SseServer;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiExampleProperty;
import io.javalin.openapi.OpenApiResponse;

/**
 * This class implements an A2A (Agent2Agent) server on top of a Covia Venue, as an additional API
 * 
 * Provides agent discovery through the /.well-known/agent-card.json endpoint
 * as specified in the A2A Protocol specification.
 */
public class A2A extends ACoviaAPI {
	
	public static final Logger log=LoggerFactory.getLogger(A2A.class);
	
	final AMap<AString,ACell> AGENT_INFO;

	protected final SseServer sseServer;

	
	public A2A(Engine venue, AMap<AString, ACell> a2aConfig) {
		super(venue);
		this.sseServer=new SseServer(venue);
		
		// Get agent info from config or use defaults
		AMap<AString,ACell> agentInfo = RT.getIn(a2aConfig, "agentInfo");
		
		if (agentInfo==null) agentInfo=Maps.of(
			"name", "covia-venue-agent",
			"title", venue.getName(),
			"version", Utils.getVersion(),
			"description", "Covia Venue Agent - provides computational capabilities through A2A protocol"
		);
		AGENT_INFO=agentInfo;
	}

	
	public void addRoutes(Javalin javalin) {
		// A2A agent card discovery endpoint
		javalin.get("/.well-known/agent-card.json", this::getAgentCard);
	} 
	
	@OpenApi(path = "/.well-known/agent-card.json", 
			methods = HttpMethod.GET, 
			tags = { "A2A"},
			summary = "Get A2A Agent Card for discovery", 
			operationId = "getAgentCard",
			responses = {
					@OpenApiResponse(
							status = "200", 
							description = "Agent Card JSON document", 
							content = {
								@OpenApiContent(
										type = "application/json", 
										from = Object.class,
										exampleObjects = {
											@OpenApiExampleProperty(name = "agentProvider", value = "{}"),
											@OpenApiExampleProperty(name = "agentCapabilities", value = "{}"),
											@OpenApiExampleProperty(name = "agentInterfaces", value = "[]")
										}
										) })
					})	
	protected void getAgentCard(Context ctx) { 
		ctx.header("Content-type", ContentTypes.JSON);
		
		try {
			// Use the existing getExternalBaseUrl function to compute the base URL
			String baseUrl = getExternalBaseUrl(ctx, "");
			
			// Create the agent card
			AMap<AString, ACell> agentCard = createAgentCard(baseUrl);
			
			buildResult(ctx, agentCard);
		} catch (Exception e) {
			log.error("Error generating agent card", e);
			buildError(ctx, 500, "Error generating agent card: " + e.getMessage());
		}
	}

	/**
	 * Create the A2A Agent Card structure according to the specification
	 * @param baseUrl The base URL of this agent
	 * @return Agent Card as Convex Map
	 */
	private AMap<AString, ACell> createAgentCard(String baseUrl) {
		// Agent Provider information
		AMap<AString, ACell> agentProvider = Maps.of(
			Fields.NAME, RT.getIn(AGENT_INFO, Fields.NAME),
			Fields.TITLE, RT.getIn(AGENT_INFO, Fields.TITLE),
			"version", RT.getIn(AGENT_INFO, "version"),
			Fields.DESCRIPTION, RT.getIn(AGENT_INFO, Fields.DESCRIPTION)
		);
		
		// Agent Capabilities
		AMap<AString, ACell> agentCapabilities = Maps.of(
			"streaming", true,
			"pushNotifications", false,
			"supportsAuthenticatedExtendedCard", false
		);
		
		// Agent Skills - based on available operations from adapters
		AVector<AMap<AString, ACell>> skills = getAgentSkills();
		
		// Agent Interfaces - transport endpoints
		AVector<AMap<AString, ACell>> interfaces = Vectors.of(
			Maps.of(
				"transport", "http+json",
				"url", baseUrl + "/api/v1",
				"preferred", true
			),
			Maps.of(
				"transport", "json-rpc",
				"url", baseUrl + "/a2a",
				"preferred", false
			)
		);
		
		// Security Scheme - basic authentication
		AMap<AString, ACell> securityScheme = Maps.of(
			"type", "http",
			"scheme", "bearer",
			"description", "Bearer token authentication"
		);
		
		// Build the complete agent card
		AMap<AString, ACell> agentCard = Maps.of(
			"agentProvider", agentProvider,
			"agentCapabilities", agentCapabilities,
			"agentSkills", skills,
			"agentInterfaces", interfaces,
			"securityScheme", securityScheme,
			"preferredTransport", "http+json",
			"additionalInterfaces", Vectors.of(
				Maps.of(
					"transport", "json-rpc",
					"url", baseUrl + "/a2a"
				)
			)
		);
		
		return agentCard;
	}
	
	/**
	 * Get agent skills based on available operations from adapters
	 * @return Vector of skill objects
	 */
	private AVector<AMap<AString, ACell>> getAgentSkills() {
		AVector<AMap<AString, ACell>> skills = Vectors.empty();
		
		// Iterate through all registered adapters to collect skills
		for (String adapterName : engine.getAdapterNames()) {
			try {
				var adapter = engine.getAdapter(adapterName);
				if (adapter == null) continue;
				
				// Get skills from this specific adapter
				AVector<AMap<AString, ACell>> adapterSkills = getAdapterSkills(adapter);
				skills = skills.concat(adapterSkills);
			} catch (Exception e) {
				log.warn("Error processing adapter " + adapterName, e);
				// ignore this adapter
			}
		}
		
		return skills;
	}
	
	/**
	 * Get A2A skills from a specific adapter's installed assets
	 * @param adapter The adapter to get skills from
	 * @return Vector of A2A skills provided by this adapter
	 */
	private AVector<AMap<AString, ACell>> getAdapterSkills(AAdapter adapter) {
		AVector<AMap<AString, ACell>> skillsVector = Vectors.empty();
		
		try {
			// Get installed assets for this adapter
			Index<Hash, AString> installedAssets = adapter.getInstalledAssets();
			int n = installedAssets.size();
			
			for (int i = 0; i < n; i++) {
				try {
					MapEntry<Hash, AString> me = installedAssets.entryAt(i);
					AString metaString = me.getValue();
					
					// Parse the metadata string to get the structured metadata
					AMap<AString, ACell> meta = RT.ensureMap(JSON.parse(metaString));
					AMap<AString, ACell> skill = checkSkill(meta);
					if (skill != null) {
						skillsVector = skillsVector.conj(skill);
					}
				} catch (Exception e) {
					log.warn("Error processing asset from adapter " + adapter.getName(), e);
					// ignore this asset
				}
			}
		} catch (Exception e) {
			log.warn("Error getting installed assets from adapter " + adapter.getName(), e);
		}
		
		return skillsVector;
	}
	
	/**
	 * Check if an asset metadata represents a valid A2A skill
	 * @param meta Asset metadata
	 * @return Skill object if valid, null otherwise
	 */
	private AMap<AString, ACell> checkSkill(AMap<AString, ACell> meta) {
		AMap<AString, ACell> op = RT.getIn(meta, Fields.OPERATION);
		if (op == null) return null;
		
		AString skillName = RT.ensureString(op.get(Fields.TOOL_NAME));
		if (skillName == null) return null;
		
		// Create skill object according to A2A specification
		AMap<AString, ACell> skill = Maps.of(
			"name", skillName,
			"description", RT.getIn(meta, Fields.DESCRIPTION),
			"category", "computation",
			"inputSchema", RT.getIn(op, Fields.INPUT),
			"outputSchema", RT.getIn(op, Fields.OUTPUT)
		);
		
		return skill;
	}
}
