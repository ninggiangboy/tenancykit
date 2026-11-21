const UUID = /\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi

/**
 * A list never shows a whole UUID: each one in the text becomes its first eight characters and an ellipsis, so
 * `subscription:01a1fc39-9ecf-7ef5-98aa-839c4d1e2f3a` reads `subscription:01a1fc39…`. The record's sheet holds the
 * whole value as a `CopyId`.
 */
export function shortenIds(text: string | undefined | null): string {
  return (text ?? '').replace(UUID, '$1…')
}
