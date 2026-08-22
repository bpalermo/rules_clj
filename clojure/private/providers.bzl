"""Providers exposed by the Clojure rules."""

ClojureInfo = provider(
    doc = """Clojure-specific information about a target.

Carried alongside JavaInfo, so that downstream rules and the BUILD generator can
reason about the graph without re-parsing source to rediscover it.""",
    fields = {
        "namespaces": "depset of strings — namespaces this target provides.",
        "srcs": "depset of Files — the Clojure sources in this target.",
        "aot": "bool — whether those namespaces were compiled ahead of time.",
    },
)
