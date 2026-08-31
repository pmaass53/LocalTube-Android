import re
import time
import threading
import json
import concurrent.futures
import requests
import traceback
from anipy_api.provider import get_provider, LanguageTypeEnum
from anipy_api.anime import Anime
from anipy_api.error import ProviderNotAvailableError
from Levenshtein import ratio

ANILIST_ENDPOINT = "https://graphql.anilist.co"
ANILIST_QUERY = """
query ($search: String) {
  Page(page: 1, perPage: 1) {
    media(search: $search, type: ANIME) {
      id
      title { romaji english native }
      coverImage { extraLarge large medium }
      bannerImage
      description(asHtml: false)
      episodes
      nextAiringEpisode { episode }
    }
  }
}
"""

# ---------------------------------------------------------------------------
# Caching & Throttling
# ---------------------------------------------------------------------------

_INFO_CACHE = {}
_rate_lock = threading.Lock()
_last_request_time = 0.0
_MIN_INTERVAL = 0.3

def _throttle():
    global _last_request_time
    with _rate_lock:
        now = time.monotonic()
        elapsed = now - _last_request_time
        if elapsed < _MIN_INTERVAL:
            time.sleep(_MIN_INTERVAL - elapsed)
        _last_request_time = time.monotonic()

def _fetch_anilist_fallback(title):
    if not title: return None
    try:
        resp = requests.post(
            ANILIST_ENDPOINT,
            json={"query": ANILIST_QUERY, "variables": {"search": title}},
            timeout=5
        )
        if resp.status_code != 200: return None

        data = resp.json().get("data", {}).get("Page", {}).get("media", [])
        if data:
            m = data[0]
            cover = m.get("coverImage") or {}
            total_eps = m.get("episodes")
            next_airing = m.get("nextAiringEpisode")
            ep_count = total_eps or (next_airing["episode"] - 1 if next_airing else 0)

            return {
                "banner": m.get("bannerImage") or "",
                "cover_large": cover.get("large") or cover.get("extraLarge") or "",
                "description": m.get("description") or "",
                "episodes_count": ep_count or 0
            }
    except:
        pass
    return None

def _get_anime_info_internal(provider, ident, title):
    if ident in _INFO_CACHE:
        return _INFO_CACHE[ident]

    res = {"banner": "", "cover_large": "", "description": "", "episodes_count": 0}

    fallback = _fetch_anilist_fallback(title)
    if fallback:
        res.update(fallback)

    try:
        _throttle()
        # Safe search helper to handle provider crashes
        results = []
        try:
            results = provider.get_search(ident if ident.isdigit() else title)
        except Exception as e:
            print(f"[anime_provider] provider search crash for {ident}: {e}")

        target = next((r for r in results if str(r.identifier) == str(ident)), None)
        if not target and results: target = results[0]

        if target:
            anime = Anime.from_search_result(provider, target)
            if not res["description"]:
                try:
                    info = anime.get_info()
                    if not res["cover_large"]: res["cover_large"] = info.image or getattr(target, 'image', "")
                    res["description"] = info.synopsis or ""
                except Exception as e:
                    print(f"[anime_provider] provider get_info failed for {ident}: {e}")

            if not res["cover_large"]:
                res["cover_large"] = getattr(target, 'image', "")

            if res["episodes_count"] == 0:
                try:
                    eps = anime.get_episodes(LanguageTypeEnum.SUB)
                    res["episodes_count"] = len(eps) if eps else 0
                except: pass
    except Exception as e:
        print(f"[anime_provider] provider lookup failed for {ident}: {e}")

    _INFO_CACHE[ident] = res
    return res

# ---------------------------------------------------------------------------
# Provider Resiliency
# ---------------------------------------------------------------------------

_BROWSER_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

def _setup_provider_headers(provider):
    """Inject a real browser User-Agent and Referer so AniDB.app returns real HTML."""
    provider.session.headers.update({
        "User-Agent": _BROWSER_UA,
        "Referer": provider.BASE_URL + "/",
        "Accept-Language": "en-US,en;q=0.9",
    })


