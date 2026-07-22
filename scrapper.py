import sys
import json
from spotify_scraper import (
    SpotifyClient,
    NotFoundError,
    RateLimitedError,
    NetworkError,
    ParsingError,
)

def get_track_data():
    try:
        if len(sys.argv) < 3:
            print(json.dumps({"error": "Missing arguments"}))
            sys.exit(1)

        mediaType = sys.argv[1]
        trackId = sys.argv[2]
        
        data = {}
        
        with SpotifyClient() as client:
            match mediaType:
                case "track":
                    track = client.get_track(trackId)
                    data = {
                        "tracks": [track.name],
                        "artists": [track.artists[0].name if track.artists else ""],
                        "duration_ms": [track.duration_ms]
                    }
                case "playlist":
                    playlist = client.get_playlist(trackId)
                    data = {
                        "tracks": [t.track.name for t in playlist.tracks if t and t.track],
                        "artists": [t.track.artists[0].name if t.track.artists else "" for t in playlist.tracks if t and t.track],
                        "duration_ms": [t.track.duration_ms for t in playlist.tracks if t and t.track]
                    }
                case "album":
                    album = client.get_album(trackId)
                    data = {
                        "tracks": [t.name for t in album.tracks if t],
                        "artists": [t.artists[0].name if t.artists else "" for t in album.tracks if t],
                        "duration_ms": [t.duration_ms for t in album.tracks if t]
                    }
                case "episode":
                    episode = client.get_episode(trackId)
                    data = {
                        "tracks": [episode.name],
                        "artists": [""],
                        "duration_ms": [episode.duration_ms]
                    }
                case _:
                    print(json.dumps({"error": "unknown type"}))
                    sys.exit(1)
            
            print(json.dumps(data))
            sys.exit(0) # Indica sucesso para o Java (exitCode = 0)
            
    except NotFoundError:
        print(json.dumps({"error": "No such track."}))
        sys.exit(1)
    except RateLimitedError as exc:
        print(json.dumps({"error": "Rate limited", "retry_after": getattr(exc, 'retry_after', None)}))
        sys.exit(1)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}))
        sys.exit(1)

if __name__ == "__main__":
    get_track_data()