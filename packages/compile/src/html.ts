import { canonicalMark, inspect } from "./text.ts";
import type { CompileWarning, PageSetup, ProseMirrorNode } from "./types.ts";

const ESCAPES: Record<string, string> = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
};

/** Escapes text and attribute values. Author text can contain anything. */
export function escapeHtml(value: string): string {
  // The fallback is unreachable — the class and the table hold the same four characters
  // — but it keeps the return a string rather than `string | undefined`, and it fails
  // towards emitting the character unchanged rather than the word "undefined".
  return value.replace(/[&<>"]/g, (character) => ESCAPES[character] ?? character);
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
  // A matched group is typed optional. It cannot be missing here, and an unsafe link is
  // the right way to be wrong if it ever were: this allowlist fails closed.
  return scheme[1] !== undefined && SAFE_SCHEMES.includes(scheme[1]);
}

function cssValue(value: string, name: string): string {
  if (/[\r\n<>;{}]/.test(value)) {
    throw new Error(`${name} contains characters that are not valid in paginated HTML`);
  }
  return value;
}

function escapeCssString(value: string): string {
  return value
    .replace(/\\/g, "\\\\")
    .replace(/"/g, "\\22 ")
    .replace(/\r/g, "\\D ")
    .replace(/\n/g, "\\A ");
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
      if (canonicalMark(mark.type) === "link") {
        const raw = String(mark.attrs?.href ?? "");
        if (isSafeHref(raw)) {
          html = `<a href="${escapeHtml(raw)}">${html}</a>`;
        } else {
          // Keep the author's words, drop the link. Reported by inspect().
          unsafeLinks.push(raw);
        }
      } else {
        const tag = MARK_TAGS[canonicalMark(mark.type) ?? ""];
        if (tag) {
          html = `<${tag}>${html}</${tag}>`;
        }
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

/**
 * Wraps rendered prose in a complete document that paginates when printed.
 *
 * <p>Page numbers cannot be counted here. A page exists only once something lays the
 * text out — the same manuscript is a different number of pages in a different font, at
 * a different size, on a different paper. So the numbering is expressed as CSS and the
 * renderer resolves it against the actual type, which is what makes it correct rather
 * than an estimate.
 *
 * <p>`@page` margin boxes are the mechanism. Chrome 131 and Safari 18.2 both ship them,
 * so "print to PDF" from a current browser produces numbered pages with no extra tool.
 * Older renderers ignore the margin boxes and still get the size, margins and page
 * breaks — the output degrades to unnumbered pages rather than to nothing.
 */
export function toPagedDocument(
  bodyHtml: string,
  title: string,
  setup: PageSetup = {},
): string {
  const {
    size = "letter",
    margin = "1in",
    fontFamily = '"Times New Roman", Times, Georgia, serif',
    fontSizePt = 12,
    lineHeight = 2,
    pageNumbers = true,
    runningHead,
    breakBetweenDocuments = true,
  } = setup;

  const safeSize = cssValue(size, "size");
  const safeMargin = cssValue(margin, "margin");
  const safeFontFamily = cssValue(fontFamily, "fontFamily");
  const head = runningHead === undefined ? "" : `${escapeCssString(runningHead)} `;
  const marginBox = pageNumbers
    ? `
    @bottom-right {
      content: "${head}" counter(page);
      font-family: ${safeFontFamily};
      font-size: ${String(fontSizePt)}pt;
    }`
    : "";

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>${escapeHtml(title)}</title>
<style>
@page {
  size: ${safeSize};
  margin: ${safeMargin};${marginBox}
}
body {
  font-family: ${safeFontFamily};
  font-size: ${String(fontSizePt)}pt;
  line-height: ${String(lineHeight)};
  /* Manuscripts are ragged-right: justification changes word spacing line by line,
     which is exactly the kind of variation a copy-editor should not have to read past. */
  text-align: left;
  margin: 0;
}
p { margin: 0; text-indent: 0.5in; }
/* The first paragraph after a break is flush left, as in a printed book. */
h1 + p, h2 + p, h3 + p, blockquote + p { text-indent: 0; }
h1, h2, h3 { font-weight: normal; page-break-after: avoid; break-after: avoid; }
h1 { font-size: ${String(fontSizePt)}pt; text-align: center; margin: 0 0 ${String(lineHeight)}em; }
blockquote { margin: 0 0 0 0.5in; }
/* Never leave one line of a paragraph alone on a page. */
p, blockquote, li { orphans: 2; widows: 2; }${
    breakBetweenDocuments
      ? `
.chapter + .chapter { break-before: page; page-break-before: always; }`
      : ""
  }
</style>
</head>
<body>
${bodyHtml}
</body>
</html>
`;
}
