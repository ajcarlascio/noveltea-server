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

/**
 * Schemes a link may use.
 *
 * <p>An allowlist, not a blocklist: `javascript:` is the obvious attack, but `data:`,
 * `vbscript:` and `file:` are all exploitable in one renderer or another, and the next one
 * has not been invented yet. Anything not named here loses its link and keeps its text.
 */
const SAFE_SCHEMES = ["http:", "https:", "mailto:"];

/**
 * Decides whether an href can be emitted.
 *
 * <p>Normalises first, because `JaVaScRiPt:`, a leading space and an embedded tab or
 * newline all reach the same place in a browser. Relative paths and fragments carry no
 * scheme and are safe.
 */
export function isSafeHref(href: string): boolean {
  const normalised = href
    // Control characters and whitespace are ignored by URL parsers but defeat naive checks.
    .replace(/[\u0000-\u0020\u007f-\u009f]/g, "")
    .toLowerCase();

  if (normalised === "") return false;
  if (normalised.startsWith("#") || normalised.startsWith("/") || normalised.startsWith("./")
      || normalised.startsWith("../")) {
    return true;
  }
  const scheme = normalised.match(/^([a-z][a-z0-9+.-]*:)/);
  if (!scheme) return true; // no scheme at all: a relative reference
  return SAFE_SCHEMES.includes(scheme[1]);
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
  const unsafeLinks: string[] = [];

  const renderText = (node: ProseMirrorNode): string => {
    let html = escapeHtml(node.text ?? "");
    for (const mark of node.marks ?? []) {
      if (mark.type === "link") {
        const raw = String(mark.attrs?.href ?? "");
        if (isSafeHref(raw)) {
          html = `<a href="${escapeHtml(raw)}">${html}</a>`;
        } else {
          // Keep the author's words, drop the link. Reported by inspect().
          unsafeLinks.push(raw);
        }
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

  const output = render(doc ?? undefined);
  if (unsafeLinks.length > 0) {
    warnings.push({
      code: "unsafe_link",
      message:
        `${unsafeLinks.length} link${unsafeLinks.length === 1 ? "" : "s"} used a scheme that is not `
        + "safe to publish and were removed; the text they covered was kept",
    });
  }
  return { output, warnings };
}
