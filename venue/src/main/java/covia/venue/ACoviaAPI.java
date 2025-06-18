package covia.venue;

import static j2html.TagCreator.head;
import static j2html.TagCreator.link;
import static j2html.TagCreator.title;

import j2html.tags.DomContent;

public abstract class ACoviaAPI  {

	/**
	 * Make a generic HTTP header
	 * @param title
	 * @return
	 */
	protected DomContent makeHeader(String title) {
		return head(
			title(title), 
			link().withRel("stylesheet").withHref("/css/pico.min.css")
		);
	}
}
