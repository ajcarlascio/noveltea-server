import { toHtml, escapeHtml } from "./html.ts";
import { toMarkdown } from "./markdown.ts";
import { planCompile } from "./plan.ts";
import { toPlainText } from "./text.ts";
import type {
  CompileInput, CompileOptions, CompileResult, CompileWarning, CoreFormat,
} from "./types.ts";

/** Formats Core ships. Everything else is provided by a commercial ExportProvider. */
export const CORE_FORMATS: readonly CoreFormat[] = ["txt", "md", "html"];

export function isCoreFormat(format: string): format is CoreFormat {
  return (CORE_FORMATS as readonly string[]).includes(format);
}

/**
 * Renders the planned items into one document.
 *
 * <p>The plan decides what is included; this only renders it. Warnings from planning are
 * carried through, so a caller that skipped {@link planCompile} still learns what was left
 * out.
 */
export function compile(
  items: CompileInput[],
  format: CoreFormat,
  options: CompileOptions = {},
): CompileResult {
  if (!isCoreFormat(format)) {
    throw new Error(`${format} is not available in this edition`);
  }

  // Normalised rather than forwarded: the option is optional on both sides, and passing
  // an explicit `undefined` is not the same as omitting it once a consumer turns on
  // exactOptionalPropertyTypes.
  const plan = planCompile(items, { includeFolderHeadings: options.includeFolderHeadings ?? false });
  const warnings: CompileWarning[] = [...plan.warnings];
  const separator = options.separator ?? "\n\n";
  const parts: string[] = [];

  for (const item of plan.included) {
    const headingLevel = Math.min(item.depth + 1, 6);

    if (!item.hasText) {
      parts.push(heading(format, item.title, headingLevel));
      continue;
    }

    const body =
      format === "txt"
        ? toPlainText(item.content)
        : format === "md"
          ? toMarkdown(item.content)
          : toHtml(item.content);

    const section =
      options.includeDocumentTitles === true
        ? heading(format, item.title, headingLevel) + body.output
        : body.output;
    parts.push(section);
  }

  return {
    format,
    output: parts.join(separator).trim() + (format === "html" ? "" : "\n"),
    warnings,
    wordCount: plan.wordCount,
  };
}

function heading(format: CoreFormat, title: string, level: number): string {
  switch (format) {
    case "html":
      return `<h${level}>${escapeHtml(title)}</h${level}>`;
    case "md":
      return `${"#".repeat(level)} ${title}\n\n`;
    default:
      return `${title}\n\n`;
  }
}
