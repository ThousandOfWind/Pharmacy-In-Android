"""Report like-for-like debug APK footprint; standard library only, no network or credentials."""
import hashlib
import json
from pathlib import Path
import struct
import zipfile

root = Path(__file__).resolve().parent
result = {}
for candidate in ("baseline", "openai", "koog"):
    apk = root / f"{candidate}-app/build/outputs/apk/debug/{candidate}-app-debug.apk"
    with zipfile.ZipFile(apk) as archive:
        dex = [archive.read(name) for name in archive.namelist() if name.endswith(".dex")]
    result[candidate] = {
        "apk_bytes": apk.stat().st_size,
        "dex_bytes": sum(map(len, dex)),
        # Sum of DEX method_id sizes, not unique defined methods across all DEX files.
        "dex_method_refs": sum(struct.unpack_from("<I", item, 88)[0] for item in dex),
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }
for candidate in ("openai", "koog"):
    result[candidate]["apk_delta_bytes"] = result[candidate]["apk_bytes"] - result["baseline"]["apk_bytes"]
print(json.dumps(result, indent=2))
