"""Tests of the CG-K05 compatibility rules (contract_diff.py) on small synthetic contracts.

    python -m unittest discover -s tools/contract-check -p "test_*.py"
"""
from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import contract_diff  # noqa: E402

BASE = {
    "openapi": "3.1.1",
    "info": {"title": "t", "version": "1"},
    "paths": {
        "/things": {
            "post": {
                "operationId": "createThing",
                "parameters": [{"name": "dryRun", "in": "query", "schema": {"type": "boolean"}}],
                "requestBody": {"required": True, "content": {"application/json": {"schema": {
                    "$ref": "#/components/schemas/ThingRequest"}}}},
                "responses": {
                    "201": {"description": "ok", "content": {"application/json": {"schema": {
                        "$ref": "#/components/schemas/Thing"}}}},
                    "404": {"$ref": "#/components/responses/NotFound"},
                },
            },
        },
        "/things/{id}": {
            "parameters": [{"name": "id", "in": "path", "required": True, "schema": {"type": "string"}}],
            "get": {
                "operationId": "getThing",
                "responses": {"200": {"description": "ok", "content": {"application/json": {"schema": {
                    "$ref": "#/components/schemas/Thing"}}}}},
            },
        },
    },
    "components": {
        "responses": {"NotFound": {"description": "nf", "content": {"application/problem+json": {"schema": {
            "$ref": "#/components/schemas/Problem"}}}}},
        "schemas": {
            "ThingRequest": {"type": "object", "required": ["name"], "properties": {
                "name": {"type": "string"}, "note": {"type": "string"},
                "options": {"type": "object", "properties": {"fast": {"type": "boolean"}}}}},
            "Thing": {"type": "object", "required": ["id", "state", "tags"], "properties": {
                "id": {"type": "string"},
                "state": {"type": "string", "enum": ["open", "closed"]},
                "kind": {"type": "string"},
                "tags": {"type": "array", "items": {"type": "object", "required": ["key"], "properties": {
                    "key": {"type": "string"}}}},
                "parent": {"oneOf": [{"$ref": "#/components/schemas/Thing"}, {"type": "null"}]},
            }},
            "Problem": {"type": "object", "required": ["code"], "properties": {
                "code": {"type": "string", "enum": ["NOT_FOUND", "INTERNAL_ERROR"]}}},
        },
    },
}


def _baseline(document: dict, releases: list[str] | None = None) -> dict:
    return {"format": contract_diff.SURFACE_FORMAT, "api": "/api/v1", "releases": releases or ["r1"],
            **contract_diff.build_surface(document)}


def _diff(change, releases: list[str] | None = None, current_release: str | None = None,
          base: dict | None = None) -> contract_diff.Diff:
    before = copy.deepcopy(base or BASE)
    after = copy.deepcopy(before)
    change(after)
    # Round-trip through JSON like the recorded file.
    recorded = json.loads(json.dumps(_baseline(before, releases)))
    return contract_diff.compare(recorded, contract_diff.build_surface(after), current_release)


def schemas(doc: dict) -> dict:
    return doc["components"]["schemas"]


