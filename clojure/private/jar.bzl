"""Turning source files into a jar.

Phase 1 packages sources; nothing is compiled yet. The interesting part is not the
zip, it is deciding what each file is called inside it — Clojure resolves a
namespace by looking for a matching path on the classpath, so `foo.bar-baz` must
land at `foo/bar_baz.clj` exactly.
"""

def workspace_path(file):
    """Returns the path of a file relative to its source root.

    Neither File.path nor File.short_path is quite right on its own: the former
    carries the bazel-out prefix for generated files, and the latter uses `../`
    for files from another repository. Stripping the roots explicitly handles both
    the same way.

    Args:
      file: a File.

    Returns:
      The workspace-relative path, as a string.
    """
    path = file.path
    if file.root.path:
        path = path[len(file.root.path) + 1:]
    workspace_root = file.owner.workspace_root if file.owner else ""
    if workspace_root and path.startswith(workspace_root + "/"):
        path = path[len(workspace_root) + 1:]
    return path

def jar_entry_path(file, strip_prefix):
    """Returns where a file goes inside the jar.

    Fails loudly on a file outside the prefix. The alternative — silently keeping
    the full path — produces a jar that builds fine and then cannot load the
    namespace it supposedly contains, which is a much worse afternoon.

    Args:
      file: a File to place in the jar.
      strip_prefix: path prefix to remove, or "" to keep the path as-is.

    Returns:
      The path of the entry inside the jar, as a string.
    """
    path = workspace_path(file)
    if not strip_prefix:
        return path
    prefix = strip_prefix.rstrip("/") + "/"
    if not path.startswith(prefix):
        fail("{} is not under strip_prefix '{}'".format(path, strip_prefix))
    return path[len(prefix):]

def build_jar(ctx, output, files, strip_prefix, extra_entries = {}):
    """Zips files into a jar, stripping a prefix from each path.

    Uses Bazel's own zipper, which writes fixed timestamps, so the output is
    byte-identical across builds of identical inputs.

    Args:
      ctx: the rule context.
      output: the File to write.
      files: list of Files to include.
      strip_prefix: path prefix to remove from each, or "" to keep paths as-is.
      extra_entries: {entry path: File} for files whose place in the jar does not
        follow from their path. A generated pom belongs at
        META-INF/maven/{group}/{artifact}/pom.xml, which no strip_prefix can produce
        from bazel-out/.../pom.xml — so the caller names the entry instead.
    """

    # Entries are computed here rather than in a map_each closure: closures run when
    # the command line is materialised, so a strip_prefix that matches nothing would
    # fail during execution with a Starlark stack trace instead of during analysis
    # with the label that caused it.
    entries = ["{}={}".format(jar_entry_path(f, strip_prefix), f.path) for f in files]

    # Sorted, so the zip's entry order depends on the contents rather than on the
    # iteration order of a dict.
    extra_files = []
    for entry in sorted(extra_entries):
        file = extra_entries[entry]
        entries.append("{}={}".format(entry, file.path))
        extra_files.append(file)

    args = ctx.actions.args()
    args.add("c", output)
    args.add_all(entries)
    args.set_param_file_format("multiline")
    args.use_param_file("@%s", use_always = False)

    ctx.actions.run(
        executable = ctx.executable._zipper,
        arguments = [args],
        inputs = files + extra_files,
        outputs = [output],
        mnemonic = "ClojureJar",
        progress_message = "Packaging %{label}",
    )

ZIPPER_ATTR = {
    "_zipper": attr.label(
        default = Label("@bazel_tools//tools/zip:zipper"),
        executable = True,
        cfg = "exec",
    ),
}
