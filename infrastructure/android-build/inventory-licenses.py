#!/usr/bin/env python3
"""Record locked Rust and resolved Android POM declarations for license review."""
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
for manifest, output, title in [
    (root / "rust/Cargo.toml", "rust-dependency-inventory.md", "Entangle"),
    (root / "Refrence/Ferrous/rust/Cargo.toml", "ferrous-rust-dependency-inventory.md", "Ferrous reference"),
]:
    if not manifest.exists():
        continue
    metadata = json.loads(subprocess.check_output([
        "cargo", "metadata", "--manifest-path", str(manifest), "--locked", "--all-features", "--format-version", "1",
    ]))
    rows = [
        f"# {title} Rust dependency inventory", "",
        "Generated from locked Cargo metadata with all features. Includes host, optional, development and target-specific "
        "packages; inclusion does not imply a package is in the Android APK. SPDX expressions are recorded as upstream declares them.", "",
        "| Crate | Version | Declared license |", "| --- | --- | --- |",
    ]
    for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
        if package["source"] is None:
            continue
        declared = package["license"] or (
            "license-file: " + Path(package["license_file"]).name if package.get("license_file")
            else "**Undeclared; review required**"
        )
        rows.append(f'| {package["name"]} | {package["version"]} | {declared.replace("|", " / ")} |')
    (root / "docs" / output).write_text("\n".join(rows) + "\n")

coordinates = root / "docs/android-dependency-coordinates.tsv"
if coordinates.exists():
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    rows = [
        "# Android resolved dependency inventory", "",
        "Resolved runtime/test coordinates and locally cached Maven POM license declarations. Host Gradle plugins are listed "
        "separately in dependencies.md. License choices and inherited POM licenses may require a release review.", "",
        "| Coordinate | Scope | Declared POM license |", "| --- | --- | --- |",
    ]
    for line in coordinates.read_text().splitlines()[1:]:
        coordinate, scope = line.split("\t")
        group, name, version = coordinate.split(":")
        declarations = []
        for pom in (cache / group / name / version).glob("*/*.pom"):
            element = ET.parse(pom).getroot()
            namespaces = {"m": "http://maven.apache.org/POM/4.0.0"}
            for license in element.findall("m:licenses/m:license", namespaces):
                label = license.findtext("m:name", default="", namespaces=namespaces)
                if label:
                    declarations.append(label)
        declaration = "; ".join(sorted(set(declarations))) or "Not declared in cached POM; review parent/source"
        scope_label = "runtime + test" if "," in scope else ("runtime" if scope == "debugRuntimeClasspath" else "test")
        rows.append(f'| {coordinate} | {scope_label} | {declaration.replace("|", " / ")} |')
    (root / "docs/android-dependency-inventory.md").write_text("\n".join(rows) + "\n")
