import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";
import pg from "pg";
import { drainQueue } from "../src/index.ts";
import type { WorkerConfig } from "../src/config.ts";

const CONNECTION =
  process.env.NOVELTEA_DB_URL ?? "postgresql://noveltea:noveltea@localhost:5432/noveltea";

/**
 * Drives the real queue against a real database: insert a job, drain it, and read the file
 * off disk. Nothing here stubs the database or the filesystem, because the parts most
 * likely to be wrong are exactly the ones a stub would replace.
 */
describe("compile queue", () => {
  let pool: pg.Pool;
  let config: WorkerConfig;
  let root: string;
  const created: string[] = [];
  let userId: string;

  before(async () => {
    pool = new pg.Pool({ connectionString: CONNECTION });
    root = await mkdtemp(join(tmpdir(), "noveltea-worker-"));
    config = {
      connectionString: CONNECTION,
      storagePath: join(root, "exports"),
      stagingPath: join(root, "staging"),
      pollIntervalMs: 1000,
      maxAttempts: 3,
    };
    userId = randomUUID();
    await pool.query("INSERT INTO app_user (id, email) VALUES ($1, $2)", [
      userId, `worker-${userId}@example.com`,
    ]);
  });

  after(async () => {
    for (const projectId of created) {
      await pool.query("DELETE FROM project WHERE id = $1", [projectId]);
    }
    await pool.query("DELETE FROM app_user WHERE id = $1", [userId]);
    await pool.end();
    await rm(root, { recursive: true, force: true });
  });

  /** A project with one folder and two chapters, plus a note that must not be exported. */
  async function seedProject(title: string): Promise<string> {
    const projectId = randomUUID();
    created.push(projectId);
    await pool.query("INSERT INTO project (id, owner_id, title) VALUES ($1, $2, $3)", [
      projectId, userId, title,
    ]);

    const actId = randomUUID();
    await pool.query(
      `INSERT INTO binder_item (id, project_id, type, title, order_key) VALUES ($1,$2,'folder','Act One','V')`,
      [actId, projectId],
    );
    const chapters = [
      ["Chapter One", "The lamp had not been lit.", "a"],
      ["Chapter Two", "She waited until morning.", "b"],
    ];
    for (const [chapterTitle, prose, key] of chapters) {
      const id = randomUUID();
      await pool.query(
        `INSERT INTO binder_item (id, project_id, parent_id, type, title, order_key)
         VALUES ($1,$2,$3,'document',$4,$5)`,
        [id, projectId, actId, chapterTitle, key],
      );
      await pool.query(
        `INSERT INTO document (id, content, synopsis, notes)
         VALUES ($1, $2::jsonb, 'SECRET SYNOPSIS', 'SECRET NOTE')`,
        [id, JSON.stringify({
          type: "doc",
          content: [{ type: "paragraph", content: [{ type: "text", text: prose }] }],
        })],
      );
    }
    return projectId;
  }

  async function queueJob(projectId: string, format: string, destination: string): Promise<string> {
    const jobId = randomUUID();
    await pool.query(
      `INSERT INTO compile_job (id, project_id, inline_config, format, destination, status)
       VALUES ($1, $2, '{}'::jsonb, $3, $4, 'queued')`,
      [jobId, projectId, format, destination],
    );
    return jobId;
  }

  async function jobRow(jobId: string) {
    const { rows } = await pool.query("SELECT * FROM compile_job WHERE id = $1", [jobId]);
    return rows[0];
  }

  test("a queued job is claimed, rendered, and written to disk", async () => {
    const projectId = await seedProject("The Lighthouse");
    const jobId = await queueJob(projectId, "md", "download");

    const handled = await drainQueue(pool, config);
    assert.ok(handled >= 1, "the worker must have handled at least this job");

    const job = await jobRow(jobId);
    assert.equal(job.status, "done");
    assert.ok(job.output_path, "a finished job must record where its file went");
    assert.ok(job.output_bytes > 0);
    assert.equal(job.word_count, 10, "6 words plus 4 words of prose");

    const written = await readFile(job.output_path, "utf8");
    assert.match(written, /The lamp had not been lit/);
    assert.match(written, /She waited until morning/);
  });

  test("SYNOPSES AND NOTES NEVER REACH THE FILE", async () => {
    const projectId = await seedProject("Private Thoughts");
    const jobId = await queueJob(projectId, "txt", "download");
    await drainQueue(pool, config);

    const job = await jobRow(jobId);
    const written = await readFile(job.output_path, "utf8");
    assert.doesNotMatch(written, /SECRET SYNOPSIS/);
    assert.doesNotMatch(written, /SECRET NOTE/);
  });

  test("warnings are stored with the job so the author sees them", async () => {
    const projectId = await seedProject("With A Folder");
    const jobId = await queueJob(projectId, "md", "download");
    await drainQueue(pool, config);

    const job = await jobRow(jobId);
    const warnings = job.warnings as { code: string }[];
    assert.ok(Array.isArray(warnings));
    assert.ok(warnings.some((w) => w.code === "notes_not_exported"));
    assert.ok(warnings.some((w) => w.code === "not_convertible"), "the folder must be reported");
  });

  test("destination decides which directory the file lands in", async () => {
    const projectId = await seedProject("Where Does It Go");
    const staged = await queueJob(projectId, "txt", "download");
    const stored = await queueJob(projectId, "txt", "server");
    await drainQueue(pool, config);

    assert.ok((await jobRow(staged)).output_path.startsWith(config.stagingPath));
    assert.ok((await jobRow(stored)).output_path.startsWith(config.storagePath));
  });

  test("a format outside this edition fails the job rather than producing something else", async () => {
    const projectId = await seedProject("Premium Format");
    const jobId = await queueJob(projectId, "docx", "download");
    await drainQueue(pool, config);

    const job = await jobRow(jobId);
    assert.equal(job.status, "queued", "retryable until attempts run out");
    assert.match(job.error_message, /not available in this edition/);
    assert.equal(job.output_path, null, "no file may be produced");
  });

  test("a failed job backs off instead of burning every retry at once", async () => {
    const projectId = await seedProject("Doomed");
    const jobId = await queueJob(projectId, "pdf", "download");

    for (let i = 0; i < 4; i++) await drainQueue(pool, config);

    const job = await jobRow(jobId);
    assert.equal(job.attempts, 1, "repeated draining must not spend the retries immediately");
    assert.equal(job.status, "queued");
    assert.ok(job.next_attempt_at, "a retry must be scheduled, not attempted at once");
    assert.ok(
      new Date(job.next_attempt_at).getTime() > Date.now(),
      "the next attempt must be in the future",
    );
  });

  test("retries do eventually stop, so a client is not left waiting forever", async () => {
    const projectId = await seedProject("Doomed Twice");
    const jobId = await queueJob(projectId, "pdf", "download");

    for (let attempt = 0; attempt < 4; attempt++) {
      await pool.query("UPDATE compile_job SET next_attempt_at = NULL WHERE id = $1", [jobId]);
      await drainQueue(pool, config);
    }

    const job = await jobRow(jobId);
    assert.equal(job.status, "failed");
    assert.equal(job.attempts, 3, "attempts must stop at maxAttempts");
    assert.ok(job.finished_at, "a client waiting on this must be told it ended");
  });

  test("draining an empty queue does nothing and does not throw", async () => {
    assert.equal(await drainQueue(pool, config), 0);
  });

  test("a job is claimed exactly once even when workers race", async () => {
    const projectId = await seedProject("Contended");
    await queueJob(projectId, "txt", "download");

    const [a, b, c] = await Promise.all([
      drainQueue(pool, config), drainQueue(pool, config), drainQueue(pool, config),
    ]);

    assert.equal(a + b + c, 1, "SKIP LOCKED must hand the job to exactly one worker");
  });
});
