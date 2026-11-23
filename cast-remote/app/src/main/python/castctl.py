"""
@file castctl.py
@summary Manages pychromecast discovery, YouTube/URL playback, position-synced mirroring, wake, and stop commands.
@description Maintains a thread-safe registry of discovered Cast devices and executes fresh-session playback/mirror/wake/stop operations.
@testing Verified via Python syntax/logic checks and end-to-end Android Chaquopy CI build.
"""

import json
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pychromecast
from pychromecast.controllers.youtube import YouTubeController

DEVICES = {}
_DEVICES_LOCK = threading.Lock()


def _is_yt_id(value):
    if not value or len(value) != 11:
        return False
    ok = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_'
    return all(c in ok for c in value)

BLOCKED_MESSAGE = "This can't be mirrored - the app playing on that TV doesn't share its stream."

_MEDIA_TYPES = {
    '.mp4': 'video/mp4', '.webm': 'video/webm', '.mov': 'video/quicktime',
    '.m3u8': 'application/x-mpegURL', '.mpd': 'application/dash+xml',
    '.mp3': 'audio/mpeg', '.aac': 'audio/aac', '.ogg': 'audio/ogg',
    '.wav': 'audio/wav', '.flac': 'audio/flac',
    '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png',
    '.gif': 'image/gif', '.webp': 'image/webp',
}


def _guess_content_type(url):
    path = re.sub(r'[?#].*$', '', url or '').lower()
    for ext, ct in _MEDIA_TYPES.items():
        if path.endswith(ext):
            return ct
    return None


def _is_media_url(value):
    return isinstance(value, str) and (value.startswith('http://') or value.startswith('https://'))


def _status_dict(cc):
    try:
        st = cc.media_controller.status
        d = {
            'state': getattr(st, 'player_state', None),
            'title': getattr(st, 'title', None) or '',
            'content_id': getattr(st, 'content_id', None),
            'current_time': getattr(st, 'current_time', 0) or 0,
        }
        idle = getattr(st, 'idle_reason', None)
        if idle:
            d['idle_reason'] = idle
        return d
    except Exception as e:
        return {
            'state': 'ERROR',
            'title': '',
            'content_id': None,
            'current_time': 0,
            'error': str(e),
        }


def _settled_status(cc, tries=7, interval=2):
    """Re-read the media status until it settles (PLAYING/PAUSED/BUFFERING/IDLE)
    or ~14s pass. A single early read often catches UNKNOWN before the receiver
    reports anything."""
    d = _status_dict(cc)
    for _ in range(tries):
        st = d.get('state')
        if st in ('PLAYING', 'PAUSED', 'BUFFERING', 'IDLE'):
            break
        time.sleep(interval)
        d = _status_dict(cc)
    return d


def _connect(cc):
    cc.wait()
    yt = YouTubeController()
    cc.register_handler(yt)
    return yt


def _drop_pair(name):
    # Drop the cached pair for name, disconnecting it quietly.
    with _DEVICES_LOCK:
        old = DEVICES.pop(name, None)
    if old is not None:
        try:
            old[0].disconnect()
        except Exception:
            pass


def _fresh_pair(name, timeout=12):
    # Return a FRESH (cc, yt) for name, never a reused connection object.
    _drop_pair(name)
    result = pychromecast.get_chromecasts(timeout=timeout)
    if isinstance(result, tuple):
        chromecasts, browser = result
    else:
        chromecasts, browser = result, None
    try:
        match = None
        for cc in chromecasts:
            if cc.name == name:
                match = cc
                break
        if match is None:
            raise RuntimeError('connect failed: device not found in discovery')
        try:
            yt = _connect(match)
        except Exception as e:
            raise RuntimeError('connect failed: %s' % e)
        with _DEVICES_LOCK:
            DEVICES[name] = (match, yt)
        return match, yt
    finally:
        try:
            if browser:
                pychromecast.stop_discovery(browser)
        except Exception:
            pass


def discover(timeout=12):
    global DEVICES
    with _DEVICES_LOCK:
        old_items = list(DEVICES.items())
        DEVICES = {}
    for name, (cc, yt) in old_items:
        try:
            cc.disconnect()
        except Exception:
            pass
    result = pychromecast.get_chromecasts(timeout=timeout)
    if isinstance(result, tuple):
        chromecasts, browser = result
    else:
        chromecasts, browser = result, None
    out = []
    for cc in chromecasts:
        try:
            yt = _connect(cc)
            with _DEVICES_LOCK:
                DEVICES[cc.name] = (cc, yt)
        except Exception as e:
            out.append({'name': cc.name, 'state': 'ERROR', 'error': str(e)})
    time.sleep(3)
    with _DEVICES_LOCK:
        snapshot = list(DEVICES.items())
    for name, (cc, yt) in snapshot:
        d = {'name': name,
            'model': getattr(cc, 'model_name', '') or ''}
        try:
            d['host'] = cc.cast_info.host
        except Exception:
            d['host'] = ''
        try:
            d['manufacturer'] = cc.cast_info.manufacturer or ''
        except Exception:
            d['manufacturer'] = ''
        d.update(_status_dict(cc))
        out.append(d)
    try:
        if browser:
            pychromecast.stop_discovery(browser)
    except Exception:
        pass
    return json.dumps(out)


