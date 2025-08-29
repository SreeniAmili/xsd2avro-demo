# xsd2avro-demo
Utility jar to convert from XSD to Avro schema
---

## 1) Requirements

- **Java 21** (JDK 21)
- **Maven 3.9+**

Verify:
```powershell
java -version
mvn -v
```

---

## 2) Build

From the project root (folder containing `pom.xml`):

```powershell
mvn -U -DskipTests clean package
```

The shaded runnable jar will be created at:
```
target\xsd2avro-demo-1.7.0-java21.jar
```

---

## 3) Quick start

Place XSDs under a folder, e.g. `xsds\`. Then run:

```
java -jar target\xsd2avro-demo-1.7.0-java21.jar   --in xsds   --glob *.xsd   --out avro   --out-naming file+root   --flatten-top   --pretty
```


This will generate one `.avsc` per XSD into `avro/`, for example:
```
avro\ah__Payload.avsc
avro\payload__Payload.avsc
avro\another__Payload.avsc
```

---

## 4) CLI options

| Option | Arg | Required | Description |
|---|---|:---:|---|
| `--in` | file or dir | ✓ | Input XSD file **or** directory |
| `--out` | dir | ✓ | Output folder for `.avsc` |
| `--glob` | pattern |  | Glob for directory mode (default: `*.xsd`) |
| `--out-naming` | `root \| file \| file+root` |  | Output file naming (default `file+root`) |
| `--root-name` | name |  | Force a specific global element as the root (when XSD has multiple globals) |
| `--namespace` | ns |  | Override Avro namespace (otherwise derived from `targetNamespace`) |
| `--avro-name` | name |  | Override Avro record name (otherwise from the root element name) |
| `--flatten-top` | (flag) |  | Flatten one level of top-level child records into the root |
| `--pretty` | (flag) |  | Pretty-print JSON output |
| `--logical-types` | (flag) |  | Reserved for parity; **solid** writer currently emits strings for date/time |

**Notes**

- **Duplicate field names** are auto-renamed during generation: `MessageNo`, `MessageNo_1`, `MessageNo_2`, … (no failures).
- `--out-naming file+root` prevents overwrites when different XSDs share the same root element name.
- If XSDs are nested in subfolders, run multiple commands or need to use a shell loop to recurse.

---

## 5) Common recipes

**A. Convert a single XSD**
```
java -jar target\xsd2avro-demo-1.7.0-java21.jar --in xsds\payload.xsd --out avro --pretty
```

**B. Batch convert all XSDs with a shared namespace**
```
java -jar target\xsd2avro-demo-1.7.0-java21.jar   --in xsds   --glob *.xsd   --out avro   --out-naming file+root   --namespace ah.scm.event.schema   --flatten-top   --pretty
```

**C. Batch convert and customize per-file record names (PowerShell loop)**
```powershell
$jar = "target\xsd2avro-demo-1.7.0-java21.jar"
$out = "avro"
New-Item -ItemType Directory -Force -Path $out | Out-Null

Get-ChildItem -Path "xsds" -Filter *.xsd -File | ForEach-Object {
  $file = $_.FullName
  $base = $_.BaseName
  $avroName = ($base -replace '[^A-Za-z0-9]', '') + 'Event'  # e.g. payload.xsd -> payloadEvent

  java -jar $jar --in $file --out $out --out-naming file+root --flatten-top --pretty --avro-name $avroName
}
```

---
## 6) Output naming modes

- `root` → `Payload.avsc`
- `file` → `payload.avsc`
- `file+root` (default) → `payload__Payload.avsc`

Use `file+root` for large batches to avoid collisions across XSDs that share the same root name.

---
