import type { CompileInput } from "@noveltea/compile";

/** The shape the worker needs; matches one row of `compile_job`. */
export interface ClaimedJob {
  id: string;
  projectId: string;
  format: string;
  destination: string;
  presetId: string | null;
  inlineConfig: Record<string, unknown> | null;
  attempts: number;
}

interface Queryable {
  query(text: string, values?: unknown[]): Promise<{ rows: any[]; rowCount: number | null }>;
}

/**
 * Takes the oldest queued job, if any.
 *
 * <p>`FOR UPDATE SKIP LOCKED` is what makes more than one worker safe: each claim locks a
 * different row rather than queueing behind the same one, so scaling out needs no
 * coordination and a crashed worker blocks nobody.
 */
export async function claimNextJob(
  db: Queryable,
  maxAttempts: number,
  leaseSeconds = 600,
): Promise<ClaimedJob | null> {
  const { rows } = await db.query(
    `UPDATE compile_job
        SET status = 'running', started_at = now(), attempts = attempts + 1
      WHERE id = (
            SELECT id FROM compile_job
             WHERE attempts < $1
               AND (
                    (status = 'queued'
                     AND (next_attempt_at IS NULL OR next_attempt_at <= now()))
                 -- A worker killed mid-render leaves its job 'running' forever. Without
                 -- reclaiming it the job is never retried, the API's dedupe keeps handing
                 -- out the dead id, and its slot counts against the per-user pending
                 -- limit — five crashes lock an account out of exporting entirely.
                 OR (status = 'running'
                     AND started_at < now() - ($2 || ' seconds')::interval)
               )
             ORDER BY next_attempt_at NULLS FIRST, created_at
             FOR UPDATE SKIP LOCKED
             LIMIT 1)
      RETURNING id, project_id, format, destination, preset_id, inline_config, attempts`,
    [maxAttempts, leaseSeconds],
  );
  if (rows.length === 0) return null;
  const row = rows[0];
  return {
    id: row.id,
    projectId: row.project_id,
    format: row.format,
    destination: row.destination,
    presetId: row.preset_id,
    inlineConfig: row.inline_config,
    attempts: row.attempts,
  };
}

/** Everything in the binder, with document bodies attached. */
export async function loadProjectItems(db: Queryable, projectId: string): Promise<CompileInput[]> {
  const { rows } = await db.query(
    `SELECT b.id, b.title, b.type, b.parent_id, b.order_key, b.deleted_at,
            d.content, d.synopsis, d.notes
       FROM binder_item b
       LEFT JOIN document d ON d.id = b.id
      WHERE b.project_id = $1
      ORDER BY b.order_key`,
    [projectId],
  );
  return rows.map((row) => ({
    id: row.id,
    title: row.title,
    type: row.type,
    parentId: row.parent_id,
    orderKey: row.order_key,
    deletedAt: row.deleted_at ? String(row.deleted_at) : null,
    content: row.content ?? null,
    synopsis: row.synopsis ?? null,
    notes: row.notes ?? null,
  }));
}

export async function loadPresetSelection(
  db: Queryable,
  presetId: string | null,
): Promise<{ includedIds: string[] | null; separatorRules: Record<string, unknown> }> {
  if (!presetId) return { includedIds: null, separatorRules: {} };
  const { rows } = await db.query(
    `SELECT included_binder_items, separator_rules FROM compile_preset WHERE id = $1`,
    [presetId],
  );
  if (rows.length === 0) return { includedIds: null, separatorRules: {} };
  return {
    includedIds: rows[0].included_binder_items ?? null,
    separatorRules: rows[0].separator_rules ?? {},
  };
}

export async function markDone(
  db: Queryable,
  jobId: string,
  result: {
    outputPath: string | null;
    outputFilename: string | null;
    outputBytes: number | null;
    wordCount: number;
    warnings: unknown[];
  },
): Promise<void> {
  await db.query(
    `UPDATE compile_job
        SET status = 'done', finished_at = now(), output_path = $2, output_filename = $3,
            output_bytes = $4, word_count = $5, warnings = $6::jsonb, error_message = NULL
      WHERE id = $1`,
    [
      jobId,
      result.outputPath,
      result.outputFilename,
      result.outputBytes,
      result.wordCount,
      JSON.stringify(result.warnings),
    ],
  );
}

/**
 * Records a failure.
 *
 * <p>A job that has attempts left goes back to `queued` so it can be retried; one that has
 * exhausted them stays `failed` so a client stops waiting. The message is the author's only
 * explanation, so it is stored rather than only logged.
 */
export async function markFailed(
  db: Queryable,
  jobId: string,
  message: string,
  attempts: number,
  maxAttempts: number,
  backoff = exponentialBackoffSeconds,
): Promise<void> {
  const retryable = attempts < maxAttempts;
  await db.query(
    `UPDATE compile_job
        SET status = $2,
            error_message = $3,
            finished_at = CASE WHEN $2 = 'failed' THEN now() END,
            next_attempt_at = CASE WHEN $2 = 'queued'
                                   THEN now() + ($4 || ' seconds')::interval END
      WHERE id = $1`,
    [jobId, retryable ? "queued" : "failed", message.slice(0, 2000), backoff(attempts)],
  );
}

/** 30s, 2m, 8m. Long enough for a transient fault to clear, short enough to notice. */
export function exponentialBackoffSeconds(attempts: number): number {
  return 30 * Math.pow(4, Math.max(attempts - 1, 0));
}
