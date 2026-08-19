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
}

export type CoreFormat = "txt" | "md" | "html";

export interface CompileResult {
  format: CoreFormat;
  output: string;
  warnings: CompileWarning[];
  wordCount: number;
}
