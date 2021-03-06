
package controllers;

import static org.bff.javampd.MPDPlayer.PlayerStatus.STATUS_PLAYING;
import helper.EmptyPage;
import helper.MpdMonitor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import models.Database;
import models.Playlist;

import org.bff.javampd.MPD;
import org.bff.javampd.MPDFile;
import org.bff.javampd.MPDPlayer;
import org.bff.javampd.MPDPlayer.PlayerStatus;
import org.bff.javampd.MPDPlaylist;
import org.bff.javampd.events.PlayerBasicChangeEvent;
import org.bff.javampd.events.PlayerBasicChangeListener;
import org.bff.javampd.events.PlaylistBasicChangeEvent;
import org.bff.javampd.events.PlaylistBasicChangeListener;
import org.bff.javampd.events.TrackPositionChangeEvent;
import org.bff.javampd.events.TrackPositionChangeListener;
import org.bff.javampd.events.VolumeChangeEvent;
import org.bff.javampd.events.VolumeChangeListener;
import org.bff.javampd.exception.MPDConnectionException;
import org.bff.javampd.exception.MPDException;
import org.bff.javampd.exception.MPDPlayerException;
import org.bff.javampd.monitor.MPDStandAloneMonitor;
import org.bff.javampd.objects.MPDSong;

import play.Logger;
import play.Routes;
import play.libs.F.Callback0;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Security;
import play.mvc.WebSocket;
import play.mvc.WebSocket.Out;
import views.html.database;
import views.html.info;
import views.html.main;
import views.html.playlist;

import com.avaje.ebean.Page;

/**
 * Manage a database of computers
 */
@Security.Authenticated(Secured.class)
public class Application extends Controller
{
	/**
	 * This result directly redirect to application home.
	 */
	public static Result GO_HOME = redirect(routes.Application.playlist(0));

	public static final List<WebSocket.Out<String>> sockets = new ArrayList<>();
	
	static
	{
		try
		{
			MPDStandAloneMonitor monitor = MpdMonitor.getInstance().getMonitor();

			Logger.info("Start monitoring ...");
			monitor.addPlayerChangeListener(new PlayerBasicChangeListener()
			{
				@Override
				public void playerBasicChange(PlayerBasicChangeEvent event)
				{
					int id = event.getId();

					try
					{
						MPDPlayer player = MpdMonitor.getInstance().getMPD().getMPDPlayer();
					
						switch (id)
						{
						case PlayerBasicChangeEvent.PLAYER_CONSUME_CHANGE:
							sendWebsocketMessage("consume", player.isConsuming() ? 1 : 0);
							break;

						case PlayerBasicChangeEvent.PLAYER_SINGLE_CHANGE:
							sendWebsocketMessage("single", player.isSingleMode() ? 1 : 0);
							break;

						case PlayerBasicChangeEvent.PLAYER_REPEAT_CHANGE:
							sendWebsocketMessage("repeat", player.isRepeat() ? 1 : 0);
							break;
							
						case PlayerBasicChangeEvent.PLAYER_RANDOM_CHANGE:
							sendWebsocketMessage("shuffle", player.isRandom() ? 1 : 0);
							break;

						case PlayerBasicChangeEvent.PLAYER_PAUSED:
							sendWebsocketMessage("status", "pause");
							break;

						case PlayerBasicChangeEvent.PLAYER_STOPPED:
							sendWebsocketMessage("status", "stop");
							break;

						case PlayerBasicChangeEvent.PLAYER_UNPAUSED:
						case PlayerBasicChangeEvent.PLAYER_STARTED:
							sendWebsocketMessage("status", "play");
							break;
							
						case PlayerBasicChangeEvent.PLAYER_BITRATE_CHANGE:
							// ignore silently - changes from 0 to x and back occur frequently 
							break;
							
						default:
							Logger.info("Ignored player change message " + id);
							break;
						}
					}
					catch (MPDException e)
					{
						Logger.warn("Error on event " + id, e);
					}
				}
			});
			
			monitor.addTrackPositionChangeListener(new TrackPositionChangeListener()
			{
				@Override
				public void trackPositionChanged(TrackPositionChangeEvent event)
				{
					sendWebsocketMessage("songpos", event.getElapsedTime());
				}
			});
			
			monitor.addVolumeChangeListener(new VolumeChangeListener()
			{
				@Override
				public void volumeChanged(VolumeChangeEvent event)
				{
					sendWebsocketMessage("volume", event.getVolume());
				}
			});
			
			monitor.addPlaylistChangeListener(new PlaylistBasicChangeListener()
			{
				@Override
				public void playlistBasicChange(PlaylistBasicChangeEvent event)
				{
					try
					{
						MPDPlayer player = MpdMonitor.getInstance().getMPD().getMPDPlayer();
						switch (event.getId())
						{
						case PlaylistBasicChangeEvent.SONG_ADDED:
						case PlaylistBasicChangeEvent.SONG_DELETED:
							sendWebsocketMessage("reload", event.getId());
							break;

						case PlaylistBasicChangeEvent.PLAYLIST_ENDED:
						case PlaylistBasicChangeEvent.PLAYLIST_CHANGED:
							// just don't care
							break;

						case PlaylistBasicChangeEvent.SONG_CHANGED:
							sendWebsocketMessage("select", player.getCurrentSong().getPosition());
							sendWebsocketMessage("songlength", player.getCurrentSong().getLength());
							break;
						}
					}
					catch (MPDException e)
					{
						Logger.warn("Error on event " + event.getId(), e);
					}
				}
			});
		}
		catch (MPDConnectionException e)
		{
			Logger.warn("Could not connect", e);
		}

	}
	
