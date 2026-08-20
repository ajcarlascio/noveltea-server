import pg from "pg";
import { configFromEnv, type WorkerConfig } from "./config.ts";
import {
  claimNextJob, loadPresetSelection, loadProjectItems, markDone, markFailed,
} from "./repository.ts";
import { runJob } from "./runner.ts";

const NOTIFY_CHANNEL = "noveltea_compile";

/**
 * Drains the queue until it is empty.
 *
 * <p>Loops rather than handling one job per notification: notifications can coalesce, and
 * a worker that stopped after one would leave jobs sitting until the next poll.
 */
export async function drainQueue(pool: pg.Pool, config: WorkerConfig): Promise<number> {
  let handled = 0;

  for (;;) {
    const client = await pool.connect();
    let job = null;
    try {
      await client.query("BEGIN");
      job = await claimNextJob(client, config.maxAttempts, config.leaseSeconds);
      await client.query("COMMIT");
    } catch (error) {
      await client.query("ROLLBACK").catch(() => {});
      client.release();
      throw error;
    }
    if (!job) {
      client.release();
      return handled;
    }

    try {
      const { rows } = await client.query("SELECT title FROM project WHERE id = $1", [job.projectId]);
      const projectTitle = rows[0]?.title ?? "manuscript";
      const items = await loadProjectItems(client, job.projectId);
      const { includedIds } = await loadPresetSelection(client, job.presetId);

      const result = await runJob(job, items, projectTitle, includedIds, config);
      await markDone(client, job.id, result);
      handled += 1;
      console.log(`compiled job ${job.id} -> ${result.outputPath} (${result.outputBytes} bytes)`);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`job ${job.id} failed: ${message}`);
      await markFailed(client, job.id, message, job.attempts, config.maxAttempts);
    } finally {
      client.release();
    }
  }
}

async function main(): Promise<void> {
  const config = configFromEnv();
  const pool = new pg.Pool({ connectionString: config.connectionString });

  // A dedicated connection holds the LISTEN; pooled ones get recycled and lose it.
  const listener = new pg.Client({ connectionString: config.connectionString });
  await listener.connect();
  await listener.query(`LISTEN ${NOTIFY_CHANNEL}`);

  let draining = false;
  const drain = async () => {
    if (draining) return;
    draining = true;
    try {
      await drainQueue(pool, config);
    } catch (error) {
      console.error("drain failed:", error);
    } finally {
      draining = false;
    }
  };

  listener.on("notification", drain);
  const timer = setInterval(drain, config.pollIntervalMs);

  const shutdown = async () => {
    clearInterval(timer);
    await listener.end().catch(() => {});
    await pool.end().catch(() => {});
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);

  console.log(`worker listening on ${NOTIFY_CHANNEL}; polling every ${config.pollIntervalMs}ms`);
  await drain(); // anything queued while the worker was down
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
