# Four Business Demo (v4)

A single Java 21 web application generated from `fourbusinesscompressedscript4.jsonc`
(canonical AST format `LLM_AST_TOKEN_MANIFEST_V4`, declared source
`RECURSIVE_FOUR_BUSINESS_HIERARCHY_V4`). This is the third generation of this build in the
project: v2 established the core multi-tenant architecture (one shared UI, one shared
file-backed database, compound storage keys that keep same-named entities across businesses
from colliding); this version adds the fully-specified Administration surface, a UI-control
and localized-label contract, an audit event vocabulary, and a seed-data contract - all
independently verified against the decoded script before anything was built (see below), then
implemented, then tested (27 automated tests, all passing; see Test results).

**Windows note:** an earlier build of this jar crashed on startup on Windows with
`InvalidPathException: Illegal char <:> at index 17: ROBERT_CONSULTING::_administration`.
The compound storage keys in `BusinessModel.BusinessNeuron` (`storageKind`,
`administrationKind`, `auditKind`, `seedMarkerKind`) were joined with `::`, and `Store` uses
that string directly as a filesystem directory name - `:` is illegal in a Windows path
component, though it's perfectly legal on Linux/macOS, which is why this wasn't caught by
testing in this environment. Fixed by joining with `__` instead (see `BusinessModel.java`);
this jar creates directories like `ROBERT_CONSULTING__clients` and
`HANS_MARKETPLACE___administration` instead. No stored data existed to migrate - the crash
happened on the very first write during first-boot seeding, before anything had been
persisted - so this is a drop-in replacement.

## Running it

```
java -jar dist/fourbusiness-app.jar
```

Then open `http://127.0.0.1:8091/`. Use the user switcher in the top bar to move between the
four demo identities (Robert/Consulting/English, Marie/Restaurant/French,
Hans/Marketplace/German, Sofia/Property Management/Spanish). Every business starts pre-loaded
with seed data (see below) so there's something to look at immediately, and an Administration
item sits at the bottom-left of the sidebar for every business.

Useful system properties (all optional, shown with their defaults):

```
-Dfourbusiness.storage.dir=./fourbusiness-data
-Dfourbusiness.http.host=127.0.0.1
-Dfourbusiness.http.port=8080
```

## Building from source

Maven Central is unreachable from the environment this was built in, so the project is built
with a documented `javac` fallback that needs no network access. On a machine with normal
internet access, `mvn clean package` runs the same `pom.xml` and test sources unmodified under
real JUnit 5.

```bash
rm -rf out dist && mkdir -p out/main out/testlib out/test dist

javac --release 21 -d out/main $(find src/main/java -name "*.java")
cp -r src/main/resources/* out/main/

javac --release 21 -d out/testlib $(find src/testlib -name "*.java")
javac --release 21 -cp out/main:out/testlib -d out/test $(find src/test/java -name "*.java")

java -cp out/main:out/testlib:out/test com.fourbusiness.TestRunner

jar cfe dist/fourbusiness-app.jar com.fourbusiness.FourBusinessApplication -C out/main .
```

**Test results:** 27/27 passing - 13 in `RecordValidatorTest`, 3 in `SeederTest`, 11 in
`WebIntegrationTest`, including a dedicated cross-business isolation test.

## How the script was decoded and verified

`fourbusinesscompressedscript4.jsonc` uses the same symbol-table compression as the prior
version (a flat `symbols` array plus `@N` base-36 index references and `["OBJECT", ...]` /
`["ARRAY", ...]` tags). It was fully expanded with a throwaway Python script before any
evaluation began, confirmed lossless by checking that zero `@`-tokens remained unresolved.

Before building anything, every claim in the script's own `semanticValidation` block (which
self-reports `"status": "PASS"` with zero issues) was independently re-derived from the raw
decoded structure rather than trusted at face value - the standard applied to every script in
this project, because two earlier scripts (a "manufacturer" script and the first four-business
script) both self-reported clean while having real, independently-discoverable defects. This
script held up under that check on every structural axis: all 40 fields' declared `uiControl`
values match `uiNeuron.fieldTypeControlMap` applied to their own type with zero exceptions; all
7 relationships' `field`/`referenceEntity` pairs are internally consistent; all 7 workflows'
transitions stay inside their subject entity's own declared `enumValues`; every `displayField`/
`secondaryDisplayField` points to a real declared field; every localized enum label set and
every workflow transition's 4-language `localizedActionLabel` is complete with no missing or
extra keys. `Businesses.java` re-runs the entity/field/relationship/workflow counts against the
same numbers (13/40/7/7) at class-load time and refuses to start if the bundled schema resource
ever drifts from them.

Two real gaps survived that verification pass and were reported back before any code was
written:

