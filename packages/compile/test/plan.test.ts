import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { planCompile } from "../src/plan.ts";
import { doc, document, folder, para, text } from "./fixtures.ts";

/**
 * Planning exists so the author is told what a compile will and will not touch, before
 * anything expensive runs. These pin the exclusions that would otherwise be surprises.
 */
describe("what gets converted", () => {
  test("only document text is included", () => {
    const plan = planCompile([
      folder("a", "Act One"),
      document("b", "Chapter One", doc(para(text("the lamp had not been lit"))), { parentId: "a" }),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["b"]);
    assert.equal(plan.wordCount, 6);
  });

  test("folders are reported as holding no text", () => {
    const plan = planCompile([folder("a", "Act One")]);

    assert.equal(plan.included.length, 0);
    const warning = plan.warnings.find((w) => w.code === "not_convertible");
    assert.ok(warning, "the author must be told a folder contributes nothing");
    assert.match(warning.message, /folder/);
    assert.equal(warning.itemId, "a");
  });

  test("folder headings are opt-in and still contribute no prose", () => {
    const plan = planCompile([folder("a", "Act One")], { includeFolderHeadings: true });

    assert.equal(plan.included.length, 1);
    assert.equal(plan.included[0].hasText, false);
    assert.equal(plan.wordCount, 0);
  });

  test("SYNOPSES AND NOTES ARE NEVER EXPORTED, and the author is told so", () => {
    const plan = planCompile([
      document("a", "Chapter One", doc(para(text("real prose here"))), {
        synopsis: "she waits at the lighthouse",
        notes: "remember to check the tide times",
      }),
    ]);

    const serialised = JSON.stringify(plan.included);
    assert.doesNotMatch(serialised, /lighthouse/, "a synopsis must never reach the output");
    assert.doesNotMatch(serialised, /tide times/, "notes must never reach the output");
    assert.ok(plan.warnings.some((w) => w.code === "notes_not_exported"));
  });

  test("TOMBSTONED items are excluded and reported", () => {
    const plan = planCompile([
      document("a", "Cut Chapter", doc(para(text("abandoned draft"))), { deletedAt: "2026-08-19T00:00:00Z" }),
      document("b", "Kept", doc(para(text("kept prose")))),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["b"]);
    const warning = plan.warnings.find((w) => w.code === "excluded_trashed");
    assert.ok(warning);
    assert.equal(warning.itemId, "a");
  });

  test("ITEMS IN THE TRASH ARE EXCLUDED — trashing sets no deletedAt", () => {
    // Trashing is a reparent, not a deleted_at write, so a discarded chapter looks like an
    // ordinary live document. A check that only reads deletedAt misses it entirely and the
    // cut scene lands in the manuscript — first, if the trash node sorts first.
    const plan = planCompile([
      { id: "trash", title: "Trash", type: "trash", orderKey: "a", parentId: null },
      document("cut", "Discarded", doc(para(text("SECRET abandoned draft"))),
        { parentId: "trash", orderKey: "a" }),
      document("kept", "Kept", doc(para(text("kept prose"))), { orderKey: "b" }),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["kept"]);
    assert.doesNotMatch(JSON.stringify(plan.included), /SECRET/);
    assert.ok(plan.warnings.some((w) => w.code === "excluded_trashed" && w.itemId === "cut"));
  });

  test("a deeply nested trashed item is excluded too", () => {
    const plan = planCompile([
      { id: "trash", title: "Trash", type: "trash", orderKey: "a", parentId: null },
      folder("act", "Discarded Act", { parentId: "trash", orderKey: "a" }),
      document("scene", "Deep Cut", doc(para(text("SECRET nested draft"))),
        { parentId: "act", orderKey: "a" }),
      document("kept", "Kept", doc(para(text("kept prose"))), { orderKey: "b" }),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["kept"]);
    assert.doesNotMatch(JSON.stringify(plan.included), /SECRET/);
  });

  test("a trash node with nothing in it produces no spurious warnings", () => {
    const plan = planCompile([
      { id: "trash", title: "Trash", type: "trash", orderKey: "a", parentId: null },
      document("kept", "Kept", doc(para(text("kept prose"))), { orderKey: "b" }),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["kept"]);
    assert.ok(!plan.warnings.some((w) => w.code === "excluded_trashed"));
  });

  test("an empty document is excluded rather than emitting a blank section", () => {
    const plan = planCompile([
      document("a", "Placeholder", doc(para(text("   ")))),
      document("b", "Real", doc(para(text("prose")))),
    ]);

    assert.deepEqual(plan.included.map((i) => i.id), ["b"]);
    assert.ok(plan.warnings.some((w) => w.code === "empty_document" && w.itemId === "a"));
  });

  test("a null document body is excluded, not crashed on", () => {
    const plan = planCompile([document("a", "Never opened", null)]);
    assert.equal(plan.included.length, 0);
    assert.ok(plan.warnings.some((w) => w.code === "empty_document"));
  });

  test("an unrecognised item type is reported, not silently converted", () => {
    const plan = planCompile([
      { id: "a", title: "Mystery", type: "whiteboard", orderKey: "a" },
    ]);
    assert.equal(plan.included.length, 0);
    assert.ok(plan.warnings.some((w) => w.code === "not_convertible" && /whiteboard/.test(w.message)));
  });

  test("warnings name the item, so the author knows which one to fix", () => {
    const plan = planCompile([document("a", "Chapter Seven", doc(para(text(""))))]);
    const warning = plan.warnings.find((w) => w.code === "empty_document");
    assert.equal(warning?.itemTitle, "Chapter Seven");
    assert.match(warning.message, /Chapter Seven/);
  });
});

describe("ordering", () => {
  test("parents come before children and siblings follow order_key", () => {
    const plan = planCompile([
      document("c2", "Second", doc(para(text("two"))), { parentId: "act", orderKey: "b" }),
      folder("act", "Act One", { orderKey: "a" }),
      document("c1", "First", doc(para(text("one"))), { parentId: "act", orderKey: "a" }),
    ], { includeFolderHeadings: true });

    assert.deepEqual(plan.included.map((i) => i.id), ["act", "c1", "c2"]);
  });

  test("depth reflects nesting, for heading levels", () => {
    const plan = planCompile([
      folder("act", "Act One", { orderKey: "a" }),
      folder("ch", "Chapter", { parentId: "act", orderKey: "a" }),
      document("sc", "Scene", doc(para(text("prose"))), { parentId: "ch", orderKey: "a" }),
    ], { includeFolderHeadings: true });

    assert.deepEqual(plan.included.map((i) => i.depth), [0, 1, 2]);
  });

  test("an item whose parent was not supplied is still included", () => {
    const plan = planCompile([
      document("orphan", "Orphan", doc(para(text("do not lose me"))), { parentId: "missing" }),
    ]);
    assert.deepEqual(plan.included.map((i) => i.id), ["orphan"]);
  });

  test("a cycle in the supplied items does not hang the planner", () => {
    const plan = planCompile([
      { id: "a", title: "A", type: "folder", parentId: "b", orderKey: "a" },
      { id: "b", title: "B", type: "folder", parentId: "a", orderKey: "a" },
    ]);
    assert.ok(Array.isArray(plan.warnings));
  });
});
