# CLAUDE.md - parallel-in-scope-demo

## Project

This directory is a standalone Java 8 example project that consumes the published `parallel-in-scope` artifact. It must remain buildable independently from the repository's root Maven project.

## Commands

- Compile: `mvn clean compile`
- Targeted test: `mvn test -Dtest=<TestClass>`
- Full test: `mvn test`
- Dependency audit: `mvn dependency:tree`
- Run one demo: `mvn exec:java -Dexec.mainClass=demo.basic.BasicParDemo`
- Run all demos: `mvn verify -Prun-all-demos`

Run commands from `demo/`.

## Structure

- `src/main/java/demo/basic/` - introductory examples.
- `src/main/java/demo/advanced/` - diagnostics and advanced behavior.
- `src/main/java/demo/integration/` - end-to-end usage scenarios.
- `src/test/java/demo/article/` - executable tests linked from articles.
- `docs/zh-CN/articles/` - problem-oriented articles backed by tests.
- `scripts/run-demos.sh` - convenience runner for supported demos.

## Critical Rules

- Keep demo packages under `demo.*`.
- Depend on the published library artifact; never reference the root project's source tree.
- Use only the public APIs allowed by `architecture-constraints.md`.
- Add or update an executable test when an article changes observable behavior.
- Keep article snippets focused; do not repeat imports when the linked complete test contains them.
- Update `scripts/run-demos.sh` when adding a runnable main class.

## Done Criteria

- Run the targeted test for changed examples or articles.
- Run `mvn test` before finishing changes that affect the demo module.
- Run `mvn dependency:tree` when changing dependencies or plugin configuration.

## References

- `@architecture-constraints.md` - Read before changing dependencies, package names, imports, or module boundaries. It is the authority for allowed APIs and architecture checks.
- `@README.en.md` - Read when adding a runnable demo or changing user-facing demo navigation.
