#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
"$ROOT/build.sh" >/dev/null
rm -rf "$ROOT/test-fixtures/work" "$ROOT/test-fixtures/input" "$ROOT/test-fixtures/output"
mkdir -p "$ROOT/test-fixtures/input/dataset-a"
python3 - <<'PY'
from pathlib import Path
import struct
root=Path('/mnt/data/CupTheCnt-java/test-fixtures/input/dataset-a')
tids=[0x0004001000021900,0x0004001000022900]
(root/'CupList').write_bytes(b''.join(struct.pack('<Q',x) for x in tids)+b'\0'*(0x800-16))
# Contents header is 0x1400 bytes. Original C seeks to 0xC00 + offset.
payloads=[b'HELLO', b'PYTHON']
entries=[]
off=0x800
for payload in payloads:
    entries.append((off, off+len(payload)))
    off += len(payload)
header=bytearray(0x1400)
header[0:4]=b'CONT'
for i,(a,b) in enumerate(entries):
    struct.pack_into('<II',header,0xC00+i*8,a,b)
(root/'Contents.cnt').write_bytes(bytes(header)+b''.join(payloads))
PY
JAR="$ROOT/build/CupTheCnt.jar"
printf '%s\n' '--- verify ---'
java -jar "$JAR" --verify "$ROOT/test-fixtures/input/dataset-a/CupList" "$ROOT/test-fixtures/input/dataset-a/Contents.cnt"
printf '%s\n' '--- list ---'
java -jar "$JAR" --list "$ROOT/test-fixtures/input/dataset-a/CupList" "$ROOT/test-fixtures/input/dataset-a/Contents.cnt"
printf '%s\n' '--- extract ---'
java -jar "$JAR" --output "$ROOT/test-fixtures/work/updates" "$ROOT/test-fixtures/input/dataset-a/CupList" "$ROOT/test-fixtures/input/dataset-a/Contents.cnt"
printf 'CIA0='; cat "$ROOT/test-fixtures/work/updates/0004001000021900.cia"; printf '\n'
printf 'CIA1='; cat "$ROOT/test-fixtures/work/updates/0004001000022900.cia"; printf '\n'
printf '%s\n' '--- collision should fail ---'
if java -jar "$JAR" --output "$ROOT/test-fixtures/work/updates" "$ROOT/test-fixtures/input/dataset-a/CupList" "$ROOT/test-fixtures/input/dataset-a/Contents.cnt"; then
  echo 'collision test FAILED'
  exit 1
else
  echo 'collision test OK'
fi
mkdir -p "$ROOT/test-fixtures/input/dataset-b/sub"
cp "$ROOT/test-fixtures/input/dataset-a/CupList" "$ROOT/test-fixtures/input/dataset-b/sub/CupList"
cp "$ROOT/test-fixtures/input/dataset-a/Contents.cnt" "$ROOT/test-fixtures/input/dataset-b/sub/Contents.cnt"
printf '%s\n' '--- batch ---'
java -jar "$JAR" --batch --recursive "$ROOT/test-fixtures/input" --output "$ROOT/test-fixtures/output"
printf '%s\n' '--- extracted files ---'
find "$ROOT/test-fixtures" -type f -name '*.cia' -print | sort
