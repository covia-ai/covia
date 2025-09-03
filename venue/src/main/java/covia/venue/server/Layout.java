package covia.venue.server;

import j2html.tags.DomContent;

import static j2html.TagCreator.*;

/**
 * Centralized layout and styling utilities for web pages
 */
public class Layout {
    
    /**
     * Creates a standard page header with title, meta tags, and stylesheets
     * @param title The page title
     * @return DomContent containing the complete head section
     */
    public static DomContent makeHeader(String title) {
		return head(
				title(title),
				meta().attr("description","Covia.ai offers decentralized AI solutions powered by Convex Lattice DLT for secure, scalable applications."),
				meta().attr("keywords","AI, agent, grid, orchestration, decentralised"),
		        link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}

    
    /**
     * Creates a styled box component with consistent styling
     * @param content The content to wrap in the styled box
     * @return DomContent containing the styled box
     */
    public static DomContent styledBox(DomContent... content) {
        return div(content).withStyle(
    			"border: 1px solid #ddd; " +
    					"border-radius: 8px; " +
    					"padding: 1.5rem; " +
    					"margin: 1rem 0; " +
    					"background-color: #091929; " +
    					"box-shadow: 0 2px 4px rgba(0,0,0,0.1);"
    				);
    }
    
    /**
     * Creates a standard page with header and body
     * @param title The page title
     * @param bodyContent The body content
     * @return DomContent containing the complete HTML page
     */
    public static DomContent standardPage(String title, DomContent bodyContent) {
        return html(
            makeHeader(title),
            body(bodyContent)
        );
    }
}
