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
    - Run `mvn clean verify` locally to ensure compilation, tests and Javadoc generation succeed.
    - Submit a **Pull Request** targeting the `main` branch. Provide a thorough description of your changes.

3. **Documentation Improvements**
    - Found a typo in the docs? Think of an example that would better illustrate how to use the API?
    - Submit a PR updating `README.md`, the Javadocs, or this `CONTRIBUTING.md`.

4. **Benchmarks & Performance Testing**
    - JMH benchmarks are built with the `benchmarks` profile: `mvn -Pbenchmarks -DskipTests package`.
    - If you propose a performance-oriented change, include before/after JMH numbers in the PR description.

## Coding Style

- Target Java 17 (`maven.compiler.release` in `pom.xml`).
- Document every public type and method with Javadoc, including `{@link ...}` tags and code examples when appropriate.
- Keep behavioral contracts explicit: absence is `null` / empty list, all failures are `XmlException`, and reads follow document order.
- If you modify `pom.xml`, ensure file structure and indentation remain consistent.

## Pull Request Checklist

- [ ] Your code compiles and tests pass (`mvn clean verify`).
- [ ] New or updated methods include proper Javadoc (`mvn javadoc:javadoc` should produce no errors).
- [ ] New behavior is covered by unit tests, including the debug-mode (`-ea`) semantics when relevant.
- [ ] Performance-sensitive changes include JMH results.
- [ ] The PR description explains both "what" and "why" (not just "how") you made the change.

Once your PR is approved, one of the maintainers will merge it and trigger CI to run additional checks. Thank you for making Fletch better!

---

## Reporting Security Issues

If you discover a security vulnerability (e.g., an entity-expansion or XXE bypass), please do **not** open a public issue. Instead, send an email to `robsonkades@outlook.com` with details and steps to reproduce. We take security seriously and will respond promptly.
