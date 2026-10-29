#!/usr/bin/env python3
"""Verifica che il Client ID SIMKL configurato arrivi davvero nella build, **senza stamparlo**.

Controlla, nell'ordine:
  1. quale canale fornisce il valore (stessa precedenza di app/build.gradle.kts: variabile
     d'ambiente, local.properties, .env) e se e' un valore utilizzabile o un segnaposto;
  2. i BuildConfig generati (debug e release): campo vuoto / segnaposto / uguale al valore;
  3. gli APK indicati: presenza dei byte del valore dentro il pacchetto (dex/risorse).

Di ogni valore viene mostrato solo l'impronta SHA-256 troncata: serve a confrontarlo con la
propria copia senza rivelarlo, e a scoprire subito un valore troncato dal copia-incolla.

Uso:
    python _tmp_verify_simkl_client_id.py [apk ...]
Senza argomenti controlla gli APK gia' presenti in app/build/outputs/apk/{debug,release}.
"""
from __future__ import annotations

import hashlib
import os
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PLACEHOLDER_PREFIXES = ("DEFAULT_", "YOUR_")
DECLARED_KEY = "SIMKL_CLIENT_ID"


def read_key_value_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def is_usable(raw: str | None) -> bool:
    value = (raw or "").strip().strip('"').strip("'")
    if not value:
        return False
    upper = value.upper()
    return not (upper.startswith(PLACEHOLDER_PREFIXES) or upper == DECLARED_KEY)


def resolve() -> tuple[str | None, str | None]:
    candidates = [
        ("variabile d'ambiente", os.environ.get(DECLARED_KEY)),
        ("local.properties", read_key_value_file(ROOT / "local.properties").get(DECLARED_KEY)),
        (".env", read_key_value_file(ROOT / ".env").get(DECLARED_KEY)),
    ]
    for name, value in candidates:
        if is_usable(value):
            return name, (value or "").strip().strip('"').strip("'")
    return None, None


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()[:12]


def describe_field(raw: str | None, expected: str) -> str:
    value = (raw or "").strip()
    if not value:
        return "VUOTO"
    if not is_usable(value):
        return f"SEGNAPOSTO (rifiutato: {fingerprint(value)})"
    if value == expected:
        return f"uguale al valore configurato ({fingerprint(value)})"
    return f"DIVERSO dal valore configurato ({fingerprint(value)})"


def check_build_config(path: Path, expected: str) -> list[str]:
    if not path.exists():
        return [f"  {path}: assente (variante non ancora compilata)"]
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = []
    for field in ("ENV_SIMKL_CLIENT_ID", "SIMKL_CLIENT_ID"):
        # \b obbligatorio: senza, il nome del secondo campo combacia dentro il primo
        # (ENV_SIMKL_CLIENT_ID contiene SIMKL_CLIENT_ID) e il report sarebbe falso.
        match = re.search(rf'\b{field}\s*=\s*"([^"]*)"', text)
        state = describe_field(match.group(1) if match else None, expected)
        lines.append(f"  {path.name}::{field} -> {state}")
    return lines


def check_apk(path: Path, expected: str) -> tuple[bool, str]:
    needle = expected.encode()
    if not path.exists():
        return False, f"  {path}: assente"
    hits: list[str] = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            # Le librerie native sono decine di MB e non contengono il campo di build.
            if name.startswith("lib/") or name.endswith((".so", ".png", ".webp", ".jpg")):
                continue
            try:
                data = archive.read(info)
            except Exception:
                continue
            if needle in data:
                hits.append(name)
    state = "PRESENTE in " + ", ".join(hits) if hits else "ASSENTE"
    return bool(hits), f"  {path.name}: {state} ({path.stat().st_size // 1024} KiB)"


def default_apks() -> list[Path]:
    found: list[Path] = []
    for variant in ("debug", "release"):
        directory = ROOT / "app" / "build" / "outputs" / "apk" / variant
        if directory.is_dir():
            found.extend(sorted(directory.glob("*.apk")))
    return found


def main() -> int:
    source, value = resolve()
    print("== Canale di configurazione ==")
    if value is None:
        print("  nessun Client ID utilizzabile: variabile d'ambiente, local.properties e .env "
              "sono vuoti o contengono un segnaposto")
        print("  (la build lo segnala con un avviso e il pannello SIMKL risponde 'non disponibile')")
        return 1
    print(f"  fornito da: {source}")
    print(f"  lunghezza: {len(value)} caratteri, impronta SHA-256 (12): {fingerprint(value)}")

    ok = True

    print("== BuildConfig generati ==")
    for variant in ("debug", "release"):
        generated = (
            ROOT / "app" / "build" / "generated" / "source" / "buildConfig" / variant
            / "com" / "example" / "BuildConfig.java"
        )
        for line in check_build_config(generated, value):
            print(line)

    # Il campo del plugin `secrets` (SIMKL_CLIENT_ID) resta il segnaposto di .env.example
    # quando non c'e' un .env: e' previsto, perche' il campo di build ha la precedenza.
    # La condizione di successo e' che ALMENO un campo di build coincida col valore
    # configurato; se nessuno coincide, il runtime non userebbe il valore atteso.
    matched_in_build_config = any(
        "uguale al valore configurato" in line
        for variant in ("debug", "release")
        for line in check_build_config(
            ROOT / "app" / "build" / "generated" / "source" / "buildConfig" / variant
            / "com" / "example" / "BuildConfig.java",
            value,
        )
    )
    print(f"  almeno un campo di build coincide col valore configurato: {matched_in_build_config}")
    ok = ok and matched_in_build_config

    apks = [Path(arg) for arg in sys.argv[1:]] or default_apks()
    print("== APK ==")
    if not apks:
        print("  nessun APK da controllare (compila prima: assembleDebug / assembleRelease)")
        ok = False
    for apk in apks:
        present, line = check_apk(apk, value)
        print(line)
        ok = ok and present

    print("== Esito ==")
    print("  OK: il valore configurato e' quello incluso nella build" if ok
          else "  ATTENZIONE: almeno un controllo non e' soddisfatto (vedi sopra)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
