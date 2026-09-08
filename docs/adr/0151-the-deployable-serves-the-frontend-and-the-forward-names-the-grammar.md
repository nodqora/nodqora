# ADR-0151: The deployable serves the frontend, and the forward names the grammar

- **Status**: Accepted
- **Date**: 2026-09-08
- **Ticket**: [How the frontend is served](https://github.com/fredskor/nodqora/issues/62)

## Context

[ADR-0117](0117-enterprise-builds-its-own-frontend.md) recorded the state of play
as *"nothing serves it in production — Community's own packaging story is
unwritten"*, and deferred a publishable npm package to a trigger this map cannot
fire. [ADR-0150](0150-the-community-distribution-is-one-public-image-and-a-compose-file.md)
then answered the packaging half: one image, one Compose file, one process. That
narrowed this question from *whether there is a second container* to a mechanism.

The facts, verified in the tree:

- **The client already assumes one origin.** `frontend/src/api/client.ts` fetches
  `/api/meta` and friends as **relative** paths. A split deployment would need a
  configurable base URL, a CORS policy and a preflight story, none of which
  exist — so same origin is not a convenience discovered afterwards, it is what
  the code was written against.
- **`nodqora-app` had no `static/` directory and no controller of its own**;
  `GraphController` (`@RequestMapping("/api")`) lives in `nodqora-core`, one of
  ADR-0114's six library artifacts the Enterprise build consumes.
- **The browser grammar is closed and tiny.**
  [ADR-0092](0092-the-url-is-an-environment-path-and-a-node-parameter.md)
  fixes it at `/environments/{envKey}` — a single path segment — plus an optional
  `?node=`. [ADR-0093](0093-root-redirects-and-an-unknown-environment-names-the-roster.md)
  puts **both** the bare-`/` redirect and the unknown-environment not-found in
  the **frontend**, because both need the roster from `/api/meta`.
- **`spring.mvc.problemdetails` is enabled** (ADR-0060), so an unrouted path
  already answers in RFC 9457 rather than in HTML.

## Decision

**The Community deployable serves its own frontend, from its own origin, out of
its own jar.**

**The frontend build stays outside Gradle.** The image's node stage runs
`npm ci && npm run build`; the Gradle stage copies `frontend/dist/` into
`nodqora-app/src/main/resources/static/` before `bootJar`. Gradle never learns
about npm, `./gradlew build` needs no Node, and the `npm run dev` + Vite-proxy
inner loop is untouched. The seam is one directory copy, in one direction, at one
moment in the Dockerfile.

**One forward, naming ADR-0092's grammar exactly:**

```java
@GetMapping({"/environments/{environmentKey}", "/environments/{environmentKey}/"})
String environment() {
    return "forward:/index.html";
}
```

Not a catch-all. The grammar is *closed*, so the server can match it rather than
guess at it: a missing bundle stays a 404, an unrouted path stays a 404, and
`/api/**` keeps its problem+json. The trailing-slash variant is mapped explicitly
because Spring Boot 3 stopped matching it implicitly while `routing/route.ts`
accepts it (`/^\/environments\/([^/]+)\/?$/`) — left alone, the two disagree and a
pasted URL is routable in the client but a 404 on refresh.

**The server does not validate the environment key.** ADR-0093 draws the
not-found screen, naming the environments that do exist. Checking here would put
that decision in two places and let them disagree, and the server would have to
duplicate the roster to do it.

**The forward lives in `nodqora-app`, not in `nodqora-core`.** Serving a frontend
is a property of *this assembly*. ADR-0114 publishes core as a library the
Enterprise build compiles against, and ADR-0117 has Enterprise build its own
frontend — so a forward to `/index.html` in core would fire in an assembly that
has no such file. [ADR-0115](0115-a-core-seam-requires-a-community-consumer.md)
already names this shape from the other side: an assembly adds *"beans,
controllers and schedulers that core never hears about"*. This is Community's
turn to do exactly that.

**`/assets/**` is immutable for a year; everything else static is `no-cache`.**
Vite content-hashes its bundles, so for those the filename *is* the version and a
re-fetch can only return the same bytes. `index.html` has a fixed name and names
the hashes, so it is the one file that must be revalidated — otherwise an
upgraded image hands a client an `index.html` cached yesterday, pointing at
bundles the new image no longer contains. The symptom is a blank canvas only a
hard refresh clears, and [ADR-0147](0147-only-the-latest-release-is-supported.md)
makes the latest release the whole support surface, so it would arrive as a
support request rather than a bug report. Spring Boot sends **no**
`Cache-Control` for static resources by default, so this is a thing that had to
be decided rather than inherited.

Rejected, and why:

- **Gradle owning the frontend build** (a node-gradle plugin wiring `dist/` into
  `processResources`). One command producing a complete artifact anywhere is
  genuinely attractive, and it would put Jib back on the table. Refused because
  it makes every backend build provision Node and pay for a frontend build, for a
  benefit only the release machine collects — and ADR-0150 already decided the
  image, so reopening it on a build plugin's terms is the tail wagging the dog.
- **A `PathResourceResolver` catch-all** returning `index.html` for anything that
  matches no file. The conventional SPA setup, and it survives future routes with
  no server change. Refused because it returns 200 + HTML for a missing bundle:
  a broken deploy then presents as a blank page with nothing failing anywhere in
  the network tab. It also renders the app shell for every typo, which is
  ADR-0093's designed not-found replaced by an accident.
- **A 404 `ErrorPage` remapped to `index.html`.** Zero routing code, catches
  everything. Refused because it launders *every* 404 in the application into a
  200 HTML page — the honesty layer's own failure mode, expressed as a status
  code.
- **Serving `dist/` from the filesystem in the runtime stage**
  (`static-locations=file:/app/static`). Better layer caching: a CSS change
  rebuilds no Java. Refused because it makes serving the UI a property of the
  container's configuration rather than of the application, so the jar and the
  image would disagree about what Nodqora is.
- **A blanket short `cache.period`.** One property, bounded staleness — and it
  throws away the immutability the hashed filenames already bought, while still
  leaving a window where `index.html` and its bundles disagree.

## Consequences

- **`nodqora-app` stops being pure wiring.** It was one class; it now has a
  `web` package. That is the honest place for it, and ADR-0115 anticipated the
  shape, but the module's job description has widened.
- **A boot jar built on a laptop has no UI.** `./gradlew bootJar` produces a
  backend-only jar; only the image's jar carries `static/`. This is contained
  rather than dangerous — ADR-0114 excluded `nodqora-app` from the published six
  and ADR-0150 publishes only the image, so the jar is not an artifact anyone
  receives. It is still a divergence to know about when debugging a release.
- **`nodqora-app/src/main/resources/static/` is a build product inside a source
  tree.** It is gitignored, and listed in `.dockerignore` so that a developer's
  stale local build cannot arrive through the Dockerfile's `COPY . .` and win
  over what the node stage just produced.
- **The frontend's tests still run under npm, and nothing in Gradle knows.** The
  release gate of [ADR-0145](0145-the-release-gate-is-a-gradle-task.md) is a
  Gradle task, so it currently cannot see `vitest`. Whether the gate has to reach
  across that line belongs to
  [cutting the release](https://github.com/fredskor/nodqora/issues/68).
- **A future route is a server change.** Naming the grammar buys precision and
  charges for growth: adding a second browser route means adding a mapping. The
  charge is deliberate — ADR-0092 made the grammar closed, and if it stops being
  closed that is a decision worth noticing rather than absorbing silently.
- **ADR-0117's premise is now fully expired.** Both halves of *"nothing serves it
  in production — Community's own packaging story is unwritten"* have been
  answered: ADR-0150 for the packaging, this for the serving. **The npm trigger is
  untouched.** Nothing here extracts a component API, exports a slot, or gives
  Enterprise anything to import; Enterprise still builds its own frontend and
  composes its own routes.
- **Revisit trigger.** A second frontend route that is not an environment path —
  at which point the closed grammar is no longer closed, and the forward should
  be reconsidered as a whole rather than extended a mapping at a time.
