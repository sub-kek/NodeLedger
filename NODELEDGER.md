# NODELEDGER Format Specification

Version: 0.1 draft

NODELEDGER is an append-only binary format for tree-shaped typed data. A file
contains a fixed header,
historical body data, the latest root `Node`, and a final `root_offset` pointing
to that root `Node`.

This document describes the current on-disk format implemented by this
repository.

## Design Goals

| Goal                  | Format choice                                                                            |
|-----------------------|------------------------------------------------------------------------------------------|
| Fast open             | Read the header, seek to `file_size - 8`, read `root_offset`, then read the root `Node`. |
| Append-only writes    | New objects and a new root `Node` are appended. Existing bytes are not rewritten.        |
| JSON-like tree        | A `Node` maps string keys to either object payloads or nested `Node` records.            |
| Custom data           | Every object has a `uint64_t type`; application code decides how to decode custom types. |
| Large-file compaction | `Object.payload_size` allows live objects to be copied without decoding whole files.     |

## Byte Order and Primitive Rules

| Rule               | Value                                                                                         |
|--------------------|-----------------------------------------------------------------------------------------------|
| Integer byte order | Little-endian.                                                                                |
| Offsets            | Absolute byte offsets from the beginning of the file.                                         |
| Strings            | `uint64_t byte_length` followed by UTF-8 bytes, no trailing `\0`.                             |
| Type id            | `uint64_t`. Short ASCII names up to 8 bytes are packed into the integer.                      |
| Type packing       | First ASCII byte goes into the least significant byte. Example: `STR` = `0x0000000000525453`. |

Readers must reject truncated fields. Readers should treat offsets outside the
file as invalid.

## File Layout

| Offset          |     Size | Field             | Description                                                   |
|-----------------|---------:|-------------------|---------------------------------------------------------------|
| `0`             |       24 | Header            | Magic, format version, last trim timestamp.                   |
| `24`            | variable | Body              | Objects, old root nodes, old nested nodes, and obsolete data. |
| `root_offset`   | variable | Root Node         | Latest root `Node`.                                           |
| `file_size - 8` |        8 | Root offset value | `uint64_t root_offset`. This is always the last field.        |

The latest root `Node` is authoritative. Older objects and older nodes remain in
the body until trim.

## Header

| Field                 | Type       | Current value / meaning                                                  |
|-----------------------|------------|--------------------------------------------------------------------------|
| `magic`               | 8 bytes    | ASCII bytes `N L E D G E R \0`.                                          |
| `version`             | `uint64_t` | Current version is `1`.                                                  |
| `last_trim_timestamp` | `uint64_t` | Milliseconds since Unix epoch when trim wrote this file, or `0` for untrimmed files. |

Header size is 24 bytes.

## Type IDs

| Name    |              Type ID | Payload                                                  |
|---------|---------------------:|----------------------------------------------------------|
| `NODE`  |                  `0` | `Node`, not an `Object`.                                 |
| `NULL`  |  packed ASCII `NULL` | Empty payload.                                           |
| `BOOL`  |  packed ASCII `BOOL` | 1 byte: `0` or `1`.                                      |
| `I32`   |   packed ASCII `I32` | 4 little-endian bytes.                                   |
| `I64`   |   packed ASCII `I64` | 8 little-endian bytes.                                   |
| `U64`   |   packed ASCII `U64` | 8 little-endian bytes.                                   |
| `F64`   |   packed ASCII `F64` | IEEE-754 double bits stored as little-endian `uint64_t`. |
| `STR`   |   packed ASCII `STR` | `uint64_t byte_length`, UTF-8 bytes.                     |
| `BYTES` | packed ASCII `BYTES` | `uint64_t byte_length`, raw bytes.                       |
| `LIST`  |  packed ASCII `LIST` | List payload described below.                            |

Custom types use any non-zero `uint64_t` not reserved by standard types.
Implementations should
prefer packed ASCII names up to 8 bytes, for example `IMG`, `PROTO`, or `ZSTD`.

## Entry

An `Entry` is the value stored under a key in a `Node`.

| Field    | Type       | Description                                            |
|----------|------------|--------------------------------------------------------|
| `type`   | `uint64_t` | Object type, or `0` for nested `Node`.                 |
| `offset` | `uint64_t` | Absolute file offset of the `Object` or nested `Node`. |

