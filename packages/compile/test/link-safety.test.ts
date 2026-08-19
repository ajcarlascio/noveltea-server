import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { parse } from "node-html-parser";
import { toHtml, isSafeHref } from "../src/html.ts";
import { toMarkdown } from "../src/markdown.ts";
import { doc, para, text } from "./fixtures.ts";

const linked = (href: string) =>
  doc(para(text("click me", [{ type: "link", attrs: { href } }])));

/**
 * Author documents are user-generated content, and a link href is the one place a document
 * can carry an executable payload into a rendered manuscript. Escaping the value is not
 * enough: `javascript:alert(1)` contains nothing that needs escaping.
 */
describe("dangerous hrefs", () => {
  const attacks = [
    "javascript:alert(1)",
    "JaVaScRiPt:alert(1)",
    "  javascript:alert(1)",
    "java\tscript:alert(1)",
    "java\nscript:alert(1)",
    "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
    "vbscript:msgbox(1)",
    "file:///etc/passwd",
  ];

  for (const href of attacks) {
    test(`html strips ${JSON.stringify(href)} but keeps the text`, () => {
      const { output, warnings } = toHtml(linked(href));
      const root = parse(output);

      assert.equal(root.querySelectorAll("a").length, 0, "no anchor may survive");
      assert.equal(root.text, "click me", "the author's words must be kept");
      assert.ok(warnings.some((w) => w.code === "unsafe_link"), "the author must be told");
    });

    test(`markdown strips ${JSON.stringify(href)} but keeps the text`, () => {
      const { output } = toMarkdown(linked(href));
      assert.equal(output, "click me");
      assert.doesNotMatch(output, /\(/, "no link target may survive");
    });
  }
});

describe("legitimate hrefs", () => {
  const allowed = [
    "https://example.com/chapter",
    "http://example.com",
    "mailto:editor@example.com",
    "/relative/path",
    "./sibling",
    "../parent",
    "#scene-three",
  ];

  for (const href of allowed) {
    test(`${href} is preserved`, () => {
      const anchor = parse(toHtml(linked(href)).output).querySelector("a");
      assert.equal(anchor?.getAttribute("href"), href);
      assert.equal(anchor?.text, "click me");
    });
  }

  test("a safe href containing quotes is still escaped", () => {
    const href = 'https://example.com/a"onmouseover="alert(1)';
    const root = parse(toHtml(linked(href)).output);

    assert.equal(root.querySelector("a")?.getAttribute("href"), href);
    assert.equal(
      root.querySelector("a")?.getAttribute("onmouseover"),
      undefined,
      "escaping must stop the value breaking out into a new attribute",
    );
  });
});

describe("the allowlist itself", () => {
  test("fails closed on schemes nobody listed", () => {
    assert.equal(isSafeHref("noveltea-future-scheme:whatever"), false);
    assert.equal(isSafeHref("intent://scan/#Intent;scheme=zxing;end"), false);
  });

  test("an empty or whitespace-only href is not a link", () => {
    assert.equal(isSafeHref(""), false);
    assert.equal(isSafeHref("   "), false);
  });
});