1. **The seed contract had no seed data.** `seedDataContract` mandated a deterministic dataset
   per business partition but never declared what it should contain.
2. **`schemaVersion` had no declared value**, despite being part of the canonical storage key
   and a required Administration field.

Building this version meant resolving both - see the next two sections for exactly what was
added and why, called out explicitly rather than folded in silently.

## The seed dataset (authored, not transcribed)

`seedDataContract` requires `"scope": "ONE_DETERMINISTIC_DATASET_PER_BUSINESS_PARTITION"`,
applied once (`"applyWhen": "SEED_VERSION_MARKER_ABSENT"`) and never reapplied
(`"idempotence": "DURABLE_MARKER_PREVENTS_RESEED_AFTER_USER_DELETION"`), with every seeded
reference resolving within its own partition
(`"referenceRule": "SEED_REFERENCES_RESOLVE_WITHIN_SAME_PARTITION"`) - but the script never
supplies the actual records. `Seeder.java` (the one file in this build whose *content*, not
just its wiring, had to be authored rather than transcribed from the decoded script) applies,
once per business, on first boot:

- **Robert (Consulting):** 2 clients (Priya Shah / Acme Corporation, Marcus Webb / Nova Retail
  Group), 2 engagements referencing them (one seeded `ACTIVE`, one `PLANNED` - seeding writes
  directly to the store and so is not subject to the same "always forced to initial state" rule
  a client-submitted create is, letting the demo start with a workflow already partway through),
  2 invoices referencing the engagements (`ISSUED` and `DRAFT`).
- **Marie (Restaurant):** 3 menu items, 2 reservations, 2 orders (`PREPARING` and `NEW`).
- **Hans (Marketplace):** 2 sellers (one `ACTIVE`, one `PENDING`), 2 listings under the active
  seller, 2 orders referencing them (`PLACED` and `PAID`).
- **Sofia (Property Management):** 2 properties (`OCCUPIED` and `AVAILABLE`), 1 tenant, 1 lease
  (`ACTIVE`), 1 maintenance request (`OPEN`).

A durable marker record (`businessAppId::_seed / MARKER`) is written after a successful seed
and checked before every attempt, so a restart never re-seeds or duplicates records. This
application has no user-deletion feature at all (the four demo identities are a fixed,
non-deletable set), so the marker can never be cleared out from under itself - which trivially
satisfies "never reseed after user deletion" since that precondition can't occur. Every seeded
`RECORD_CREATED`-equivalent write emits a `SEED_RECORD_CREATED` audit event and the pass as a
whole emits `DEMO_SEED_COMPLETED`, exactly as `auditNeuron.events` declares.

## Administration: the surface, and the two things it required inventing

Administration is now fully specified in the script: a standalone view at `/administration`,
bottom-left navigation, six fields (`businessName`, `theme`, `notifications` - editable and
persisted; `locale`, `schemaVersion`, `persistencePartition` - visible but immutable), one save
action, and seven acceptance scenarios. All of that was implementable directly from the script.
Two things were not declared and had to be decided:

- **`schemaVersion`'s value.** The spec requires Administration to persist and display a schema
  version per business, and it's one of the five parts of the canonical storage key, but no
  business neuron or anywhere else assigns an actual value. `Seeder.SCHEMA_VERSION = "1"` is
  used uniformly for every business as the starting version - a judgment call, not a value from
  the specification.
- **`notifications`'s concrete values and labels.** The control is declared as
  `LOCALIZED_ENABLED_DISABLED_SELECT`, which is clearly boolean-shaped, but the script never
  gives the two option strings or their localized text (unlike every entity ENUM field, which
  gets explicit `enumValues` and `localizedEnumLabels`). This is implemented as a plain boolean,
  with "Enabled"/"Disabled" supplied in all four UI languages as generic chrome text (the same
  category of necessary-but-undeclared string as "Save"/"Cancel"/"Delete", which the script
  also never spells out).

Saving `businessName` actually changes what's displayed as the business's name everywhere in
the UI (`schemaOf`'s `business` field and `containerInfo`'s per-user listing both read the
current Administration record) rather than being a decorative form field with no effect -
otherwise "saved business name... survive[s] a complete process restart" would have nothing
visible to demonstrate. Attempting to submit a different value for `locale`, `schemaVersion`,
or `persistencePartition` than what's currently stored is rejected with a 400, the same pattern
already used for a workflow-governed status field being edited directly:
`WebIntegrationTest.administrationRejectsAnAttemptToEditAReadOnlyField` covers this.
`auditResponsibility: NONE_USE_AUDIT_NEURON` and `administrationProducesAudit: false` are
satisfied structurally - the Administration handler calls the exact same shared `audit()`
helper every record mutation uses, rather than any Administration-specific auditing logic.