def _wrap_provider_debug(provider):
    """Wrap the provider's _request_page to log HTTP status and response length."""
    original = provider._request_page

    def logged_request_page(req):
        url = str(req.url) if hasattr(req, "url") else repr(req)
        try:
            resp = original(req)
            body_len = len(resp.text) if resp is not None and resp.text else 0
            status = resp.status_code if resp is not None else "??? (null response)"
            print(f"[anime_provider] [DEBUG] {provider.BASE_URL} "
                  f"request -> status={status}, body_len={body_len}, url={url}")
            return resp
        except Exception as e:
            print(f"[anime_provider] [DEBUG] request raised: {e!r} for url={url}")
            raise

    provider._request_page = logged_request_page


def _patch_provider_get_search(provider):
    original = provider.get_search

    def safe_get_search(query, *args, **kwargs):
        try:
            return original(query, *args, **kwargs)
        except AttributeError as e:
            if "findAll" in str(e) or "NoneType" in str(e):
                print(f"[anime_provider] [DEBUG] get_search({query!r}) returned 0 results (anime-grid missing), returning []")
                return []
            raise

    provider.get_search = safe_get_search


def _get_provider_list():
    """Return a list of prioritized provider instances."""
    providers = []
    for name in ["gogoanime", "anidbapp", "animehub", "animekai"]:
        try:
            p = get_provider(name)
            if p:
                _setup_provider_headers(p)
                _wrap_provider_debug(p)
                _patch_provider_get_search(p)
                providers.append(p)
        except: pass
    return providers

# ---------------------------------------------------------------------------
# API Entry Points
# ---------------------------------------------------------------------------

def search_anime(query: str):
    try:
        providers = _get_provider_list()
        if not providers: return []

        results = []
        for provider in providers:
            try:
                results = provider.get_search(query)
                if results: break
            except Exception as e:
                print(f"[anime_provider] search failed for provider {type(provider).__name__}: {e}")

        output = []
        for r in results[:20]:
            image = getattr(r, 'image', "")
            ident = r.identifier
            cached = _INFO_CACHE.get(ident)
            output.append({
                "id": ident,
                "title": r.name,
                "url": f"anime://{ident}",
                "banner": cached.get("banner", "") if cached else "",
                "cover_large": cached.get("cover_large", image) if cached else image,
                "thumbnail": cached.get("cover_large", image) if cached else image,
                "description": cached.get("description", "") if cached else ""
            })
        return output
    except Exception as e:
        print(f"[anime_provider] search_anime failed: {e}")
        return []

def get_anime_images_batch(items_input):
    if isinstance(items_input, (str, bytes, bytearray)):
        items = json.loads(items_input)
    else:
        items = items_input

    providers = _get_provider_list()
    if not providers: return {}
    provider = providers[0]

    results_map = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        to_fetch = [it for it in items if it['id'] not in _INFO_CACHE]
        for it in items:
            if it['id'] in _INFO_CACHE:
                results_map[it['id']] = _INFO_CACHE[it['id']]

        future_to_id = {
            executor.submit(_get_anime_info_internal, provider, it['id'], it['title']): it['id']
            for it in to_fetch
        }
        for future in concurrent.futures.as_completed(future_to_id):
            ident = future_to_id[future]
            try:
                results_map[ident] = future.result()
            except:
                results_map[ident] = {"banner": "", "cover_large": "", "description": "", "episodes_count": 0}

    return results_map

def get_anime_details(anime_id, title=""):
    providers = _get_provider_list()
    if not providers: return {}
    return _get_anime_info_internal(providers[0], anime_id, title)

def get_episodes_list(anime_id, lang="sub"):
    try:
        details = get_anime_details(anime_id, "")
        count = details.get("episodes_count", 0)
        if count > 0: return list(range(1, count + 1))

        for provider in _get_provider_list():
            try:
                results = provider.get_search(anime_id)
                target = next((r for r in results if str(r.identifier) == str(anime_id)), None)
                if not target and results: target = results[0]
                if target:
                    anime = Anime.from_search_result(provider, target)
                    lang_enum = LanguageTypeEnum.DUB if str(lang).lower() in ("dub", "d") else LanguageTypeEnum.SUB
                    eps = anime.get_episodes(lang_enum)
                    if eps: return [int(ep) for ep in eps]
            except: continue
    except: pass
    return []

