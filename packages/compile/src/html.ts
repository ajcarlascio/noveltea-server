import { inspect, KNOWN_MARKS } from "./text.ts";
import type { CompileWarning, ProseMirrorNode } from "./types.ts";

const ESCAPES: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
};

/** Escapes text and attribute values. Author text can contain anything. */
export function escapeHtml(value: string): string {
  return value.replace(/[&<>"]/g, (character) => ESCAPES[character]);
}

const MARK_TAGS: Record<string, string> = {
  strong: "strong",
  em: "em",
  code: "code",
  underline: "u",
  strike: "s",
};

/**
 * Serialises to XHTML-compatible HTML.
 *
 * <p>Every other format Core ships derives from this, so it stays well-formed: void
 * elements are self-closed and all text is escaped. Unknown nodes contribute their text
 * without a wrapper rather than being dropped.
 */
export function toHtml(doc: ProseMirrorNode | null | undefined): {
  output: string;
  warnings: CompileWarning[];
} {
  const { warnings } = inspect(doc);

  const renderText = (node: ProseMirrorNode): string => {
    let html = escapeHtml(node.text ?? "");
    for (const mark of node.marks ?? []) {
      if (mark.type === "link") {
        const href = escapeHtml(String(mark.attrs?.href ?? ""));
        html = `<a href="${href}">${html}</a>`;
      } else if (KNOWN_MARKS.has(mark.type) && MARK_TAGS[mark.type]) {
        const tag = MARK_TAGS[mark.type];
        html = `<${tag}>${html}</${tag}>`;
      }
    }
    return html;
  };

  const render = (node: ProseMirrorNode | undefined): string => {
    if (!node) return "";
    if (node.type === "text") return renderText(node);
    if (node.type === "hardBreak" || node.type === "hard_break") return "<br />";
    if (node.type === "horizontalRule" || node.type === "horizontal_rule") return "<hr />";

    const children = (node.content ?? []).map(render).join("");

    switch (node.type) {
      case "doc":
        return children;
      case "paragraph":
        return `<p>${children}</p>`;
      case "heading": {
        const level = Math.min(Math.max(Number(node.attrs?.level ?? 1), 1), 6);
        return `<h${level}>${children}</h${level}>`;
      }
      case "blockquote":
        return `<blockquote>${children}</blockquote>`;
      case "bulletList":
      case "bullet_list":
        return `<ul>${children}</ul>`;
      case "orderedList":
      case "ordered_list":
        return `<ol>${children}</ol>`;
      case "listItem":
      case "list_item":
        return `<li>${children}</li>`;
      case "codeBlock":
      case "code_block":
        return `<pre><code>${children}</code></pre>`;
      default:
        // Unknown node: keep the words, drop the wrapper.
        return children;
    }
  };

  return { output: render(doc ?? undefined), warnings };
}
