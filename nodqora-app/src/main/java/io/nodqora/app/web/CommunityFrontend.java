// SPDX-License-Identifier: Apache-2.0
package io.nodqora.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * ADR-0151: the Community deployable serves its own frontend, and this is the only server-side
 * routing it needs.
 *
 * <p>ADR-0092 fixed the browser grammar at {@code /environments/{envKey}} plus an optional
 * {@code ?node=} — a <em>closed</em> grammar, which is why this is a forward that names it exactly
 * rather than the conventional SPA catch-all. A catch-all would return {@code index.html} with a
 * 200 for a missing bundle and for every typo; here a missing asset is still a 404, an unrouted path
 * is still a 404, and {@code /api/**} keeps ADR-0060's problem+json.
 *
 * <p><strong>The key is not validated against the roster.</strong> ADR-0093 draws the
 * unknown-environment screen in the frontend, naming the environments that do exist; checking here
 * would put that decision in two places and let them disagree. The server's job is to hand over the
 * application, which then reads the URL it was opened with.
 *
 * <p>The trailing-slash variant is mapped explicitly because Spring Boot 3 no longer matches it
 * implicitly, while {@code routing/route.ts} accepts it ({@code /^\/environments\/([^/]+)\/?$/}).
 * Without it a pasted URL would be routable in the client and a 404 on refresh.
 *
 * <p>This lives in {@code nodqora-app} rather than beside {@code GraphController} in
 * {@code nodqora-core} because it is a property of <em>this assembly</em>. ADR-0114 publishes core
 * as a library the Enterprise build consumes, and ADR-0117 has Enterprise build its own frontend, so
 * a forward to {@code /index.html} in core would fire in an assembly that has no such file.
 * ADR-0115 names this shape from the other side: an assembly adds controllers core never hears
 * about.
 */
@Controller
class CommunityFrontend {

    @GetMapping({"/environments/{environmentKey}", "/environments/{environmentKey}/"})
    String environment() {
        return "forward:/index.html";
    }
}
