import { countWords, inspect } from "./text.ts";
import type { CompileInput, CompilePlan, CompileWarning, PlannedItem } from "./types.ts";

/**
 * Decides what a compile would actually convert, and says what it would not.
 *
 * <p>Run this before compiling. Two reasons: a long manuscript is expensive to render, so
 * the author should learn that half their selection is folders *before* waiting for it;
 * and silently converting something they did not mean to publish — a note, a trashed
 * draft — is worse than refusing.
 *
 * <p>Only `document` bodies carry prose. Folders contribute a title at most. Synopses and
 * notes are never exported under any option: they are the author's scaffolding, not the
 * book.
 */
export function planCompile(
  items: CompileInput[],
  options: { includeFolderHeadings?: boolean } = {},
): CompilePlan {
  const warnings: CompileWarning[] = [];
  const included: PlannedItem[] = [];
  const depths = depthOf(items);
  const trashed = trashedIds(items);

  let wordCount = 0;
  let sawNotes = false;

  for (const item of ordered(items)) {
    const depth = depths.get(item.id) ?? 0;

    if (item.synopsis || item.notes) {
      sawNotes = true;
    }

    if (item.deletedAt) {
      warnings.push({
        code: "excluded_trashed",
        message: `"${item.title}" is in the trash and will not be included`,
        itemId: item.id,
        itemTitle: item.title,
      });
      continue;
    }

    if (item.type === "trash") {
      continue;
    }

    // Trashing is a REPARENT, not a `deleted_at` write, so a discarded chapter still looks
    // like an ordinary live document — it simply sits under the trash node. Checking only
    // `deletedAt` above therefore misses it entirely, and it lands in the manuscript.
    if (trashed.has(item.id)) {
      warnings.push({
        code: "excluded_trashed",
        message: `"${item.title}" is in the trash and will not be included`,
        itemId: item.id,
        itemTitle: item.title,
      });
      continue;
    }

    if (item.type === "folder") {
      warnings.push({
        code: "not_convertible",
        message: `"${item.title}" is a folder: its title can appear as a heading, but folders hold no text`,
        itemId: item.id,
        itemTitle: item.title,
      });
      if (options.includeFolderHeadings) {
        included.push({ id: item.id, title: item.title, type: item.type, depth, hasText: false });
      }
      continue;
    }

    if (item.type !== "document") {
      warnings.push({
        code: "not_convertible",
        message: `"${item.title}" is a ${item.type} and cannot be converted`,
        itemId: item.id,
        itemTitle: item.title,
      });
      continue;
    }

    const { text, warnings: nodeWarnings } = inspect(item.content);
    if (text.trim() === "") {
      warnings.push({
        code: "empty_document",
        message: `"${item.title}" has no text and will not be included`,
        itemId: item.id,
        itemTitle: item.title,
      });
      continue;
    }

    for (const warning of nodeWarnings) {
      warnings.push({ ...warning, itemId: item.id, itemTitle: item.title });
    }

    wordCount += countWords(text);
    included.push({
      id: item.id,
      title: item.title,
      type: item.type,
      depth,
      hasText: true,
      // Normalised rather than passed through: CompileInput.content is optional, so
      // this could hand back `undefined` for a field typed `ProseMirrorNode | null`.
      // Harmless until a consumer turns on exactOptionalPropertyTypes, at which point
      // it is a type that does not describe the value.
      content: item.content ?? null,
    });
  }

  if (sawNotes) {
    warnings.push({
      code: "notes_not_exported",
      message: "Synopses and document notes are never exported; only document text is compiled",
    });
  }

  return { included, warnings, wordCount };
}

/**
 * Every item inside the trash, at any depth.
 *
 * <p>Walks down from each trash node rather than up from each item, so a deeply nested
 * discarded scene is caught as surely as a top-level one.
 */
function trashedIds(items: CompileInput[]): Set<string> {
  const childrenOf = new Map<string, CompileInput[]>();
  for (const item of items) {
    const key = item.parentId ?? "";
    const siblings = childrenOf.get(key) ?? [];
    siblings.push(item);
    childrenOf.set(key, siblings);
  }

  const trashed = new Set<string>();
  const walk = (id: string) => {
    for (const child of childrenOf.get(id) ?? []) {
      if (trashed.has(child.id)) continue; // a cycle in supplied items must not hang this
      trashed.add(child.id);
      walk(child.id);
    }
  };
  for (const item of items) {
    if (item.type === "trash") walk(item.id);
  }
  return trashed;
}

/** Binder order: parents before children, siblings by order_key. */
function ordered(items: CompileInput[]): CompileInput[] {
  const byParent = new Map<string, CompileInput[]>();
  for (const item of items) {
    const key = item.parentId ?? "";
    const siblings = byParent.get(key) ?? [];
    siblings.push(item);
    byParent.set(key, siblings);
  }
  for (const siblings of byParent.values()) {
    siblings.sort((a, b) => a.orderKey.localeCompare(b.orderKey));
  }

  const known = new Set(items.map((item) => item.id));
  const output: CompileInput[] = [];
  const visit = (parentKey: string) => {
    for (const item of byParent.get(parentKey) ?? []) {
      output.push(item);
      visit(item.id);
    }
  };
  visit("");
  // Items whose parent was not supplied are still the author's work: append rather than drop.
  for (const item of items) {
    if (!output.includes(item) && !known.has(item.parentId ?? "")) {
      output.push(item);
    }
  }
  return output;
}

function depthOf(items: CompileInput[]): Map<string, number> {
  const parents = new Map(items.map((item) => [item.id, item.parentId ?? null]));
  const depths = new Map<string, number>();
  for (const item of items) {
    let depth = 0;
    let cursor = item.parentId ?? null;
    const guard = new Set<string>();
    while (cursor && parents.has(cursor) && !guard.has(cursor)) {
      guard.add(cursor);
      depth += 1;
      cursor = parents.get(cursor) ?? null;
    }
    depths.set(item.id, depth);
  }
  return depths;
}
