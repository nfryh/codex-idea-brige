#!/usr/bin/env bash
# 使用固定版本和文件摘要校验 Java 格式工具；不改变源码字符串内容。
set -euo pipefail

mode="${1:---check}"
if [[ $# -gt 1 || ( "$mode" != "--check" && "$mode" != "--write" ) ]]; then
    echo 'Usage: scripts/format-java.sh [--check|--write]' >&2
    exit 2
fi

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
formatter_dir="$project_dir/.tools"
formatter_jar="$formatter_dir/google-java-format-1.37.0-all-deps.jar"
formatter_sha='834b2a0c38cb774953322a84b5ca3f2f40dd3156650b3cd44d3b744345962f7a'
mkdir -p "$formatter_dir"
temporary_dir="$(mktemp -d "$formatter_dir/.format-XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT

if [[ ! -f "$formatter_jar" ]]; then
    temporary_jar="$temporary_dir/formatter.jar"
    curl --fail --location --silent --show-error \
        'https://github.com/google/google-java-format/releases/download/v1.37.0/google-java-format-1.37.0-all-deps.jar' \
        --output "$temporary_jar"
    actual_sha="$(shasum -a 256 "$temporary_jar")"
    if [[ "${actual_sha%% *}" != "$formatter_sha" ]]; then
        echo 'Java 格式工具的文件摘要不匹配，已停止。' >&2
        exit 1
    fi
    mv "$temporary_jar" "$formatter_jar"
fi

actual_sha="$(shasum -a 256 "$formatter_jar")"
if [[ "${actual_sha%% *}" != "$formatter_sha" ]]; then
    echo '缓存中的 Java 格式工具校验失败，已停止。' >&2
    exit 1
fi

java_command='java'
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_command="$JAVA_HOME/bin/java"
fi
format_options=(--aosp --skip-reflowing-long-strings)
if [[ "$mode" == '--write' ]]; then
    format_options+=(--replace)
else
    format_options+=(--dry-run --set-exit-if-changed)
fi

# Git 列出已跟踪和尚未提交的源码，零字节分隔能够保留带空格的文件名。
java_files=()
git ls-files --cached --others --exclude-standard -z -- '*.java' > "$temporary_dir/sources"
while IFS= read -r -d '' source_file; do
    java_files+=("$source_file")
done < "$temporary_dir/sources"
if [[ ${#java_files[@]} -gt 0 ]]; then
    "$java_command" -jar "$formatter_jar" "${format_options[@]}" "${java_files[@]}"
fi