class CompatibilityRulesTest(unittest.TestCase):
    def test_unchanged_contract_has_no_findings(self) -> None:
        diff = _diff(lambda doc: None)
        self.assertEqual([], diff.breaking)
        self.assertEqual([], diff.additive)

    def test_removed_operation_is_breaking(self) -> None:
        diff = _diff(lambda doc: doc["paths"]["/things/{id}"].pop("get"))
        self.assertEqual(1, len(diff.breaking))
        self.assertIn("getThing", diff.breaking[0])
        self.assertIn("not deprecated", diff.breaking[0])

    def test_removed_required_response_field_is_breaking(self) -> None:
        diff = _diff(lambda doc: (schemas(doc)["Thing"]["properties"].pop("state"),
                                  schemas(doc)["Thing"]["required"].remove("state")))
        self.assertTrue(any("required response field `state` was removed" in b for b in diff.breaking), diff.breaking)

    def test_required_response_field_becoming_optional_is_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["required"].remove("id"))
        self.assertTrue(any("`id` is no longer required" in b for b in diff.breaking), diff.breaking)

    def test_nested_required_response_field_is_checked(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["properties"]["tags"]["items"]["required"].clear())
        self.assertTrue(any("`tags[].key` is no longer required" in b for b in diff.breaking), diff.breaking)

    def test_removed_optional_response_field_is_not_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["properties"].pop("kind"))
        self.assertEqual([], diff.breaking)

    def test_narrowed_response_enum_is_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["properties"]["state"].update(enum=["open"]))
        self.assertTrue(any('no longer returns "closed"' in b for b in diff.breaking), diff.breaking)

    def test_enum_placed_on_a_free_response_field_is_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["properties"]["kind"].update(enum=["a"]))
        self.assertTrue(any("`kind` is now limited to" in b for b in diff.breaking), diff.breaking)

    def test_widened_response_enum_is_additive(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Thing"]["properties"]["state"]["enum"].append("archived"))
        self.assertEqual([], diff.breaking)
        self.assertTrue(any('may also return "archived"' in a for a in diff.additive), diff.additive)

    def test_narrowed_shared_problem_enum_is_reported_once(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["Problem"]["properties"]["code"]["enum"].remove("INTERNAL_ERROR"))
        self.assertEqual(1, len(diff.breaking), diff.breaking)
        self.assertIn("shared response `NotFound`", diff.breaking[0])

    def test_new_required_request_field_is_breaking(self) -> None:
        def change(doc: dict) -> None:
            schemas(doc)["ThingRequest"]["properties"]["owner"] = {"type": "string"}
            schemas(doc)["ThingRequest"]["required"].append("owner")
        diff = _diff(change)
        self.assertTrue(any("request field `owner`" in b for b in diff.breaking), diff.breaking)

    def test_optional_request_field_becoming_required_is_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["ThingRequest"]["required"].append("note"))
        self.assertTrue(any("request field `note`" in b for b in diff.breaking), diff.breaking)

    def test_required_child_of_existing_optional_object_is_breaking(self) -> None:
        diff = _diff(lambda doc: schemas(doc)["ThingRequest"]["properties"]["options"].update(required=["fast"]))
        self.assertTrue(any("`options.fast`" in b for b in diff.breaking), diff.breaking)

    def test_new_optional_object_with_required_children_is_additive(self) -> None:
        def change(doc: dict) -> None:
            schemas(doc)["ThingRequest"]["properties"]["audit"] = {
                "type": "object", "required": ["by"], "properties": {"by": {"type": "string"}}}
        diff = _diff(change)
        self.assertEqual([], diff.breaking)
        self.assertTrue(any("new optional request field `audit`" in a for a in diff.additive))

    def test_new_required_parameter_is_breaking(self) -> None:
        def change(doc: dict) -> None:
            doc["paths"]["/things"]["post"]["parameters"].append(
                {"name": "Idempotency-Key", "in": "header", "required": True, "schema": {"type": "string"}})
        diff = _diff(change)
        self.assertTrue(any("`header:Idempotency-Key` is now required" in b for b in diff.breaking), diff.breaking)

    def test_new_operation_and_response_field_are_additive(self) -> None:
        def change(doc: dict) -> None:
            doc["paths"]["/things/{id}"]["delete"] = {"operationId": "deleteThing", "responses": {"204": {
                "description": "gone"}}}
            schemas(doc)["Thing"]["properties"]["createdAt"] = {"type": "string", "format": "date-time"}
        diff = _diff(change)
        self.assertEqual([], diff.breaking)
        self.assertTrue(any("deleteThing" in a for a in diff.additive))
        self.assertTrue(any("new response field `createdAt`" in a for a in diff.additive))

    def test_recursive_schema_terminates(self) -> None:
        surface = contract_diff.build_surface(BASE)
        fields = surface["operations"]["GET /things/{id}"]["responses"]["200"]["application/json"]["fields"]
        # The second visit of Thing stops the walk instead of recursing.
        self.assertEqual("O null|recursive", fields["parent"])
        self.assertNotIn("parent.id", fields)


