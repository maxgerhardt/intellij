#
# Description:
# Proto dependencies from bazel.
#
load(
    "//intellij_platform_sdk:build_defs.bzl",
    "combine_visibilities",
)
load(
    "//:build-visibility.bzl",
    "ASPECT_PROTO_VISIBILITY",
    "PLUGIN_PACKAGES_VISIBILITY",
    "create_proto_visibility_group",
)

licenses(["notice"])  # Apache 2.0

create_proto_visibility_group()

java_import(
    name = "proto_deps",
    jars = [":repackaged_proto_deps"],
    visibility = PLUGIN_PACKAGES_VISIBILITY,
)

proto_library(
    name = "common_proto",
    srcs = ["common.proto"],
    visibility = ["//visibility:private"],
)

java_proto_library(
    name = "common_java_proto",
    visibility = combine_visibilities(
        ASPECT_PROTO_VISIBILITY,
        PLUGIN_PACKAGES_VISIBILITY,
    ),
    deps = [":common_proto"],
)

proto_library(
    name = "fast_build_info_proto",
    srcs = ["fast_build_info.proto"],
    visibility = PLUGIN_PACKAGES_VISIBILITY,
    deps = [":common_proto"],
)

java_proto_library(
    name = "fast_build_info_java_proto",
    visibility = PLUGIN_PACKAGES_VISIBILITY,
    deps = [":fast_build_info_proto"],
)

proto_library(
    name = "intellij_ide_info_proto",
    srcs = ["intellij_ide_info.proto"],
    visibility = combine_visibilities(
        ASPECT_PROTO_VISIBILITY,
        PLUGIN_PACKAGES_VISIBILITY,
    ),
    deps = [
        ":common_proto",
    ],
)

java_proto_library(
    name = "intellij_ide_info_java_proto",
    visibility = combine_visibilities(
        ASPECT_PROTO_VISIBILITY,
        PLUGIN_PACKAGES_VISIBILITY,
    ),
    deps = [":intellij_ide_info_proto"],
)

proto_library(
    name = "project_data_proto",
    srcs = ["project_data.proto"],
    deps = [
        ":common_proto",
        ":intellij_ide_info_proto",
        "//third_party/bazel/src/main/protobuf:build_proto",
    ],
)

java_proto_library(
    name = "project_data_java_proto",
    deps = [":project_data_proto"],
)

proto_library(
    name = "intellij_plugin_target_deploy_info_proto",
    srcs = ["intellij_plugin_target_deploy_info.proto"],
)

java_proto_library(
    name = "intellij_plugin_target_deploy_info_java_proto",
    deps = [":intellij_plugin_target_deploy_info_proto"],
)

genrule(
    name = "repackaged_proto_deps",
    srcs = [
        ":proto_deps_binary_deploy.jar",
        ":jarjar_protobuf_rule.txt",
    ],
    outs = [":repackaged_proto_deps.jar"],
    cmd = """
    $(location @jarjar//:jarjar_bin) \
        process \
        $(location :jarjar_protobuf_rule.txt) \
        $(location :proto_deps_binary_deploy.jar) \
        $@
    """,
    tools = ["@jarjar//:jarjar_bin"],
)

java_binary(
    name = "proto_deps_binary",
    main_class = "None",
    runtime_deps = [
        ":fast_build_info_java_proto",
        ":intellij_ide_info_java_proto",
        ":intellij_plugin_target_deploy_info_java_proto",
        ":project_data_java_proto",
        "//third_party/bazel/src/main/java/com/google/devtools/build/lib/buildeventstream/proto:build_event_stream_java_proto",
        "//third_party/bazel/src/main/java/com/google/devtools/build/lib/starlarkdebug/proto:starlark_debugging_java_proto",
        "//third_party/bazel/src/main/protobuf:android_deploy_info_java_proto",
        "//third_party/bazel/src/main/protobuf:build_java_proto",
        "//third_party/bazel/src/main/protobuf:deps_java_proto",
    ],
)
