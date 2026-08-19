import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { parse } from "node-html-parser";
import { compile, CORE_FORMATS, isCoreFormat } from "../src/compile.ts";
import type { CoreFormat } from "../src/types.ts";
import { doc, document, folder, heading, para, text } from "./fixtures.ts";

const manuscript = [
  folder("act", "Act One", { orderKey: "a" }),
  document("ch1", "Chapter One",
    doc(heading(1, "Chapter One"), para(text("The lamp had not been lit."))),
    { parentId: "act", orderKey: "a" }),
  document("ch2", "Chapter Two",
    doc(para(text("She waited until morning."))),
    { parentId: "act", orderKey: "b", synopsis: "she waits", notes: "check tides" }),
];

/** Reads words back out of an artifact without reusing the code that wrote it. */
function wordsIn(format: CoreFormat, output: string): string {
  if (format === "html") return parse(output).text;
  if (format === "md") return output.replace(/\\(.)/g, "$1");
  return output;
}

describe("assembling a manuscript", () => {
  test("plain text output matches a hand-written expectation", () => {
    const result = compile(manuscript, "txt");
    assert.equal(
      result.output,
      "Chapter One\n\nThe lamp had not been lit.\n\nShe waited until morning.\n",
    );
  });

  test("markdown output matches a hand-written expectation", () => {
    const result = compile(manuscript, "md");
    assert.equal(
      result.output,
      "# Chapter One\n\nThe lamp had not been lit.\n\nShe waited until morning.\n",
    );
  });

  test("html output parses into the expected structure", () => {
    const root = parse(compile(manuscript, "html").output);

    assert.equal(root.querySelector("h1")?.text, "Chapter One");
    assert.deepEqual(
      root.querySelectorAll("p").map((p) => p.text),
      ["The lamp had not been lit.", "She waited until morning."],
    );
  });

  test("folder headings are opt-in and land at the right level", () => {
    const root = parse(compile(manuscript, "html", { includeFolderHeadings: true }).output);
    assert.equal(root.querySelector("h1")?.text, "Act One");
  });

  test("document titles are opt-in", () => {
    const without = compile(manuscript, "md");
    assert.doesNotMatch(without.output, /## Chapter Two/);

    const with_ = compile(manuscript, "md", { includeDocumentTitles: true });
    assert.match(with_.output, /## Chapter Two/);
  });

  test("a custom separator is used between documents", () => {
    const result = compile(manuscript, "txt", { separator: "\n\n* * *\n\n" });
    assert.match(result.output, /\* \* \*/);
  });

  test("word count counts prose, not titles or notes", () => {
    // "The lamp had not been lit." = 6, "She waited until morning." = 4,
    // plus the in-document heading "Chapter One" = 2.
    assert.equal(compile(manuscript, "txt").wordCount, 12);
  });
});

describe("nothing is lost or leaked", () => {
  for (const format of CORE_FORMATS) {
    test(`PROPERTY (${format}): every authored word survives into the output`, () => {
      const authored = ["The", "lamp", "had", "not", "been", "lit", "She", "waited", "until", "morning"];
      const readBack = wordsIn(format, compile(manuscript, format).output);

      for (const word of authored) {
        assert.ok(
          readBack.includes(word),
          `"${word}" is missing from the ${format} output — a compile must never drop prose`,
        );
      }
    });

    test(`PROPERTY (${format}): synopses and notes never appear`, () => {
      const output = compile(manuscript, format).output;
      assert.doesNotMatch(output, /she waits/, "synopsis leaked into the manuscript");
      assert.doesNotMatch(output, /check tides/, "notes leaked into the manuscript");
    });

    test(`PROPERTY (${format}): the same input always produces the same bytes`, () => {
      const first = compile(manuscript, format).output;
      const second = compile(manuscript, format).output;
      assert.equal(first, second, "non-deterministic output makes golden tests worthless");
    });
  }

  test("warnings from planning reach the caller of compile", () => {
    const result = compile(manuscript, "txt");
    assert.ok(result.warnings.some((w) => w.code === "notes_not_exported"));
    assert.ok(result.warnings.some((w) => w.code === "not_convertible"));
  });

  test("compiling nothing produces empty output rather than throwing", () => {
    const result = compile([], "md");
    assert.equal(result.output.trim(), "");
    assert.equal(result.wordCount, 0);
  });

  test("a selection of only folders produces no prose", () => {
    const result = compile([folder("a", "Act One")], "txt");
    assert.equal(result.output.trim(), "");
    assert.ok(result.warnings.some((w) => w.code === "not_convertible"));
  });
});

describe("edition boundary", () => {
  test("Core ships exactly txt, md and html", () => {
    assert.deepEqual([...CORE_FORMATS], ["txt", "md", "html"]);
  });

  test("a format Core does not ship is refused, not silently downgraded", () => {
    for (const format of ["docx", "epub", "pdf", "rtf", "odt"]) {
      assert.equal(isCoreFormat(format), false);
      assert.throws(
        () => compile(manuscript, format as CoreFormat),
        /not available in this edition/,
        `${format} must be refused rather than quietly producing something else`,
      );
    }
  });
});
