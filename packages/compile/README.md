# @noveltea/compile

Turns ProseMirror documents into the manuscript formats Core ships: `txt`, `md`, `html`.

```bash
npm test -w @noveltea/compile
```

## What it converts, and what it refuses

**Only document text.** A compile takes prose and nothing else:

| Item | Result |
|---|---|
| `document` with text | converted |
| `document` with no text | skipped, warned |
| `folder` | title only, and only with `includeFolderHeadings`; warned either way |
| trashed / `deleted_at` | skipped, warned |
| anything else | skipped, warned |
| **synopsis, notes** | **never exported, under any option** |

Synopses and notes are the author's scaffolding, not the book. Exporting them by accident
is worse than refusing to, so they are excluded unconditionally and the author is told.

**Run `planCompile()` first.** It reports what would be included and what would not,
without rendering anything. A long manuscript is expensive to produce, and the author
should learn that half their selection is folders before waiting for it — not after.

## Unrecognised content

An unknown node or mark produces a warning and **keeps its text**. Losing an author's
words to a node type this package has not met yet would be the worst possible outcome, so
the wrapper is dropped and the words survive. Each unknown type is reported once, not once
per occurrence.

Nodes carrying no text at all — images, embeds — contribute nothing and are reported.

## Formats

`html` is the root: `md` and `txt` are separate serializers, and every commercial format
derives from the HTML output. That is why the HTML serializer lives in Core regardless of
which formats an installation can produce.

Markdown escaping is deliberately narrow. Escaping every `.` and `-` would put backslashes
through ordinary prose; only genuinely ambiguous inline characters are escaped, and block
markers (`#`, `>`, `-`, `1.`) only at the start of a line.

Requesting a format Core does not ship throws rather than quietly producing something
else. The Java side reports the same boundary through `ExportProvider`.

## Testing approach

HTML assertions parse the output with an independent parser rather than comparing it to a
string this package produced — a serializer emitting subtly malformed markup still passes
a string comparison, but does not survive being parsed and queried. Text and Markdown are
pinned against hand-written expectations, and one property test asserts that every
authored word survives into every format.