def _get_headers_for_stream(stream, provider_instance):
    headers = getattr(stream, 'headers', {})
    if not headers:
        headers = {
            "User-Agent": _BROWSER_UA,
            "Referer": provider_instance.BASE_URL + "/",
            "Accept": "*/*",
            "Connection": "keep-alive"
        }
    return headers

def get_available_streams(anime_id, episode, lang="sub"):
    """
    Returns a JSON list of available qualities for an episode.
    Uses the same resolution logic as get_stream_url.
    """
    print(f"[anime_provider] get_available_streams(id={anime_id}, ep={episode}, lang={lang})")
    try:
        episode_num = int(episode)
    except: return json.dumps([])

    providers = _get_provider_list()
    lang_enum = LanguageTypeEnum.DUB if str(lang).lower() in ("dub", "d") else LanguageTypeEnum.SUB

    for provider in providers:
        try:
            print(f"[anime_provider] trying provider: {type(provider).__name__}")
            results = provider.get_search(anime_id)
            target = next((r for r in results if str(r.identifier) == str(anime_id)), None)
            if not target and results: target = results[0]

            anime = None
            if target:
                anime = Anime.from_search_result(provider, target)
            elif str(anime_id).isdigit():
                print(f"[anime_provider] direct construction for {anime_id}")
                anime = Anime(provider, name="", identifier=str(anime_id),
                              languages={LanguageTypeEnum.SUB, LanguageTypeEnum.DUB})

            if anime:
                streams = anime.get_videos(episode_num, lang_enum)
                if streams:
                    output = []
                    for s in streams:
                        if getattr(s, "url", None):
                            q_name = str(getattr(s, 'quality', ""))
                            if not q_name or q_name.lower() == "unknown":
                                # Try to infer from resolution if available
                                res = getattr(s, 'resolution', "")
                                q_name = f"{res}p" if res else "Auto/Best"

                            output.append({
                                "quality": q_name,
                                "url": str(s.url),
                                "headers": _get_headers_for_stream(s, provider)
                            })
                    if output: return json.dumps(output)
        except Exception as e:
            print(f"[anime_provider] provider {type(provider).__name__} quality fetch failed: {e}")
            continue

    return json.dumps([])

def get_stream_url(anime_id, episode, lang="sub"):
    print(f"[anime_provider] get_stream_url(id={anime_id}, ep={episode}, lang={lang})")
    try:
        episode_num = int(episode)
    except (TypeError, ValueError):
        return json.dumps({"error": f"Episode '{episode}' is not a valid integer."})

    providers = _get_provider_list()
    if not providers:
        return json.dumps({"error": "No provider available."})

    lang_enum = LanguageTypeEnum.DUB if str(lang).lower() in ("dub", "d") else LanguageTypeEnum.SUB

    def _try_get_stream(anime, provider_instance):
        try:
            stream = anime.get_video(episode_num, lang_enum, preferred_quality="best")
            if stream and getattr(stream, "url", None):
                return json.dumps({
                    "url": str(stream.url),
                    "headers": _get_headers_for_stream(stream, provider_instance)
                })
        except Exception as e:
            print(f"[anime_provider] get_video() raised: {e}")
        return None

    # Try each provider until we find a working stream
    for provider in providers:
        try:
            print(f"[anime_provider] trying provider: {type(provider).__name__}")
            results = provider.get_search(anime_id)
            target = next((r for r in results if str(r.identifier) == str(anime_id)), None)
            if not target and results: target = results[0]

            if target:
                anime = Anime.from_search_result(provider, target)
                res = _try_get_stream(anime, provider)
                if res: return res
                continue

            if str(anime_id).isdigit():
                print(f"[anime_provider] no search results for {anime_id!r}, constructing Anime directly from identifier")
                anime = Anime(
                    provider, name="", identifier=str(anime_id),
                    languages={LanguageTypeEnum.SUB, LanguageTypeEnum.DUB}
                )
                res = _try_get_stream(anime, provider)
                if res: return res
        except Exception as pe:
            print(f"[anime_provider] provider {type(provider).__name__} failed: {pe}")
            continue

    return json.dumps({"error": "No playable streams found across all providers."})
