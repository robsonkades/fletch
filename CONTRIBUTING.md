# Contributing to Fletch

The [project guide](README.md) documents the public API and behavior. This file
covers changes to the repository, verification and releases.

## Reporting bugs and proposing changes

Open a [GitHub issue](https://github.com/robsonkades/fletch/issues) with a minimal XML
sample, extractor or mapping, expected result, actual result and exception if any.
Include the Fletch version, JDK version, operating system and input form
(`byte[]`, `String` or `InputStream`). Remove credentials and private document data.
For a feature proposal, describe the use case and the behavior a caller needs.

## Development setup

Use a JDK 17 or later and Maven. Sources target Java 17; do not introduce preview
features or a newer minimum runtime unintentionally. CI runs on JDK 17, 21 and 25.
Create a branch from `master` for the change. Commands below run from the repository
root in Bash or PowerShell; quote Maven `-D` properties in PowerShell.

The root POM builds the standalone core. `codegen/pom.xml` aggregates the core,
optional generator and executable example. See the [generator guide](codegen/README.md)
when changing generated lookup behavior or the build order.

## Implementation and documentation

- Follow the surrounding Java style and document public types and methods with
  Javadoc, including null behavior, ownership, limits and relevant exceptions.
- Keep runtime dependencies out of the core. Review allocation and copying in
  frequently executed paths; confirm performance claims with measurements.
- Preserve cursor ordering and consumption semantics, mapping group boundaries,
  stream ownership, session recovery and thread ownership.
- Preserve the distinction between absent/blank element text and whitespace-only
  attributes. Application converters receive decoded text; exceptions and explicit
  null results propagate. See [conversion contracts](README.md#custom-conversions-presence-and-fallbacks).
- Add focused tests for new behavior or bug fixes. Use observable outcomes and
  boundary cases rather than assertions tied only to implementation structure.
- Update the relevant README examples and API Javadoc with behavior changes. Mark
  new source-only APIs as unreleased until they are actually published.

Tests are colocated with their module: `src/test/java`,
`codegen/generator/src/test/java` and `codegen/example/src/test/java`. The core also
contains differential checks and JMH workloads; their dependencies have test scope.

## Verification

Run the complete reactor before submitting a change:

```sh
mvn -B -ntp -f codegen/pom.xml verify '-Dgpg.skip=true'
```

This compiles and tests the core, builds generated example sources, tests the
generator and consumer, and produces the core source and Javadoc jars. Local
verification skips signing; the publication workflow signs release artifacts.

For a focused core iteration, `mvn -B -ntp verify '-Dgpg.skip=true'` is also useful.
For public documentation changes, compile and run changed Java examples against
the appropriate artifact. `mvn -B -ntp javadoc:javadoc` generates browsable API
documentation. Check Markdown links and workflow references when moving files.

Preserve local measurements and other important files under build directories
before using Maven `clean`. A normal `verify` does not require `clean`.

### Performance changes

Use the [benchmark commands and methodology](README.md#performance-and-benchmarks).
Record baseline/candidate commits, complete JVM and workload settings, raw JMH
results, independent forks and allocation per operation. Verify equivalent output
outside timing. Report uncertainty and observed regressions alongside gains.
Performance evidence may be attached to the PR or retained as CI artifacts; it
does not need a permanent directory in the source tree.

## Pull requests

Target `master`. Explain the concrete problem, resulting behavior and meaningful
validation. State any checks that were not run or remain inconclusive. Keep the
change scoped so it can be reviewed and reverted independently.

Before requesting review:

- Confirm the full reactor passes and relevant behavior has test coverage.
- Review the diff for unrelated changes, generated outputs and local artifacts.
- Check that examples, Javadoc and version availability match the implementation.
- Include before/after evidence for performance claims.

## Preparing a release

Version synchronization updates all four POMs: the core, codegen aggregator,
generator and example. Use the pinned Versions Maven Plugin directly. For example,
to prepare version `1.4.0` (substitute the intended version):

```sh
mvn -B -ntp -f codegen/pom.xml org.codehaus.mojo:versions-maven-plugin:2.22.0:set '-DprocessAllModules=true' '-DnewVersion=1.4.0' '-DgenerateBackupPoms=false'
mvn -B -ntp -f codegen/pom.xml verify '-Dgpg.skip=true'
```

Review all four POMs and their parent/dependency versions together. Update jar
names and consumer examples where appropriate. Prepare the user-visible changes
and upgrade considerations in the release PR, including the README compatibility
section. Keep the README's published-version statement accurate while the release
is still pending, and update it after publication succeeds.

The JDK 17 CI job rehearses version synchronization with `0.0.0-ci` and rebuilds
the full reactor. The [Build workflow](.github/workflows/maven.yml) and
[Release workflow](.github/workflows/release.yml) both invoke the same pinned
Maven operation directly.

### Publication

After preparation is merged, a maintainer dispatches **Release** on `master` with
the intended version. Accepted version syntax is `major.minor.patch`, optionally
followed by a suffix such as `-RC1`. The workflow:

1. Validates the version and rejects an existing `v<version>` tag.
2. Synchronizes module versions and verifies the full reactor on Java 17.
3. Commits any version change, creates the annotated version tag and pushes it.
4. Signs and publishes the core to Maven Central, waiting for publication to finish.
5. Creates a GitHub release with the core, source and Javadoc jars and generated
   release notes based on merged changes. Maintainers can edit the release body
   to add the prepared migration guidance.

The optional generator and example are source-built tools and are not deployed by
this workflow. A build or merge alone does not publish a release.

Repository secrets required by the workflow are `CENTRAL_USERNAME`,
`CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE`. Credentials
belong in repository secrets, not source files or command output.

The publication plugin waits up to 30 minutes. A failure stops the workflow before
GitHub release creation. Because the tag is pushed before publication, inspect the
existing tag and Central deployment before retrying. The workflow rejects a version
whose tag already exists; do not blindly rerun or replace a release tag. After
success, verify the published artifacts and update the documented release status.

The Central publishing plugin is pinned to `0.11.0`, which supports deployment
`warnings` returned by the Portal API ([release notes](https://central.sonatype.org/publish/publish-portal-maven/#0110)).
Older versions can fail while reading deployment status after uploading the bundle,
even when Central completes publication. If the version is already published,
finish the skipped GitHub release using the existing tag and the core, source and
Javadoc jars downloaded from Maven Central. Do not deploy that version again or
recreate its tag.

## Reporting security issues

For a security vulnerability, email `robsonkades@outlook.com` with a minimal
reproduction and affected versions instead of opening a public issue. Use the
normal issue tracker for non-sensitive bug reports.
