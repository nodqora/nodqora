// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import license from 'rollup-plugin-license'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const here = path.dirname(fileURLToPath(import.meta.url))

// ADR-0154: the npm half of THIRD-PARTY is emitted from inside the bundler, so the set it
// attributes is the set that actually ships. `npm ls --omit=dev` would over-list by 41% — nine
// `@types/*` packages, `csstype`, `js-tokens` and `loose-envify` are production dependencies that
// contribute no code to an ESM production build — and ADR-0146 rejected listing what an artifact
// does not contain. Emitted as data, not prose: ADR-0154 gives the Gradle side the one formatter,
// so both halves of the file are laid out by the same code.
//
// The fragment lands in `build/`, never in `dist/`. ADR-0151 copies `dist/` into the jar's
// `static/`, so anything written there is served over HTTP, and ADR-0154 puts the notices at
// /app/THIRD-PARTY and nowhere else.
const thirdPartyNpm = license({
  thirdParty: {
    includePrivate: false,
    output: {
      file: path.join(here, 'build', 'third-party-npm.json'),
      template: (dependencies) =>
        JSON.stringify(
          dependencies
            .map((d) => ({
              name: d.name ?? '',
              version: d.version ?? '',
              license: d.license ?? null,
              licenseText: d.licenseText ?? null,
              homepage: d.homepage ?? null,
              author: d.author?.text() ?? null,
            }))
            .sort((a, b) => a.name.localeCompare(b.name)),
          null,
          2,
        ),
    },
  },
})

export default defineConfig({
  plugins: [react()],
  build: {
    // Build-only: `vite dev` and the vitest runs have no artifact to attribute.
    rollupOptions: { plugins: [thirdPartyNpm] },
  },
  server: {
    // ADR-0053's three GETs, served by the Spring Boot app next door.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    globals: true,
    // The tests are over pure logic — layering, traversal, phrasing — and read the golden documents
    // straight off disk, so they need Node rather than a DOM. `canvas/pan.test.tsx` is the one
    // exception and opts itself into jsdom with a docblock: ADR-0069's viewport rule is a comparison
    // against a live viewport rather than a function, so it is asserted against the mounted canvas.
    environment: 'node',
  },
})
