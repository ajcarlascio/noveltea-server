import type { CompileWarning, ProseMirrorNode } from "./types.ts";

/**
 * Node types the manuscript formats understand.
 *
 * <p>Anything absent from this list still has its text extracted — an unknown node is a
 * reason to warn, never a reason to lose an author's words.
 */
export const KNOWN_BLOCKS = new Set([
  "doc",
  "paragraph",
  "heading",
  "blockquote",
  "bulletList",
  "bullet_list",
  "orderedList",
  "ordered_list",
  "listItem",
  "list_item",
  "codeBlock",
  "code_block",
  "horizontalRule",
  "horizontal_rule",
  "hardBreak",
  "hard_break",
  "text",
]);

export const KNOWN_MARKS = new Set(["strong", "em", "code", "link", "underline", "strike"]);

/**
 * Walks a document, collecting text and reporting anything unrecognised.
 *
 * <p>Deliberately text-only: images, embeds and anything else carrying no words produce a
 * warning and contribute nothing. Converting them would cost time and produce output the
 * author did not ask for.
 */
export function inspect(doc: ProseMirrorNode | null | undefined): {
  text: string;
  warnings: CompileWarning[];
} {
  const warnings: CompileWarning[] = [];
  const seenNodes = new Set<string>();
  const seenMarks = new Set<string>();

  const walk = (node: ProseMirrorNode | undefined): string => {
    if (!node) return "";

    if (!KNOWN_BLOCKS.has(node.type) && !seenNodes.has(node.type)) {
      seenNodes.add(node.type);
      warnings.push({
        code: "unsupported_node",
        message: `"${node.type}" is not a text node; any text inside it is kept, the node itself is not converted`,
      });
    }
    for (const mark of node.marks ?? []) {
      if (!KNOWN_MARKS.has(mark.type) && !seenMarks.has(mark.type)) {
        seenMarks.add(mark.type);
        warnings.push({
          code: "unsupported_mark",
          message: `"${mark.type}" formatting is not carried into the output; the text it covers is kept`,
        });
      }
    }

    if (node.type === "text") return node.text ?? "";
    if (node.type === "hardBreak" || node.type === "hard_break") return "\n";

    const inner = (node.content ?? []).map(walk).join("");
    return isBlock(node.type) ? inner + "\n\n" : inner;
  };

  const text = walk(doc ?? undefined).replace(/\n{3,}/g, "\n\n").trim();
  return { text, warnings };
}

function isBlock(type: string): boolean {
  return [
    "paragraph", "heading", "blockquote", "codeBlock", "code_block",
    "listItem", "list_item", "horizontalRule", "horizontal_rule",
  ].includes(type);
}

/** Words, counted from text content only. Matches what the author sees. */
export function countWords(text: string): number {
  const trimmed = text.trim();
  return trimmed === "" ? 0 : trimmed.split(/\s+/).length;
}

/** Plain text, with no markup of any kind. */
export function toPlainText(doc: ProseMirrorNode | null | undefined): {
  output: string;
  warnings: CompileWarning[];
} {
  const { text, warnings } = inspect(doc);
  return { output: text, warnings };
}
