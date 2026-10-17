"""Bind a Tier-1 definition to actual reviewed Git content before publication.

Schema checks here complement, rather than replace, server compiler and golden-scenario gates.
The injected provenance describes the source commit; it is not a self-referential commit hash.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from jsonschema import Draft202012Validator


def prepare(root, source, commit, review_reference=""):
    root = Path(root).resolve()
    source = (root / source).resolve()
    if not source.is_relative_to(root):
        raise ValueError("Source must be inside the repository.")
    if not re.fullmatch(r"[a-f0-9]{40}(?:[a-f0-9]{24})?", commit):
        raise ValueError("Use the full reviewed Git commit hash.")
    relative = source.relative_to(root).as_posix()
    committed = subprocess.check_output(["git", "show", f"{commit}:{relative}"], cwd=root, stderr=subprocess.PIPE)
    definition = json.loads(source.read_text(encoding="utf-8"))
    reviewed = json.loads(committed)
    # Provenance is injected by this preparation step, after the source has been committed/reviewed.
    for doc in (definition, reviewed):
        doc.pop("provenance", None)
    if definition != reviewed:
        raise ValueError("Working definition differs from the reviewed Git source. Commit and review the change first.")
    if definition.get("acceptanceProfile") != "tier1":
        raise ValueError("This preparation command requires the Tier-1 acceptance profile.")
    if len(review_reference) > 200:
        raise ValueError("Review reference exceeds 200 characters.")
    definition["provenance"] = {"gitCommit": commit}
    if review_reference:
        definition["provenance"]["reviewReference"] = review_reference
    schema = json.loads((root / "contracts/workflow-v0.schema.json").read_text(encoding="utf-8"))
    Draft202012Validator(schema).validate(definition)
    manifest = {"sourceCommit": commit, "sourcePath": relative,
                "sourceBlobSha256": hashlib.sha256(committed).hexdigest(),
                "reviewReference": review_reference,
                "pendingGates": ["server compiler", "golden scenarios", "independent publisher"]}
    return definition, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    parser.add_argument("--source", required=True, help="Repository-relative workflow JSON")
    parser.add_argument("--commit", required=True)
    parser.add_argument("--review-reference", default="")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        definition, manifest = prepare(args.root, args.source, args.commit, args.review_reference)
        payload = (json.dumps(definition, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
        target = Path(args.output)
        if target.resolve() == (Path(args.root) / args.source).resolve():
            raise ValueError("Write the prepared artifact separately from its reviewed source.")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        manifest["preparedArtifactSha256"] = hashlib.sha256(payload).hexdigest()
        target.with_suffix(".manifest.json").write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
        print("Prepared reviewed source and manifest; server validation and independent publishing remain required.")
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Preparation refused: {exc}\n")


if __name__ == "__main__":
    main()
