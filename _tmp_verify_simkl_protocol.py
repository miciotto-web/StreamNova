#!/usr/bin/env python3
"""Verifica reale del protocollo SIMKL con il Client ID configurato in local.properties.

Non stampa mai il Client ID, il device_code o lo user_code: di quei valori mostra solo
lunghezza e impronta SHA-256. Esegue due sole richieste (una per endpoint), come farebbe
l'app all'apertura del pannello:

  1. POST /oauth2/device            -> il codice da mostrare (QR + PIN)
  2. POST /oauth2/token (device_code) -> il polling: senza approvazione deve rispondere
                                        `authorization_pending`, non un errore generico

Non autorizza nulla e non salva token: il codice richiesto scade da solo.
"""
from __future__ import annotations

import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BASE_URL = "https://api.simkl.com/"
APP_NAME = "streamnova-tv"
SCOPE = "media:read media:write"
CLIENT_ID_BASE = "https://simkl.com/pin"


def read_key(name: str) -> str | None:
    path = ROOT / "local.properties"
    if not path.exists():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip().startswith(name + "="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()[:12]


def post(path: str, fields: dict[str, str]) -> tuple[int, str]:
    data = urllib.parse.urlencode(fields).encode()
    url = f"{BASE_URL}{path}?{urllib.parse.urlencode({'app-name': APP_NAME, 'app-version': '1.0.2'})}"
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/x-www-form-urlencoded")
    request.add_header("User-Agent", "StreamNova/1.0.2")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


def main() -> int:
    client_id = read_key("SIMKL_CLIENT_ID")
    if not client_id:
        print("nessun SIMKL_CLIENT_ID in local.properties")
        return 1
    print("Client ID configurato:")
    print(f"  lunghezza {len(client_id)} caratteri, impronta SHA-256 (12) {fingerprint(client_id)}")
    print(f"  (valore non stampato: confrontalo con l'impronta della tua copia)")

    print("\n== 1. POST /oauth2/device (richiesta del codice: flusso V2) ==")
    status, body = post("oauth2/device", {"client_id": client_id, "scope": SCOPE})
    print(f"  HTTP {status}")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        print("  risposta non JSON (primi 120 caratteri, ripuliti):")
        print("   ", body[:120].replace(client_id, "<client-id-omesso>"))
        return 1

    if status != 200:
        print("  corpo d'errore (chiavi):", sorted(payload.keys()))
        print("  dettaglio:", json.dumps(payload, ensure_ascii=False)[:200].replace(client_id, "<omesso>"))
        return 1

    print("  campi presenti:", sorted(payload.keys()))
    user_code = str(payload.get("user_code", ""))
    device_code = str(payload.get("device_code", ""))
    verification_uri = str(payload.get("verification_uri", ""))
    complete = payload.get("verification_uri_complete")
    print(f"  user_code        : {len(user_code)} caratteri, impronta {fingerprint(user_code)} (valore omesso)")
    print(f"  device_code      : {len(device_code)} caratteri, impronta {fingerprint(device_code)} (valore omesso)")
    print(f"  verification_uri : {verification_uri!r}")
    print(f"  expires_in       : {payload.get('expires_in')}   interval: {payload.get('interval')}")
    if complete:
        print("  verification_uri_complete: presente "
              f"(lunghezza {len(str(complete))}, base corretta: {str(complete).startswith(CLIENT_ID_BASE)}, "
              f"contiene lo user_code: {user_code in str(complete)})")
        print("  -> il QR del pannello conterra' l'URL precompilato (nessuna digitazione)")
    else:
        print("  verification_uri_complete: assente")
        print("  -> il QR conterra' il solo indirizzo di verifica e il codice si digita")

    print("\n== 2. POST /oauth2/token (polling, grant device_code, senza approvazione) ==")
    status, body = post("oauth2/token", {
        "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        "client_id": client_id,
        "device_code": device_code,
    })
    print(f"  HTTP {status}")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError:
        print("  risposta non JSON (primi 120 caratteri):", body[:120])
        return 1
    print("  risposta:", json.dumps(payload, ensure_ascii=False)[:200].replace(client_id, "<omesso>"))
    expected = payload.get("error") == "authorization_pending"
    print("  esito atteso 'authorization_pending':", "OK" if expected else "NO")
    print("\nIl codice richiesto scade da solo (15 minuti): nessun token salvato, nessuna autorizzazione concessa.")
    return 0 if expected else 1


if __name__ == "__main__":
    raise SystemExit(main())
