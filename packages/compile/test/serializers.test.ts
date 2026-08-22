import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { parse } from "node-html-parser";
import { toHtml } from "../src/html.ts";
import { toMarkdown } from "../src/markdown.ts";
import { toPlainText, countWords } from "../src/text.ts";
import { doc, heading, para, text } from "./fixtures.ts";

/**
 * The HTML assertions parse the output with an independent parser rather than comparing
 * it to a string this code produced. A serializer that emitted subtly malformed markup
 * would still satisfy a string comparison; it does not survive being parsed and queried.
 */
describe("html", () => {
  test("produces real elements in document order", () => {
    const { output } = toHtml(doc(
      heading(1, "Chapter One"),
      para(text("The lamp had not been lit.")),
      para(text("She waited.")),
    ));
    const root = parse(output);

    assert.equal(root.querySelector("h1")?.text, "Chapter One");
    const paragraphs = root.querySelectorAll("p").map((p) => p.text);
    assert.deepEqual(paragraphs, ["The lamp had not been lit.", "She waited."]);
  });

  test("marks become nested elements, not literal characters", () => {
    const { output } = toHtml(doc(para(
      text("she was "),
      text("certain", [{ type: "strong" }]),
      text(" of it"),
    )));
    const root = parse(output);

    assert.equal(root.querySelector("strong")?.text, "certain");
    assert.equal(root.querySelector("p")?.text, "she was certain of it");
  });

  test("author text cannot inject markup", () => {
    const hostile = '<script>alert("x")</script> & <b>bold</b>';
    const { output } = toHtml(doc(para(text(hostile))));
    const root = parse(output);

    assert.equal(root.querySelectorAll("script").length, 0, "a script tag must never appear");
    assert.equal(root.querySelectorAll("b").length, 0);
    assert.equal(
      root.querySelector("p")?.text,
      hostile,
      "the characters survive as text, exactly as typed",
    );
  });

  test("link hrefs are escaped and preserved", () => {
    const { output } = toHtml(doc(para(
      text("see this", [{ type: "link", attrs: { href: 'https://example.com/a"b' } }]),
    )));
    const anchor = parse(output).querySelector("a");

    assert.equal(anchor?.text, "see this");
    assert.equal(anchor?.getAttribute("href"), 'https://example.com/a"b');
  });

  test("void elements are self-closed so the output stays well-formed", () => {
    const { output } = toHtml(doc(
      para(text("a"), { type: "hardBreak" }, text("b")),
      { type: "horizontalRule" },
    ));
    assert.match(output, /<br \/>/);
    assert.match(output, /<hr \/>/);
    assert.doesNotMatch(output, /<br>|<hr>/);
  });
});

describe("markdown", () => {
  test("headings and paragraphs match hand-written expectations", () => {
    const { output } = toMarkdown(doc(
      heading(2, "Chapter One"),
      para(text("The lamp had not been lit.")),
    ));
    assert.equal(output, "## Chapter One\n\nThe lamp had not been lit.");
  });

  test("ordinary prose is not littered with backslashes", () => {
    const { output } = toMarkdown(doc(para(text("It was not lit. Rain-soaked, she waited."))));
    assert.equal(
      output,
      "It was not lit. Rain-soaked, she waited.",
      "escaping every . and - would corrupt normal writing",
    );
  });

  test("text that looks like syntax is escaped", () => {
    assert.equal(toMarkdown(doc(para(text("# not a heading")))).output, "\\# not a heading");
    assert.equal(toMarkdown(doc(para(text("a *literal* asterisk")))).output, "a \\*literal\\* asterisk");
    assert.equal(toMarkdown(doc(para(text("- not a list")))).output, "\\- not a list");
  });

  test("NUMBERS AN ORDERED LIST INSTEAD OF BULLETING IT", () => {
    // listItem rendered "- " whatever its parent was, so an author's numbered list came
    // out of the compiler unnumbered. The position was already being passed down and
    // simply never read.
    const item = (body: string) => ({ type: "listItem", content: [para(text(body))] });
    const { output } = toMarkdown(doc({
      type: "orderedList",
      content: [item("first"), item("second"), item("third")],
    }));

    assert.match(output, /^1\. first$/m);
    assert.match(output, /^2\. second$/m);
    assert.match(output, /^3\. third$/m);
  });

  test("still bullets an unordered list", () => {
    const item = (body: string) => ({ type: "listItem", content: [para(text(body))] });
    const { output } = toMarkdown(doc({
      type: "bulletList",
      content: [item("one"), item("two")],
    }));

    assert.match(output, /^- one$/m);
    assert.match(output, /^- two$/m);
  });

  test("emphasis marks produce markdown syntax", () => {
    const { output } = toMarkdown(doc(para(
      text("she was "), text("certain", [{ type: "em" }]), text(" of it"),
    )));
    assert.equal(output, "she was *certain* of it");
  });
});

