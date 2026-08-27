import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { filenameFor, directoryFor, runJob } from "../src/runner.ts";
import type { ClaimedJob } from "../src/repository.ts";

const job = (destination: string): ClaimedJob => ({
  id: "j", projectId: "p", format: "md", destination,
  presetId: null, inlineConfig: null, attempts: 1,
});

describe("filenames", () => {
  const at = new Date("2026-08-19T12:34:56.000Z");

  test("a plain title becomes a dated slug", () => {
    assert.equal(filenameFor("The Lighthouse", "md", at), "the-lighthouse-2026-08-19T12-34-56.md");
  });

  test("path separators cannot escape the directory", () => {
    const name = filenameFor("../../etc/passwd", "txt", at);
    assert.doesNotMatch(name, /\.\./);
    assert.doesNotMatch(name, /\//);
  });

  test("quotes and newlines cannot break a Content-Disposition header", () => {
    const name = filenameFor('a"title\nwith\r\nbreaks', "txt", at);
    assert.doesNotMatch(name, /["\r\n]/);
  });

  test("a title of only punctuation still yields a usable name", () => {
    assert.match(filenameFor("!!!???", "md", at), /^manuscript-/);
  });

  test("an absurdly long title is truncated", () => {
    const name = filenameFor("x".repeat(500), "txt", at);
    assert.ok(name.length < 120, `filename was ${name.length} characters`);
  });

  test("the extension follows the format", () => {
    assert.match(filenameFor("Book", "html", at), /\.html$/);
    assert.match(filenameFor("Book", "txt", at), /\.txt$/);
  });
});

describe("html page setup", () => {
  // Through runJob, not compile(): the page setup is a decision the runner makes, and a
  // test that called compile() directly with `page` would pass just as happily with the
  // runner's option removed.
  const doc = (text: string) => ({
    type: "doc",
    content: [{ type: "paragraph", content: [{ type: "text", text }] }],
  });
  const items = [
    { id: "a", title: "Chapter One", type: "document" as const, depth: 0, content: doc("Once.") },
  ];
  const htmlJob = (format: string): ClaimedJob => ({
    id: "j", projectId: "p", format, destination: "download",
    presetId: null, inlineConfig: null, attempts: 1,
  });

  async function exportWith(format: string): Promise<string> {
    const root = await mkdtemp(join(tmpdir(), "noveltea-page-"));
    try {
      const config = {
        connectionString: "", storagePath: join(root, "out"), stagingPath: join(root, "staging"),
        pollIntervalMs: 1000, maxAttempts: 3, leaseSeconds: 60,
      };
      const result = await runJob(htmlJob(format), items, "The Lighthouse", null, config);
      return await readFile(result.outputPath, "utf8");
    } finally {
      await rm(root, { recursive: true, force: true });
    }
  }

  test("an html export is a whole manuscript-formatted document, not a fragment", async () => {
    const output = await exportWith("html");
    // Standard manuscript format is what a submission is expected to look like; these
    // are the parts of it a page carries. Without the runner passing `page` the output
    // is a bare body fragment and every one of these fails.
    assert.match(output, /<!doctype html>/i);
    assert.match(output, /size: letter;/);
    assert.match(output, /margin: 1in;/);
    assert.match(output, /font-size: 12pt;/);
    assert.match(output, /line-height: 2;/);
    assert.match(output, /text-indent: 0.5in;/);
    // The project title becomes the running head beside the page number.
    assert.match(output, /The Lighthouse/);
  });

  test("txt and md carry no page setup, because neither format has a page", async () => {
    for (const format of ["txt", "md"]) {
      const output = await exportWith(format);
      assert.doesNotMatch(output, /<!doctype html>/i);
      assert.doesNotMatch(output, /@page/);
    }
  });
});

describe("destinations", () => {
  const config = {
    connectionString: "", storagePath: "/mnt/exports", stagingPath: "/tmp/staging",
    pollIntervalMs: 1000, maxAttempts: 3,
  };

  test("server exports land in the operator's mount", () => {
    assert.equal(directoryFor(job("server"), config), "/mnt/exports");
  });

  test("download exports are staged separately, so purging one never touches the other", () => {
    assert.equal(directoryFor(job("download"), config), "/tmp/staging");
    assert.notEqual(directoryFor(job("download"), config), directoryFor(job("server"), config));
  });
});

describe("artifact collisions", () => {
  const at = new Date("2026-08-19T12:34:56.000Z");

  test("two exports of the same project in the same second do not share a filename", () => {
    const first = filenameFor("The Lighthouse", "md", at, "aaaaaaaa-1111-2222-3333-444444444444");
    const second = filenameFor("The Lighthouse", "md", at, "bbbbbbbb-1111-2222-3333-444444444444");

    assert.notEqual(
      first,
      second,
      "identical names let one export overwrite another, so a download serves the wrong content",
    );
  });

  test("the same job always produces the same name", () => {
    const id = "cccccccc-1111-2222-3333-444444444444";
    assert.equal(filenameFor("Book", "txt", at, id), filenameFor("Book", "txt", at, id));
  });

  test("the job id cannot smuggle path separators into the name", () => {
    const name = filenameFor("Book", "txt", at, "../../etc/passwd");
    assert.doesNotMatch(name, /\//);
    assert.doesNotMatch(name, /\.\./);
  });
});
