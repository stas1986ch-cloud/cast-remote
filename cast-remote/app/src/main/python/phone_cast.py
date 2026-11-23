"""
@file phone_cast.py
@summary Discovers peer Cast Remote phones via Zeroconf and forwards YouTube cast/mirror commands over HTTP.
@description Resolves IPv4/IPv6 mDNS service addresses safely and POSTs JSON video payloads with playback position sync.
@testing Verified via Python syntax/address-parsing checks and Chaquopy APK CI build.
"""

import json
import socket
import time
import urllib.request

import castctl
from zeroconf import Zeroconf, ServiceBrowser, ServiceListener

SERVICE_TYPE = "_castremote._tcp.local."
SUFFIX = "._castremote._tcp.local."


class _Collector(ServiceListener):
    def __init__(self):
        self.names = []

    def add_service(self, zc, type_, name):
        if name not in self.names:
            self.names.append(name)

    def remove_service(self, zc, type_, name):
        pass

    def update_service(self, zc, type_, name):
        pass


def discover_phones(timeout=5):
    zc = Zeroconf()
    try:
        collector = _Collector()
        browser = ServiceBrowser(zc, SERVICE_TYPE, collector)
        time.sleep(timeout)
        try:
            browser.cancel()
        except Exception:
            pass
        out = []
        for full in collector.names:
            try:
                info = zc.get_service_info(SERVICE_TYPE, full, timeout=2000)
            except Exception:
                info = None
            if info is None or not info.addresses:
                continue
            host = None
            for raw_addr in info.addresses:
                if len(raw_addr) == 4:
                    host = socket.inet_ntoa(raw_addr)
                    break
            if host is None:
                raw_addr = info.addresses[0]
                if len(raw_addr) == 16 and hasattr(socket, "inet_ntop"):
                    host = socket.inet_ntop(socket.AF_INET6, raw_addr)
                else:
                    continue
            if full.endswith(SUFFIX):
                name = full[:-len(SUFFIX)]
            else:
                name = full
            out.append({"name": name, "host": host, "port": info.port})
        return json.dumps(out)
    finally:
        try:
            zc.close()
        except Exception:
            pass


def _send(host, port, video_id, start):
    try:
        formatted_host = "[%s]" % host if (":" in host and not host.startswith("[")) else host
        body = json.dumps({"video_id": video_id, "start": max(0, int(start))}).encode("utf-8")
        req = urllib.request.Request("http://%s:%d/play" % (formatted_host, port), data=body, headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=5) as resp:
            resp.read()
        return {"ok": True}
    except Exception as e:
        return {"ok": False, "error": str(e)}


def send_to_phone(host, port, video_id, start=0):
    return json.dumps(_send(host, port, video_id, start))


def mirror_with_phones(targets_json, phones_json):
    try:
        targets = json.loads(targets_json)
    except Exception:
        targets = []
    try:
        phones = json.loads(phones_json)
    except Exception:
        phones = []
    src = json.loads(castctl.source_any())
    if not src.get("name"):
        return json.dumps({"ok": False, "error": "nothing playing to mirror"})
    if src.get("kind") == "blocked":
        return json.dumps({"ok": False, "error": castctl.BLOCKED_MESSAGE})
    res = json.loads(castctl.mirror_to(targets_json))
    tv_results = res.get("results", []) if res.get("ok") else []
    phone_results = []
    if src.get("kind") == "youtube":
        video_id = src["video_id"]
        start_pos = max(0, int(src.get("position", 0) or 0))
        if "ALL" in targets:
            phone_list = phones
        else:
            phone_list = [p for p in phones if p.get("name") in targets]
        for p in phone_list:
            r = _send(p.get("host", ""), p.get("port", 8766), video_id, start_pos)
            phone_results.append({"name": p.get("name"), "ok": r.get("ok"), "error": r.get("error", "")})
    tv_ok = sum(1 for r in tv_results if r.get("ok"))
    phone_ok = sum(1 for r in phone_results if r.get("ok"))
    out = {"ok": True, "source": src["name"], "kind": src.get("kind"), "tvs": tv_ok, "phones": phone_ok, "tv_results": tv_results, "phone_results": phone_results}
    if src.get("kind") == "youtube":
        out["video_id"] = src["video_id"]
    else:
        out["url"] = src["url"]
    return json.dumps(out)

