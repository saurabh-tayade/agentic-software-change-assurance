# ADR-0001 — Modular Monolith

## Status

Accepted for M0. One deployable application exists; domain modules do not yet exist.

## Context

The platform currently has only application bootstrap and health verification.
Domain boundaries, workloads, and independent scaling needs have not been tested.
A distributed design would add operational and failure-handling work before there
is evidence that it improves change assurance.

## Decision

Begin with one Spring Boot deployable application. Introduce domain boundaries
under `dev.changeassurance` as actual capabilities emerge, keeping responsibilities
cohesive and dependencies explicit. Do not add placeholder packages, separate Maven
modules, microservices, or distributed communication to represent planned features.
Revisit extraction only when measured constraints or independent lifecycle needs
justify it. This decision does not require a module framework.

## Alternatives considered

- Microservices from the outset: allow independent deployment and scaling, but
  require contracts, remote failure handling, and operational infrastructure before
  domain boundaries are understood.
- An unstructured single application: keeps initial setup simple, but makes later
  ownership and dependency boundaries harder to reason about.
- Multiple build modules immediately: enforce some boundaries early, but would
  encode untested divisions and increase build maintenance.

## Consequences

Build, test, and run one artifact. Review boundaries as functionality is added,
using packages and observable tests before adopting additional enforcement tools.
Components share a process and release cycle; they cannot be independently scaled
or isolated merely by putting them in separate packages.

## Tradeoffs

This favors development speed and simple integration over independent deployment
and fault isolation. Package discipline relies on review at this stage. Future
extraction may require refactoring; that cost is preferable to committing to
speculative distributed boundaries now.