describe("plain text", () => {
  test("carries no markup at all", () => {
    const { output } = toPlainText(doc(
      heading(1, "Chapter One"),
      para(text("she was "), text("certain", [{ type: "strong" }]), text(" of it")),
    ));
    assert.equal(output, "Chapter One\n\nshe was certain of it");
    assert.doesNotMatch(output, /[<>*_#]/);
  });

  test("counts words from text only", () => {
    assert.equal(countWords("the lamp had not been lit"), 6);
    assert.equal(countWords("   "), 0);
    assert.equal(countWords(""), 0);
  });
});

describe("unrecognised content", () => {
  test("an unknown node warns but keeps its words", () => {
    const { output, warnings } = toPlainText(doc(
      { type: "callout", content: [para(text("do not lose me"))] },
    ));
    assert.match(output, /do not lose me/);
    assert.equal(warnings.filter((w) => w.code === "unsupported_node").length, 1);
    assert.match(warnings[0].message, /callout/);
  });

  test("an unknown mark warns but keeps its text", () => {
    const { output, warnings } = toPlainText(doc(para(
      text("highlighted", [{ type: "highlight" }]),
    )));
    assert.equal(output, "highlighted");
    assert.equal(warnings.filter((w) => w.code === "unsupported_mark").length, 1);
  });

  test("each unknown type is reported once, not once per occurrence", () => {
    const { warnings } = toPlainText(doc(
      { type: "callout", content: [para(text("one"))] },
      { type: "callout", content: [para(text("two"))] },
      { type: "callout", content: [para(text("three"))] },
    ));
    assert.equal(warnings.filter((w) => w.code === "unsupported_node").length, 1);
  });

  test("an image contributes no text and is reported", () => {
    const { output, warnings } = toPlainText(doc(
      para(text("before")),
      { type: "image", attrs: { src: "cover.png" } },
      para(text("after")),
    ));
    assert.equal(output, "before\n\nafter");
    assert.ok(warnings.some((w) => w.message.includes("image")));
  });
});

describe("mark naming conventions", () => {
  // prosemirror-schema-basic says strong/em; TipTap's StarterKit says bold/italic for the
  // same marks. Recognising one set silently drops every emphasised run in a manuscript:
  // the words survive, the formatting does not, and the export looks plausible.
  const equivalents: [string, string][] = [
    ["strong", "bold"],
    ["em", "italic"],
    ["strike", "strikethrough"],
  ];

  for (const [canonical, alias] of equivalents) {
    test(`"${alias}" renders identically to "${canonical}"`, () => {
      const of = (mark: string) =>
        doc(para(text("she was certain", [{ type: mark }])));

      assert.equal(toHtml(of(alias)).output, toHtml(of(canonical)).output);
      assert.equal(toMarkdown(of(alias)).output, toMarkdown(of(canonical)).output);
    });

    test(`"${alias}" does not warn`, () => {
      const result = toHtml(doc(para(text("x", [{ type: alias }]))));
      assert.equal(
        result.warnings.filter((w) => w.code === "unsupported_mark").length,
        0,
        `${alias} must be recognised, not treated as unknown`,
      );
    });
  }

  test("a genuinely unknown mark still warns and keeps its text", () => {
    const result = toHtml(doc(para(text("highlighted", [{ type: "highlight" }]))));
    assert.match(result.output, /highlighted/);
    assert.equal(result.warnings.filter((w) => w.code === "unsupported_mark").length, 1);
  });

  test("a link under either convention is still a link", () => {
    const result = toHtml(doc(para(
      text("see", [{ type: "link", attrs: { href: "https://example.com" } }]),
    )));
    assert.match(result.output, /<a href="https:\/\/example\.com">see<\/a>/);
  });
});