def _get(name):
    with _DEVICES_LOCK:
        return DEVICES.get(name)


def play_on(name, video_id, start=0):
    if _get(name) is None:
        return json.dumps({'ok': False, 'error': 'device not connected, refresh first'})
    last_error = None
    for _attempt in range(2):
        try:
            cc, yt = _fresh_pair(name)
        except Exception as e:
            last_error = str(e)
            continue
        try:
            yt.play_video(video_id)
            time.sleep(5)
            if start and start > 0:
                try:
                    cc.media_controller.seek(int(start))
                except Exception:
                    pass
                time.sleep(2)
            d = _settled_status(cc)
            d['ok'] = True
            d['name'] = name
            return json.dumps(d)
        except Exception as e:
            last_error = 'load failed: %s' % e
            continue
    return json.dumps({'ok': False, 'name': name, 'error': last_error})


def source_status():
    for name, (cc, yt) in DEVICES.items():
        d = _status_dict(cc)
        cid = d.get('content_id')
        if _is_yt_id(cid) and d.get('state') in ('PLAYING', 'BUFFERING', 'PAUSED'):
            return json.dumps({'name': name, 'video_id': cid, 'position': d.get('current_time', 0), 'state': d.get('state')})
    return json.dumps({'name': None})


def source_any():
    blocked = None
    for name, (cc, yt) in DEVICES.items():
        d = _status_dict(cc)
        if d.get('state') not in ('PLAYING', 'BUFFERING', 'PAUSED'):
            continue
        cid = d.get('content_id')
        if _is_yt_id(cid):
            return json.dumps({'name': name, 'kind': 'youtube', 'video_id': cid, 'position': d.get('current_time', 0), 'state': d.get('state')})
        if _is_media_url(cid):
            try:
                ct = getattr(cc.media_controller.status, 'content_type', None)
            except Exception:
                ct = None
            return json.dumps({'name': name, 'kind': 'url', 'url': cid, 'position': d.get('current_time', 0), 'state': d.get('state'), 'content_type': ct})
        if blocked is None:
            blocked = name
    if blocked is not None:
        return json.dumps({'name': blocked, 'kind': 'blocked'})
    return json.dumps({'name': None})


def _mirror_one(args):
    target, video_id, start = args
    if _get(target) is None:
        return {'name': target, 'ok': False, 'error': 'not connected'}
    last_error = None
    for _attempt in range(2):
        try:
            cc, yt = _fresh_pair(target)
        except Exception as e:
            last_error = str(e)
            continue
        try:
            cur = _status_dict(cc)
            if cur.get('content_id') == video_id and cur.get('state') in ('PLAYING', 'BUFFERING'):
                return {'name': target, 'ok': True, 'skipped': True}
            yt.play_video(video_id)
            time.sleep(3)
            if start and int(start) > 0:
                try:
                    cc.media_controller.seek(int(start))
                except Exception:
                    pass
                time.sleep(1)
            d = _status_dict(cc)
            return {'name': target, 'ok': True, 'state': d.get('state')}
        except Exception as e:
            last_error = 'load failed: %s' % e
            continue
    return {'name': target, 'ok': False, 'error': last_error}


def _mirror_url_one(args):
    target, url, content_type = args
    if _get(target) is None:
        return {'name': target, 'ok': False, 'error': 'not connected'}
    last_error = None
    for _attempt in range(2):
        try:
            cc, yt = _fresh_pair(target)
        except Exception as e:
            last_error = str(e)
            continue
        try:
            cur = _status_dict(cc)
            if cur.get('content_id') == url and cur.get('state') in ('PLAYING', 'BUFFERING'):
                return {'name': target, 'ok': True, 'skipped': True}
            ct = content_type or _guess_content_type(url) or 'video/mp4'
            cc.media_controller.play_media(url, ct)
            time.sleep(3)
            d = _status_dict(cc)
            return {'name': target, 'ok': True, 'state': d.get('state')}
        except Exception as e:
            last_error = 'load failed: %s' % e
            continue
    return {'name': target, 'ok': False, 'error': last_error}


