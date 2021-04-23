package functions;

import io.quarkus.funqy.Context;
import io.quarkus.funqy.Funq;
import io.quarkus.funqy.knative.events.CloudEvent;
import io.quarkus.funqy.knative.events.CloudEventMapping;
import io.quarkus.funqy.knative.events.CloudEventBuilder;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.json.JsonObject;

import javax.inject.Inject;
import java.net.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.text.*;

import org.uth.summit.utils.*;

public class AttackEvent 
{
    private static final int DEFAULT_DESTROYED_SCORE = 100;
    private static final int DEFAULT_HIT_SCORE = 5;
    private long start = System.currentTimeMillis();

    @ConfigProperty(name = "WATCHMAN")
    String _watchmanURL;

    @ConfigProperty(name = "SCORINGSERVICE")
    String _scoringServiceURL;

    @ConfigProperty(name = "PRODMODE")
    String _prodmode;

    @Funq
    public CloudEvent<MessageOutput> processor( String input )  
    {
      MessageOutput output = buildResponse( input );
      String eventName = ( output.getHostname() == null ? "attackprocessed" : "attackprocessed-" + output.getHostname() );

      return CloudEventBuilder.create()
        .id(output.getGame() + ":" + output.getMatch())
        .type(eventName)
        .build(output);      
    }
 
    public MessageOutput buildResponse( String input )
    {
      // Setup Watchman
      Watchman watchman = new Watchman( _watchmanURL );

      //Process the payload
      try
      {
        // Build a return packet
        MessageOutput output = new MessageOutput();

        JsonObject message = new JsonObject(input);

        String game = message.getString("game");
        String match = message.getString("match");
        boolean hit = message.getBoolean("hit");
        Long ts = message.getLong("ts");
        JsonObject by = message.getJsonObject("by");
        String uuid = by.getString("uuid");
        String username = by.getString("username");
        boolean human = by.getBoolean("human");
        Integer shotCount = by.getInteger("shotCount");
        Integer consecutiveHits = by.getInteger("consecutiveHitsCount");
        String destroyed = message.getString("destroyed");
        String hostname = message.getString("hostname");
  
        // Watchman
        if( _prodmode.equals("dev"))
        {
          LocalDateTime now = LocalDateTime.now();

          boolean watched = watchman.inform( "[ATTACK] (" + now.toString() +"):" + match + " game:" + game + " hit:" + hit + " uuid:" + uuid + " human:" + human + " destroyed: " + ( destroyed == null ? "false" : destroyed ));
        
          // Log for verbosity :-) 
          System.out.println( "  Game: " + game );
          System.out.println( "  Match: " + match );
          System.out.println( "  UUID: " + uuid );
          System.out.println( "  Hostname: " + hostname );
          System.out.println( "  Hit: " + hit );
          System.out.println( "  Username: " + username );
          System.out.println( "  TS: " + ts );
          System.out.println( "  Human: " + human );
          System.out.println( "  ShotCount: " + shotCount );
          System.out.println( "  ConsecutiveHits: " + consecutiveHits );
          System.out.println( "  Destroyed: " + destroyed );
        }

        // Replace spaces in the username for URL transmission
        username = username.replaceAll(" ", "%20");

        // Build SHOTS rest URL here as we have all info
        // Format /shot/{game}/{match}/{user}/{ts}?type=[HIT,MISS,SUNK]&human={human}[&ship=(ship type)]
        String type = ( !hit ? "MISS" : ( destroyed != null ? "SUNK" : "HIT"));
        String compositeShotsURL = _scoringServiceURL + "shot/" + game + "/" + match + "/" + uuid + "/" + ts + "?type=" + type + "&human=" + human + ( destroyed != null ? "&ship=" + destroyed.toUpperCase() : "" );

        // Update the SHOTS cache
        Postman postman = new Postman( compositeShotsURL );
        if( !( postman.deliver("dummy")))
        {
          System.out.println( "Failed to update SHOTS cache");
        }

        int delta = 0;

        // *If* we hit emit a score event for game server and scoring service cache
        if( hit )
        {
          // If we haven't destroyed anything just increment the score using the HIT_SCORE if it exists
          if( destroyed == null )
          {
            String envValue = System.getenv("HIT_SCORE");
            delta = ( envValue == null ? DEFAULT_HIT_SCORE : Integer.parseInt(envValue) );
          }
          else
          {
            // Otherwise we destroyed something; use (type)[uppercased]_SCORE instead
            String targetShipENV = destroyed.toUpperCase() + "_SCORE";
            String envValue = System.getenv(targetShipENV);

            delta = ( envValue == null ? DEFAULT_DESTROYED_SCORE : Integer.parseInt(envValue) );
          }

          // Post to Scoring Service
          String compositePostURL = _scoringServiceURL + "scoring/" + game + "/" + match + "/" + uuid + "?delta=" + delta + "&human=" + human + "&username=" + username + "&timestamp=" + ts;

          postman = new Postman( compositePostURL );
          if( !( postman.deliver("dummy")))
          {
            System.out.println( "Failed to update Scoring Service");
          }

          // TIMESTAMP DEBUG
          DateFormat formatter = new SimpleDateFormat("HH:mm:ss" ); 
          String sent = formatter.format( new Date( ts ));
          System.out.println( "(Timing) Recv: " + LocalTime.now() + " Sent: " + sent );
        }

        output.setGame(game);
        output.setMatch(match);
        output.setUuid(uuid);
        output.setHostname( hostname );
        output.setTs(ts);
        output.setDelta(Integer.valueOf(delta));
        output.setHuman(human);

        return output;
      }
      catch( Exception exc )
      {
        System.out.println("Failed to parse JSON due to " + exc.toString());
        return null;
      }
    }
}
