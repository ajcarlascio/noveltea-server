import type { CompileInput, ProseMirrorNode } from "../src/types.ts";

export const text = (value: string, marks?: { type: string; attrs?: Record<string, unknown> }[]):
  ProseMirrorNode => ({ type: "text", text: value, ...(marks ? { marks } : {}) });

export const para = (...content: ProseMirrorNode[]): ProseMirrorNode => ({
  type: "paragraph", content,
});

export const heading = (level: number, value: string): ProseMirrorNode => ({
  type: "heading", attrs: { level }, content: [text(value)],
});

export const doc = (...content: ProseMirrorNode[]): ProseMirrorNode => ({ type: "doc", content });

export const document = (
  id: string,
  title: string,
  content: ProseMirrorNode | null,
  extra: Partial<CompileInput> = {},
): CompileInput => ({ id, title, type: "document", orderKey: id, content, ...extra });

export const folder = (id: string, title: string, extra: Partial<CompileInput> = {}): CompileInput => ({
  id, title, type: "folder", orderKey: id, ...extra,
});