def mirror_to(targets_json):
    try:
        targets = json.loads(targets_json)
    except Exception:
        targets = []
    src = json.loads(source_any())
    if not src.get('name'):
        return json.dumps({'ok': False, 'error': 'nothing playing to mirror'})
    if src.get('kind') == 'blocked':
        return json.dumps({'ok': False, 'error': BLOCKED_MESSAGE})
    if 'ALL' in targets:
        targets = [n for n in DEVICES.keys() if n != src['name'] and not n.lower().endswith('group')]
    else:
        targets = [t for t in targets if t != src['name']]
    if src.get('kind') == 'youtube':
        video_id = src['video_id']
        start_pos = int(src.get('position', 0) or 0)
        results = []
        with ThreadPoolExecutor(max_workers=4) as pool:
            for r in pool.map(_mirror_one, [(t, video_id, start_pos) for t in targets]):
                results.append(r)
        return json.dumps({'ok': True, 'source': src['name'], 'kind': 'youtube', 'video_id': video_id, 'results': results})
    url = src['url']
    content_type = src.get('content_type')
    results = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        for r in pool.map(_mirror_url_one, [(t, url, content_type) for t in targets]):
            results.append(r)
    return json.dumps({'ok': True, 'source': src['name'], 'kind': 'url', 'url': url, 'results': results})




def play_url(name, url):
    if _get(name) is None:
        return json.dumps({'ok': False, 'error': 'device not connected, refresh first'})
    url = (url or '').strip()
    if not _is_media_url(url):
        return json.dumps({'ok': False, 'name': name, 'error': 'not a web link'})
    ct = _guess_content_type(url)
    last_error = None
    for _attempt in range(2):
        try:
            cc, yt = _fresh_pair(name)
        except Exception as e:
            last_error = str(e)
            continue
        try:
            if ct is not None:
                cc.media_controller.play_media(url, ct)
                d = _settled_status(cc)
                d['ok'] = True
                d['name'] = name
                return json.dumps(d)
            # Web page: try DashCast when available, else the default receiver.
            dash_ok = False
            try:
                from pychromecast.controllers.dashcast import DashCastController
                from pychromecast.config import APP_DASHCAST
                dc = DashCastController()
                cc.register_handler(dc)
                dc.load_url(url)
                time.sleep(4)
                app_id = getattr(cc, 'app_id', None)
                if app_id is None or app_id == APP_DASHCAST:
                    dash_ok = True
            except Exception:
                dash_ok = False
            if dash_ok:
                return json.dumps({'ok': True, 'name': name, 'state': 'WEB', 'title': url})
            cc.media_controller.play_media(url, 'video/mp4')
            d = _settled_status(cc)
            d['ok'] = True
            d['name'] = name
            return json.dumps(d)
        except Exception as e:
            last_error = 'load failed: %s' % e
            continue
    return json.dumps({'ok': False, 'name': name, 'error': last_error})

def _stop_one(name):
    item = _get(name)
    if item is None:
        return {'name': name, 'ok': False, 'error': 'not connected'}
    cc, yt = item
    try:
        cc.quit_app()
    except Exception:
        try:
            cc.media_controller.stop()
        except Exception as e:
            return {'name': name, 'ok': False, 'error': str(e)}
    time.sleep(2)
    d = _status_dict(cc)
    return {'name': name, 'ok': True, 'state': d.get('state')}


def wake_device(name):
    """Wakes an HDMI-CEC TV by starting a Cast receiver session (YouTube app)."""
    if _get(name) is None:
        return json.dumps({'ok': False, 'error': 'device not connected, refresh first'})
    try:
        cc, _yt = _fresh_pair(name)
        cc.start_app('233637DE')
        return json.dumps({'ok': True, 'name': name})
    except Exception as e:
        return json.dumps({'ok': False, 'name': name, 'error': str(e)})


def stop_device(name):
    return json.dumps(_stop_one(name))


def stop_all():
    results = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        for r in pool.map(_stop_one, list(DEVICES.keys())):
            results.append(r)
    return json.dumps({'ok': True, 'results': results})


def _parse_start(text):
    text = text.strip()
    if text.endswith('s') and text[:-1].isdigit():
        return int(text[:-1])
    if text.isdigit():
        return int(text)
    total = 0
    m = re.search(r'(\d+)h', text)
    if m:
        total += int(m.group(1)) * 3600
    m = re.search(r'(\d+)m', text)
    if m:
        total += int(m.group(1)) * 60
    m = re.search(r'(\d+)s', text)
    if m:
        total += int(m.group(1))
    return total


def parse_link(link):
    link = (link or '').strip()
    video_id = None
    start = 0
    m = re.search(r'[?&]v=([A-Za-z0-9_-]{11})', link)
    if m:
        video_id = m.group(1)
    if not video_id:
        m = re.search(r'youtu\.be/([A-Za-z0-9_-]{11})', link)
        if m:
            video_id = m.group(1)
    if not video_id:
        m = re.search(r'/(shorts|embed|live)/([A-Za-z0-9_-]{11})', link)
        if m:
            video_id = m.group(2)
    if not video_id and re.fullmatch(r'[A-Za-z0-9_-]{11}', link):
        video_id = link
    m = re.search(r'[?&]t=([^&]+)', link)
    if m:
        start = _parse_start(m.group(1))
    return json.dumps({'video_id': video_id, 'start': start})

