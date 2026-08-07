import sys
import json
import logging
import httpx

from spotify_scraper import (
    SpotifyClient,
    NotFoundError,
    RateLimitedError,
    NetworkError,
    ParsingError,
    CacheConfig,
    FileCache   
)

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

    try:
        with SpotifyClient(cache=CacheConfig(store=FileCache())) as client:
            match mediaType:
                case "track":
                    batch_results = client.get_tracks(item_ids)
                case "episode":
                    batch_results = client.get_episodes(item_ids)
                case "playlist":
                    batch_results = client.get_playlists(item_ids)
                case "album":
                    batch_results = client.get_albums(item_ids)
                case _:
                    print(json.dumps({"error": f"Unsupported media type: {mediaType}", "success": False}))
                    return 1

            for idx, item in enumerate(batch_results):
                item_id = item_ids[idx]

                if item.error:
                    response_items.append({
                        "id": item_id,
                        "success": False,
                        "error": str(item.error)
                    })
                    continue

                data = item.result
                
                if mediaType == "track":
                    track_id = getattr(data, 'id', item_id)
                    response_items.append({
                        "id": track_id,
                        "success": True,
                        "track_ids": [track_id],
                        "tracks": [data.name],
                        "artists": [data.artists[0].name if data.artists else ""],
                        "duration_ms": [data.duration_ms]
                    })
                elif mediaType == "episode":
                    ep_id = getattr(data, 'id', item_id)
                    show_name = data.show.name if hasattr(data, 'show') and data.show else "Podcast"
                    response_items.append({
                        "id": ep_id,
                        "success": True,
                        "track_ids": [ep_id],
                        "tracks": [data.name],
                        "artists": [show_name],
                        "duration_ms": [data.duration_ms]
                    })
                elif mediaType == "playlist":
                    valid_tracks = [t.track for t in data.tracks if t and t.track]
                    response_items.append({
                        "id": getattr(data, 'id', item_id),
                        "success": True,
                        "track_ids": [getattr(t, 'id', '') for t in valid_tracks],
                        "tracks": [t.name for t in valid_tracks],
                        "artists": [t.artists[0].name if t.artists else "" for t in valid_tracks],
                        "duration_ms": [t.duration_ms for t in valid_tracks]
                    })
                elif mediaType == "album":
                    valid_tracks = [t for t in data.tracks if t]
                    response_items.append({
                        "id": getattr(data, 'id', item_id),
                        "success": True,
                        "track_ids": [getattr(t, 'id', '') for t in valid_tracks],
                        "tracks": [t.name for t in valid_tracks],
                        "artists": [t.artists[0].name if t.artists else "" for t in valid_tracks],
                        "duration_ms": [t.duration_ms for t in valid_tracks]
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