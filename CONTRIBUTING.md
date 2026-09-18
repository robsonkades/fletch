# Contributing to Fletch

First off, **thank you** for considering a contribution to Fletch! Your support and feedback make this library better for everyone.

## Ways to Contribute

1. **Bug Reports & Feature Requests**
    - Open a new [Issue](https://github.com/robsonkades/fletch/issues) describing the bug or feature.
    - Provide as much detail as possible: a minimal XML sample and extractor that reproduce the problem, JVM version, operating system, stack traces, etc.

2. **Pull Requests**
    - Fork the repository and create a new branch:
      ```bash
      git checkout -b feature/your-feature-name
      ```
    - Write clear, concise commit messages.
    - Follow the existing code style and keep the hot path allocation-free (see the performance notes in the Javadoc of `XmlCursorImpl` and `TypeConverter`).
    - Include **unit tests** for any new functionality or bug fixes. Tests use JUnit 5 and live in `src/test/java`.
    - Run `mvn verify -Dgpg.skip=true` locally to ensure compilation, tests and Javadoc generation succeed. (Artifact signing is bound to `verify` but runs only during a release; `-Dgpg.skip=true` skips it — the CI build uses the same flag.)
    - Run `mvn -f codegen/pom.xml verify -Dgpg.skip=true` to include the optional generator and its consumer, as CI does. See the [codegen build guide](codegen/README.md). Preserve benchmark evidence before using `clean`.
    - Submit a **Pull Request** targeting the `master` branch. Describe the resulting behavior and relevant validation.

3. **Documentation Improvements**
    - Found a typo in the docs? Think of an example that would better illustrate how to use the API?
    - Submit a PR updating `README.md`, the Javadocs, or this `CONTRIBUTING.md`.

4. **Benchmarks & Performance Testing**
    - JMH benchmarks are built with the `benchmarks` profile: `mvn -Pbenchmarks -DskipTests package`.
    - If you propose a performance-oriented change, include before/after JMH numbers in the PR description.

## Coding Style

- Target Java 17 (`maven.compiler.release` in `pom.xml`).
- Document every public type and method with Javadoc, including `{@link ...}` tags and code examples when appropriate.
- Keep behavioral contracts explicit: absence is `null` / empty list, parse failures use `XmlException`, conversion errors retain their Java exception type, and reads tolerate children in any order.
- Custom value converters receive already-decoded text and are not invoked for absent or empty values. Preserve element trimming, attribute normalization without trimming, and propagation of application exceptions. See [value conversions](docs/value-conversions.md).
- If you modify `pom.xml`, ensure file structure and indentation remain consistent.

## Pull Request Checklist

- [ ] Your code compiles and tests pass (`mvn -f codegen/pom.xml verify -Dgpg.skip=true`).
- [ ] New or updated methods include proper Javadoc (`mvn javadoc:javadoc` should produce no errors).
- [ ] New behavior is covered by unit tests.
- [ ] Performance-sensitive changes include JMH results.
- [ ] The PR description explains both "what" and "why" (not just "how") you made the change.

Once your PR is approved, one of the maintainers will merge it and trigger CI to run additional checks. Thank you for making Fletch better!

## Preparing a release

Choose the release version and run `bash scripts/set-release-version.sh <version>`
from the repository root (Git Bash on Windows). This updates the core, optional
generator and example together. Run `mvn -f codegen/pom.xml verify -Dgpg.skip=true`,
update the installation/consumer examples, and write `docs/releases/<version>.md`
with the user-visible changes and upgrade considerations. Commit all four POMs.
The Java 17 CI job rehearses this version update and reactor build with a temporary
version. Preserve local benchmark evidence before using `clean`.

Publication is a separate operation: after the preparation is merged, a maintainer
can dispatch the **Release** workflow on `master` with that version. It signs and
publishes the core to Maven Central and waits for Central to report `PUBLISHED`.
It then creates the GitHub release with the prepared notes followed by generated
PR notes. The optional generator and example remain source-built tools and are
not deployed by that workflow.

The publishing plugin waits up to 30 minutes for publication to complete. A failure
or timeout stops the workflow before GitHub release creation. Since the version tag
is pushed before publication, inspect the existing deployment in the Central Portal
and the existing tag before retrying. The workflow rejects a version whose tag
already exists. See the [plugin's publication wait options](https://central.sonatype.org/publish/publish-portal-maven/#wait-for-publishing).

---

## Reporting Security Issues

If you discover a security vulnerability (e.g., an entity-expansion or XXE bypass), please do **not** open a public issue. Instead, send an email to `robsonkades@outlook.com` with details and steps to reproduce. We take security seriously and will respond promptly.
