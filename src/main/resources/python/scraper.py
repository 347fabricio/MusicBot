import sys
import json
import logging
import httpx
import os

from spotify_scraper import (
    SpotifyClient,
    NotFoundError,
    RateLimitedError,
    NetworkError,
    ParsingError,
    CacheConfig,
    FileCache,
    RateLimit
)

MAX_TRACKS = int(os.environ.get("SPOTIFY_MAX_TRACKS", 500))

logging.basicConfig(level=logging.CRITICAL)
logging.getLogger("spotify_scraper").setLevel(logging.CRITICAL)
logging.getLogger("httpx").setLevel(logging.CRITICAL)

def get_batch_data():
    if len(sys.argv) < 3:
        print(json.dumps({"error": "Missing arguments", "success": False}))
        return 1

    mediaType = sys.argv[1].lower()
    item_ids = sys.argv[2:]
    response_items = []

    if mediaType not in ("track", "episode", "playlist", "album"):
        print(json.dumps({"error": f"Unsupported media type: {mediaType}", "success": False}))
        return 1

    try:
        with SpotifyClient(
            rate_limit=RateLimit(per_second=1.0, burst=3),
            cache=CacheConfig(store=FileCache())
        ) as client:
            for item_id in item_ids:
                try:
                    if mediaType == "track":
                        data = client.get_track(item_id)
                        track_id = getattr(data, 'id', None) or item_id
                        artist_name = data.artists[0].name if (hasattr(data, 'artists') and data.artists) else ""

                        response_items.append({
                            "id": track_id,
                            "success": True,
                            "track_ids": [track_id],
                            "tracks": [getattr(data, 'name', '')],
                            "artists": [artist_name],
                            "duration_ms": [getattr(data, 'duration_ms', 0)]
                        })

                    elif mediaType == "episode":
                        data = client.get_episode(item_id)
                        ep_id = getattr(data, 'id', None) or item_id
                        show_name = data.show.name if (hasattr(data, 'show') and data.show) else "Podcast"

                        response_items.append({
                            "id": ep_id,
                            "success": True,
                            "track_ids": [ep_id],
                            "tracks": [getattr(data, 'name', '')],
                            "artists": [show_name],
                            "duration_ms": [getattr(data, 'duration_ms', 0)]
                        })

                    elif mediaType == "playlist":
                        data = client.get_playlist(item_id, max_tracks=MAX_TRACKS)
                        raw_tracks = data.tracks.items if hasattr(data.tracks, 'items') else data.tracks
                        valid_tracks = [t.track for t in raw_tracks if t and getattr(t, 'track', None)]
                        playlist_id = getattr(data, 'id', None) or item_id

                        response_items.append({
                            "id": playlist_id,
                            "success": True,
                            "track_ids": [getattr(t, 'id', None) or '' for t in valid_tracks],
                            "tracks": [getattr(t, 'name', '') for t in valid_tracks],
                            "artists": [t.artists[0].name if (hasattr(t, 'artists') and t.artists) else "" for t in valid_tracks],
                            "duration_ms": [getattr(t, 'duration_ms', 0) for t in valid_tracks]
                        })

                    elif mediaType == "album":
                        data = client.get_album(item_id, max_tracks=MAX_TRACKS)
                        raw_tracks = data.tracks.items if hasattr(data.tracks, 'items') else data.tracks
                        valid_tracks = [t for t in raw_tracks if t]
                        album_id = getattr(data, 'id', None) or item_id

                        response_items.append({
                            "id": album_id,
                            "success": True,
                            "track_ids": [getattr(t, 'id', None) or '' for t in valid_tracks],
                            "tracks": [getattr(t, 'name', '') for t in valid_tracks],
                            "artists": [t.artists[0].name if (hasattr(t, 'artists') and t.artists) else "" for t in valid_tracks],
                            "duration_ms": [getattr(t, 'duration_ms', 0) for t in valid_tracks]
                        })

                except Exception as item_err:
                    response_items.append({
                        "id": item_id,
                        "success": False,
                        "error": str(item_err)
                    })

        print(json.dumps({"success": True, "results": response_items}))
        return 0

    except RateLimitedError as exc:
        retry_after = getattr(exc, 'retry_after', None)
        print(json.dumps({"success": False, "error": "Rate limited", "retry_after": retry_after}))
        return 1
    except (httpx.TimeoutException, httpx.ConnectTimeout):
        print(json.dumps({"success": False, "error": "Spotify request timed out."}))
        return 1
    except Exception as exc:
        print(json.dumps({"success": False, "error": str(exc)}))
        return 1

if __name__ == "__main__":
    exit_code = get_batch_data()
    sys.exit(exit_code)