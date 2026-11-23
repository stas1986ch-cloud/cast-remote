"""
@file adb.py
@summary Minimal ADB-over-TCP client (port 5555) providing fallback power wake/sleep/toggle commands for Android TVs.
@description Implements ADB CNXN/AUTH/OPEN/WRTE packet framing with RSA authentication and explicit WAKEUP/SLEEP/POWER keyevents.
@testing Verified for protocol sequencing and error reporting; used as optional fallback behind Android TV Remote v2.
"""

import base64
import binascii
import json
import os
import socket
import struct
import time

# Minimal ADB client: connect to an Android TV's network-debugging port and
# run a shell command (power key). Every failure returns ok:false with a
# plain-language reason - never a fake success.

A_CNXN = 0x4e584e43
A_AUTH = 0x48545541
A_OPEN = 0x4e45504f
A_OKAY = 0x4b41594f
A_CLSE = 0x45534c43
A_WRTE = 0x45545257
ADB_VERSION = 0x01000000
MAX_PAYLOAD = 4096


def _msg(cmd, arg0, arg1, data=b''):
    crc = binascii.crc32(data) & 0xffffffff
    return struct.pack('<6I', cmd, arg0, arg1, len(data), crc, cmd ^ 0xffffffff) + data


def _read_exact(sock, n):
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError('connection closed by device')
        buf += chunk
    return buf


def _read_msg(sock):
    header = _read_exact(sock, 24)
    cmd, arg0, arg1, data_len, _crc, _magic = struct.unpack('<6I', header)
    data = _read_exact(sock, data_len) if data_len > 0 else b''
    return cmd, arg0, arg1, data


def _load_or_create_key(keydir):
    import rsa
    priv_path = os.path.join(keydir, 'adbkey')
    pub_path = os.path.join(keydir, 'adbkey.pub')
    if os.path.exists(priv_path) and os.path.exists(pub_path):
        with open(priv_path, 'rb') as f:
            priv = rsa.PrivateKey.load_pkcs1(f.read())
        with open(pub_path, 'r') as f:
            pub_line = f.read().strip()
        return priv, pub_line
    os.makedirs(keydir, exist_ok=True)
    pub, priv = rsa.newkeys(2048)
    with open(priv_path, 'wb') as f:
        f.write(priv.save_pkcs1(format='PEM'))
    pub_line = base64.b64encode(pub.save_pkcs1(format='DER')).decode() + ' castremote'
    with open(pub_path, 'w') as f:
        f.write(pub_line)
    return priv, pub_line


def _connect_and_auth(host, port, keydir, auth_wait=60):
    import rsa
    priv, pub_line = _load_or_create_key(keydir)
    try:
        sock = socket.create_connection((host, port), timeout=5)
    except Exception:
        raise ConnectionError('cannot reach the TV on ADB port 5555 - enable network debugging on the TV')
    sock.settimeout(1)
    try:
        sock.sendall(_msg(A_CNXN, ADB_VERSION, MAX_PAYLOAD, b'host::'))
        deadline = time.time() + auth_wait
        authed = False
        sent_sig = False
        while time.time() < deadline:
            try:
                cmd, arg0, _arg1, data = _read_msg(sock)
            except socket.timeout:
                continue
            if cmd == A_CNXN:
                authed = True
                break
            if cmd == A_AUTH and arg0 == 1:
                if not sent_sig:
                    sig = rsa.sign(data, priv, 'SHA-1')
                    sock.sendall(_msg(A_AUTH, 2, 0, sig))
                    sent_sig = True
                else:
                    sock.sendall(_msg(A_AUTH, 3, 0, pub_line.encode() + b'\x00'))
                continue
            if cmd == A_CLSE:
                raise ConnectionError('ADB authorization was denied on the TV')
        if not authed:
            raise ConnectionError('TV asked for ADB authorization - accept the prompt on the TV and try again')
        sock.settimeout(10)
        return sock
    except ConnectionError:
        try:
            sock.close()
        except Exception:
            pass
        raise
    except Exception as e:
        try:
            sock.close()
        except Exception:
            pass
        raise ConnectionError('ADB connection failed: ' + str(e))


def _shell(sock, command, timeout=15):
    local_id = 1
    sock.sendall(_msg(A_OPEN, local_id, 0, ('shell:' + command).encode() + b'\x00'))
    remote_id = None
    out = b''
    deadline = time.time() + timeout
    while time.time() < deadline:
        cmd, _arg0, _arg1, data = _read_msg(sock)
        if cmd == A_OKAY and remote_id is None:
            remote_id = _arg0
            continue
        if cmd == A_WRTE and remote_id is not None:
            out += data
            sock.sendall(_msg(A_OKAY, local_id, remote_id, b''))
            continue
        if cmd == A_CLSE:
            if remote_id is None:
                raise ConnectionError('ADB shell service was refused by the device')
            return out
    raise ConnectionError('ADB command timed out')


def _run(host, command, keydir, port=5555):
    try:
        sock = _connect_and_auth(host, port, keydir)
    except ConnectionError as e:
        return {'ok': False, 'error': str(e)}
    except Exception as e:
        return {'ok': False, 'error': 'ADB failed: ' + str(e)}
    try:
        out = _shell(sock, command)
        return {'ok': True, 'output': out.decode(errors='replace')}
    except ConnectionError as e:
        return {'ok': False, 'error': str(e)}
    except Exception as e:
        return {'ok': False, 'error': 'ADB failed: ' + str(e)}
    finally:
        try:
            sock.close()
        except Exception:
            pass


def adb_power(host, keydir, port=5555):
    res = _run(host, 'input keyevent 26', keydir, port)
    if res.get('ok'):
        return json.dumps({'ok': True})
    return json.dumps({'ok': False, 'error': res.get('error', 'failed')})


def adb_power_on(host, keydir, port=5555):
    res = _run(host, 'input keyevent 224', keydir, port)
    if res.get('ok'):
        return json.dumps({'ok': True})
    return json.dumps({'ok': False, 'error': res.get('error', 'failed')})


def adb_power_off(host, keydir, port=5555):
    res = _run(host, 'input keyevent 223', keydir, port)
    if res.get('ok'):
        return json.dumps({'ok': True})
    return json.dumps({'ok': False, 'error': res.get('error', 'failed')})


def adb_shell(host, command, keydir, port=5555):
    return json.dumps(_run(host, command, keydir, port))
