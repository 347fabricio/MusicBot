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
        mediaType = sys.argv[1]
        trackId = sys.argv[2]
        
        data = {}
        
        with SpotifyClient() as client:
            match mediaType:
                case "track":
                    track = client.get_track(trackId)
                    data = {
                        "tracks": [track.name],
                        "artists": [track.artists[0].name]
                    }
                case "playlist":
                    playlist = client.get_playlist(trackId)
                    data = {
                        "tracks": [t.track.name for t in playlist.tracks],
                        "artists": [[a.name for a in t.track.artists] for t in playlist.tracks]
                    }
                case "album":
                    album = client.get_album(trackId)
                    data = {
                        "tracks": [t.name for t in album.tracks],
                        "artists": [[a.name for a in t.artists] for t in album.tracks]
                    }
                case "episode":
                    episode = client.get_episode(trackId)
                    data = {
                        "tracks": [episode.name],
                        "artists": [""]
                    }
                case _:
                    data = {"error": "unknown type"}
            
            print(json.dumps(data))
            
    except NotFoundError:
        print(json.dumps({"error": "No such track."}))
    except RateLimitedError as exc:
        print(json.dumps({"error": "Rate limited", "retry_after": exc.retry_after}))
    except Exception as exc:
        print(json.dumps({"error": str(exc)}))

if __name__ == "__main__":
    get_track_data()