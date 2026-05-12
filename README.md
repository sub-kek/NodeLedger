# NodeLedger

NodeLedger is an append-only binary file format and Java API for tree-shaped typed data.

The current format is documented in [NODELEDGER.md](NODELEDGER.md). This README describes the Java API and intended usage.

## Public API

Use these classes directly:

| Class | Purpose |
|---|---|
| `NodeLedger` | Static entry point: create, open, and create parsers. |
| `NodeLedgerDocument` | Mutable document returned by `NodeLedger.create()` or `NodeLedger.open(path)`. |
| `NodeLedgerNode` | Read-only view of a document node returned by `document.root()` or `node.getNode(key)`. |
| `NodeLedgerParser` | Parser instance for custom codecs and lower-level read/write/trim operations. |
| `NodeLedgerCodec`, `NodeLedgerReader`, `NodeLedgerWriter` | Custom type extension points. |
| `NodeLedgerTypeId` | Built-in type IDs and packed ASCII custom type IDs. |
| `NodeLedgerException` | Checked exception for file, format, encode, and decode failures. |

Do not instantiate documents, nodes, registry objects, or internal values directly. Use `NodeLedger.create()`, `NodeLedger.open(path)`, and `NodeLedger.parser()`.

## Internal API

These details are implementation concerns:

| Internal detail | Notes |
|---|---|
| `NodeLedgerValue` | Package-private stored/in-memory value holder. |
| `NodeLedgerCodecRegistry` construction | Registry is owned by `NodeLedgerParser`; access it through `parser.registry()` only when needed. |
| Node mutation methods | Node mutation is package-private. User code should mutate documents through `document.set(...)` and `document.remove(...)`. |
| Binary offsets, root records, object headers | Stable on-disk format details, but not normal application API. See `NODELEDGER.md` for readers/writers. |

## Supported Types

Built-in Java values:

| Java type | NODELEDGER type |
|---|---|
| `null` | `NULL` |
| `Boolean` | `BOOL` |
| `Integer` | `I32` |
| `Long` | `I64` |
| `BigInteger` | `U64`, must be in `0..2^64-1` |
| `Double` | `F64` |
| `String` | `STR`, UTF-8 |
| `byte[]` | `BYTES` |
| `List<?>` | `LIST` |

Lists can contain built-in values and custom codec values. Nested nodes are not encoded inside lists.

## Paths

Document paths use dot-separated keys:

```java
document.set("user.profile.name", "alice");
document.set("user.profile.age", 30);
```

Rules:

- path must not be empty;
- empty segments are rejected: `user..name`, `.user`, `user.`;
- path segments must not have surrounding whitespace;
- dots are separators and cannot be part of a key.

## Create And Save

```java
Path path = Path.of("data.nl");

NodeLedgerDocument document = NodeLedger.create();
document.set("user.name", "alice");
document.set("user.age", 30);
document.set("user.active", true);
document.set("blob", new byte[] {1, 2, 3});
document.set("items", List.of("one", 2, false));

document.save(path);
```

`save(path)` writes a valid NODELEDGER file and marks the document clean.

## Open And Read

```java
NodeLedgerDocument document = NodeLedger.open(Path.of("data.nl"));

String name = document.get("user.name", String.class);
Integer age = document.get("user.age", Integer.class);
boolean exists = document.contains("user.active");
```

`get(...)` returns `null` when the path is missing or the stored value is `NULL`.

Use `contains(...)` when you need to distinguish a missing path from an explicit null value.

## Update And Append

```java
Path path = Path.of("data.nl");

NodeLedgerDocument document = NodeLedger.open(path);
document.set("user.name", "bob");
document.remove("user.active");
document.save(path);
```

When saving back to the same opened path, NodeLedger appends new data and a new root record. Old bytes remain in the file until trim.

If the document is clean and saved to the same path, `save(path)` does nothing.

## Save As

```java
NodeLedgerDocument document = NodeLedger.open(Path.of("data.nl"));
document.save(Path.of("copy.nl"));
```

Saving to a different path writes a new file containing the current document state.

## Trim / Compact

Trim writes a compacted file containing only live data reachable from the current root:

```java
Path source = Path.of("data.nl");
Path compacted = Path.of("data.compacted.nl");

NodeLedgerDocument document = NodeLedger.open(source);
document.trim(compacted);
```

After `document.trim(compacted)`, the document is associated with `compacted`, marked clean, and its `fileSize()` / `lastTrimTimestamp()` metadata are updated.

The source and target must be different files.

You can also trim without keeping a document instance:

```java
NodeLedger.parser().trim(Path.of("data.nl"), Path.of("data.compacted.nl"));
```

## Metadata

```java
document.path();              // Optional<Path>
document.fileSize();          // size observed after open/save/trim
document.lastTrimTimestamp(); // millis timestamp written by trim, or 0 for untrimmed files
document.isDirty();           // true after mutation until save/trim succeeds
```

## Root Inspection

`root()` gives a read-only node view:

```java
NodeLedgerNode root = document.root();
boolean hasUser = root.containsKey("user");
NodeLedgerNode user = root.getNode("user");
Set<String> userKeys = user.keys();
```

Use `document.get(...)`, `document.set(...)`, and `document.remove(...)` for values. `NodeLedgerNode` is intended for navigation and key inspection.

## Custom Types

Create a parser, register a codec, then create/read documents through that parser:

```java
record Point(int x, int y) {}

long pointType = NodeLedgerTypeId.pack("POINT");

NodeLedgerParser parser = NodeLedger.parser();
parser.register(
    Point.class,
    pointType,
    payload -> new Point(payload.getInt(), payload.getInt()),
    point -> {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(point.x());
        buffer.putInt(point.y());
        buffer.flip();
        return buffer;
    }
);

NodeLedgerDocument document = parser.create();
document.set("point", new Point(10, 20));
document.save(Path.of("point.nl"));

Point point = parser.read(Path.of("point.nl")).get("point", Point.class);
```

Codec rules:

- `typeId` must not be `NodeLedgerTypeId.NODE`;
- duplicate Java types and duplicate type IDs are rejected;
- readers must consume the full payload;
- writers must return a non-null `ByteBuffer` positioned at the bytes to write.

## Error Handling

Most file and format failures throw `NodeLedgerException`:

```java
try {
    NodeLedgerDocument document = NodeLedger.open(Path.of("data.nl"));
    String value = document.get("value", String.class);
} catch (NodeLedgerException exception) {
    // invalid file, unsupported format, codec failure, or type mismatch
}
```

Invalid API arguments such as bad paths, unknown Java value types, or duplicate codec registration throw `IllegalArgumentException`.

## File Behavior

NodeLedger files are append-only during normal saves:

- open reads the last root offset;
- save to the same file appends changed objects and a new root;
- old data remains physically present;
- trim writes a smaller compacted file with only live data.

This is why hex dumps can show old values after updates. The latest root is authoritative.
