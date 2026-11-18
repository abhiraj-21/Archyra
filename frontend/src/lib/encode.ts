/**
 * Percent-encodes a whole node id for use as one URL path segment. Node ids contain `/`, `#`, `(`, `)`, `:` and
 * spaces (`file:src/A.java`, `method:com.foo.A#m(String)`); the backend accepts the encoded slash. Every
 * node, class and file id that goes into a path must pass through here.
 */
export function encodeNodeId(id: string): string {
  // encodeURIComponent leaves ( ) ! * ' unescaped; encode them too so the id is one opaque segment.
  return encodeURIComponent(id).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}
