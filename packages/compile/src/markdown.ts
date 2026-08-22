import { isSafeHref } from "./html.ts";
import { canonicalMark, inspect } from "./text.ts";
import type { CompileWarning, ProseMirrorNode } from "./types.ts";

/**
 * Escapes only what is genuinely ambiguous inline.
 *
 * <p>Escaping every `.` and `-` would put backslashes through ordinary prose — "it was
 * not lit\." is not what the author wrote. Block-level markers are handled separately by
 * {@link escapeLeadingBlockMarker}, because they only mean anything at the start of a line.
 */
function escapeMarkdown(value: string): string {
  return value.replace(/([\\`*_\[\]])/g, "\\$1");
}

/** `#`, `>`, `-`, `+` and `1.` are only syntax at the start of a line. */
function escapeLeadingBlockMarker(line: string): string {
  return line
    .replace(/^(\s*)([#>+-])(\s)/, "$1\\$2$3")
    .replace(/^(\s*)(\d+)\.(\s)/, "$1$2\\.$3");
}

/**
 * Serialises to Markdown.
 *
 * <p>Author text is escaped: a line beginning "# " in a novel is dialogue or a heading the
 * author typed literally, not a request for an H1.
 */
export function toMarkdown(doc: ProseMirrorNode | null | undefined): {
  output: string;
  warnings: CompileWarning[];
} {
  const { warnings } = inspect(doc);

  const renderText = (node: ProseMirrorNode): string => {
    let text = escapeMarkdown(node.text ?? "");
    for (const mark of node.marks ?? []) {
      switch (canonicalMark(mark.type) ?? mark.type) {
        case "strong": text = `**${text}**`; break;
        case "em": text = `*${text}*`; break;
        case "code": text = `\`${node.text ?? ""}\``; break;
        case "strike": text = `~~${text}~~`; break;
        case "link": {
          // Markdown links execute in renderers too; same allowlist applies.
          const href = String(mark.attrs?.href ?? "");
          if (isSafeHref(href)) text = `[${text}](${href})`;
          break;
        }
        default: break;
      }
    }
    return text;
  };

  const render = (node: ProseMirrorNode | undefined, depth = 0, ordinal = 0): string => {
    if (!node) return "";
    if (node.type === "text") return renderText(node);
    if (node.type === "hardBreak" || node.type === "hard_break") return "  \n";
    if (node.type === "horizontalRule" || node.type === "horizontal_rule") return "\n---\n\n";

    // A list item's marker is decided by its parent, so the position is passed down.
    // Zero means "not in an ordered list" and renders a bullet.
    const numbered = node.type === "orderedList" || node.type === "ordered_list";
    const children = (node.content ?? [])
      .map((child, index) => render(child, depth + 1, numbered ? index + 1 : 0))
      .join("");

    switch (node.type) {
      case "doc":
        return children;
      case "paragraph":
        return `${escapeLeadingBlockMarker(children)}\n\n`;
      case "heading": {
        const level = Math.min(Math.max(Number(node.attrs?.level ?? 1), 1), 6);
        return `${"#".repeat(level)} ${children}\n\n`;
      }
      case "blockquote":
        return `${children.trim().split("\n").map((line) => `> ${line}`).join("\n")}\n\n`;
      case "codeBlock":
      case "code_block":
        return `\`\`\`\n${plain(node)}\n\`\`\`\n\n`;
      case "listItem":
      case "list_item":
        return ordinal > 0 ? `${String(ordinal)}. ${children.trim()}\n` : `- ${children.trim()}\n`;
      case "bulletList":
      case "bullet_list":
      case "orderedList":
      case "ordered_list":
        return `${children}\n`;
      default:
        return children;
    }
  };

  const output = render(doc ?? undefined).replace(/\n{3,}/g, "\n\n").trim();
  return { output, warnings };
}

/** Raw text with no escaping, for code blocks. */
function plain(node: ProseMirrorNode): string {
  if (node.type === "text") return node.text ?? "";
  return (node.content ?? []).map(plain).join("");
}