If `type == 0`, `offset` must point to a `Node` record. Otherwise, `offset` must
point to an
`Object` record whose `type` matches the entry.

## Node

A `Node` is the standard tree container. The root is also a normal `Node`.

| Field    | Type       | Description                |
|----------|------------|----------------------------|
| `type`   | `uint64_t` | Always `0`.                |
| `length` | `uint64_t` | Number of key-entry pairs. |
| `items`  | repeated   | Repeated `length` times.   |

Each item:

| Field          | Type       | Description                          |
|----------------|------------|--------------------------------------|
| `key_length`   | `uint64_t` | Key byte length.                     |
| `key_bytes`    | bytes      | UTF-8 key bytes.                     |
| `entry.type`   | `uint64_t` | Value type or `0` for nested `Node`. |
| `entry.offset` | `uint64_t` | Absolute offset.                     |

Keys must be unique within one `Node`. The current implementation writes keys in
lexicographic order.
Independent implementations should also write sorted keys for stable files and
fast lookup.

## Object

An `Object` stores a non-node value.

| Field          | Type       | Description                   |
|----------------|------------|-------------------------------|
| `type`         | `uint64_t` | Object type. Must not be `0`. |
| `payload_size` | `uint64_t` | Payload byte length.          |
| `payload`      | bytes      | Type-specific payload.        |

`payload_size` is required. It allows readers to skip unknown custom types and
allows trim to copy
large payloads without decoding them.

## List Payload

`LIST` payload is inline and self-delimiting.

| Field    | Type       | Description              |
|----------|------------|--------------------------|
| `length` | `uint64_t` | Number of list items.    |
| `items`  | repeated   | Repeated `length` times. |

Each list item:

| Field               | Type       | Description                      |
|---------------------|------------|----------------------------------|
| `item.type`         | `uint64_t` | Standard or custom object type.  |
| `item.payload_size` | `uint64_t` | Inline payload size.             |
| `item.payload`      | bytes      | Encoded payload for `item.type`. |

Nested `Node` values are not currently encoded inside lists by the reference
implementation.

## Read Algorithm

1. Open the file in binary mode.
2. Read and validate the 24-byte header.
3. Seek to `file_size - 8`.
4. Read `uint64_t root_offset`.
5. Seek to `root_offset`.
6. Read the root `Node`.
7. Resolve paths by walking `Node` entries. For non-node entries, seek to
   `entry.offset` and read the
   referenced `Object`.

Readers may lazily read object payloads. The reference implementation loads the
root node immediately
and reads objects on demand.

## Save Algorithm

If a document is not dirty, save may do nothing.

For a dirty document:

1. Open or create the file.
2. Append new or changed object payloads.
3. Rebuild changed `Node` branches. Unchanged existing payloads and branches may
   keep their old
   offsets.
4. Append the latest root `Node`.
5. Append `uint64_t root_offset` as the final 8 bytes.
6. Flush the file and mark the document clean.

The file remains append-only. Old bytes become unreachable but remain physically
present until trim.

## Trim Algorithm

Trim builds a new file from the latest root:

1. Read the source header and latest root `Node`.
2. Write a new header to the destination file.
3. Recursively walk live entries from the latest root.
4. Copy each live object as `type | payload_size | payload`.
5. Rewrite nested nodes with updated offsets.
6. Write the new root `Node`.
7. Write the final `root_offset`.

The reference implementation copies object payloads with a fixed-size buffer. It
does not load large
payloads into RAM.

## Validation Notes

The current format does not yet include root magic, root length, root checksum,
or object checksum.
Production implementations should add validation before relying on recovery from
torn writes.

Current readers should at minimum validate:

- header magic and version;
- file size is at least 32 bytes;
- `root_offset` points inside the file;
- `Node.type == 0`;
- object type matches the referencing entry;
- string and bytes payload lengths match their enclosing payload size.

## Logical Example

Logical tree:

```json
{
  "user": {
    "profile": {
      "name": "alice",
      "age": 30
    },
    "active": true
  },
  "settings": {}
}
```

Root `Node` entries:

| Key        | Type         | Offset points to                                 |
|------------|--------------|--------------------------------------------------|
| `settings` | `0` / `NODE` | Empty nested `Node`.                             |
| `user`     | `0` / `NODE` | Nested `Node` containing `active` and `profile`. |

The `user.profile.name` entry points to an `Object` with type `STR`;
`user.profile.age` points to an
`Object` with type `U64`.
