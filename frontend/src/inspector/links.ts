// SPDX-License-Identifier: Apache-2.0

/**
 * The `href` a link may be given, or null when it may not be given one.
 *
 * A link's URL is written by a plugin, and some plugins take it from whoever may annotate a
 * workload. React 18 renders a `javascript:` href as it is given one, and a click on it is script
 * in the viewer's session — so the drawer opens `http` and `https` and nothing else. The Kubernetes
 * plugin refuses the same URLs at the source; this is the one place every plugin's links pass.
 *
 * The URL is parsed rather than matched, because a browser strips tabs and newlines out of a scheme
 * before it reads it and a pattern does not.
 */
export function navigable(url: string): string | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return null
  }
  return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
}