## Two things this build deliberately did *not* add

`constructionContract.requirements` explicitly says: "Report optional additions separately
from implemented requirements," and lists a `prohibitedInference` block banning invented
entities, fields, calculations, and enum values (`TIME_ENTRY`, `totalAmount`, hourly-rate
calculation, `Invoice.CANCELLED`) and silently-implemented enhancements like search. In that
spirit:

- **No audit-log HTTP endpoint exists.** Every declared audit event is persisted (one JSON
  record per event, in the same file-backed store as everything else, under
  `businessAppId::_audit`), but the script never declares a route to read them back, so none
  was invented. Exposing one would be a reasonable optional addition; it isn't built here.
- **Search (`?q=`) already existed from the prior version** and was kept, since the script
  never asked for it to be removed and it predates this revision - but it's still not something
  this script asks for, so it's called out here rather than presented as a requirement.

## Architecture (carried forward, updated where the script changed)

**One shared UI, per-user behavior**, projected via `REPLACE_NOT_MERGE` - switching the active
user replaces the rendered view rather than merging it with the previous business's state.
`index.html` reads everything it renders (entities, fields, their declared `uiControl`,
localized enum labels, `displayField`/`secondaryDisplayField` for list and reference-picker
labeling, per-transition localized action labels) from `GET /schema` and
`GET /administration` - it hardcodes no business-specific content.

**One shared database, per-business partitions that cannot collide.** Unchanged from v2:
`BusinessNeuron#storageKind` compounds `applicationId` and `entityId`
(e.g. `"MARIE_RESTAURANT::orders"` vs. `"HANS_MARKETPLACE::orders"`), which is what keeps
Marie's and Hans's both-declared `orders` entities apart.
`WebIntegrationTest.marieAndHansOrdersNeverContaminateEachOther` creates a record in each
(on top of what each was already seeded with) and confirms neither list, nor a cross-business
direct fetch by id, ever surfaces the other's data.

**State transitions moved to their own route.** v2 used
`/api/{userId}/workflows/{workflowId}/transition` with `recordId` in the body; this script
declares `requestContextContract.transitionRoute:
"/api/{selectedUserId}/records/{entityId}/{recordId}/transition"` instead, which is what's
implemented - the governing workflow is resolved server-side from `entityId`, and the client
only names the target state.

**Demo switcher, not authentication** - unchanged: `productionAuthentication: false` is
returned from `/api/container` with a visible warning, and there is no login screen.

**Schema resource, not hand-transcribed Java.** v2 hand-transcribed every business's schema
into `Businesses.java` as literal Java record construction. At this version's size (40 fields,
each now carrying localized labels and UI metadata, plus 4-language transition labels), that
approach's main risk is a manual transcription typo that only independent verification catches
after the fact. Instead, a small Python script (documented above) extracted exactly the needed
subset of the decoded AST into `src/main/resources/schema/businesses.json`, and
`Businesses.java` loads and parses it at startup with the project's existing hand-rolled `Json`
class - and independently re-checks the loaded counts against the spec's own declared shape
before the application will start.

## Project layout

```
src/main/java/com/fourbusiness/
  Json.java                hand-rolled JSON parser/writer (no external dependency)
  Store.java                generic file-backed record store, one JSON file per record
  Config.java                system-property-driven runtime configuration
  BusinessModel.java          typed shapes: FieldDef, EntityDef, RelationshipDef, WorkflowDef, TransitionDef, BusinessNeuron
  UiNeuron.java                the declared field-type-to-control map and related UI/storage constants
  Businesses.java              loads /schema/businesses.json, verifies its shape, exposes the 4 BusinessNeurons
  RecordValidator.java          generic schema-driven validation + workflow state handling + delete-restrict
  Seeder.java                    the authored seed dataset + Administration defaults (see above)
  FourBusinessApplication.java     HTTP boundary (com.sun.net.httpserver), routing, Administration, audit
src/main/resources/
  schema/businesses.json          script-generated extract of the decoded AST (see decode process above)
  static/index.html                 single-file shared UI (theming, i18n, schema-driven CRUD + Administration)
src/test/java/com/fourbusiness/
  RecordValidatorTest.java   unit tests for validation, workflow-forced state, UI-control derivation, delete-restrict
  SeederTest.java              seed dataset reference integrity, idempotence, Administration defaults
  WebIntegrationTest.java      HTTP integration tests, incl. the cross-business isolation test and the new transition route
  TestRunner.java                 reflection-based test runner (no network access to fetch real JUnit)
src/testlib/                  source-compatible JUnit 5 annotation/assertion shims
fourbusinesscompressedscript4.jsonc   the source specification, included for reference
pom.xml                         Maven project file (for a machine with normal internet access)
```
