// SPDX-License-Identifier: Apache-2.0

/**
 * ADR-0175: a `401` is answered with a top-level navigation to the current URL, and the server sends
 * that to the provider and back. A module of its own because jsdom will not let a test replace
 * `location.assign`, and whether the shell navigates is the thing under test.
 */
export function renavigate() {
  location.assign(location.href)
}
