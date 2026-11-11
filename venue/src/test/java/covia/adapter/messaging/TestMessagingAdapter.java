package covia.adapter.messaging;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.venue.AdapterState;
import covia.venue.RequestContext;

/**
 * An in-memory messaging adapter for testing {@link AMessagingAdapter}'s
 * registry and lifecycle: bots record whether they run, and nothing leaves
 * the venue. Each test gives it a name of its own, so tests can share a venue.
 */
class TestMessagingAdapter extends AMessagingAdapter<TestMessagingAdapter.Spec, TestMessagingAdapter.Bot> {

	record Spec(String name, String userRef) implements MessagingBotSpec {
		@Override public String agent() { return null; }
		@Override public String operation() { return "v/test/ops/echo"; }
		@Override public ACell reply() { return null; }
	}

	static class Bot implements MessagingBot<Spec> {
		final Spec spec;
		final Managed managed;
		final AdapterState state;
		boolean running;
		Bot(TestMessagingAdapter adapter, Spec spec, Managed managed) {
			this.spec = spec; this.managed = managed;
			state = adapter.state(spec.userDID(adapter.engine));
		}
		@Override public Spec spec() { return spec; }
		@Override public Managed managed() { return managed; }
		@Override public void start() { running = true; }
		@Override public void stop() { running = false; }
		@Override public AMap<AString, ACell> status() { return Maps.of("running", running); }
	}

	private final String name;
	private final AString owner;
	Runnable creating = () -> { };
	Runnable deleting = () -> { };

	TestMessagingAdapter(String name, AString owner) {
		this.name = name; this.owner = owner;
	}

	@Override public String getName() { return name; }
	@Override public String getDescription() { return "In-memory messaging lifecycle test"; }
	@Override protected Spec parseBot(String name, ACell settings, boolean strict) {
		return new Spec(name, RT.getIn(settings, Strings.create("user")).toString());
	}
	@Override protected Bot newBot(Spec spec, Managed managed) {
		Bot bot = new Bot(this, spec, managed);
		creating.run();
		return bot;
	}
	@Override protected boolean configurationMatches(Bot runner) { return true; }
	@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		return CompletableFuture.completedFuture(handleCreate(ctx, input));
	}
	/** Every adapter on the venue hears of every deletion: another test's account is none of this one's business. */
	@Override public void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
		if (!did.equals(owner)) return;
		deleting.run();
		super.onUserDeleted(did, previousUser);
	}

	/** Declares operator bots of the test's owner, by name. */
	void configureOwner(String... names) {
		Map<String, Spec> specs = new java.util.LinkedHashMap<>();
		for (String bot : names) specs.put(bot, new Spec(bot, owner.toString()));
		configureBots(specs);
	}
	void create(AString user) { handleCreate(RequestContext.of(user), Maps.of("name", "runtime", "token", "s/token")); }
	void delete(AString user) { handleDelete(RequestContext.of(user), Maps.of("name", "runtime")); }
}
