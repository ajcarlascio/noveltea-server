import { compile, isCoreFormat, type CompileInput, type CoreFormat } from "@noveltea/compile";
import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import type { WorkerConfig } from "./config.ts";
import type { ClaimedJob } from "./repository.ts";

const EXTENSIONS: Record<string, string> = { txt: "txt", md: "md", html: "html" };

/**
 * Turns a title into a filename.
 *
 * <p>Titles are author text: they contain slashes, quotes, emoji and occasionally a
 * newline. Anything outside a conservative set is replaced rather than escaped, because
 * this value becomes a path and a Content-Disposition header.
 */
export function filenameFor(title: string, format: string, now = new Date()): string {
  const slug = title
    .normalize("NFKD")
    .replace(/[^\w\s-]/g, "")
    .trim()
    .replace(/\s+/g, "-")
    .toLowerCase()
    .slice(0, 60) || "manuscript";
  const stamp = now.toISOString().replace(/[:.]/g, "-").slice(0, 19);
  return `${slug}-${stamp}.${EXTENSIONS[format] ?? format}`;
}

/** `download` is staged for collection; `server` lands in the operator's mount. */
export function directoryFor(job: ClaimedJob, config: WorkerConfig): string {
  return job.destination === "server" ? config.storagePath : config.stagingPath;
}

export interface RunResult {
  outputPath: string;
  outputFilename: string;
  outputBytes: number;
  wordCount: number;
  warnings: unknown[];
}

/**
 * Renders one job and writes its artifact.
 *
 * <p>Formats outside this edition are refused here as well as at submission: a job row can
 * outlive the configuration that created it, and a worker must never produce something
 * other than what was asked for.
 */
export async function runJob(
  job: ClaimedJob,
  items: CompileInput[],
  projectTitle: string,
  includedIds: string[] | null,
  config: WorkerConfig,
  now = new Date(),
): Promise<RunResult> {
  if (!isCoreFormat(job.format)) {
    throw new Error(`${job.format} is not available in this edition`);
  }

  const selected =
    includedIds && includedIds.length > 0
      ? items.filter((item) => includedIds.includes(item.id))
      : items;

  const result = compile(selected, job.format as CoreFormat, {
    includeFolderHeadings: true,
    includeDocumentTitles: false,
  });

  const directory = directoryFor(job, config);
  await mkdir(directory, { recursive: true });

  const filename = filenameFor(projectTitle, job.format, now);
  const path = join(directory, filename);
  await writeFile(path, result.output, "utf8");

  return {
    outputPath: path,
    outputFilename: filename,
    outputBytes: Buffer.byteLength(result.output, "utf8"),
    wordCount: result.wordCount,
    warnings: result.warnings,
  };
}