class DeprecationWindowTest(unittest.TestCase):
    @staticmethod
    def _deprecated() -> dict:
        doc = copy.deepcopy(BASE)
        doc["paths"]["/things/{id}"]["get"].update({"deprecated": True, "x-orqix-deprecated-in": "r1"})
        return doc

    def test_deprecating_an_operation_is_listed(self) -> None:
        diff = _diff(lambda doc: doc["paths"]["/things/{id}"]["get"].update(
            {"deprecated": True, "x-orqix-deprecated-in": "r2"}))
        self.assertEqual([], diff.breaking)
        self.assertEqual(1, len(diff.deprecated))

    def test_removal_inside_the_window_is_breaking(self) -> None:
        diff = _diff(lambda doc: doc["paths"]["/things/{id}"].pop("get"), releases=["r1", "r2"],
                     base=self._deprecated())
        self.assertEqual(1, len(diff.breaking))
        self.assertIn("deprecation window", diff.breaking[0])

    def test_removal_after_two_releases_is_allowed(self) -> None:
        diff = _diff(lambda doc: doc["paths"]["/things/{id}"].pop("get"), releases=["r1", "r2"],
                     current_release="r3", base=self._deprecated())
        self.assertEqual([], diff.breaking)
        self.assertEqual(1, len(diff.removed_after_window))

    def test_deprecated_operation_needs_its_release_and_headers(self) -> None:
        doc = copy.deepcopy(BASE)
        doc["paths"]["/things/{id}"]["get"]["deprecated"] = True
        problems = contract_diff.deprecation_problems(doc)
        self.assertEqual(3, len(problems), problems)
        doc["paths"]["/things/{id}"]["get"]["x-orqix-deprecated-in"] = "r1"
        doc["paths"]["/things/{id}"]["get"]["responses"]["200"]["headers"] = {
            "Deprecation": {"schema": {"type": "string"}}, "Sunset": {"schema": {"type": "string"}}}
        self.assertEqual([], contract_diff.deprecation_problems(doc))

    def test_removal_with_an_unknown_deprecation_release_is_breaking(self) -> None:
        base = self._deprecated()
        base["paths"]["/things/{id}"]["get"]["x-orqix-deprecated-in"] = "r9"
        diff = _diff(lambda doc: doc["paths"]["/things/{id}"].pop("get"), releases=["r1", "r2", "r3"], base=base)
        self.assertEqual(1, len(diff.breaking))


class RepositoryFilesTest(unittest.TestCase):
    def test_record_check_and_changelog_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "contracts").mkdir()
            (root / "docs").mkdir()
            (root / "contracts/openapi.yaml").write_text(yaml.safe_dump(BASE), encoding="utf-8")
            (root / "docs/api-changelog.md").write_text(
                f"# Changelog\n\n{contract_diff.BEGIN}\n{contract_diff.END}\n", encoding="utf-8")
            self.assertEqual(0, contract_diff.main(["--root", tmp, "--record", "r1"]))
            self.assertEqual(0, contract_diff.main(["--root", tmp]))

            changed = copy.deepcopy(BASE)
            changed["paths"]["/things/{id}"]["delete"] = {"operationId": "deleteThing", "responses": {
                "204": {"description": "gone"}}}
            (root / "contracts/openapi.yaml").write_text(yaml.safe_dump(changed), encoding="utf-8")
            self.assertEqual(1, contract_diff.main(["--root", tmp]), "a stale changelog fails")
            self.assertEqual(0, contract_diff.main(["--root", tmp, "--changelog"]))
            self.assertIn("deleteThing", (root / "docs/api-changelog.md").read_text(encoding="utf-8"))
            self.assertEqual(0, contract_diff.main(["--root", tmp]))

            del changed["paths"]["/things"]
            (root / "contracts/openapi.yaml").write_text(yaml.safe_dump(changed), encoding="utf-8")
            self.assertEqual(1, contract_diff.main(["--root", tmp, "--changelog"]), "a breaking change fails")

    def test_recorded_baseline_matches_its_format(self) -> None:
        root = Path(__file__).resolve().parents[2]
        baseline = contract_diff.load_baseline(root)
        self.assertEqual(contract_diff.SURFACE_FORMAT, baseline["format"])
        self.assertTrue(baseline["releases"])


if __name__ == "__main__":
    unittest.main()
