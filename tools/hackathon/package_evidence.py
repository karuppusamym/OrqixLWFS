"""Verify the local qualification receipt and package explicitly selected artifacts.

No directory recursion: credentials, databases, captures and application JARs are
never collected implicitly. The receipt and all selected artifacts need review
before sharing. This packages historical evidence; it does not rerun acceptance.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import zipfile


ARTIFACTS = frozenset({
    "frontend-sbom.json", "runtime-sbom-patched.json",
    "runtime-cves-patched.sarif", "frontend-audit.json",
    "third-party-notices.txt", "prepared.json", "prepared.manifest.json",
})


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def collect(source):
    source = Path(source).resolve()
    receipt = json.loads((source / "candidate.json").read_text(encoding="utf-8"))
    declared = receipt["artifacts"]
    if set(declared) != ARTIFACTS:
        raise ValueError("receipt must declare exactly the supported qualification artifacts")
    files = {}
    for name, expected in {**declared, "load.json": receipt["loadResultSha256"]}.items():
        path = source / name
        if path.is_symlink() or path.resolve().parent != source:
            raise ValueError(f"artifact must be a regular file inside the evidence directory: {name}")
        data = path.read_bytes()
        if digest(data) != expected:
            raise ValueError(f"SHA-256 mismatch: {name}")
        files[name] = data
    # Keep qualification tied to the original image/source, rather than HEAD.
    manifest = {key: receipt[key] for key in (
        "sourceCommit", "qualificationHarnessCommit", "image", "imageId",
        "sourceSha256", "checks", "hostHardware", "completedAtUtc",
    )}
    manifest["artifactSha256"] = {name: digest(data) for name, data in sorted(files.items())}
    manifest["limits"] = [
        "Historical local synthetic qualification; packaging does not rerun tests.",
        "Fifty sessions share four seeded personas; enterprise identity is unqualified.",
        "Security release gate remains open; scanner findings are not risk acceptance.",
        "HC01-HC07 require owner evidence and approval.",
    ]
    load = json.loads(files["load.json"])
    load.pop("fixture", None)  # remove machine-specific absolute location
    manifest["load"] = load
    return files, manifest


def reports(files):
    output = {}
    for label, name in (("frontend", "frontend-sbom.json"), ("runtime", "runtime-sbom-patched.json")):
        rows = []
        for component in json.loads(files[name])["components"]:
            licenses = []
            for entry in component.get("licenses", []):
                license_info = entry.get("license", {})
                licenses.append(entry.get("expression") or license_info.get("id") or license_info.get("name") or "unreported")
            rows.append((component.get("purl", component["name"]), component.get("version", ""), "; ".join(licenses) or "unreported"))
        stream = io.StringIO(newline="")
        writer = csv.writer(stream, lineterminator="\n")
        writer.writerow(("package", "version", "declaredLicense"))
        writer.writerows(sorted(rows))
        output[f"{label}-dependencies.csv"] = stream.getvalue().encode("utf-8")
    runs = json.loads(files["runtime-cves-patched.sarif"])["runs"]
    findings = []
    for run in runs:
        rules = run["tool"]["driver"]["rules"]
        for result in run["results"]:
            # One CVE can have separate rules for different affected packages.
            # SARIF's index preserves that relationship; an ID-only map loses it.
            if "ruleIndex" in result:
                rule = rules[result["ruleIndex"]]
                if rule["id"] != result["ruleId"]:
                    raise ValueError("SARIF rule ID/index mismatch")
            else:
                matches = [rule for rule in rules if rule["id"] == result["ruleId"]]
                if len(matches) != 1:
                    raise ValueError("SARIF rule reference is missing or ambiguous")
                rule = matches[0]
            properties = rule["properties"]
            findings.append({
                "advisory": rule["id"], "packages": properties.get("purls", []),
                "severity": properties.get("cvssV3_severity", properties.get("tags", [])),
                "fixedVersion": properties.get("fixed_version", "unreported"),
                "reference": rule.get("helpUri"), "disposition": "open",
            })
    output["security-findings.json"] = encode({
        "sourceSha256": digest(files["runtime-cves-patched.sarif"]),
        "gate": "open", "findings": findings,
    })
    return output


def package(source, output, report_dir=None):
    files, manifest = collect(source)  # fail before producing any output
    summaries = reports(files)
    files["qualification-manifest.json"] = encode(manifest)
    files.update(summaries)
    output = Path(output)
    if output.exists():
        raise ValueError("output already exists; choose a new evidence package name")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also protects against an output being created meanwhile.
    with output.open("xb") as destination:
        with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, data)
    if report_dir is not None:
        report_dir = Path(report_dir)
        report_dir.mkdir(parents=True, exist_ok=True)
        for name, data in {**summaries, "qualification-manifest.json": encode(manifest)}.items():
            (report_dir / name).write_bytes(data)
    return digest(output.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("evidence/hackathon"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reports-dir", type=Path, help="also write sanitized, reviewable reports for Git")
    args = parser.parse_args()
    try:
        checksum = package(args.source, args.output, args.reports_dir)
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        parser.exit(1, f"Evidence packaging failed: {error}\n")
    print(f"SHA-256 {checksum}  {args.output}")


if __name__ == "__main__":
    main()
