# Identity Resolution in Comparable Tools

Research input for [Identity-resolution rules for the MVP](https://github.com/fredskor/nodqora/issues/8)
and [Discovery-engine merge semantics](https://github.com/fredskor/nodqora/issues/12).
This document reports what four comparable systems actually do. It does not
recommend anything; the **Implications** section lists options and their costs
and stops there.

All load-bearing claims below were fetched from the cited primary source —
official docs, upstream repository files, or first-party API references. Where a
claim could not be checked against a primary source it says **not verified**
rather than guessing. Nothing here rests on a blog post or a listicle. Coverage is
uneven by design: DataHub and OpenMetadata were read down to model and service
source, ServiceNow only as far as its documentation site would render.

---

## Summary

Findings that bear on Nodqora's decision, stated as findings.

**Two of the four went finer than per-entity provenance; the two that did were
driven there by collection-valued fields with many concurrent writers, not by
scalar conflicts.** DataHub started at per-aspect (`SystemMetadata`, carrying
`runId` and `pipelineName`) and then added per-*value* attribution
(`MetadataAttribution` on individual owners, tags and glossary terms).
ServiceNow stores per-attribute-per-source values in `cmdb_multisource_data`.
Backstage stayed per-entity and shows no sign of moving. OpenMetadata keeps
per-entity provenance (`providerType`) plus a per-field change *log* that the
merge path never consults.

**The thing that forced finer grain was lost-update on shared collections, not
"who set this field".** DataHub's own words: a whole-aspect upsert means "when
you want to change even a single field within an aspect without modifying
others, you need to do a read-modify-write to avoid overwriting existing
fields". `ownership`, `globalTags`, `glossaryTerms` and `upstreamLineage` are
lists written independently by every ingestion source plus the UI, so an
aspect-granular replace makes those writers mutually destructive. Nodqora's
`sources[]`, `links[]` and `backings[]` are the same shape of field. Its scalars
(`ownerKey`, `description`, `displayName`) are not, and no system in this survey
needed per-field provenance to settle a scalar.

**Manual-override survival was solved three different ways, none of which reads
a provenance record at merge time.** DataHub splits storage by writer class into
parallel `editable*` aspects; OpenMetadata gates the write on *(verb, actor
class, is-the-field-already-non-empty)*; Backstage gives one writer exclusive
ownership of the whole entity via `locationKey`. ServiceNow is the only one that
consults a rule table, and it ranks *sources per attribute* — still not a record
of which source actually set the value.

**Annotation-driven resolution is the mainstream mechanism, not an exotic one.**
Backstage's Kubernetes plugin resolves many physical objects to one logical
entity through a `backstage.io/kubernetes-id` label on the objects matched
against an annotation of the same name on the entity — structurally identical to
the fixture's `topology.io/service: payments-enricher` on Deployment
`enricher-v2`. It also ships an escape hatch,
`backstage.io/kubernetes-label-selector`, an arbitrary selector query that takes
precedence over the id match.

**Environment-in-the-key is a live hazard, and DataHub's docs now argue against
their own design.** They warn that adding `platform_instance` to an existing
ingestion changes every URN and orphans descriptions, tags, lineage and
ownership; on the environment axis specifically they write "Environment
information is best handled by tags instead of fabric type which allows for
promotion over time". Backstage went further: `metadata.namespace` "has no
special semantics apart from bounding the name uniqueness constraint", and the
catalog FAQ discourages one entity per environment. Nodqora's
`(environmentKey, key)` sits on the side of this split that both projects now
caution about.

**ServiceNow's own justification for per-attribute provenance is audit, not
correctness.** "Without CMDB 360, details about the lower-priority discovery
sources whose values were rejected, are discarded. Also, it is difficult to
identify the source of an attribute value without CMDB 360." The winning value
was already determined by per-attribute *precedence* — a rule table. Provenance
was added so a human could see why, and so rejected values were not lost.
Precedence and provenance are separable, and only one of them is a merge input.

**Absence is the shared trap, and three of the four guard against it explicitly.**
OpenMetadata's code comment — "a connector that finds no comment on the column
omits the field entirely, and an override run must not read that absence as
'delete the description'" — ServiceNow's per-attribute `Update with Null` flag,
and DataHub's `fail_safe_threshold: 75.0` on stale-entity removal are the same
problem solved at three granularities. ADR-0004's "drift is absence" makes this
directly load-bearing for Nodqora.

**Stable-key churn ends in a duplicate or an orphan in all four, and three ship a
human remediation path rather than an automatic one.** DataHub is the exception
and the most instructive: it accepts URN immutability as a hard constraint and
provides an offline migration CLI that clones every aspect to a new URN and
repoints every incoming reference, rather than pretending rename is possible.

---

## Backstage (Software Catalog)

### 1. Canonical ID scheme

An entity is "uniquely identified by the triplet of its kind, namespace, and
name", written `[<kind>:][<namespace>/]<name>`
([entity references](https://backstage.io/docs/features/software-catalog/references/)).
[ADR009](https://backstage.io/docs/architecture-decisions/adrs-adr009/) fixes
both the string form and a compound form, and notes that only `name` is always
required in input data, while "In protocols, storage systems, or when referring
to entities externally, the entity ref always consists of all three parts". Refs
"should always be lowercased in an `en-US` locale" when passed between systems,
and receivers "should treat incoming refs case insensitively".

Validation, from the
[descriptor format](https://backstage.io/docs/features/software-catalog/descriptor-format/):
`metadata.name` is "Strings of length at least 1, and at most 63. Must consist of
sequences of `[a-z0-9A-Z]` possibly separated by one of `[-_.]`", unique per kind
within a namespace, case-insensitively. `metadata.namespace` is "sequences of
`[a-zA-Z0-9]`, possibly separated by `-`, at most 63 characters in total",
defaulting to `default`.

The ref is **minted by the author of the descriptor file**, not by the catalog.
There is a surrogate `uid` and an `etag`, but the ref is the addressable
identity.

**Stability**: no rename support. A changed `metadata.name` is a different
entity; names "may be reused after an entity is deleted from the registry",
which implies the old one must be deleted. What happens to the old row is
orphaning — see §5.

**Namespace is not an environment axis.** The descriptor format states the
namespace "is optional, and has no special semantics apart from bounding the name
uniqueness constraint if specified", and advises "it's practical to use the
default namespace for simplicity until it's necessary to use supplemental ones".
The [catalog FAQ](https://backstage.io/docs/features/software-catalog/faq/) goes
further on environments: an entity should represent "the 'human concept' of a
thing", and multiple running versions across environments should be one entity
with plugins showing per-environment runtime detail. This is the direct opposite
of ADR-0004.

### 2. Alias / secondary-key model

**There is no first-class alias or secondary-key concept.** Everything is
annotation convention. Keys are namespaced: an optional lowercase domain-name
prefix (≤253 chars) plus a name part matching "sequences of `[a-zA-Z0-9]`
separated by any of `[-_.]`, at most 63 characters"; values are strings of any
length; the `backstage.io/` prefix is reserved for core
([descriptor format](https://backstage.io/docs/features/software-catalog/descriptor-format/)).

The [well-known annotations](https://backstage.io/docs/features/software-catalog/well-known-annotations/)
that carry identity:

| annotation | meaning |
|---|---|
| `backstage.io/managed-by-location` | "a so called location reference string, that points to the source from which the entity was" fetched; "added automatically by the catalog and not meant for human editing" |
| `backstage.io/managed-by-origin-location` | "points to the location, whose registration lead to the creation of the entity" — differs when one location delegates to others |
| `backstage.io/source-location` | "A `Location` reference that points to the source code of the entity" |
| `backstage.io/orphan` | "either absent, or present with the exact _string_ value `"true"`" |

The load-bearing case for Nodqora is the Kubernetes plugin
([configuration](https://backstage.io/docs/features/kubernetes/configuration/)).
The entity carries `backstage.io/kubernetes-id: dice-roller`; the Kubernetes
objects carry the **label** `backstage.io/kubernetes-id: <BACKSTAGE_ENTITY_NAME>`.
Many objects sharing that label value resolve to one entity. Two escape hatches:
`backstage.io/kubernetes-namespace` scopes the lookup, and
`backstage.io/kubernetes-label-selector` lets you "write your own custom label
selector query that Backstage will use to lookup the objects", which "takes
precedence over the `backstage.io/kubernetes-id` method".

This is annotation-as-alias — exactly Nodqora's `topology.io/service` shape, and
many-to-one by construction. It is not registered anywhere central: there is no
index of "which entity claims label value X", so **de-confliction is not
performed**. Two entities annotated with the same `kubernetes-id` would both
match the same objects. Not verified whether Backstage warns about this.

Annotations are queryable through the catalog API's `filter` parameter on
`metadata.annotations.<key>`. **Not verified** from a fetched API reference page.

### 3. Merge and source-precedence rules

Three stages
([life of an entity](https://backstage.io/docs/features/software-catalog/life-of-an-entity/)):
**ingestion** by entity providers, **processing** by processors, **stitching**.

Providers "fetch raw data from external sources and seed unprocessed entities
into the database", and the constraint is stated flatly: "The database always
keeps track of the set of entities that belong to each provider; no two providers
can try to output the same entity."

Processors run downstream and "can emit new entities, errors, and relations", but
"the processing does not lead to deletion or unregistration of entities; it can
only call new entities into existence or update entities".

Stitching assembles the final entity from the processed entity, emitted errors,
relations from this entity's processing, and "relations emitted by _other_ entity
processing steps that happen to point at the current entity".

**Conflict handling is first-writer-wins by location key, not merge.** From
[custom entity providers](https://backstage.io/docs/features/software-catalog/external-integrations/entity-providers):
`locationKey` "is a conflict resolution key — an opaque string that should be
unique for each location where an entity could originate", and the rule is "If
the existing entity has no location key, the new entity wins. If the existing
entity has a location key, the new entity only wins when the location keys
match." Purpose: "This prevents 'rogue' takeovers of entities that belong to
other providers."

Providers push a *full mutation* — "replaces the entire bucket contents. The
catalog implements this as an efficient delta internally" — or a *delta
mutation* — "upserts or deletes specific entities in the bucket. This is a better
fit for event-based providers".

**Provenance is per-entity.** `backstage.io/managed-by-location` names one
location for the whole entity. There is no per-field attribution and no mechanism
to combine field values from two sources onto one entity: the second source is
rejected. Nothing in the
[BEP list](https://github.com/backstage/backstage/tree/master/beps) — 14 BEPs at
time of reading, covering notifications, frontend plugins, auth, scaffolder,
docs, plugin metadata, events, metrics, AI and connections — proposes entity
overlays, metadata merging or provenance. **Backstage has not moved toward
per-field attribution.**

### 4. Declared vs. discovered

The distinction largely does not arise, because the two do not merge. A
`catalog-info.yaml` and a discovery provider are both *sources of the whole
entity*; whichever owns the location key owns every field. The FAQ frames the
catalog as holding "_rarely changing_, _human curated_ data that is easily
overseen and _managed by the owners_ of those entities"
([FAQ](https://backstage.io/docs/features/software-catalog/faq/)) — the declared
file is the source of truth, and discovery mostly serves to *find* those files
rather than to author entity fields.

There is no documented mechanism for a manual override of a discovered entity's
field that survives rediscovery, and no UI-editable entity in core. **Manual
override survival is not a problem Backstage solves; it is one it avoids by
making the descriptor the only writer.**

Zero-to-one and many-to-one both work: an entity with no Kubernetes objects is
ordinary, and the `kubernetes-id` label maps many objects to one entity (§2).

### 5. Documented failure modes

| failure | documented behaviour and mitigation |
|---|---|
| duplicate descriptors | "When multiple `catalog-info.yaml` files with the same `metadata.name` property are discovered, one will be processed and all others will be skipped" ([configuration](https://backstage.io/docs/features/software-catalog/configuration/)) — silent loss |
| provider takeover | rejected by the `locationKey` rule (§3) |
| orphaning | "The stitching process injects a `backstage.io/orphan: 'true'` annotation on the child entity" when nothing registered keeps it alive ([life of an entity](https://backstage.io/docs/features/software-catalog/life-of-an-entity/)) |
| orphan cleanup | "The default behavior is to automatically remove orphaned entities"; `catalog.orphanStrategy: keep` retains them ([configuration](https://backstage.io/docs/features/software-catalog/configuration/)) |
| unresolvable relations | "Never throw errors due to 'soft' errors, in particular relations not matching an existing target" — let them in and check externally, "nudging people gently toward fixing their own metadata" ([FAQ](https://backstage.io/docs/features/software-catalog/faq/)) |
| processing errors | surfaced on the entity by stitching rather than failing the run |
| unbounded kinds | "By default, the catalog will only allow the ingestion of entities with the kind `Component`, `API`, and `Location`" |

Tunables: `catalog.processingInterval` ("only a suggested minimum") and
`catalog.stitchingStrategy` (`pollingInterval`, `stitchTimeout`) — stitching
"finalizes entities asynchronously via a worker queue".

The soft-error rule is the most transferable: an unresolvable reference is *data
to be shown*, not an error to raise. Directly applicable to Nodqora edges
pointing at absent nodes.

**Not verified**: the database unique constraint on `refresh_state.entity_ref`
and the `DefaultProcessingDatabase` conflict path were not read at source.

---

## DataHub

### 1. Canonical ID scheme

The URN: `urn:<Namespace>:<EntityType>:<ID>`, where the namespace is always `li`
— "All URNs available in DataHub are using `li` as their namespace. This can be
easily changed to a different namespace for your organization if you fork
DataHub" ([what is a URN](https://docs.datahub.com/docs/what/urn)). Complex URNs
nest other URNs in the ID position; `DatasetUrn` "contains 3 ID fields:
`platform`, `name` and `fabric`". `(`, `)` and U+241F are reserved anywhere, `,`
inside a tuple.

Identity is carried by a **key aspect**: "A key is a special type of aspect that
contains the fields that uniquely identify an individual Entity. Key aspects can
be serialized into _Urns_ … Moreover, _Urns_ can be converted back into key
aspect structs, making key aspects a type of 'virtual' aspect." All key fields
"must be of STRING or ENUM type" and "must be REQUIRED"
([metadata model](https://github.com/datahub-project/datahub/blob/master/docs/modeling/metadata-model.md)).

[`DatasetKey.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/metadata/key/DatasetKey.pdl)
has three required fields: `platform: Urn`, `name: string`, `origin: FabricType`
— giving
`urn:li:dataset:(urn:li:dataPlatform:redshift,userdb.public.customer_table,PROD)`
([dataset metamodel](https://docs.datahub.com/docs/generated/metamodel/entities/dataset)).
Note the in-source comment on `name`: "This is no longer to be used for Dataset
native name. Use name, qualifiedName from DatasetProperties instead" — the key's
name segment is an identity string, deliberately decoupled from display.

**Environment is inside the identifier.**
[`FabricType.pdl`](https://github.com/datahub-project/datahub/blob/master/li-utils/src/main/pegasus/com/linkedin/common/FabricType.pdl)
enumerates DEV, TEST, QA, UAT, EI, PRE, STG, NON_PROD, PROD, CORP, RVW, PRD, TST,
SIT, SBX, SANDBOX, CERT. Recipes set `env: str`, "The environment that all assets
produced by this connector belong to"
([`source_common.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/configuration/source_common.py)).

**So is instance.** Platform instances exist because the three-part key "does not
allow for easy representation of the multiplicity of platforms (or technologies)
that might be deployed at an organization within the same environment or fabric"
([platform instances](https://docs.datahub.com/docs/platform-instances)). The
instance is folded into the *name* segment, not added as a fourth tuple slot:
from `urn:li:dataset:(urn:li:dataPlatform:<platform>,<name>,ENV)` to
`urn:li:dataset:(urn:li:dataPlatform:<platform>,<instance.name>,ENV)`.
`make_dataset_urn(platform, name, env)` simply calls
`make_dataset_urn_with_platform_instance(..., platform_instance=None, ...)`
([`mce_builder.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/emitter/mce_builder.py)).

The instance is encoded **twice**: load-bearingly inside the URN string, and
again as a non-identifying, filterable
[`DataPlatformInstance.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/DataPlatformInstance.pdl)
aspect (`platform: Urn`, `instance: optional Urn`) used for search facets.

Minted by the ingestion recipe. `platform_instance` is "The instance of the
platform that all assets produced by this recipe belong to. This should be unique
within the platform", with `platform_instance_map` for cross-platform sources and
`PlatformDetail.platform_instance` documented as needing to "match with platform
instance name used in ingestion recipe of other datahub sources"
([`source_common.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/configuration/source_common.py))
— i.e. cross-source URN agreement is a convention enforced by nothing.

**Stability is absolute**: "DataHub URNs are immutable identifiers that must
remain unchanged once assigned to an entity", and URNs are compared as "exact,
case-sensitive strings"
([platform instances](https://docs.datahub.com/docs/platform-instances);
[lineage URN casing](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/dev_guides/lineage_urn_casing.md)).

**There is no rename primitive.** Display name lives in `datasetProperties.name`
and UI-edited display name in `editableDatasetProperties.name`; neither touches
identity. For `corpuser` the docs spell out the consequence: "The username (in
`corpUserKey`) is immutable once a user is created. If a user's username changes
in the source system: A new CorpUser entity must be created with the new username
/ Ownership and other relationships need to be migrated / The old user can be
soft-deleted using the status aspect"
([corpuser](https://docs.datahub.com/docs/generated/metamodel/entities/corpuser)).
Rename is a migration, and DataHub ships one — see §5.

### 2. Alias / secondary-key model

**There is no alias or secondary-key mechanism in the identity model.** One URN,
one entity, lookups by exact URN. The Python graph client exposes exact-URN reads
plus *search-based* name lookups (`get_domain_urn_by_name`,
`get_data_product_urn_by_name`) — not stored aliases
([`graph/client.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/graph/client.py)).

**Siblings are the many-to-one mechanism, and they merge at read time.**
[`Siblings.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/Siblings.pdl):
`siblings: array[Urn]` ("List of sibling entities", `@Relationship SiblingOf`)
and `primary: boolean` ("If this is the leader entity of the set of siblings").

Attached two ways. Automatically, server-side, by
[`SiblingAssociationHook.java`](https://github.com/datahub-project/datahub/blob/master/metadata-jobs/mae-consumer/src/main/java/com/linkedin/metadata/kafka/hook/siblings/SiblingAssociationHook.java)
("This hook associates dbt datasets with their sibling entities"), an MCL hook
enabled by default, acting as `urn:li:corpuser:__datahub_system_sibling_hook`,
firing only on dataset MCLs for `upstreamLineage`, `subTypes` or `datasetKey`.
Its rules are narrow: a dbt node with `subTypes` containing `source` and **exactly
one** non-dbt upstream becomes its sibling; a non-dbt dataset with exactly one dbt
upstream likewise — "We're assuming a data asset (eg. snowflake table) will only
ever be downstream of 1 dbt model", and more than one "logs an error and does
nothing". Or explicitly, by ingestion via patches: `dbt_is_primary_sibling`
"Controls sibling relationship primary designation … Uses aspect patches for
precise control"
([dbt source](https://docs.datahub.com/docs/generated/ingestion/sources/dbt)),
with the hook deferring — "Skip if any siblings exist to avoid conflicts with
patch-based management".

The **read-time merge rule is the most transferable detail here.** Lineage:
`SiblingGraphService` — "if you have siblings, we want to fetch their lineage too
and merge it in" — removes intra-cohort edges and unions the rest
([`SiblingGraphService.java`](https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/graph/SiblingGraphService.java)).
Entity payload: the web app deep-merges the cohort, taking **scalars from the
primary** and making **collections additive** — tags, terms, owners,
customProperties, fields, assertions, health, incidents and structured properties
are unioned by urn
([`siblingUtils.ts`](https://github.com/datahub-project/datahub/blob/master/datahub-web-react/src/app/entity/shared/siblingUtils.ts)).
"if no entity in the cohort is primary, just have the entity whos urn is
navigated to be primary." A `separateSiblings` flag un-merges on demand.

That is a complete, working answer to "two identities, one logical thing":
**scalars follow a nominated primary, collections union, and the merge is a view
rather than a write.**

`structuredProperties`, `customProperties` and `browsePathsV2` are attributes,
not keys; nothing documents them as alternate lookup keys.

**The closest thing to alias resolution is ingest-time casing reconciliation.**
Because URNs compare case-sensitively, Snowflake `DB.SCHEMA.TABLE` and Looker
`db.schema.table` are two entities and no edge is drawn. The legacy fix,
`convert_urns_to_lowercase`, "flattens every identity to lowercase … loses the
warehouse's real display casing and can even merge two genuinely different tables
(`MyTable` and `mytable`) into one entity". The recommended fix,
`flags.auto_resolve_lineage_urns`, "resolves each upstream reference to the casing
of the entity that **already exists** in DataHub" and stamps a `matchType` of
`EXACT` / `NORMALIZED` / `UNRESOLVED`. It is opt-in, rewrites *references* only
and never an entity's own identity, and "Only reconciles full-aspect (UPSERT)
lineage, not PATCH"
([lineage URN casing](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/dev_guides/lineage_urn_casing.md)).
The conservatism is explicit: "On case-sensitive platforms where two genuinely
different tables differ only by case, ambiguous references are left unchanged
rather than risk merging distinct entities."

**Not verified / does not exist**: a "Merged Entities" feature (no such docs
page), and corpuser identity merging (explicitly absent — LDAP `jdoe` and OIDC
`jdoe@company.com` are two unrelated entities, remedied only by username-format
consistency or create-new-and-migrate).

### 3. Merge and source-precedence rules

**The aspect is the atomic unit of write.** "we've focused in on the 'aspect' as
the atomic unit of write in DataHub. MetadataChangeProposal & MetadataChangeLog …
carry only a single aspect in their payload", and "multiple aspects associated
with the same Entity can be updated independently"
([MCP/MCL](https://github.com/datahub-project/datahub/blob/master/docs/advanced/mcp-mcl.md);
[metadata model](https://github.com/datahub-project/datahub/blob/master/docs/modeling/metadata-model.md)).
An aspect is "a structured document, or more precisely a `record` in PDL"
([what is an aspect](https://docs.datahub.com/docs/what/aspect)); the split
bought the "Ability to independently version different aspects".

`ChangeType` ∈ UPSERT ("Insert if not exists, update otherwise"), CREATE,
CREATE_ENTITY, UPDATE, DELETE, PATCH ("Patch the aspect instead of doing a full
replace"). MCL is `MetadataChangeLog includes MetadataChangeProposal` plus
`previousAspectValue` and `previousSystemMetadata`. The same doc records
**conditional writes** — `If-Version-Match` ("If the expected `version` does not
match the actual `version` stored in the database, the write will fail. This
prevents overwriting an aspect that has been modified by another process"),
`If-Modified-Since`, `If-None-Match: *`.

**Default writes clobber.** From
[Advanced: Patch](https://docs.datahub.com/docs/advanced/patch): "By default, most
of the SDK tutorials and APIs involve applying full upserts at the aspect level,
e.g. replacing the aspect entirely. This means that when you want to change even
a single field within an aspect without modifying others, you need to do a
read-modify-write to avoid overwriting existing fields." PATCH is JSON Patch
limited to **add** and **remove**, with "arrays … converted to maps so elements
are keyed, not indexed" — the mechanism that makes concurrent list writers safe.
Coverage is partial: "Traditional PATCH support is only available for a selected
set of aspects", listed in `SUPPORTED_TEMPLATES`.

**Provenance is stored at two grains, and this is the crux of the whole survey.**

*Per aspect*: every aspect row carries
[`SystemMetadata.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/mxe/SystemMetadata.pdl)
— `lastObserved` ("The timestamp the metadata was observed at"), `runId` ("The
original run id that produced the metadata"), `lastRunId`, `pipelineName` ("The
ingestion pipeline id that produced the metadata"), `registryName`,
`registryVersion`, `version`, `schemaVersion`, and `AuditStamp`s `aspectCreated`
("who created it") and `aspectModified` ("the actor that performed the
modification"). Storage is `metadata_aspect_v2`, primary key
`(urn, aspect, version)`, with a `systemmetadata` column
([`EbeanAspectV2.java`](https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/entity/ebean/EbeanAspectV2.java)).
Versioning keeps an audit trail: "v0 (latest), v1 (oldest), v2 (second oldest) …
you can also simply view all the non-zero versions as an audit trail"
([aspect versioning](https://github.com/datahub-project/datahub/blob/master/docs/advanced/aspect-versioning.md)).

*Per value*:
[`MetadataAttribution.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/MetadataAttribution.pdl)
is embedded on individual array elements — `TagAssociation`,
`GlossaryTermAssociation`, `Owner`, `StructuredPropertyValueAssignment`,
`DocumentationAssociation` — carrying `time`, `actor` ("This can either be a user
(in case of UI edits) or the datahub system for automation"), `source` ("The
DataHub source responsible … includes the specific metadata test urn, the
automation urn") and `sourceDetail` ("the actual regex rule, sql statement,
**ingestion pipeline ID**, etc."). It is search-indexed
(`ownerAttributionActors`, `tagAttributionSources`, …).

So **DataHub went finer than per-aspect, but only for collection-valued
metadata** — because "which pipeline produced this whole aspect" cannot answer
"who added *this one tag*". Scalars were left at per-aspect grain.

**Who wins when two sources write the same aspect: nobody arbitrates.** There is
no source-precedence table anywhere in the docs. `EntityServiceImpl` reads the
previous `runId` off the row and does `setLastRunId(previousRunId)` then
`setRunId(current)` — the row retains exactly two run identities, and the aspect
body is wholly replaced unless the writer used PATCH
([`EntityServiceImpl.java`](https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java)).
The only ordering guarantees are the opt-in conditional-write headers above.

The clearest admission that precedence remains unsolved is
[`Documentation.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/Documentation.pdl):

> This aspect supports multiple documentations from different sources. There is
> an implicit assumption that there is only one documentation per source. For
> example, if there are two documentations from the same source, the latest one
> will overwrite the previous one. If there are two documentations from different
> sources, both will be stored.

…with the caveat "The values of the documentation are not currently searchable.
This will be changed once this aspect develops opinion on which documentation
entry is the authoritative one." A source-keyed multi-value store with **no
precedence rule yet**. That is DataHub, in 2026, still working the exact problem
#12 has to settle.

### 4. Declared vs. discovered

**It depends on the field, and the docs never state it in one sentence.**

*Protected structurally.* Dataset description and display name: ingestion writes
`datasetProperties`, the UI writes
[`EditableDatasetProperties.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/dataset/EditableDatasetProperties.pdl):

> EditableDatasetProperties stores editable changes made to dataset properties.
> This separates changes made from ingestion pipelines and edits in the UI to
> avoid accidental overwrites of user-provided data by ingestion pipelines

The identical rationale governs
[`EditableSchemaMetadata.pdl`](https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/schema/EditableSchemaMetadata.pdl),
restated in prose on the
[dataset metamodel](https://docs.datahub.com/docs/generated/metamodel/entities/dataset):
"This separation allows the writes from the replication of metadata from the
source system to be isolated from the edits made in the UI." Different aspects,
different rows, no collision.

*Not protected structurally.* Entity-level `ownership`, `globalTags`,
`glossaryTerms` and `domains` have **no** `editable*` twin. They are single shared
aspects written by both UI and ingestion, and survival depends on the writer
choosing PATCH. The transformer docs say the quiet part out loud: `semantics`
controls "whether to overwrite or patch owners present on DataHub GMS server.
**These owners might be added from DataHub Portal**" — and the default is
`OVERWRITE`
([dataset transformers](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/transformer/dataset_transformer.md)).
Source-level `write_semantics` (dbt and others) defaults the other way, to
`PATCH`: "Whether the new tags, terms and owners to be added will override the
existing ones **added only by this source** or not"
([`dbt_common.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/dbt/dbt_common.py)).
`incremental_lineage` does the same for lineage by rewriting upserts into patches
([`incremental_lineage_helper.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/api/incremental_lineage_helper.py)).

**How UI writes are distinguished from ingested writes: the sentinel `runId`.**
The clearest primary statement is the sync-status doc:

> we look at the system metadata of all aspects associated with the entity. **We
> exclude any aspects where the system metadata `runId` value is unset or equal to
> `no-run-id-provided`, as this is what filters out changes made through the UI.**

([sync status](https://docs.datahub.com/docs/sync-status); the default is stamped
by `SystemMetadataUtils.generateSystemMetadataIfEmpty`). So DataHub *does* have a
declared-vs-discovered discriminator, and it is a per-aspect provenance field
being read as a boolean.

**Zero-to-one** is fully supported: UI-created glossary terms, domains, data
products, tags and datasets are ordinary entities. They are invisible to
stale-entity removal (only source-emitted URNs enter the checkpoint) and their
aspects carry the sentinel `runId`, so a declared-only entity has *no* sync status
rather than a stale one. **Many-to-one** is siblings (§2), restricted to datasets.

Known gap acknowledged in-repo: schema changes from ingestion do not cascade into
`editableSchemaMetadata`, so terms attached to dropped columns linger
([issue #11642](https://github.com/datahub-project/datahub/issues/11642)).

### 5. Documented failure modes

**URN drift is the flagship.** "Once a URN is created, it should never be
modified… **Orphaned Assets**: When URNs change, all metadata added outside of
ingestion (descriptions, tags, lineage, ownership) associated with the old asset /
**Integration Disruption** … / **Operational Overhead**: Teams must migrate all
references to new URNs." The mitigation is prescriptive naming from day one:
instance names must be "**Intrinsic to the data** … **Not subject to change** …
**Consistent across all ingestion sources**", technical not business
(`us-east-1-cluster-1`, not `customer_data_warehouse` or `redshift_v2`). And an
explicit escape hatch: use data products, tags, custom properties, glossary terms
or domains for business context — all rated "URN Impact: No change" against
Platform Instances' "Changes URN"
([platform instances](https://docs.datahub.com/docs/platform-instances)).

**A documented URN-migration path exists.** `datahub migrate
dataplatform2instance --platform <p> --instance <i>` "allows you to migrate your
entities from an instance-agnostic platform identifier to an instance-specific
platform identifier… if your users have added documentation or added tags or
terms to your datasets, then you should run this command"; siblings
`instance2instance` and `urns-mapping --mapping-file`
([CLI](https://docs.datahub.com/docs/cli)). Guarantees: "**All aspects are
migrated.** The set of aspects carried to the new URN is sourced from the entity
registry, so every user-authored aspect … is transferred — not a fixed subset"
and "**All incoming references are repointed.**" Limits are equally explicit:
timeseries aspects, browse paths and `schemaField` entities are not migrated;
`dataJob` URNs embed their parent `DataFlow` URN so migrating flows without jobs
leaves stale references; and per-aspect conflict handling is enumerated —
always-merged (ownership, tags, terms, lineage), strategy-dependent (schema),
mixed (`datasetProperties`: customProperties merged, description by strategy),
**never overwritten (`status` — "the target's soft-delete state is
authoritative")**
([`migrate.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/cli/migrate.py)).

**Stateful ingestion and mass-deletion guards.** Opt-in; `pipeline_name` is
mandatory and "If this is changed after using with stateful ingestion, the
previous state will not be available to the next run". Stale entity removal will
"automatically soft-delete the tables and views that are seen in a previous run
but absent in the current run"
([stateful ingestion](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/dev_guides/stateful.md)).
Three fail-safes against config-drift flip-flop
([`stale_entity_removal_handler.py`](https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/state/stale_entity_removal_handler.py)):

- `fail_safe_threshold: float = 75.0` — "Prevents large amount of soft deletes &
  the state from committing from **accidental changes to the source
  configuration** if the relative change percent in entities compared to the
  previous state is above the 'fail_safe_threshold'."
- zero-output guard — "The source did not produce any metadata. Despite stateful
  ingestion being enabled, we will not delete any metadata. This is a fail-safe
  mechanism to prevent the accidental deletion of all entities."
- source-failure guard — "The soft-deletion of stale entities will be skipped
  because the source reported a failure."

On any trip it copies the previous state forward so deletions still happen on the
next clean run. This is the most directly reusable safety design in the survey
for a system whose semantics are "drift is absence".

**Delete semantics** ([delete metadata](https://github.com/datahub-project/datahub/blob/master/docs/how/delete-metadata.md)):
soft delete sets `status.removed` and hides from the UI while keeping the entity
reachable by direct link; hard delete "will physically delete all rows for all
aspects of the entity. This action cannot be undone". Guidance: "Always use
`--dry-run`"; "Prefer reversible soft deletes". Containers do not cascade without
`--recursive`. Rollback is by `runId` — which is exactly what per-aspect
provenance is *for*, operationally.

**Orphaned siblings** are handled defensively rather than documented: the hook
filters cohort members through an existence check on every write ("clean up any
references to stale siblings that have been deleted") and re-fires on
`datasetKey` MCLs to rebuild after delete-and-re-ingest. A known correctness bug
is filed at [issue #10406](https://github.com/datahub-project/datahub/issues/10406);
the `>1 dbt upstream` case silently produces no siblings, logged as an error.

**Not verified**: what happens when `target_platform_instance` is unset or
disagrees with the warehouse recipe's `platform_instance`. Structurally the URNs
simply will not collide and you get two unlinked entities, but no documented
warning or validation was found.

---

## OpenMetadata

### 1. Canonical ID scheme

Two identifiers, both first-class. A server-minted **UUID** `id` ("Unique
identifier of this table instance") and a **fullyQualifiedName** built from the
containment path: "Fully qualified name of a table in the form
`serviceName.databaseName.tableName`"
([`table.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/entity/data/table.json)).
`name` is "Name of a table. Expected to be unique within a database";
`displayName` is separate and cosmetic.

From
[`basic.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/basic.json):
`entityName` is bounded at 256 chars with pattern `^((?!::)[^>"\x00-\x1f])*$`, and
`fullyQualifiedEntityName` is "A unique name that identifies an entity. Example
for table 'DatabaseService.Database.Schema.Table'", up to 3072 chars.

**The service name is the root namespace**, and is the closest analogue to
Nodqora's `environmentKey` — but it is a *service* (a connection to a specific
system), not an environment. Two environments are two services, hence two
entirely separate FQN trees. There is no environment dimension as such.

**Stability**: the UUID survives renames; the FQN does not, being derived. The API
addresses entities both ways (`GET /v1/{entityType}/name/{fqn}` and by id), so
renaming requires cascading FQN updates to descendants. Renames are handled as a
special case in
[`EntityRepository.java`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityRepository.java):
change consolidation bails out, logging "Skipping consolidation for {} - name
change detected" and "Skipping consolidation for {} - entity FQN changed in
session", and a rename publishes a `"rename-old"` event. **Not verified**: the
full cascading-rename implementation for children and the lineage rewrite it
implies.

### 2. Alias / secondary-key model

`EntityReference`
([`entityReference.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/entityReference.json))
is the universal pointer: required `id` (uuid) and `type` ("Entity type/class
name - Examples: `database`, `table`, `metrics`"), plus optional `name`,
`fullyQualifiedName`, `description`, `displayName`, `deleted` ("If true the entity
referred to has been soft-deleted"), `inherited` and `href`. Note that the
reference carries denormalized copies of name and FQN alongside the stable id, so
a rename leaves stale copies until they are refreshed — the same coupling cost
DataHub avoids by never renaming.

`Table` also carries `sourceUrl` and `sourceHash` ("Source hash of the entity"),
which is change detection rather than an identity key.

**There is no alias table and no secondary-key mechanism.** Identity is the FQN
path; a source whose native name differs from the FQN segment has no documented
place to record that native name as a resolvable key. This is the sharpest
contrast with Backstage annotations and with the fixture's `enricher-v2` case:
OpenMetadata assumes the source's own naming *is* the identity.

**Not verified**: `entityFQNHash`, named in the brief, was not located in a
fetched schema.

### 3. Merge and source-precedence rules

Two write verbs with different semantics. Ingestion uses **PUT**
(`createOrUpdate`); humans and fine-grained clients use **PATCH** (JSON Patch).

Every write produces a **per-field** change record.
[`entityHistory.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/entityHistory.json)
defines `ChangeDescription` with `fieldsAdded` ("Names of fields added during the
version changes"), `fieldsUpdated` ("Fields modified during the version changes
with old and new values"), `fieldsDeleted` and `previousVersion`; each
`FieldChange` carries `name`, `oldValue` and `newValue`. The entity carries
`version`, `updatedAt`, `updatedBy` ("User who made the update") and
`changeDescription` ("Change that lead to this version of the entity").

This is **per-field change history attributed to one actor per version** — richer
than ADR-0008's per-node list. But note what it is *not*: a log, not a precedence
input. Nothing in the merge path consults `changeDescription` to decide who wins.

Separately, per-entity provenance exists. `providerType` in
[`basic.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/basic.json):
"Type of provider of an entity. Some entities are provided by the `system`. Some
are entities created and provided by the `user`. Typically `system` provide
entities can't be deleted and can only be disabled. Some apps such as AutoPilot
create entities with `automation` provider type." Enum
`["system", "user", "automation"]`, default `"user"`. This is a `sources`-like
per-entity field, and it drives lifecycle permissions rather than field merge.

There is no per-source precedence table. Precedence is the rule in §4.

### 4. Declared vs. discovered — the mechanism

The sub-question OpenMetadata answers most concretely, and the answer is not
per-field provenance. It is a write-time guard on three variables: **the verb (PUT
vs PATCH), the actor class (bot vs human), and whether the field is already
non-empty.**

The user-facing switch is `overrideMetadata`
([`databaseServiceMetadataPipeline.json`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/metadataIngestion/databaseServiceMetadataPipeline.json)),
boolean, **default `false`**:

> Set the 'Override Metadata' toggle to control whether to override the existing
> metadata in the OpenMetadata server with the metadata fetched from the source.
> If the toggle is set to true, the metadata fetched from the source will override
> the existing metadata in the OpenMetadata server. If the toggle is set to false,
> the metadata fetched from the source will not override the existing metadata in
> the OpenMetadata server. This is applicable for fields like description, tags,
> owner and displayName

Enforcement is in
[`EntityRepository.java`](https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityRepository.java).
`updateDescription()`:

```java
if (operation.isPut()
    && !nullOrEmpty(original.getDescription())
    && updatedByBot()
    && !overrideMetadata) {
  // Revert change to non-empty description if it is being updated by a bot
  // This is to prevent bots from overwriting the description. Description need to be
  // updated with a PATCH request, or via the bulk path with overrideMetadata=true
  updated.setDescription(original.getDescription());
  return;
}
```

`updatedByBot()` is `Boolean.TRUE.equals(updatingUser.getIsBot())` — the
discriminator is the *identity of the current writer*, not a stored record of who
wrote the field last.

`updateColumnDescription()` adds a refinement with an instructive comment:

```java
// A bot PUT preserves a non-empty column description. A bulk force-sync
// (overrideMetadata=true) may replace it with a real source comment, but must never blank
// it: a connector that finds no comment on the column omits the field entirely, and an
// override run must not read that absence as "delete the description".
if (operation.isPut()
    && !nullOrEmpty(origColumn.getDescription())
    && updatedByBot()
    && (!overrideMetadata || nullOrEmpty(updatedColumn.getDescription()))) {
  updatedColumn.setDescription(origColumn.getDescription());
  return;
}
```

That comment is the most directly useful sentence in this document for Nodqora:
**absence in a discovery payload must not be read as deletion**, because a
connector that finds nothing omits the field rather than sending null.

`updateDisplayName()` layers policy on top, preserving a user-curated
`displayName` when the writing bot is denied `EDIT_DISPLAY_NAME`:

```java
// A bot whose policy denies EditDisplayName (e.g. the ingestion bot via DefaultBotPolicy /
// IngestionBotPolicy) must not clobber a user-curated displayName. A PUT or bulk update
// authorizes with the coarse EDIT_ALL operation, which does not intersect that field-level
// deny, so re-apply it here.
```

Domains, data products and certification use the same
`operation.isPut() && !nullOrEmpty(original.getX()) && updatedByBot()` pattern.
There is also **session change consolidation** — successive edits by one user
within a session timeout collapse into one version — deliberately skipped for bot
imports and renames.

| writer | verb | target field empty? | result |
|---|---|---|---|
| bot (ingestion) | PUT | empty | written |
| bot (ingestion) | PUT | non-empty | **reverted to existing value** |
| bot, `overrideMetadata: true` | PUT | non-empty | overwritten (but never blanked) |
| human | PATCH | either | written |

**Many-to-one / zero-to-one**: an entity may be created manually
(`providerType: user`) with no ingested source. Whether one physical object can
produce several OpenMetadata entities is **not verified** — the FQN containment
model implies one source object per entity, and no counter-example was found.

### 5. Documented failure modes

- **Soft delete**: `deleted`, "When `true` indicates the entity has been soft
  deleted", mirrored onto `EntityReference.deleted`. `updateDeleted()` enforces
  that "Update operation can't set delete attributed to true. This can only be
  done as part of delete operation", and that a PUT or PATCH against a
  soft-deleted entity restores it.
- **Blanking on absence** — guarded in code (above) rather than by configuration.
- **Rename churn** — consolidation skipped, `rename-old` event published; the
  broader cascade is **not verified**.
- **`markDeletedTables`** — the option exists but its description was **not
  verified**; no claim is made about its semantics.
- **FQN collisions and duplicate entities from FQN change** — **not verified**.
  No primary source was fetched describing the outcome.

---

## ServiceNow CMDB (Identification and Reconciliation Engine)

ServiceNow is the only one of the four that merges per-attribute *and* ships a
per-source-per-attribute store. It is also the only one whose docs describe what
that bought.

Coverage caveat: ServiceNow's documentation site frequently returns navigation
shells rather than content, and several plausible URLs 404. The gaps below are
larger than for the other three systems and are marked.

### 1. Canonical ID scheme

Every CI has a `sys_id` surrogate. Identity is established not by a natural key
field but by **identification rules** evaluated by IRE
([Identification rules](https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/c_IdentificationRules.html)):

> An identification rule applies to a CI class and consists of a single CI
> identifier and one or more identifier entries and related entries, each with a
> different priority.

Criterion attributes are "Designated sets of criterion attribute values of a CI
that can be used to uniquely identify the CI." Three kinds of identifier entry: a
**regular** entry on the CI's own attributes, a **lookup** entry using "a lookup
table (related table) which can be any table that has a reference to the CI that
is being identified", and a **hybrid** entry. **Related entries** are "rules that
are based on related CIs" that "let you create or update records on other tables
in which the data is associated with the CI being identified".

Entries are **ordered by priority**; guidance is to "Create strong identification
rules that are set with the highest priority for the strongest identifier entries
and related entries." The effect is a **cascade of candidate keys** — try serial
number, fall back to name, and so on. Materially different from all three catalog
tools, which have exactly one key each, and the only worked pattern in this survey
for the fixture's "three consumer-group naming conventions" problem.

**Independent vs dependent CIs**: independent CIs "exist on their own and are not
dependent on any other CIs", while dependent CIs "depend on a relationship to
another CI and can't exist on their own in the absence of the dependent
relationship". A dependent CI's identity is partly its parent's — the closest
prior art to resolving a connector node through the Connect cluster hosting it.

**Environment/scope**: `sys_domain` domain separation, and whether environment is
an attribute rather than part of the key, are **not verified**.

### 2. Alias / secondary-key model

Partially verified. The lookup identifier entry (§1) is the documented alias
mechanism: a related table "which can be any table that has a reference to the CI
that is being identified" supplies alternative keys — serial numbers and network
adapters being the canonical examples. Multiple lookup rows can point at one CI,
giving many-aliases-to-one-CI, and the priority order on identifier entries is
the de-confliction mechanism.

**Not verified**: the `sys_object_source` table (per-source native ID,
`last_scan`), `correlation_id` on `cmdb_ci`, and the specific lookup tables
`cmdb_serial_number` / `cmdb_ci_network_adapter`. These are named in the brief and
widely described in secondary sources, but no ServiceNow documentation page
describing them was successfully fetched. Treat as unverified.

### 3. Merge and source-precedence rules — per attribute, explicitly

The two halves are separated in the docs
([CMDB Identification and Reconciliation](https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/c_CMDBIdentifyandReconcile.html)):
identification rules "uniquely identify CIs based on CI attributes", while
reconciliation rules "manage authorization and update priority between discovery
sources" and "determine which discovery sources can update CI attributes". IRE
"provides a centralized framework for identifying and reconciling data from
different data sources".

A reconciliation rule's fields
([Create a CI reconciliation rule](https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/create-reconciliation-rule.html)):

| field | meaning |
|---|---|
| Discovery Source | the source being authorized |
| Priority | ranking among sources; "Smaller numbers designate higher priority" |
| Attributes | the specific class attributes this source may update |
| Apply to all attributes | authorizes the source for every attribute of the class |
| Update with Null | attributes this source may overwrite with null |
| Filter Condition | which CIs the rule applies to |

Resolution order — **specificity beats priority**:

1. "Rule configured for a specific attribute, has precedence over rule set with
   Apply to all attributes" — regardless of priority.
2. "Between two rules for the same attribute … the rule that is specific directly
   for the class has precedence over the derived rule."
3. Priority breaks ties at equal specificity.
4. Dynamic rules supersede static rules for the same attribute.

Write outcome: "Updates from a discovery source with a lower priority are
rejected, unless" the lower-priority source was the first to update the CI, or
"The CI became stale based on data refresh rules".

Two details worth carrying over. **`Update with Null`** is an explicit,
per-attribute answer to the same hazard OpenMetadata handles in code. The
**staleness escape** means a high-priority source that stops reporting eventually
yields, rather than freezing a stale value forever — a mechanism neither catalog
tool has.

**Provenance is per-attribute-per-source, and it is stored.** From
[CMDB 360 / Multisource CMDB](https://www.servicenow.com/docs/bundle/zurich-servicenow-platform/page/product/configuration-management/concept/multisource-cmdb.html):
"CMDB 360 data, consisting of records for each discovery source and CI
combination, is stored in the CMDB MultiSource Data [cmdb_multisource_data]
table." The reconciled value is still chosen by rules — "the Identification and
Reconciliation Engine (IRE) uses reconciliation rules to select a single discovery
source for the update" — but you may "Create a dynamic reconciliation rule" that
"uses CMDB 360 data to choose a value such as the largest value that is reported".
The Reconciliation Rules page shows, "per attribute, discovery sources that are
authorized to update that attribute, in precedence order".

**What CMDB 360 bought them, verbatim**: "Without CMDB 360, details about the
lower-priority discovery sources whose values were rejected, are discarded. Also,
it is difficult to identify the source of an attribute value without CMDB 360."

That is a statement about **explanation and audit**, not about picking a better
value. Per-attribute *precedence* already produced the answer; per-attribute
*provenance* was added so a human could see why, and so rejected values were not
lost. This distinction is the single most relevant finding for ADR-0008.

### 4. Declared vs. discovered

Manual entry is modelled as another discovery source with its own precedence. A
manual source ranked above a scanner keeps its values; ranked below, its values
are rejected per attribute. Because precedence is per-attribute, an organization
can declare "humans own `business_criticality`, Discovery owns `ram`" — a
granularity none of the three catalog tools offers.

The staleness escape (§3) modifies the naive answer: manual values are not
permanently immune.

**Not verified**: the specific `discovery_source` values (`ServiceNow`, `Manual`),
and the documented behaviour of manual edits made directly in the CMDB UI outside
the IRE path.

### 5. Documented failure modes

**Duplicate CIs are a first-class, expected outcome with a human remediation
workflow rather than an automatic merge**
([Duplicate CIs remediation](https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/de-duplication-tasks.html)):

> Duplicate CIs in the CMDB unnecessarily overload the system and interfere with
> the CMDB's integrity and reliability.

> When Identification and Reconciliation Engine (IRE) processes detect duplicate
> CIs, IRE groups each set of duplicate CIs into a de-duplication task for review
> and remediation. De-duplication tasks provide details about the duplication,
> including a list of all the duplicate CIs in the set.

Remediation "consolidates its set of duplicate CIs into a single CI": a human
picks a main CI, decides how to process the rest, and may reconcile "attribute
values, relationships, and related items from the duplicate CIs" into the main CI,
or "choose not to consolidate any data". The docs concede the automation is
incomplete: "Under some circumstances, IRE doesn't automatically generate
de-duplication tasks for duplicate CIs."

**Not verified**, despite being named in the brief: CI reclassification (switch /
upgrade / downgrade / no-change) and its remediation tasks; IRE's multi-match and
no-match outcomes; partial-payload errors from the `IdentificationEngine`
scriptable API; orphaned relationships; re-parenting churn for dependent CIs when
the parent changes; and the CMDB Health duplicate KPI.

### Device42

**Not researched.** No primary source was fetched. No claims are made.

---

## Cross-cutting patterns

### Where they converge

**A surrogate id plus a derived-or-authored natural key.** Backstage: `uid` plus
the triplet. DataHub: the URN *is* the key, no surrogate. OpenMetadata: UUID plus
FQN. ServiceNow: `sys_id` plus rule-evaluated identity. Nodqora's `id` plus
`(environmentKey, key)` is OpenMetadata's shape.

**Nobody supports rename.** Every system treats a natural-key change as the birth
of a new entity and the orphaning of the old, with a different cleanup story
each: Backstage's `orphanStrategy`, DataHub's migration CLI plus soft delete,
OpenMetadata's `deleted` flag, ServiceNow's de-duplication tasks. Structural to
derived keys, not a gap any of them intends to close. DataHub is the most honest
about it: it does not pretend rename exists, it ships a migration.

**Absence is ambiguous, and three of them guard against it at different
granularities.** OpenMetadata at field level (a bot PUT never blanks a non-empty
field); ServiceNow at attribute level (`Update with Null`); DataHub at entity
level (`fail_safe_threshold`, zero-output guard, source-failure guard). Nodqora
needs the entity-level guard for ADR-0004's "drift is absence" and the field-level
guard for whatever #12 decides.

**Human remediation over automatic merge.** ServiceNow raises a task. Backstage
surfaces processing errors on the entity and says to nudge people. DataHub says
`--dry-run`. None auto-merges two entities it suspects are the same.

**Fuzzy matching appears nowhere as a primary mechanism.** Backstage matches an
exact annotation value; DataHub an exact, case-sensitive URN; OpenMetadata an
exact FQN; ServiceNow exact criterion attribute values in priority order.
ServiceNow's cascade is the only multi-strategy approach, and each strategy in it
is still exact. DataHub's casing reconciler is the closest thing to fuzziness and
is deliberately conservative: "ambiguous references are left unchanged rather than
risk merging distinct entities."

**Collections and scalars get different treatment everywhere it matters.**
DataHub's siblings merge takes scalars from the primary and unions collections;
DataHub's PATCH exists because collections have many writers; ServiceNow's
per-attribute precedence is scalar-oriented while relationships get their own
reconciliation. Nobody applies one rule to both shapes.

### Where they genuinely diverge

| axis | Backstage | DataHub | OpenMetadata | ServiceNow |
|---|---|---|---|---|
| key | authored triplet | authored URN, env + instance inside | derived FQN + UUID | rule-evaluated, cascade of candidate keys |
| environment in key | no — "no special semantics" | yes, and now regretted in their own docs | via service name | not verified |
| alias mechanism | annotation convention, no index | siblings (two entities + `primary`), read-time merge | none | lookup identifier entries |
| provenance grain | per entity | per aspect (`SystemMetadata`) **and** per value (`MetadataAttribution`) | per entity (`providerType`) + per-field change log | per attribute per source (`cmdb_multisource_data`) |
| conflict rule | reject the second writer | last writer per aspect wins; PATCH to coexist | bot PUT cannot overwrite non-empty | per-attribute precedence, specificity then priority |
| manual override survival | n/a — descriptor is sole writer | `editable*` aspects for description/schema; PATCH semantics for owners/tags | write-time guard on bot + non-empty | precedence rank, subject to staleness |
| duplicates | one processed, others skipped silently | two URNs, both live; migration CLI to merge | not verified | de-duplication task for a human |

The sharpest divergence is **environment scoping**, and it is not a close call in
the sources. DataHub put env in the key and their platform-instances doc now says
"Environment information is best handled by tags instead of fabric type which
allows for promotion over time" — an explicit repudiation, because a dataset
promoted DEV→PROD would otherwise change identity. Backstage kept it out of the
key deliberately and pushes per-environment runtime into plugins. ADR-0004 chose
DataHub's side. That is a defensible choice — Nodqora's environments are separate
graphs, not promotion stages, so nothing "gets promoted" between them — but the
disagreement is now legible and the retrofit hazard is real.

The second sharpest is **what "provenance" is for**, and the three answers are
different jobs wearing one word:

| system | grain | job it does |
|---|---|---|
| DataHub `SystemMetadata` | per aspect | **operational** — powers `rollback --run-id` and sync status |
| DataHub `MetadataAttribution` | per value | **explanatory** — who added *this one tag* |
| OpenMetadata `changeDescription` | per field | **historical** — a version log, never read by merge |
| ServiceNow `cmdb_multisource_data` | per attribute per source | **explanatory + recovery** — why this value won, and what was rejected |
| Backstage `managed-by-location` | per entity | **ownership** — who is allowed to write |

None of these five is "an input the merge algorithm reads to pick a winner."
ServiceNow comes closest, and even there the winner is chosen by the
*reconciliation rule table*, with the multisource store existing alongside it.
ADR-0008 defers "which source wins" to #12, which means Nodqora has not yet had to
say which of these jobs `sources[]` is for — and the answer determines whether
per-entity grain is sufficient.

---

## Implications for Nodqora

Input to [#8](https://github.com/fredskor/nodqora/issues/8) and
[#12](https://github.com/fredskor/nodqora/issues/12). **These are options with
costs, not a recommendation.** No winner is picked here; the decision belongs to a
later ticket worked with a human.

### On ADR-0008's per-node `sources[]`

The survey does not show per-field provenance to be a prerequisite for manual
overrides surviving rediscovery. Three of the four achieved that survival without
reading any provenance record at merge time:

- **OpenMetadata** — a write-time guard on `(verb, actor class, field emptiness)`.
  No stored attribution consulted; the rule reads the *current* writer and the
  *current* value.
- **DataHub** — storage separation, for the fields it protects: parallel
  `editable*` aspects so declared and discovered values never occupy the same cell.
- **Backstage** — exclusivity: one writer per entity, enforced by `locationKey`.

But the survey also shows that **two systems did go finer, and the reason is
specific and checkable against Nodqora's model**. DataHub added
`MetadataAttribution` per *value* on `Owner`, `TagAssociation` and
`GlossaryTermAssociation` because those are lists with many independent writers
and "which pipeline produced this whole aspect" could not answer "who added this
one entry". ServiceNow added `cmdb_multisource_data` so a human could see why a
value won and recover a rejected one.

Neither driver is "a scalar field had two candidate values and we could not
choose". Both are (a) collections with concurrent writers, or (b) audit.

The distinction that matters for #12: **precedence and provenance are separable.**
Nodqora can have per-field *precedence* (a static rule: "yaml beats kubernetes for
`ownerKey`") without per-field *provenance* (a stored record of which source
actually set `ownerKey` on this node). ADR-0008 forecloses the second, not the
first. On that reading, ADR-0008's consequence note — "After a merge you cannot
tell which source set a given field" — is a statement about the *inspector's*
explanatory power, which is exactly what ServiceNow paid for and DataHub paid for,
and not about whether merge can be made correct.

The concrete question ADR-0008 has not yet had to answer: **which Nodqora fields
are collections with more than one writer?** `links[]` is the obvious candidate —
YAML contributes `repository` and `runbook`, Kubernetes contributes `workload` and
`logs`, Grafana contributes `dashboard`. If both YAML and an adapter write
`links[]`, Nodqora has DataHub's exact lost-update problem, and a whole-node
replace makes the two writers mutually destructive regardless of what `sources[]`
records. `backings[]` has the same shape but effectively one writer class
(adapters). `metadata{}` is already plugin-namespaced by ADR-0006, which is
DataHub's `editable*` trick applied preemptively — each plugin owns its own
sub-object and cannot clobber another's.

### Options for the merge rule (#12)

**A. Exclusive writer per node, Backstage-style.** Whichever source first creates
`(environmentKey, key)` owns it; others are rejected. Cheapest to implement and
explain. Directly contradicts the fixture: `payments-enricher` has
`sources: [yaml, kubernetes]` and needs owner and repository from YAML while
needing backings and health from Kubernetes. Effectively ruled out by the fixture.

**B. Field-class partition, DataHub-style.** Declare structurally which fields
adapters may write and which only YAML may write — e.g. adapters own `backings`
and `type`; YAML owns `ownerKey`, `links`, `description`. No conflict can arise
because the sets are disjoint. ADR-0006's plugin-namespaced `metadata{}` already
works this way. Cost: a field cannot be authored by both, so a node discovered but
not declared gets no owner even when an adapter could read one from a
`topology.io/owner` annotation — which the fixture's Deployment actually carries.
That cost lands squarely on the fixture's most important node.

**C. Precedence table, ServiceNow-style, without per-field provenance.** A static
rule per field naming the winning source, evaluated at merge time. Handles the
fixture's mixed node, and lets the Kubernetes adapter supply `ownerKey` from the
annotation while YAML overrides it when present. Cost: the inspector can explain
which source *would* win by rule, not which source *did* set the value — usually
the same answer, but not when the winning source did not supply the field.
Compatible with ADR-0008 as written.

**D. Emptiness guard, OpenMetadata-style.** An adapter may fill an empty field but
not overwrite a populated one; YAML may always write. No precedence table, no
provenance. Cost: order of arrival becomes semantically significant for two
adapters writing the same field, and a wrong value once written is sticky until a
human clears it. OpenMetadata pairs it with an explicit `overrideMetadata` escape
hatch precisely because of that stickiness, and guards separately against
blanking.

**E. Per-field provenance.** ADR-0008's explicitly deferred option. Buys
explanation and makes rejected values recoverable, at roughly the doubling of
write-model complexity ADR-0008 already estimated. Both systems that paid it
(DataHub for collections, ServiceNow for attributes) paid it for audit and
concurrency, not to make scalar merge correct.

**F. Split the question by field shape** — the pattern all four converge on
without any of them naming it. Scalars (`ownerKey`, `description`, `displayName`,
`type`) settled by B, C or D; collections (`links[]`, `backings[]`, `sources[]`)
made additive with per-element keys so writers never clobber each other, which is
what DataHub's PATCH and its siblings merge both do. Costs an element identity for
each collection — `links` keyed by `rel`, `backings` keyed by
`(adapter, kind, reference)` — and leaves per-element provenance as a separate,
later question.

Options B, C, D and F all leave ADR-0008 intact. They differ in where the rule
lives: in the schema (B), in a table (C), in the write path (D), or in the
collection's element key (F).

### On annotation-driven resolution (#8)

Backstage's Kubernetes plugin validates the fixture's design. Annotation on the
logical entity, matching label on the physical objects, many objects to one
entity, is the mainstream mechanism. Three details are worth stealing:

1. **An escape hatch above the convention.**
   `backstage.io/kubernetes-label-selector` "takes precedence over the
   `backstage.io/kubernetes-id` method". The fixture already needs one: the
   `kafka-connect` StatefulSet backs two connector nodes, so no single
   `topology.io/service` annotation on the workload can express the mapping. That
   direction has to be resolved from the Connect API side, or by a selector-style
   rule, not by an annotation on the StatefulSet.
2. **A scoping annotation.** `backstage.io/kubernetes-namespace` bounds the
   lookup. Nodqora's Environment already carries the namespace per ADR-0004 — but
   note Backstage found it necessary to make the scope explicit *per entity*, not
   only per connection.
3. **A priority cascade when one rule is not enough.** ServiceNow's identifier
   entries are the only worked pattern in the survey for multiple candidate keys
   evaluated in order — precisely the fixture's "three consumer-group naming
   conventions … group attribution needs more than one rule". The shape is: an
   ordered list of resolution strategies, each exact, first match wins, and a
   remediation task when two CIs match or none does.

The gap Backstage does not fill and Nodqora will hit: **there is no index of
annotation claims**, so two entities claiming the same label value both match.
Nodqora resolving `enrich-consumer-prod` → `payments-enricher` needs a defined
outcome when two nodes claim the same consumer group. The only two answers in the
survey are ServiceNow's (raise a de-duplication task) and Backstage's (process one,
skip the rest, silently) — and Backstage's is widely regarded in its own docs as a
thing that merely happens rather than a thing that is right.

### On environment in the key

ADR-0004's `(environmentKey, key)` is the DataHub choice, and DataHub's warning is
concrete: adding `platform_instance` to an existing ingestion changes every URN
and orphans descriptions, tags, lineage and ownership. Nodqora's analogue is a
renamed or re-scoped `environmentKey`, which would orphan every node in it.
Mitigations visible in the prior art:

- **Treat `environmentKey` as immutable from creation**, as DataHub treats URNs,
  and accept that renaming an environment is a migration rather than an update.
- **Ship the migration rather than pretending rename works.** DataHub's
  `datahub migrate` clones every aspect to the new URN and repoints every incoming
  reference, and documents exactly what it cannot carry. That is a far smaller job
  for Nodqora — clone rows, repoint `fromKey`/`toKey` — and it converts an
  unbounded hazard into a bounded one.
- **Keep the surrogate `id` as the only thing other tables store**, so
  `(environmentKey, key)` is a lookup key and not a foreign key. OpenMetadata does
  this with UUID-plus-FQN, and its `EntityReference` shows the residual cost:
  denormalized `name` and `fullyQualifiedName` copies go stale on rename.

Note that DataHub's repudiation of `env`-in-the-URN turns on *promotion* — a
dataset moving DEV→PROD should not change identity. Nodqora's environments are
parallel graphs, not stages a node moves through, so that specific argument does
not transfer. The orphaning argument does.

**Not verified**: whether ServiceNow's `sys_domain` offers a cleaner precedent.

### On absence

Three systems guard against reading a missing value as a deletion, at three
granularities. For Nodqora this bears directly on ADR-0004's "drift is absence,
never a flag": absence of a *node* in an environment is meaningful, but absence of
a *field* in an adapter payload is not, and absence of an *entire adapter run* is
not either. Those three absences need to be distinguishable in the discovery
contract, or:

- a Kubernetes adapter that cannot read an annotation this run blanks an owner
  that YAML set (OpenMetadata's field-level case);
- a Kafka adapter that fails to connect deletes every topic node (DataHub's
  entity-level case, and the reason for `fail_safe_threshold` and the zero-output
  guard).

DataHub's three fail-safes are cheap and directly portable: refuse to apply
deletions when the source reported a failure, when it produced nothing, or when
the delta exceeds a threshold — while still carrying the previous state forward so
that a genuinely-removed node disappears on the next clean run.

---

## Sources

Every URL below was fetched during this research. Items named in the brief but not
fetched are marked "not verified" in the relevant section and are not cited here.

**Backstage**

- Entity references — https://backstage.io/docs/features/software-catalog/references/
- ADR009: Entity References — https://backstage.io/docs/architecture-decisions/adrs-adr009/
- Descriptor format — https://backstage.io/docs/features/software-catalog/descriptor-format/
- Well-known annotations — https://backstage.io/docs/features/software-catalog/well-known-annotations/
- Life of an entity — https://backstage.io/docs/features/software-catalog/life-of-an-entity/
- Custom entity providers — https://backstage.io/docs/features/software-catalog/external-integrations/entity-providers
- Catalog configuration — https://backstage.io/docs/features/software-catalog/configuration/
- Catalog FAQ — https://backstage.io/docs/features/software-catalog/faq/
- Kubernetes plugin configuration — https://backstage.io/docs/features/kubernetes/configuration/
- BEP index — https://github.com/backstage/backstage/tree/master/beps

**DataHub**

- What is a URN — https://docs.datahub.com/docs/what/urn
- Metadata model — https://github.com/datahub-project/datahub/blob/master/docs/modeling/metadata-model.md
- What is an aspect — https://docs.datahub.com/docs/what/aspect
- Aspect versioning — https://github.com/datahub-project/datahub/blob/master/docs/advanced/aspect-versioning.md
- MCP / MCL — https://github.com/datahub-project/datahub/blob/master/docs/advanced/mcp-mcl.md
- Advanced: Patch — https://docs.datahub.com/docs/advanced/patch
- Dataset metamodel — https://docs.datahub.com/docs/generated/metamodel/entities/dataset
- CorpUser metamodel — https://docs.datahub.com/docs/generated/metamodel/entities/corpuser
- Platform instances — https://docs.datahub.com/docs/platform-instances
- Sync status — https://docs.datahub.com/docs/sync-status
- CLI (incl. `migrate`) — https://docs.datahub.com/docs/cli
- dbt ingestion source — https://docs.datahub.com/docs/generated/ingestion/sources/dbt
- Deleting metadata — https://github.com/datahub-project/datahub/blob/master/docs/how/delete-metadata.md
- Stateful ingestion — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/dev_guides/stateful.md
- Lineage URN casing — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/dev_guides/lineage_urn_casing.md
- Dataset transformers — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/docs/transformer/dataset_transformer.md
- Browse paths v2 — https://github.com/datahub-project/datahub/blob/master/docs/browseV2/browse-paths-v2.md
- Structured properties — https://docs.datahub.com/docs/api/tutorials/structured-properties
- `DatasetKey.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/metadata/key/DatasetKey.pdl
- `FabricType.pdl` — https://github.com/datahub-project/datahub/blob/master/li-utils/src/main/pegasus/com/linkedin/common/FabricType.pdl
- `DataPlatformInstance.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/DataPlatformInstance.pdl
- `SystemMetadata.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/mxe/SystemMetadata.pdl
- `MetadataAttribution.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/MetadataAttribution.pdl
- `Documentation.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/Documentation.pdl
- `Siblings.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/Siblings.pdl
- `Status.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/common/Status.pdl
- `EditableDatasetProperties.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/dataset/EditableDatasetProperties.pdl
- `EditableSchemaMetadata.pdl` — https://github.com/datahub-project/datahub/blob/master/metadata-models/src/main/pegasus/com/linkedin/schema/EditableSchemaMetadata.pdl
- `SiblingAssociationHook.java` — https://github.com/datahub-project/datahub/blob/master/metadata-jobs/mae-consumer/src/main/java/com/linkedin/metadata/kafka/hook/siblings/SiblingAssociationHook.java
- `SiblingGraphService.java` — https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/graph/SiblingGraphService.java
- `siblingUtils.ts` — https://github.com/datahub-project/datahub/blob/master/datahub-web-react/src/app/entity/shared/siblingUtils.ts
- `EntityServiceImpl.java` — https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/entity/EntityServiceImpl.java
- `EbeanAspectV2.java` — https://github.com/datahub-project/datahub/blob/master/metadata-io/src/main/java/com/linkedin/metadata/entity/ebean/EbeanAspectV2.java
- `source_common.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/configuration/source_common.py
- `mce_builder.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/emitter/mce_builder.py
- `graph/client.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/graph/client.py
- `stale_entity_removal_handler.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/state/stale_entity_removal_handler.py
- `incremental_lineage_helper.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/api/incremental_lineage_helper.py
- `dbt_common.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/ingestion/source/dbt/dbt_common.py
- `cli/migrate.py` — https://github.com/datahub-project/datahub/blob/master/metadata-ingestion/src/datahub/cli/migrate.py
- Issue #10406 (sibling mis-association) — https://github.com/datahub-project/datahub/issues/10406
- Issue #11642 (editableSchemaMetadata not cascaded) — https://github.com/datahub-project/datahub/issues/11642

**OpenMetadata**

- `basic.json` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/basic.json
- `entityReference.json` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/entityReference.json
- `entityHistory.json` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/type/entityHistory.json
- `table.json` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/entity/data/table.json
- `databaseServiceMetadataPipeline.json` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-spec/src/main/resources/json/schema/metadataIngestion/databaseServiceMetadataPipeline.json
- `EntityRepository.java` — https://github.com/open-metadata/OpenMetadata/blob/main/openmetadata-service/src/main/java/org/openmetadata/service/jdbi3/EntityRepository.java

**ServiceNow**

- CMDB Identification and Reconciliation (IRE) — https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/c_CMDBIdentifyandReconcile.html
- Identification rules — https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/c_IdentificationRules.html
- Create a CI reconciliation rule — https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/create-reconciliation-rule.html
- Duplicate CIs remediation — https://www.servicenow.com/docs/r/servicenow-platform/configuration-management-database-cmdb/de-duplication-tasks.html
- CMDB 360 / Multisource CMDB — https://www.servicenow.com/docs/bundle/zurich-servicenow-platform/page/product/configuration-management/concept/multisource-cmdb.html

**Nodqora, for cross-reference**

- [`CONTEXT.md`](../../CONTEXT.md)
- [`docs/reference-pipeline.md`](../reference-pipeline.md)
- [ADR-0004: environment-scoped graph](../adr/0004-environment-scoped-graph.md)
- [ADR-0005: logical nodes with backings](../adr/0005-logical-nodes-with-backings.md)
- [ADR-0006: plugin-namespaced metadata](../adr/0006-plugin-namespaced-metadata.md)
- [ADR-0008: per-node provenance](../adr/0008-per-node-provenance.md)
