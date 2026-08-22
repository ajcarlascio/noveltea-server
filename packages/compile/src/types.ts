/** A ProseMirror node, as stored in `document.content`. */
export interface ProseMirrorNode {
  type: string;
  content?: ProseMirrorNode[];
  text?: string;
  marks?: { type: string; attrs?: Record<string, unknown> }[];
  attrs?: Record<string, unknown>;
}

/** One binder item offered to the compiler. */
export interface CompileInput {
  id: string;
  title: string;
  /** `folder`, `document` or `trash`. */
  type: string;
  parentId?: string | null;
  orderKey: string;
  deletedAt?: string | null;
  /** Present only for documents. */
  content?: ProseMirrorNode | null;
  /** Never exported. Accepted here only so the compiler can say so out loud. */
  synopsis?: string | null;
  notes?: string | null;
}

export type WarningCode =
  | "not_convertible"
  | "empty_document"
  | "excluded_trashed"
  | "notes_not_exported"
  | "unsupported_node"
  | "unsupported_mark"
  | "unsafe_link";

/**
 * Something the author should know before or after a compile.
 *
 * <p>Warnings are never fatal. The rule throughout is that text is preserved and the
 * author is told what happened, rather than the compile failing or quietly dropping
 * words.
 */
export interface CompileWarning {
  code: WarningCode;
  message: string;
  itemId?: string;
  itemTitle?: string;
}

/** An item that will actually contribute to the output. */
export interface PlannedItem {
  id: string;
  title: string;
  type: string;
  /** Depth in the binder, used for heading levels. */
  depth: number;
  /** False for folders, which contribute a heading and no prose. */
  hasText: boolean;
  content?: ProseMirrorNode | null;
}

export interface CompilePlan {
  included: PlannedItem[];
  warnings: CompileWarning[];
  /** Words that will be written, counted from text nodes only. */
  wordCount: number;
}

export interface CompileOptions {
  /** Emit folder titles as headings. Folders never contribute prose either way. */
  includeFolderHeadings?: boolean;
  /** Emit document titles as headings. */
  includeDocumentTitles?: boolean;
  /** Inserted between documents. Defaults to a blank line. */
  separator?: string;
  /**
   * Page setup for `html`. Omitted, the output is a fragment as before.
   *
   * Supplying it wraps the prose in a complete document whose stylesheet paginates it,
   * which is the only way page numbers can exist at all: the numbers come from the
   * renderer laying out the real text in the real font at the real size, not from
   * anything countable here.
   */
  page?: PageSetup;
  /** The document's title. Only used for a paginated export's <title>. */
  title?: string;
}

/**
 * How a paginated export is laid out.
 *
 * The defaults are standard manuscript format — 12pt serif, double-spaced, one-inch
 * margins — because that is what a submission is expected to look like, and it is not
 * the same thing as what an author likes on screen. Someone drafting in 19px Atkinson
 * still submits in 12pt double-spaced, and quietly exporting their screen settings
 * would produce a manuscript an agent bounces.
 */
export interface PageSetup {
  /** Any CSS `@page size` value: `letter`, `A4`, `5.5in 8.5in`. */
  size?: string;
  /** Any CSS length. */
  margin?: string;
  /** A CSS font stack. */
  fontFamily?: string;
  /** Points, as manuscripts are specified. */
  fontSizePt?: number;
  /** Unitless line height. 2 is double-spaced. */
  lineHeight?: number;
  /** Page numbers in the bottom margin. */
  pageNumbers?: boolean;
  /** Printed beside the page number, as a running head. */
  runningHead?: string;
  /** Start each document on a fresh page. */
  breakBetweenDocuments?: boolean;
}

export type CoreFormat = "txt" | "md" | "html";

export interface CompileResult {
  format: CoreFormat;
  output: string;
  warnings: CompileWarning[];
  wordCount: number;
}
