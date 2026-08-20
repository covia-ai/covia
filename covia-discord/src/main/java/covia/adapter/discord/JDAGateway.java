package covia.adapter.discord;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;

/** JDA-backed Discord Gateway connection. */
final class JDAGateway implements DiscordGateway {
	private final JDA jda;
	private JDAGateway(JDA jda){this.jda=jda;}
	static DiscordGateway connect(String token, Consumer<InboundMessage> sink) throws Exception {
		ListenerAdapter listener=new ListenerAdapter(){
			@Override public void onMessageReceived(MessageReceivedEvent e){
				if(e.getAuthor().isBot()||e.getAuthor().equals(e.getJDA().getSelfUser())) return;
				Message m=e.getMessage(); boolean guild=e.isFromGuild();
				sink.accept(new InboundMessage(m.getId(),e.getChannel().getId(),e.getChannelType().name(),
					e.getChannel().getName(),guild?e.getGuild().getId():null,guild?e.getGuild().getName():null,
					e.getAuthor().getId(),e.getAuthor().getName(),e.getAuthor().getGlobalName(),m.getContentRaw(),
					!guild,m.getMentions().isMentioned(e.getJDA().getSelfUser()),attachments(m)));
			}
		};
		JDA jda=JDABuilder.createLight(token, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES,
			GatewayIntent.MESSAGE_CONTENT).addEventListeners(listener).build();
		try { jda.awaitReady(); return new JDAGateway(jda); }
		catch(Exception e){jda.shutdownNow(); throw e;}
	}
	private static AVector<ACell> attachments(Message message){
		AVector<ACell> out=Vectors.empty();
		for(Message.Attachment a:message.getAttachments()){
			AMap<AString,ACell> item=Maps.of("id",Strings.create(a.getId()),"filename",Strings.create(a.getFileName()),
				"url",Strings.create(a.getUrl()),"proxy_url",Strings.create(a.getProxyUrl()),
				"size",CVMLong.create(a.getSize()),"spoiler",CVMBool.create(a.isSpoiler()));
			if(a.getContentType()!=null)item=item.assoc(Strings.intern("content_type"),Strings.create(a.getContentType()));
			out=out.conj(item);
		}
		return out;
	}
	@Override public String botId(){return jda.getSelfUser().getId();}
	@Override public String username(){return jda.getSelfUser().getName();}
	@Override public void close(){jda.shutdownNow(); try{jda.awaitShutdown(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