	private static void sendWebsocketMessage(String type, long value)
	{
		sendWebsocketMessage(type, String.valueOf(value));
	}
	
	private static void sendWebsocketMessage(String type, String value)
	{
		String json = "{ \"type\": \"" + type + "\", \"value\": \"" + value + "\" }";

		if (Logger.isDebugEnabled() && !sockets.isEmpty())
			Logger.debug("Update " + json);
		
		for (Out<String> socket : sockets)
		{
			socket.write(json);
		}
	}

	public static WebSocket<String> sockHandler()
	{
		WebSocket<String> webSocket = new WebSocket<String>()
		{
			// called when the websocket is established
			
			@Override
			public void onReady(final WebSocket.In<String> in, final WebSocket.Out<String> out)
			{			
				sockets.add(out);
				Logger.info("New browser connected (" + sockets.size() + " browsers currently connected)");

				in.onClose(new Callback0()
				{
					@Override
					public void invoke() throws Throwable
					{
						sockets.remove(out);
						Logger.info("Browser disconnected (" + sockets.size() + " browsers currently connected)");
					}
				});
			}
			
		};
		
		return webSocket;
	}
	
	/**
	 * Handle default path requests, redirect to computers list
	 * @return an action result
	 */
	public static Result index()
	{
		return GO_HOME;
	}
	
	public static Result javascriptRoutes() 
	{
	    response().setContentType("text/javascript");
	    return ok(Routes.javascriptRouter("jsRoutes",
	            controllers.routes.javascript.Application.prevSong(),
	            controllers.routes.javascript.Application.playSong(),
	            controllers.routes.javascript.Application.nextSong(),
	            controllers.routes.javascript.Application.stopSong(),

	            controllers.routes.javascript.Application.toggleShuffle(),
	            controllers.routes.javascript.Application.toggleRepeat(),
	            controllers.routes.javascript.Application.toggleSingleMode(),
	            controllers.routes.javascript.Application.toggleConsuming(),
	            
	            controllers.routes.javascript.Application.setVolume(),
	            controllers.routes.javascript.Application.selectSong(),
	            controllers.routes.javascript.Application.setSongPos(),
	            controllers.routes.javascript.Application.addDbEntry(),
	            controllers.routes.javascript.Application.remove()
	        )
	    );
	}

	/**
	 * Display the paginated list of playlist entries.
	 * @param page Current page number (starts from 0)
	 * @return an action result
	 */
	public static Result playlist(int page)
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			Page<MPDSong> songs = Playlist.getSongs(page, 10);

