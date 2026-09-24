# CupTheCnt Java

A standalone Java 17+ implementation of the original CupTheCnt utility.
It reads a matching 3DS `CupList` and `Contents.cnt`, validates their layout,
and extracts the referenced CIA ranges without loading whole CIA files into RAM.

The implementation is a clean rewrite of the observable behavior of the upstream
CupTheCnt project; it does not copy the original C source.

## Build

No external libraries are required.

```text
./build.sh
```

This produces:

```text
build/CupTheCnt.jar
```

The JAR is executable with:

```text
java -jar CupTheCnt.jar ...
```

## Usage

### Extract (default)

```text
java -jar CupTheCnt.jar CupList Contents.cnt
```

Default output directory:

```text
updates/
```

Custom output:

```text
java -jar CupTheCnt.jar --output extracted CupList Contents.cnt
```

Existing files are not overwritten unless `--overwrite` is supplied.

### Verify

```text
java -jar CupTheCnt.jar --verify CupList Contents.cnt
```

Verification checks:

- `CupList` contains at least one Title ID.
- no duplicate Title IDs occur.
- `Contents.cnt` is at least `0x1400` bytes.
- `Contents.cnt` starts with `CONT`.
- every referenced `offset` / `offset_end` pair is ordered correctly.
- every referenced CIA range fits inside `Contents.cnt` using the same `0xC00 + offset` base as the original program.

### List

```text
java -jar CupTheCnt.jar --list CupList Contents.cnt
```

Prints index, Title ID, relative offsets, and CIA size without writing files.

### Batch

A batch dataset is a directory containing exactly named files:

```text
dataset-A/
  CupList
  Contents.cnt

dataset-B/
  CupList
  Contents.cnt
```

Run:

```text
java -jar CupTheCnt.jar --batch input-dir --output output-dir
```

Use `--recursive` to search all descendant directories.
For each dataset directory, CIAs are written to:

```text
output-dir/<relative-dataset-path>/updates/
```

## Compatibility note

The original C implementation reads up to 0x100 Title IDs from the first 0x800
bytes of CupList and uses `Contents_entry.offset_end - offset` for the CIA size.
It seeks to `offset + sizeof(Contents_header) - 2048`, which is `0xC00 + offset`.
This Java implementation preserves that file-layout behavior.

## Exit codes

- `0`: success
- `1`: verification/I/O/batch failure
- `2`: command-line usage error
