"""Preserve original ZIP payloads; replace only the reviewed hook DEX and add ours.

Never round-trip vendor resources through a case-insensitive filesystem: res/A.xml
and res/a.xml are different ZIP members even on macOS. Signing happens separately.
"""
import pathlib
import re
import sys
import zipfile

source, assembled, addon, destination = map(pathlib.Path, sys.argv[1:5])
extra = {}

def addon_dex_name(names):
    # 1.0.4/1.0.5 ship three DEX files; 1.0.6 already ships classes4.dex.
    index = 2
    while f'classes{index}.dex' in names:
        index += 1
    if index < 4:
        raise SystemExit('Unexpected host DEX layout')
    return f'classes{index}.dex'

def dex_index(name):
    if name == 'classes.dex':
        return 1
    m = re.fullmatch(r'classes(\d+)\.dex', name)
    return int(m.group(1)) if m else None

if sys.argv[5:] == ['--public-assets']:
    from addon_assets import assets
    from background_manifest import patch as patch_manifest
    extra = assets()
elif sys.argv[5:]:
    raise SystemExit('Unknown asset profile')
if destination.resolve() in {source.resolve(), assembled.resolve(), addon.resolve()}:
    raise SystemExit('Output must not overwrite input')
temporary = destination.with_suffix('.assembling.apk')
if temporary.exists():
    raise SystemExit('Temporary output exists; inspect before retrying')
with zipfile.ZipFile(source) as original, zipfile.ZipFile(assembled) as modified:
    # Hook-only DEX files apktool emitted beyond the vendor set (1.0.6 64K split).
    hook_dex = sorted((n for n in modified.namelist() if re.fullmatch(r'classes\d+\.dex', n) and n not in original.namelist()),
                      key=lambda n: int(n[7:-4]))
    addon_name = addon_dex_name(original.namelist() + hook_dex)
    if len(original.namelist()) != len(set(original.namelist())):
        raise SystemExit('Duplicate original ZIP members')
    if set(extra) & set(original.namelist()):
        raise SystemExit('Addon asset collision')
    # Vendor DEX files apktool rebuilt after in-place wraps or class relocation.
    # 1.0.6/1.0.7: hooks left a patched classes2.dex (ceiling DEX) plus a new hook DEX.
    # 1.0.10: listener/activity/event wraps live in classes3 — must ship that DEX too.
    # Detect reviewed patches by turboio markers; always keep classes2 for the classic path.
    patched = {}
    for name in original.namelist():
        if dex_index(name) is None or name not in modified.namelist():
            continue
        data = modified.read(name)
        if not data.startswith(b'dex\n'):
            raise SystemExit('Invalid DEX input: ' + name)
        if name == 'classes2.dex' or b'turboioOriginal_' in data or b'Lcom/turboio/addon/' in data:
            patched[name] = data
    if 'classes2.dex' not in patched:
        raise SystemExit('Expected patched classes2.dex from apktool assemble')
    if not addon.read_bytes().startswith(b'dex\n'):
        raise SystemExit('Invalid DEX input')
    with zipfile.ZipFile(temporary, 'x', allowZip64=True) as output:
        for info in original.infolist():
            if re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', info.filename, re.I):
                continue
            content = patched[info.filename] if info.filename in patched else original.read(info)
            if extra and info.filename == 'AndroidManifest.xml': content = patch_manifest(content)
            output.writestr(info, content)
        for name in hook_dex:
            data = modified.read(name)
            if not data.startswith(b'dex\n'):
                raise SystemExit('Invalid hook DEX')
            info = zipfile.ZipInfo(name, (2026, 9, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, data)
        info = zipfile.ZipInfo(addon_name, (2026, 9, 26, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        output.writestr(info, addon.read_bytes())
        for name, path in sorted(extra.items()):
            info = zipfile.ZipInfo(name, (2026, 9, 26, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            output.writestr(info, path.read_bytes())
temporary.replace(destination)
print('Original resource/native payloads retained; patched vendor DEX',
      ','.join(sorted(patched, key=lambda n: dex_index(n) or 0)) + ',',
      'hook DEX, addon DEX/assets and scoped TTS/background manifest additions reviewed.')
