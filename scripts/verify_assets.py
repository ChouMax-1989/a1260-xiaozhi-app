"""Check the exact public runtime/model/media inputs shipped by this repository."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'docs/assets-sha256.json').read_text(encoding='utf-8'))
for relative, expected in manifest.items():
    path = root / relative
    if not path.is_file():
        raise SystemExit(f'Missing public asset: {relative}')
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f'Public asset hash mismatch: {relative}')
print(f'PASS: {len(manifest)} public assets match SHA-256 manifest')
