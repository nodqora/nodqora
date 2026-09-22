# Installing Nodqora

Community Nodqora ships as **one image and one `compose.yaml`** (ADR-0150). You need Docker with
Compose v2 and nothing else — no JDK, no clone, no build. If you have arrived here wanting to work
*on* Nodqora rather than run it, the source path is in [the README](../README.md#from-source).

The whole install is five steps, and the third and fifth are the ones that take thought.

## 1. Get the compose file

```bash
curl -LO https://github.com/nodqora/nodqora/releases/latest/download/compose.yaml
```

**Take it from the Release, never from this repository.** The `compose.yaml` in the tree carries a
`@VERSION@` placeholder where the image tag belongs and does not run as it stands — deliberately, so
that an unpinned install cannot be copied out of `main` (ADR-0155). The release renders it and
uploads *that* file.

The URL says `latest` and the file you get is **pinned**. `releases/latest/download/` resolves to the
most recent release and hands you its asset, which names one exact version. That is the intended
shape: a moving URL, an immovable file.

## 2. Start it

```bash
mkdir -p config topology
docker compose up -d
```

Make the two directories **before** the first `up`. They are bind mounts, and Docker creates a
missing mount source itself — as root, which on Linux leaves you needing `sudo` to write the config
file into a directory you are about to be told to edit.

Two containers come up — Nodqora and `postgres:16-alpine`, the same version the integration tests run
against. Postgres is required and deliberately not portable: the schema uses `jsonb`, generated
columns and partial unique indexes over `lower(btrim(...))`. Flyway migrates it on startup, so there
is no schema step for you to run.

The database lives in a named volume, which is not incidental — the canvas reasons about what it has
seen before (ADR-0087, ADR-0088), so a store that emptied on `docker compose down` would make the
second `up` lie about history.

Open **http://localhost:8080**. You should see:

> **No identity provider is configured.**

That is a **successful install**, not a failure. Nodqora started, migrated its schema and is serving
its frontend; it is withholding everything else until you say who may read it. An install that has
not been told is closed, and the API answers `401` to everyone (ADR-0173). Step 3 is where you tell
it.

Behind that screen the roster is empty too, and that is also on purpose. The image ships zero
environments: Spring's property merge lets a mounted file override a baked-in environment but never
*delete* one, so anything shipped in the image would be permanently stuck in your roster (ADR-0152).
Once sign-in is declared, an install with no environments says *No environments are configured*
instead.

If you see something else, jump to [Troubleshooting](#troubleshooting).

### It listens on this machine only

The compose file publishes the port as `127.0.0.1:8080:8080`, so Nodqora answers on the machine it
runs on and nowhere else. That is deliberate. What Nodqora shows — your environments, your
workloads, the URLs of your clusters — is a description of what you run, and step 3 lets you declare
an install that shows it without signing anyone in. On loopback, that means anyone on this machine.

If Nodqora is on a server and you are not:

- **Tunnel**, for one person: `ssh -L 8080:localhost:8080 <host>`, then open
  `http://localhost:8080` as above.
- **Sign in against your identity provider**, for a team: declare a provider
  ([Signing in](#signing-in)) and put a reverse proxy that terminates TLS in front of the port. The
  proxy reaches Nodqora on loopback, and every browser reaches it through the proxy's `https` URL.
- **Remove `127.0.0.1:`** from the `ports` line where people should reach Nodqora directly. With a
  provider declared, they must sign in first. With `none`, everyone who can reach the host sees
  everything Nodqora shows.

## 3. Declare sign-in and an environment

Write an `application.yaml` into the `config` directory beside your `compose.yaml`. It answers the
screen from step 2 with `nodqora.authentication`, and gives Nodqora something to show with
`nodqora.environments`:

```yaml
# ./config/application.yaml
nodqora:
  authentication:
    oidc:
      base-url: http://localhost:8080
      issuer: https://idp.example.com/realms/acme
      client-id: nodqora
  environments:
    homelab:
      display-name: Homelab
      plugins:
        kubernetes:
          namespaces: [n8n, monitoring, actual]
```

`nodqora.authentication` takes one of two answers:

- **An identity provider**, under `oidc`, as above. Anyone who can reach Nodqora is sent to your
  provider to sign in first. What each line means, what your provider needs to be told, and where a
  client secret goes are in [Signing in](#signing-in).
- **`none`**, written `authentication: none`. Anyone who can reach this install reads it without
  signing in. With the compose file's loopback port, that is anyone on this machine.

This file **merges with the one inside the image** rather than replacing it, so it carries
`nodqora.authentication` and `nodqora.environments` and nothing else — the plugin orders, the
refresh cadences and the cache and error settings all inherit. `/app/config` is Spring Boot's own default search location, which is why
no flag names it and why `compose.yaml` mounts `./config` there.

If you declare the `yaml` plugin, its topology is a second mount: one directory per environment
under `./topology/<environment>/`, read-only, matching ADR-0061's path. It is optional to *start*,
but with the `kubernetes` plugin alone it is the only source of edges, so leave it out and every
workload renders unconnected — [step 5](#5-draw-the-edges).

### Kubernetes needs a kubeconfig in the container

Nodqora runs in a container, and the container cannot see `~/.kube/config`. With no `kubeconfig`
key, the plugin assumes it is running *inside* the cluster, and every namespace fails:

> Kubernetes could not read discovery for this environment — namespace n8n could not be listed:
> no kubeconfig is configured and Nodqora is not running inside a cluster: listing deployments
> failed: UnknownHostException: kubernetes.default.svc

`kubectl` and `k9s` working on the same machine prove nothing here; they read a file the container
does not have. Unless Nodqora runs inside the cluster it observes, it takes three changes.

**1. Write a self-contained kubeconfig beside `compose.yaml`.** `--minify` keeps only the current
context, and `--flatten` inlines the certificates — a kubeconfig naming
`client-certificate: /home/you/...` points at a path that does not exist in the container:

```bash
kubectl config view --minify --flatten > kubeconfig
```

**2. Change its `server:` to an address the container can reach.** k3s, kind, minikube and Docker
Desktop write `https://127.0.0.1:<port>`, and inside the container `127.0.0.1` is the container.

| the cluster runs | `server:` |
|---|---|
| on another machine | that machine's LAN address, e.g. `https://192.168.1.50:6443` |
| on this machine, under Docker Desktop | `https://host.docker.internal:6443` |

The address must also be one the API server's certificate names, or the call fails on the TLS
hostname. k3s's certificate covers the node's own addresses; for `host.docker.internal` or any other
name, start k3s with `--tls-san <name>`.

**3. Mount it and reference it.** Under the `nodqora` service's `volumes:` in `compose.yaml` (newer
compose files carry this line commented out):

```yaml
      - ./kubeconfig:/etc/nodqora/kubeconfig:ro
```

and beside `namespaces` — the value is the file's *contents*, which `${file:...}` reads (ADR-0014):

```yaml
        kubernetes:
          namespaces: [n8n, monitoring, actual]
          kubeconfig: "${file:/etc/nodqora/kubeconfig}"
```

The file is a credential: keep it out of any repository that directory lives in. On Linux the
container reads it as uid `10001`, so a `0600` file you own is unreadable to it;
`sudo chown 10001 kubeconfig` keeps it private and readable. A kubeconfig whose credentials come
from an `exec` plugin (`aws eks get-token`, `gke-gcloud-auth-plugin`) needs that binary in the image,
which ADR-0154 keeps out — use a long-lived ServiceAccount token instead.

**[Running against your own cluster](running-against-your-own-cluster.md) is the full grammar** —
which plugin knows what, how a declared node joins an observed workload, and the annotations that let
your manifests carry the same facts. For a complete worked example, the repository's
`fixtures/reference-pipeline/application-demo.yaml` is a four-plugin configuration that the test
suite binds on every build, so it cannot rot without CI going red.

## 4. Restart

```bash
docker compose restart nodqora
```

Configuration is read at startup. Reload the page: with a provider declared it sends you to sign in
and back, and within one discovery cadence — five minutes by default — the canvas fills in.

If Nodqora exits instead, its log names the key it could not accept — `docker compose logs nodqora`.
A declaration that is there and wrong stops startup; only a missing one boots and explains
([What stops startup](#what-stops-startup)).

If you added a mount — the kubeconfig above — run `docker compose up -d` instead. `restart` reuses the
existing container, and the new mount is not in it.

## 5. Draw the edges

With only the `kubernetes` plugin, the canvas that fills in is every workload on its own row and
nothing connected. **That is correct, not broken.** Kubernetes knows what runs; it does not know what
talks to what, so the plugin emits no edges at all (ADR-0033). Nodqora does not guess them from
Services or network traffic either. Edges come from:

| source | edges it gives you |
|---|---|
| `yaml` — a topology file you write | anything you write down |
| `kafka`, `connect` | observed producers, consumers, topics and connectors |

For a cluster without Kafka, that means a topology file. Turn the `yaml` plugin on beside
`kubernetes` — `compose.yaml` already mounts `./topology` at `/etc/nodqora/topology`, so no new mount:

```yaml
# ./config/application.yaml
      plugins:
        yaml: { dir: /etc/nodqora/topology/homelab }
        kubernetes:
          namespaces: [n8n, monitoring, actual]
```

and write the edges into any `*.yaml` under `./topology/homelab/`:

```yaml
# ./topology/homelab/homelab.yaml
environment: homelab            # must match the environment key

owners:                         # optional: groups nodes on the canvas
  - key: apps
    displayName: Apps

nodes:
  - key: cloudflared            # the exact Deployment / StatefulSet / CronJob name
    owner: apps
    calls: [n8n]
  - key: n8n
    owner: apps
    calls: [postgres]
  - key: grafana
    queries: [prometheus]       # written from the consumer's side; the arrow still follows the data
```

Then `docker compose restart nodqora`. Four rules cover almost every surprise:

- **`key` must equal the workload's name exactly.** A mismatch is not an error — it renders as a second,
  unconnected card beside the real one. List the names first:
  `kubectl -n <namespace> get deploy,sts,cronjob --no-headers -o custom-columns=NAME:.metadata.name`.
- **DaemonSets are never nodes**, so nothing can point at one. A Deployment whose only peer is a
  DaemonSet — Longhorn's CSI sidecars, MetalLB's controller — has no edge to draw.
- **Six verbs.** `calls`, `producesTo` and `writesTo` are written from the source.
  `consumesFrom`, `sourcesFrom` and `queries` are written from the consumer, and stored reversed so
  the arrow always points the way data moves.
- **Declare the gaps, not the graph.** Anything this file sets that a plugin also observes is a
  permanent override of the live value, so write edges and owners and leave replicas and Services to
  Kubernetes.

A file with an unknown key or a YAML error is not ignored: the `yaml` plugin's discovery reports
`FAILED` with the file and the key named, and the Kubernetes nodes still render. While
you are iterating, `nodqora.refresh.discovery: 30s` shows each edit in half a minute instead of five.
Annotations on your manifests carry type, owner and links but **cannot** carry edges.
[Running against your own cluster](running-against-your-own-cluster.md#file-2--the-topology) has the
full grammar.

---

## Signing in

Nodqora signs people in with **OIDC against your own identity provider** — Keycloak, authentik,
Zitadel, Dex, Entra ID, Okta, Google, anything that publishes an OIDC discovery document (ADR-0172).
It holds no accounts of its own. **Everyone who signs in sees every environment in the install**;
there are no roles to assign (ADR-0109).

`nodqora.authentication` lives in `./config/application.yaml`, beside `nodqora.environments`. Leave
it out and the install stays closed behind the *No identity provider is configured* screen
(ADR-0173). Otherwise it is one of:

```yaml
nodqora:
  authentication:
    oidc:
      base-url: https://nodqora.example.com        # required
      issuer: https://idp.example.com/realms/acme  # required
      client-id: nodqora                           # required
      client-secret: ${file:/run/secrets/oidc}     # optional
      scopes: [openid, profile]                    # default
      name-claim: name                             # default
    session:
      idle: 30m                                    # default
      absolute: 12h                                # default
```

```yaml
nodqora:
  authentication: none
```

### Tell your provider about Nodqora first

Create an OIDC client at your provider — *application*, *client* or *app registration*, depending on
whose words — using the **authorization code** flow, with these two URLs:

| | |
|---|---|
| Redirect URI (*callback*, *valid redirect URI*) | `base-url` + `/login/oauth2/code/oidc`, e.g. `https://nodqora.example.com/login/oauth2/code/oidc` |
| Post-logout redirect URI | `base-url` + `/`, e.g. `https://nodqora.example.com/` |

The path is fixed; only `base-url` is yours. Register it exactly — a provider refuses a redirect URI
it was not given, on its own error page, where Nodqora cannot see it or log it.

The client can be **confidential**, with a secret, or **public**, without one. Nodqora uses PKCE on
every sign-in either way (ADR-0175). The provider's issuer URL and the client ID go into `issuer` and
`client-id`.

- **Google Workspace** is configured in the Google Cloud console, not the Workspace Admin console:
  an OAuth client of type *Web application*, with the consent screen's audience set to *Internal* to
  limit sign-in to your organisation. The issuer is `https://accounts.google.com`.
- **An identity provider that speaks only SAML** is put behind one that speaks OIDC — Keycloak,
  authentik and Zitadel all broker SAML — and Nodqora is pointed at that. Nodqora does not speak
  SAML (ADR-0172).
- **Two identity providers** are brokered the same way. An install has one.

### `base-url` is the URL a browser uses

`base-url` is Nodqora's address **exactly as a browser reaches it**: scheme, host and port, no path.
The redirect URI is built from it, and nothing else.

- **Behind a reverse proxy, it is the proxy's URL.** Nodqora never reads `X-Forwarded-*` for sign-in
  (ADR-0176), so the proxy needs no special headers, and a client that reaches the port directly
  cannot steer the redirect.
- **Nodqora is served from the root of that address.** `https://tools.example.com/nodqora` is
  refused at startup, because it has a path.
- **An `https` `base-url` makes the session cookie `Secure`.** Put TLS in front of any install that
  people reach over a network.

### A client secret is a reference, never a value

`client-secret` takes a **reference** that Nodqora resolves at startup, as every credential in this
file does (ADR-0014):

- **`${file:/path}`** reads a file inside the container. With a compose secret —

  ```yaml
  services:
    nodqora:
      secrets: [oidc]
  secrets:
    oidc:
      file: ./oidc-client-secret
  ```

  — the file arrives at `/run/secrets/oidc`, which is the `client-secret` in the example above. On
  Linux the container reads it as uid `10001`, so `sudo chown 10001 oidc-client-secret` keeps it
  private and readable.
- **`${env:NAME}`** reads an environment variable, set under the `nodqora` service's `environment:`.
  Name it something like `OIDC_CLIENT_SECRET`, not `NODQORA_AUTHENTICATION_…`: Spring also reads a
  variable as a key, and `NODQORA_AUTHENTICATION_CLIENT_SECRET` would be refused as one that is not
  a sign-in key.

**Spring's own `${NAME}` placeholder stays literal here.** Under `nodqora.authentication`, only
`${env:…}` and `${file:…}` are resolved; `${OIDC_CLIENT_SECRET}` or `${ISSUER:https://…}` reaches
Nodqora as those characters exactly. In `issuer` or `base-url` that stops startup as not a URL.
In `client-id` or `client-secret`, the provider rejects it at the first sign-in (ADR-0165, ADR-0176).

A reference that does not resolve — an unset variable, a file the container cannot read — stops
startup and names the key.

### `scopes` and `name-claim`

The top bar shows who is signed in. Nodqora reads the name from `name-claim`, then
`preferred_username`, then `sub`, so it is never blank. If your provider puts people's names in
another claim, name it here. Nodqora never reads an email address, so `scopes` does not ask for one.
A list without `openid` has it added.

### How long a session lasts

A session lives in Postgres behind one cookie. It survives a restart of Nodqora, and more than one
replica can share it (ADR-0175). It holds who signed in and no token.

| | Default | Ends a session that |
|---|---|---|
| `session.idle` | `30m` | has made no request for that long |
| `session.absolute` | `12h` | began that long ago, whatever it has done since |

An open canvas polls every thirty seconds, so a browser tab left open is never idle; it is the
absolute limit that ends it. **The absolute limit is also how long a user you disable at the provider
keeps reading**, because Nodqora does not ask the provider again until the session ends.

When a session ends, the page goes back to the provider and returns to the same URL. Your provider
has its own session, with its own limits. If it is still live, the round trip is silent. If it has
ended — the demo's Keycloak caps its own at ten hours, less than Nodqora's twelve — the provider asks
for a password.

**Sign out** ends the Nodqora session. When the provider's discovery document advertises
`end_session_endpoint` — Keycloak, authentik and Zitadel do, Google does not — it ends the session
at the provider too, which may sign that person out of other applications on the same provider.

### `none`

`authentication: none` serves everything to whoever can reach Nodqora, without signing in, as
`0.2.0` and earlier did. It says so twice, and quietly: a `WARN` in the log at every startup, and
*Open — no sign-in* at the right-hand end of the top bar.

It is a whole answer on its own. `none` beside anything under `oidc` or `session` stops startup,
and that includes a `NODQORA_AUTHENTICATION=none` in `compose.yaml` beside an `oidc:` block in the
file: Spring merges every source, so the two meet.

### What stops startup

Each of these exits with a log line naming the key (ADR-0176):

| Declaration | |
|---|---|
| `none` beside anything under `oidc` or `session` | contradictory |
| any value other than `none` or an `oidc` block | not a sign-in declaration |
| `oidc` without `base-url`, `issuer` or `client-id` | incomplete |
| `session` without `oidc` | session limits belong to a provider |
| `base-url` or `issuer` not an absolute `http` or `https` URL, or `base-url` with a path | malformed |
| `session.idle` longer than `session.absolute`, or either not positive | the idle limit could never fire |
| a key under `nodqora.authentication` that is not in the grammar above | usually a typo |
| a `${env:}` or `${file:}` that does not resolve | [see above](#a-client-secret-is-a-reference-never-a-value) |
| **anything under `spring.security.oauth2.client`** | Nodqora does not read Spring's own client settings. Declare sign-in under `nodqora.authentication.oidc` |

Only a missing `nodqora.authentication` boots and explains.

### When the provider is down

Nodqora does not contact your provider at startup. It fetches the provider's discovery document at
the first sign-in, and keeps it once it has it. A provider that is down therefore never stops
Nodqora booting, and:

- **anyone already signed in keeps reading**, because their session needs nothing from the provider;
- **anyone who needs to sign in** gets a page naming the issuer that could not be reached, with
  status `503`. The next sign-in tries again;
- **Nodqora logs an `ERROR`** naming the issuer and the cause, on every failed attempt.

An `issuer` with a typo in it looks exactly the same, so the first sign-in after a deploy is when you
find it.

### Probes and public paths

Point a liveness or readiness probe at **`GET /healthz`**: `200` and an empty body, with no session
needed, while Nodqora is running. It stays `200` while the provider is down, because Nodqora is up.
There is no Actuator.

Without a session, Nodqora answers only these: `/healthz`, `GET /session` (which of *signed in*,
*signed out*, *open* or *not configured* this browser is), the frontend's static files, and the
sign-in paths under `/oauth2/` and `/login/`. Every `/api` request answers `401`, and every page
sends the browser to sign in.

## Pointing at a PostgreSQL you already run

Delete the `postgres` service and its `depends_on` block, and set the three variables the compose
file already names:

```yaml
environment:
  NODQORA_DB_URL: jdbc:postgresql://db.internal:5432/nodqora
  NODQORA_DB_USER: nodqora
  NODQORA_DB_PASSWORD: ${NODQORA_DB_PASSWORD}
```

That triple is the whole database surface. Environment variables are **not** a way to declare an
environment: `KafkaConfig` holds dotted client keys like `security.protocol`, and Boot's environment
source maps `_` to `.`, so those keys are not expressible as variables at all. Credentials inside
your `application.yaml` stay out of the file as references — `${env:...}` and `${file:...}`, resolved
at use time and never persisted (ADR-0014).

## Upgrading

**Back up your database before you upgrade.** This is not general caution. Flyway is forward-only and
[ADR-0139](adr/0139-support-covers-the-latest-release-and-the-repo-keeps-one-line-of-development.md) leaves a regression no rollback path,
so `0.2.0` → `0.1.0` against one database is not a supported operation and will not become one. The
backup *is* your escape hatch.

```bash
pg_dump ... > nodqora-backup.sql          # or `docker compose exec postgres pg_dump ...`
curl -LO https://github.com/nodqora/nodqora/releases/latest/download/compose.yaml
docker compose up -d
```

What the number promises (ADR-0153):

| | |
|---|---|
| **patch** (`0.1.0` → `0.1.1`) | drop-in. Same config, no migration. |
| **minor** (`0.1.0` → `0.2.0`) | your `./config/application.yaml` **may need an edit**, and a schema migration **may run**. |

Below `1.0` the config grammar is still settling; `1.0` is the point at which it stops moving.

**Coming from `0.2.0` or earlier:**

- **Nodqora serves no data until you declare `nodqora.authentication`.** Every earlier release served
  everything to whoever reached it. This one boots to *No identity provider is configured* until
  `./config/application.yaml` names a provider or says `none` (ADR-0173). `none` is how `0.2.0`
  behaved; [Signing in](#signing-in) covers both. Add the key, then `docker compose up -d`.
- **The `compose.yaml` you download now publishes the port on loopback**, where the older one
  published it on every interface. If you open Nodqora from a machine other than the one it runs
  on, it will stop answering there after the upgrade — see
  [It listens on this machine only](#it-listens-on-this-machine-only).

**`latest` is not for installs.** The image tag exists and is honest — the latest release is the
entire support surface (ADR-0147) — so that `docker run ghcr.io/nodqora/nodqora` works for someone
thirty seconds into meeting this project. But every `compose.yaml` asset pins an exact version, which
is what stops a stray `docker compose pull` walking you across a one-way migration you did not
choose.

A bad release is **superseded, never deleted**. `0.1.1` supersedes `0.1.0` and `latest` moves; the
broken version stays pullable, so pinned compose files keep resolving.

### Security updates arrive as releases

The image's base is **pinned by digest**, so nothing picks up a patched OS userland until the pin is
bumped and a new version is cut (ADR-0154). A base-image CVE therefore reaches you as a *release* to
upgrade to, not as a rebuild of a tag you already have. The upside is the one that made the pin worth
it: rebuilding a given version produces that version, and the notices file describes what is actually
in the image rather than what was in it once.

## What the image bundles

Everything the build adds is attributed in a notices file inside the image:

```bash
docker run --rm --entrypoint cat ghcr.io/nodqora/nodqora:<version> /app/THIRD-PARTY
```

It lives at that path and nowhere else — not a release asset, not served over HTTP, not in the jar.
It covers what the build adds; the `eclipse-temurin` base attributes itself in place, and the file's
header says where to read that.

## Troubleshooting

**The page says "No identity provider is configured" after I wrote my config.**
Nodqora read no `nodqora.authentication`. Configuration is read at startup, so
`docker compose restart nodqora` first. If the screen stays, check that the key is spelled
`authentication` and sits under `nodqora:`, beside `environments:`, and that the file landed where
the container reads it — `docker compose exec nodqora ls /app/config`.

**Nodqora exits at startup, and the log names `nodqora.authentication`.**
The declaration is there and wrong, and the line says which key and why:
[What stops startup](#what-stops-startup).

**The provider shows its own error page about the redirect URI.**
The redirect URI registered at the provider is not `base-url` + `/login/oauth2/code/oidc`, exactly —
scheme, host, port and all. Nodqora never sees this failure, so its log is silent.
[Tell your provider about Nodqora first](#tell-your-provider-about-nodqora-first).

**A page says Nodqora cannot reach its identity provider.**
Either the provider is down or unreachable from the container, or `issuer` is not the provider's
issuer exactly. The `ERROR` in `docker compose logs nodqora` names the cause. `issuer` must match the
`issuer` in the provider's `/.well-known/openid-configuration` character for character, trailing
slash included. See [When the provider is down](#when-the-provider-is-down).

**Signing in comes back with "Signing in did not succeed."**
The provider sent the browser back with an error, or with a code or ID token Nodqora could not
verify. A `WARN` in the log names the provider's error code. A wrong `client-secret` shows up here.

**The name in the top bar is a string of letters and digits.**
That is the user's `sub`: the provider sent neither the claim named by `name-claim` nor
`preferred_username`. Set `name-claim` to the claim your provider puts names in.

**The page says "No environments are configured" after I wrote my config.**
Configuration is read at startup — `docker compose restart nodqora`. If it persists, your file did
not land where the container reads: `docker compose exec nodqora ls /app/config` should show
`application.yaml`. A mount typo is silent by design, because an empty roster is a better first five
minutes than a crash-loop diagnosable only by `docker logs`.

**The page never loads at all.**
That is a different state and Nodqora will say so rather than claim your config is empty — an
unreachable server and an unconfigured one are never reported as the same thing (ADR-0156). Check
`docker compose ps` and `docker compose logs nodqora`.

**Every namespace fails with "could not be listed", and `kubectl` works.**
The end of the reason names the cause:

| the reason ends with | fix |
|---|---|
| `no kubeconfig is configured and Nodqora is not running inside a cluster` | [give the container a kubeconfig](#kubernetes-needs-a-kubeconfig-in-the-container) |
| `ConnectException: Failed to connect to /127.0.0.1:…` | the kubeconfig's `server:` is the container itself — [change it](#kubernetes-needs-a-kubeconfig-in-the-container) |
| `UnknownHostException: <host>` | the container cannot resolve `server:`; use an IP address |
| `SSLPeerUnverifiedException: Hostname … not verified` | the API server's certificate does not name that address — `--tls-san` |
| `SSLHandshakeException: PKIX path building failed` | the kubeconfig's CA is not the one that signed the API server's certificate |
| `HTTP 401 Unauthorized` | the credential is expired or wrong |
| `HTTP 403 Forbidden: …` | the credential works but may not list that namespace; the message names the user and the resource |
| `the kubeconfig could not be read: …` | the value is not a kubeconfig — `kubeconfig:` takes the contents, so write `"${file:/etc/nodqora/kubeconfig}"`, not the bare path |

`docker compose exec nodqora grep server: /etc/nodqora/kubeconfig` shows what the container has; no
such file means the mount is missing or `docker compose up -d` has not run since it was added. A
cause Nodqora cannot vouch for is named by its class alone, never quoted, because a parse error
can print a kubeconfig line and the reason is shown in the UI.

**`docker compose up` fails on the image tag.**
You are running the repository's `compose.yaml` rather than the release asset — it names `@VERSION@`
on purpose. See [step 1](#1-get-the-compose-file).

**Nodqora crashed on the first `up` and worked on the retry.**
It should not: the compose file health-checks Postgres before starting Nodqora, because `depends_on`
alone waits for the container rather than for the server. If you edited that block out, put it back.

**Every node sits on its own row and nothing is connected.**
Expected with only the `kubernetes` plugin: it emits no edges. See [step 5](#5-draw-the-edges).

**My environment renders, but the nodes and my workloads are separate cards.**
That is the join, and it is a topology question rather than an install one —
see [Running against your own cluster](running-against-your-own-cluster.md#the-join-which-is-the-part-that-actually-matters).

## Getting help

**Open an issue.** That is the whole channel: support for the Apache-2.0 edition is public, so there
is no support email and no private channel, and every answer is where the next person will find it.
Support covers the latest release (ADR-0147), so say which version you are on — the running one is on
the first-run screen, and in the image's `org.opencontainers.image.version` label.

The commercial editions and the response commitment that comes with the paid one are in
[docs/editions.md](editions.md).
