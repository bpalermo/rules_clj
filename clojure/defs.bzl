"""Public API of rules_clj.

This is the only load path with a compatibility promise. Everything under
//clojure/private is an implementation detail and may change without notice.
"""

load("//clojure:native_toolchain.bzl", _clj_native_binary = "clj_native_binary")
load("//clojure/private:binary.bzl", _clj_binary = "clj_binary", _clj_repl = "clj_repl")
load("//clojure/private:cljs.bzl", _cljs_library = "cljs_library")
load("//clojure/private:library.bzl", _clj_library = "clj_library")
load("//clojure/private:maven.bzl", _clj_maven_export = "clj_maven_export")
load("//clojure/private:providers.bzl", _ClojureInfo = "ClojureInfo")
load("//clojure/private:test.bzl", _clj_test = "clj_test")

clj_library = _clj_library
clj_binary = _clj_binary
clj_test = _clj_test
clj_repl = _clj_repl
cljs_library = _cljs_library
clj_native_binary = _clj_native_binary
clj_maven_export = _clj_maven_export
ClojureInfo = _ClojureInfo
