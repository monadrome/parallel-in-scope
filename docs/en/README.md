[**Chinese**](../zh-CN/README.md) | **English**

# English Documentation

The root README is the project overview. This page separates stable user guidance from implementation notes and Chinese-only deep dives.

## User Documentation

| Document | Role |
|---|---|
| [Full user guide](user-guide.md) | **Primary guide**: configuration, public APIs, runtime behavior, and advanced features |
| [Demo project](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/README.en.md) | Runnable examples and build commands |
| [Demo documentation map](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/docs/en/README.md) | English entry point for the example catalog |

## Deep Dives

The following documents are currently available in Chinese. They are linked here explicitly rather than silently mixing languages.

| Document | Role |
|---|---|
| [Cooperative cancellation](../zh-CN/reference/cooperative-cancellation.md) | **API/contract reference**: checkpoints and cancellation propagation (Chinese) |
| [ThreadRelay internals](../zh-CN/internals/thread-relay.md) | **Internals**: cross-thread context relay (Chinese) |
| [Design philosophy](../zh-CN/design/philosophy.md) | **Design decisions** and boundaries (Chinese) |
| [Idea Graveyard](../zh-CN/design/idea-graveyard.md) | **Design decisions**: intentionally unsupported features (Chinese) |
| [Demo articles](https://github.com/HuaTalk/parallel-in-scope/blob/main/demo/docs/zh-CN/README.md) | Problem-oriented examples (Chinese) |

## Documentation Roles

- The primary guide describes supported user workflows.
- API/contract references define current contracts.
- Demo articles explain problems and examples; they do not override the API contract.
- Internal documents may evolve with the implementation.
