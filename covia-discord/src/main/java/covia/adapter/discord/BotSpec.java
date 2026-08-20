package covia.adapter.discord;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Engine;

/** Immutable and validated configuration for one Discord bot. */
public record BotSpec(String name, String tokenRef, String userRef, String agent,
		String operation, ACell reply, Set<String> allowIds, Set<String> allowNames,
		boolean open, boolean mentionOnly, String greeting) {
	static final AString K_TOKEN=Strings.intern("token"), K_USER=Strings.intern("user"),
		K_AGENT=Strings.intern("agent"), K_OPERATION=Strings.intern("operation"),
		K_REPLY=Strings.intern("reply"), K_ALLOW=Strings.intern("allow"),
		K_OPEN=Strings.intern("open"), K_MENTION_ONLY=Strings.intern("mentionOnly"),
		K_GREETING=Strings.intern("greeting");
	private static final Set<AString> KNOWN=Set.of(K_TOKEN,K_USER,K_AGENT,K_OPERATION,K_REPLY,
		K_ALLOW,K_OPEN,K_MENTION_ONLY,K_GREETING);
	static final String PUBLIC_USER="public";

	public BotSpec {
		allowIds=Collections.unmodifiableSet(new LinkedHashSet<>(allowIds));
		allowNames=Collections.unmodifiableSet(new LinkedHashSet<>(allowNames));
	}
	public AString userDID(Engine engine) {
		if (!PUBLIC_USER.equals(userRef)) return Strings.create(userRef);
		if (engine==null || !engine.config().isPublicAccess()) throw new IllegalStateException(
			"bot '"+name+"' acts as public but public access is disabled");
		return Strings.create(engine.getDIDString()+":public");
	}
	public boolean routesToAgent(){return agent!=null;}
	public String target(){return routesToAgent()?"agent "+agent:"operation "+operation;}
	public boolean silent(){return CVMBool.FALSE.equals(reply);}
	public String fixedReply(){return reply instanceof AString s?s.toString():null;}
	public boolean allows(String id,String username,String globalName){
		if(open) return true;
		if(id!=null && allowIds.contains(id)) return true;
		return matches(username)||matches(globalName);
	}
	private boolean matches(String name){return name!=null&&allowNames.contains(name.toLowerCase(Locale.ROOT));}

	static BotSpec parse(String name, ACell cell, boolean strict) {
		String where="adapters.discord.bots."+name;
		if(!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException(where+": bot name must match [A-Za-z0-9_-]+");
		AMap<AString,ACell> m=RT.castMap(cell);
		if(m==null) throw new IllegalArgumentException(where+" must be an object");
		if(strict) for(long i=0;i<m.count();i++) { ACell k=m.entryAt(i).getKey(); if(!(k instanceof AString s)||!KNOWN.contains(s))
			throw new IllegalArgumentException(where+": unknown setting "+k); }
		String token=str(m,K_TOKEN,where), user=str(m,K_USER,where), agent=str(m,K_AGENT,where), op=str(m,K_OPERATION,where);
		if(token==null||token.isBlank()) throw new IllegalArgumentException(where+".token is required (prefer s/NAME)");
		if(user==null||user.isBlank()||(!PUBLIC_USER.equals(user)&&!user.startsWith("did:")))
			throw new IllegalArgumentException(where+".user must be a DID or public");
		if((agent==null)==(op==null)) throw new IllegalArgumentException(where+": exactly one of agent or operation is required");
		if(agent!=null&&agent.isBlank()||op!=null&&op.isBlank()) throw new IllegalArgumentException(where+": handler must not be blank");
		ACell reply=m.get(K_REPLY);
		if(reply!=null) {
			if(agent!=null) throw new IllegalArgumentException(where+".reply only applies to operation handlers");
			if(!(reply instanceof CVMBool)&&!(reply instanceof AString)) throw new IllegalArgumentException(where+".reply must be boolean or string");
			if(CVMBool.TRUE.equals(reply)) reply=null;
		}
		Set<String> ids=new LinkedHashSet<>(), names=new LinkedHashSet<>();
		ACell ac=m.get(K_ALLOW);
		if(ac!=null) {
			AVector<ACell> v=RT.ensureVector(ac); if(v==null) throw new IllegalArgumentException(where+".allow must be an array");
			for(long i=0;i<v.count();i++) { ACell e=v.get(i); String x;
				if(e instanceof CVMLong n) x=Long.toUnsignedString(n.longValue()); else if(e instanceof AString s) x=s.toString().trim();
				else throw new IllegalArgumentException(where+".allow entries must be Discord user ids or usernames");
				if(x.startsWith("@")) x=x.substring(1); if(x.matches("[0-9]+")) ids.add(x); else if(!x.isBlank()) names.add(x.toLowerCase(Locale.ROOT));
			}
		}
		boolean open=bool(m,K_OPEN,false,where), mention=bool(m,K_MENTION_ONLY,true,where);
		return new BotSpec(name,token,user,agent,op,reply,ids,names,open,mention,str(m,K_GREETING,where));
	}
	private static String str(AMap<AString,ACell> m,AString k,String where){ ACell v=m.get(k); if(v==null)return null; if(!(v instanceof AString s))throw new IllegalArgumentException(where+"."+k+" must be a string"); return s.toString(); }
	private static boolean bool(AMap<AString,ACell> m,AString k,boolean d,String where){ ACell v=m.get(k); if(v==null)return d; if(!(v instanceof CVMBool b))throw new IllegalArgumentException(where+"."+k+" must be boolean"); return b.booleanValue(); }
	@Override public String toString(){return "BotSpec["+name+" as "+userRef+" -> "+target()+"]";}
}
