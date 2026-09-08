// SPDX-License-Identifier: Apache-2.0
/**
 * ADR-0156: the version this bundle was built from, replaced at build time by the `define` in
 * `vite.config.ts`. `null` in a working tree, which has no build argument.
 */
declare const __NODQORA_VERSION__: string | null