			return ok(playlist.render(player, songs));
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);

			flash("error", "Command failed! " + e.getMessage());
			return ok(playlist.render(null, new EmptyPage<MPDSong>()));
		}
		
	}

	/**
	 * Display the paginated list of computers.
	 * @param page Current page number (starts from 0)
	 * @param sortBy Column to be sorted
	 * @param order Sort order (either asc or desc)
	 * @param filter Filter applied on computer names
	 * @return an action result
	 */
	public static Result browseDb(int page, String sortBy, String order, String filter)
	{
		Page<MPDSong> songs = null;
		List<String> playlistfiles = new ArrayList<>();
		
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			
			List<MPDSong> playlist = mpd.getMPDPlaylist().getSongList();
			for (MPDSong song : playlist)
			{
				playlistfiles.add(song.getFile());
			}
			
			songs = Database.getSongs(page, 10, sortBy, order, filter);
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);

			flash("error", "Command failed! " + e.getMessage());
			songs = new EmptyPage<>();
		}
		
		return ok(database.render(songs, playlistfiles, sortBy, order, filter));
	}

	/**
	 * Performs POST /addUrl
	 * Display the 'Add from URL form'.
	 * @return an action result
	 */
	public static Result addUrl(String url)
	{
		Logger.info("Adding to playlist: " + url);
		
		try
		{
			// TODO: parse ending
			// extract URL from playlist URL if necessary
			
			Logger.info("Adding to playlist: " + url);
			
			MPD mpd = MpdMonitor.getInstance().getMPD();

			if (url.endsWith(".m3u"))
			{
				URL website = new URL(url);
				URLConnection conn = website.openConnection();
				
				int size = conn.getContentLength();
				
				if (size > 256 * 1024)
					throw new IllegalArgumentException("File suspiciously big");

				try (InputStream is = conn.getInputStream())
				{
					InputStreamReader read = new InputStreamReader(is, Charset.defaultCharset());
					BufferedReader reader = new BufferedReader(read);
					
					String line;
					while ((line = reader.readLine()) != null)
					{
						int comIdx = line.indexOf('#');
						if (comIdx >= 0)
							line = line.substring(0, comIdx);
						line = line.trim();
						
						if (!line.isEmpty())
							addUrl(line);
					}
				}
			}
			
			if (url.endsWith(".mp3"))
			{
				String name = "";
				int from = url.lastIndexOf('/');
				int to = url.length();
				
				if (from != -1 && from < to)
					name = url.substring(from, to);
				
				MPDFile file = new MPDFile();
				file.setDirectory(false);
				file.setPath(url);
				file.setName(name);
				
				mpd.getMPDPlaylist().addFileOrDirectory(file);
			}
		}
		catch (Exception e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
			
		
		// TODO: parse ending
		// extract URL from playlist URL if necessary
		
		
		return GO_HOME;
	}
	/**
	 * Performs POST /addDbEntry
	 * @return an action result
	 */
	public static Result addDbEntry(String path)
	{
		try
		{
			Logger.info("Adding db entry to playlist: " + path);
			
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDSong song = new MPDSong();
			song.setFile(path);
			
			mpd.getMPDPlaylist().addSong(song);

			return ok(path);
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
			
			return notFound(path);
		}		
	}

	/**
	 * Performs GET /playSong
	 * @return an action result
	 */
	public static Result playSong()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			PlayerStatus status = player.getStatus();
			
			if (status == STATUS_PLAYING)
				player.pause(); else
				player.play();
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs GET /toggleRepeat
	 * @return an action result
	 */
	public static Result toggleRepeat()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			player.setRepeat(!player.isRepeat());
			
			Logger.info("Setting repeat: " + player.isRepeat());
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs GET /toggleRandome
	 * @return an action result
	 */
	public static Result toggleShuffle()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			player.setRandom(!player.isRandom());

			Logger.info("Setting shuffle: " + player.isRandom());
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs GET /toggleConsuming
	 * @return an action result
	 */
	public static Result toggleConsuming()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			player.setConsuming(!player.isConsuming());

			Logger.info("Setting consuming: " + player.isConsuming());
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}
	
	/**
	 * Performs GET /toggleSingleMode
	 * @return an action result
	 */
	public static Result toggleSingleMode()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			player.setSingleMode(!player.isSingleMode());

			Logger.info("Setting single mode: " + player.isSingleMode());
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}
	
	/**
	 * Performs GET /nextSong
	 * @return an action result
	 */
	public static Result nextSong()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();

			player.playNext();
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs GET /prevSong
	 * @return an action result
	 */
	public static Result prevSong()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			
			player.playPrev();
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}
		
		return ok("");
	}
	

	/**
	 * Performs GET /stopSong
	 * @return an action result
	 */
	public static Result stopSong()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlayer player = mpd.getMPDPlayer();
			
			player.stop();
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Command failed! " + e.getMessage());
		}

		return ok("");
	}

	/**
	 * Performs POST /setsongpos
	 * @param pos the new song position in seconds
	 * @return an action result
	 */
	public static Result setSongPos(int pos)
	{
		Logger.info("Set song pos " + pos);
		
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			mpd.getMPDPlayer().seek(pos);
		}
		catch (MPDPlayerException | MPDConnectionException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Changing song position failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs POST /volume
	 * @param volume the new volume level
	 * @return an action result
	 */
	public static Result setVolume(int volume)
	{
		Logger.info("Set volume " + volume);
		
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			mpd.getMPDPlayer().setVolume(volume);
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Changing volume failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Performs POST /selectsong/:pos
	 * @return an action result
	 */
	public static Result selectSong(int pos)
	{
		Logger.info("Play Song " + pos);
		
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDSong song = mpd.getMPDPlaylist().getSongList().get(pos);
			mpd.getMPDPlayer().playId(song);
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Changing song failed! " + e.getMessage());
		}
		
		return ok("");
	}
	
	/**
	 * Performs GET /update
	 * @return an action result
	 */
	public static Result updateDb()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();

			mpd.getMPDAdmin().updateDatabase();
			
			flash("success", "Updating database!");
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Updating database failed!" + e.getMessage());
		}

		return GO_HOME;
	}

	/**
	 * Remove entry from playlist
	 * @param id the playlist entry pos
	 * @return an action result
	 */
	public static Result remove(int id)
	{
		Logger.info("Removing entry from playlist: " + id);
		
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			MPDPlaylist mpdPlaylist = mpd.getMPDPlaylist();
			MPDSong song = mpdPlaylist.getSongList().get(id);

			mpdPlaylist.removeSong(song);
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", "Removing entry from playlist failed! " + e.getMessage());
		}
		
		return ok("");
	}

	/**
	 * Render info page GET /info
	 * @return the info page
	 */
	public static Result info()
	{
		try
		{
			MPD mpd = MpdMonitor.getInstance().getMPD();
			return ok(info.render(mpd));
		}
		catch (MPDException e)
		{
			Logger.error("MPD error", e);
			flash("error", e.getMessage());
			return ok(main.render(null, null)); 
		}
	}
}
