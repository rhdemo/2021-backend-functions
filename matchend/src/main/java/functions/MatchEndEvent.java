package functions;

import io.quarkus.funqy.Context;
import io.quarkus.funqy.Funq;
import io.quarkus.funqy.knative.events.CloudEvent;
import io.quarkus.funqy.knative.events.CloudEventMapping;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.Vertx;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.vertx.core.json.JsonObject;

import java.time.LocalDateTime;
import javax.inject.Inject;

import org.uth.summit.utils.*;

public class MatchEndEvent 
{
    @Inject
    Vertx vertx;

    @ConfigProperty(name = "WATCHMAN")
    String _watchmanURL;

    @ConfigProperty(name = "SCORINGSERVICE")
    String _scoringServiceURL;

    @ConfigProperty(name = "PRODMODE")
    String _prodmode;

    @Funq
    @CloudEventMapping(responseType = "matchendprocessed")
    //public Uni<MessageOutput> function( Input input, @Context CloudEvent cloudEvent)
    public Uni<MessageOutput> function( String input, @Context CloudEvent cloudEvent)
    {
      return Uni.createFrom().emitter(emitter -> 
      {
        buildResponse(input, cloudEvent, emitter);
      });    
    }
 
    public void buildResponse( String input, CloudEvent cloudEvent, UniEmitter<? super MessageOutput> emitter )
    {
      // Setup Watchman
      Watchman watchman = new Watchman( _watchmanURL );

      System.out.println("Match End Event Received..." );

      //Process the payload
      try
      {
        // Build a return packet
        MessageOutput output = new MessageOutput();

        JsonObject message = new JsonObject(input);

        String game = message.getString("game");
        String match = message.getString("match");
        JsonObject winner = message.getJsonObject("winner");
        JsonObject loser = message.getJsonObject("loser");
        String winnerUsername = winner.getString("username");
        String winnerUuid = winner.getString("uuid");
        boolean winnerHuman = winner.getBoolean("human");
        String loserUsername = loser.getString("username");
        String loserUuid = loser.getString("uuid");
        boolean loserHuman = loser.getBoolean("human");
  
        // Watchman
        if( _prodmode.equals("dev"))
        {
          LocalDateTime now = LocalDateTime.now();
          boolean watched = watchman.inform( "[MATCH-END] (" + now.toString() + ") match:" + match + " game:" + game + " Winner: " + winnerUsername + " " + ( winnerHuman ? "(HUME)" : "(BOTTY)" ) + " Loser: " + loserUsername + " " + ( loserHuman ? "(HUME)" : "(BOTTY)") );
        }

        // Log for verbosity :-) 
        System.out.println( "  Game: " + game );
        System.out.println( "  Match: " + match );
        System.out.println( "    WINNER: " + winnerUsername + " " + ( winnerHuman ? "(HUME)" : "(BOTTY)" ) );
        System.out.println( "    LOSER: " + loserUsername + " " + ( loserHuman ? "(HUME)" : "(BOTTY)") );

        Postman postman = null;
        
        // Award the winner a set amount of points for being the victor
        String envValue = System.getenv("WIN_SCORE");

        int winDelta = ( envValue == null ? 200 : Integer.parseInt(envValue));

        String compositeScoreURL = _scoringServiceURL + "scoring/" + game + "/" + match + "/" + winnerUuid + "?delta=" + winDelta + "&human=" + winnerHuman + "&username=" + winnerUsername + "&timestamp=" + System.currentTimeMillis();

        postman = new Postman( compositeScoreURL);
        if( !( postman.deliver("dummy")))
        {
          System.out.println( "Failed to add winner score for " + winnerUsername );
          System.out.println( " Failed URL - " + compositeScoreURL );
        }
        else
        {
          System.out.println( "Rewarded " + winnerUsername + " " + winDelta + " for the win...");
        }

        // Build WIN/LOSS rest URL here as we have all info
        // Format /scoring/(game)/(match)/(uuid)/win?timestamp
        // Format /scoring/(game)/(match)/(uuid)/loss?timestamp
        String compositeWinURL = _scoringServiceURL + "scoring/" + game + "/" + match + "/" + winnerUuid + "/win?" + System.currentTimeMillis();
        String compositeLoseURL = _scoringServiceURL + "scoring/" + game + "/" + match + "/" + loserUuid + "/loss?" + System.currentTimeMillis();

        // Update the WIN/LOSS cache
        postman = new Postman( compositeWinURL );
        if( !( postman.deliver("dummy")))
        {
          System.out.println( "Failed to update WIN cache");
        }

        postman = new Postman( compositeLoseURL );
        if( !( postman.deliver("dummy")))
        {
          System.out.println( "Failed to update LOSE cache");
        }

        output.setGame(game);
        output.setMatch(match);
        output.setWinnerUsername( winnerUsername );
        output.setWinnerUuid( winnerUuid );
        output.setWinnerHuman( winnerHuman );
        output.setLoserUsername( loserUsername );
        output.setLoserUuid( loserUuid );
        output.setLoserHuman( loserHuman );
        
        emitter.complete(output);
      }
      catch( Exception exc )
      {
        System.out.println("Failed to parse JSON due to " + exc.toString());
        return;
      }
    }
}
