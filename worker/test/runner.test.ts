import { test, describe } from "node:test";
import assert from "node:assert/strict";
import { filenameFor, directoryFor } from "../src/runner.ts";
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
