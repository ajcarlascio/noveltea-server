/** Worker configuration. Mirrors the API's `noveltea.compile.*` paths. */
export interface WorkerConfig {
  connectionString: string;
  storagePath: string;
  stagingPath: string;
  /** Fallback poll interval. LISTEN delivers jobs promptly; this catches anything missed. */
  pollIntervalMs: number;
  maxAttempts: number;
  /** How long a claimed job may run before another worker may reclaim it. */
  leaseSeconds: number;
}

export function configFromEnv(env: NodeJS.ProcessEnv = process.env): WorkerConfig {
  const connectionString = env.NOVELTEA_DB_URL;
  if (!connectionString) {
    throw new Error(
      "NOVELTEA_DB_URL is not set. The worker reads jobs directly from Postgres and " +
        "will not start without it.",
    );
  }
  return {
    connectionString,
    storagePath: env.NOVELTEA_EXPORT_PATH ?? "/var/lib/noveltea/exports",
    stagingPath: env.NOVELTEA_STAGING_PATH ?? "/var/lib/noveltea/staging",
    pollIntervalMs: Number(env.NOVELTEA_POLL_INTERVAL_MS ?? 30_000),
    maxAttempts: Number(env.NOVELTEA_MAX_ATTEMPTS ?? 3),
    leaseSeconds: Number(env.NOVELTEA_LEASE_SECONDS ?? 600),
  };
}
