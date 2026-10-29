#!/usr/bin/env python3
"""Generate the synthetic native Pega rule archives used by MIG11 tests (docs/build/S112).

The archives hold rule-instance pages in the clipboard XML shape Pega documents for rule and
data instances (a <pagedata> root, one element per property, PageList/PropertyList values as
REPEATINGTYPE lists of <rowdata REPEATINGINDEX="n">). They are SYNTHETIC: no genuine Pega or
customer export was available, so they show what the decoder accepts, not that real exports
match it. Grammar fidelity against real exports is AC01/EXT01 customer qualification.

Run from the repository root:
    python examples/migration/pega-native/generate.py          # write archives and expectations
    python examples/migration/pega-native/generate.py --check  # verify the committed files
"""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import sys
import zipfile
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parents[2] / "tools" / "migration-equivalence"))
import pega_native  # noqa: E402  (the independent decoder/resolver used as a test oracle)

NOTICE = "SYNTHETIC TEST FIXTURE for Orqix MIG11: not a Pega export and not a customer export."
WORK = "ACME-Purchasing-Work"
REQUEST = "ACME-Purchasing-Work-Request"
VENDOR = "ACME-Purchasing-Data-Vendor"


class PageList(list):
    """A PageList property: a list of dicts (embedded pages)."""


class PropertyList(list):
    """A PropertyList property: a list of plain values."""


def render(name: str, value, indent: str) -> str:
    if isinstance(value, PageList):
        rows = "".join(
            f'{indent}  <rowdata REPEATINGINDEX="{i}">\n'
            + "".join(render(k, v, indent + "    ") for k, v in row.items())
            + f"{indent}  </rowdata>\n"
            for i, row in enumerate(value, start=1))
        return f'{indent}<{name} REPEATINGTYPE="PageList">\n{rows}{indent}</{name}>\n'
    if isinstance(value, PropertyList):
        rows = "".join(f'{indent}  <rowdata REPEATINGINDEX="{i}">{escape(str(v))}</rowdata>\n'
                       for i, v in enumerate(value, start=1))
        return f'{indent}<{name} REPEATINGTYPE="PropertyList">\n{rows}{indent}</{name}>\n'
    return f"{indent}<{name}>{escape(str(value))}</{name}>\n"


def page(obj: str, cls: str, name: str, ruleset: str, version: str, available: str = "Yes",
         body: dict | None = None, stamp: str = "20261001T090000.000", circumstance: tuple[str, str] | None = None,
         extra: dict | None = None) -> bytes:
    fields = {"pxObjClass": obj, "pyClassName": cls, "pyRuleName": name, "pyRuleSet": ruleset,
              "pyRuleSetVersion": version, "pyRuleAvailable": available,
              "pzInsKey": f"{obj.upper()} {cls.upper()} {name.upper()} #{stamp} GMT"}
    if circumstance:
        fields["pyCircumstanceProp"], fields["pyCircumstanceVal"] = circumstance
    fields.update(extra or {})
    fields.update(body or {})
    text = (f'<?xml version="1.0" encoding="UTF-8"?>\n<!-- {NOTICE} -->\n<pagedata>\n'
            + "".join(render(k, v, "  ") for k, v in fields.items()) + "</pagedata>\n")
    return text.encode("utf-8")


def data_page(obj: str, key: str, body: dict) -> bytes:
    fields = {"pxObjClass": obj, "pzInsKey": f"{obj.upper()} {key.upper()}", **body}
    text = (f'<?xml version="1.0" encoding="UTF-8"?>\n<!-- {NOTICE} -->\n<pagedata>\n'
            + "".join(render(k, v, "  ") for k, v in fields.items()) + "</pagedata>\n")
    return text.encode("utf-8")


def archive(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.comment = NOTICE.encode("ascii")
        for name, content in entries.items():
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            zf.writestr(info, content)
    return output.getvalue()


def prop(cls: str, name: str, string_type: str, version: str = "01-01-05", **body) -> bytes:
    return page("Rule-Obj-Property", cls, name, "Purchasing", version,
                body={"pyPropertyMode": body.pop("mode", "Single Value"), "pyStringType": string_type, **body},
                stamp=f"20261001T09{version[-2:]}00.000")


def flow_action(name: str, section: str) -> bytes:
    return page("Rule-Obj-FlowAction", WORK, name, "Purchasing", "01-01-01", body={"pySection": section})


def assignment(shape_id: str, label: str, workbasket: str, sla: str | None = None) -> dict:
    shape = {"pyShapeID": shape_id, "pyShapeType": "Assignment", "pyShapeName": label,
             "pyRouterType": "ToWorkbasket", "pyRouterParam": workbasket}
    if sla:
        shape["pyServiceLevelName"] = sla
    return shape


def connector(cid: str, source: str, target: str, kind: str, **fields) -> dict:
    return {"pyConnectorID": cid, "pyFrom": source, "pyTo": target, "pyConditionType": kind, **fields}


def purchase_request() -> dict[str, bytes]:
    e: dict[str, bytes] = {"META-INF/MANIFEST.MF": f"Manifest-Version: 1.0\nComment: {NOTICE}\n".encode("ascii")}
    e["application/Purchasing.xml"] = page(
        "Rule-Application", "@baseclass", "Purchasing", "Purchasing", "01-01-01",
        body={"pyRuleSets": PageList([{"pyRuleSetName": "PurchasingOrg", "pyRuleSetVersion": "01-02-01"},
                                       {"pyRuleSetName": "Purchasing", "pyRuleSetVersion": "01-01-10"}]),
              "pyBuiltOnApplications": PageList([{"pyApplicationName": "PegaRULES", "pyApplicationVersion": "08-08-01"}])})
    for cls, parent, kind in ((REQUEST, WORK, "Concrete"), (WORK, "Work-Cover-", "Abstract"), (VENDOR, "Data-", "Concrete")):
        e[f"classes/{cls}.xml"] = page("Rule-Obj-Class", cls, cls, "Purchasing", "01-01-01",
                                       body={"pyDerivesFrom": parent, "pyClassType": kind, "pyPatternInheritance": "true"})
    # Properties. Amount exists twice: 01-01-01 (Integer) is shadowed by 01-01-05 (Decimal).
    e["properties/Amount-01-01-01.xml"] = prop(WORK, "Amount", "Integer", "01-01-01")
    e["properties/Amount.xml"] = prop(WORK, "Amount", "Decimal", pyLabel="Amount (USD)")
    e["properties/Description.xml"] = prop(WORK, "Description", "Text", pyMaxLength="500")
    e["properties/Category.xml"] = prop(WORK, "Category", "Text", pyTableType="Local List",
                                        pyLocalList=PropertyList(["Hardware", "Software", "Services"]))
    e["properties/NeededBy.xml"] = prop(WORK, "NeededBy", "Date")
    e["properties/Vendor.xml"] = prop(WORK, "Vendor", "Text", mode="Page", pyPageClass=VENDOR)
    e["properties/VendorName.xml"] = prop(VENDOR, "Name", "Text", pyMaxLength="120")
    e["properties/VendorPreferred.xml"] = prop(VENDOR, "Preferred", "TrueFalse")
    e["properties/InternalNote.xml"] = prop(WORK, "InternalNote", "Text")  # never referenced: reported as not used
    # Decision table: the 01-01-05 version is not available, so resolution falls back to 01-01-01.
    for version, threshold, available in (("01-01-01", "5000", "Yes"), ("01-01-05", "7000", "No")):
        e[f"decisions/ApprovalLevel-{version}.xml"] = page(
            "Rule-Declare-DecisionTable", WORK, "ApprovalLevel", "Purchasing", version, available,
            stamp=f"20261001T10{version[-2:]}00.000",
            body={"pyEvaluateAllRows": "false",
                  "pyColumns": PageList([{"pyProperty": ".Amount", "pyColumnDataType": "Decimal"}]),
                  "pyRows": PageList([{"pyCondition": f"<{threshold}", "pyResult": "Standard"}]),
                  "pyOtherwise": "Finance"})
    # When rule: the request class overrides the work class (class distance wins).
    when_rows = PageList([{"pyLabel": "A", "pyLeft": ".Vendor.Preferred", "pyOperator": "=", "pyRight": "false"},
                          {"pyLabel": "B", "pyLeft": ".Amount", "pyOperator": ">=", "pyRight": "1000"}])
    e["when/NeedsVendorCheck-Work.xml"] = page("Rule-Obj-When", WORK, "NeedsVendorCheck", "Purchasing", "01-01-05",
        body={"pyConditions": PageList([{"pyLabel": "A", "pyLeft": ".Amount", "pyOperator": ">=", "pyRight": "100"}]),
              "pyLogicString": "A"})
    e["when/NeedsVendorCheck-Request.xml"] = page("Rule-Obj-When", REQUEST, "NeedsVendorCheck", "Purchasing", "01-01-03",
        body={"pyConditions": when_rows, "pyLogicString": "A AND B"})
    # Flow. 01-01-01 is an older version; the sandbox ruleset is not in the application stack.
    approve_shapes = PageList([
        {"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
        {"pyShapeID": "Level", "pyShapeType": "Decision", "pyShapeName": "Approval level",
         "pyDecisionType": "DecisionTable", "pyDecisionName": "ApprovalLevel"},
        assignment("ManagerReview", "Manager review", "Managers", "ManagerReviewSLA"),
        {"pyShapeID": "VendorCheck", "pyShapeType": "Decision", "pyShapeName": "Vendor check needed?", "pyDecisionType": "Fork"},
        assignment("FinanceReview", "Finance review", "Finance"),
        {"pyShapeID": "Approved", "pyShapeType": "End", "pyShapeName": "Approved"},
        {"pyShapeID": "Rejected", "pyShapeType": "End", "pyShapeName": "Rejected", "pyStatusWork": "Resolved-Rejected"},
    ])
    approve_connectors = PageList([
        connector("c1", "Start1", "Level", "Always"),
        connector("toManager", "Level", "ManagerReview", "Result", pyResultValue="Standard"),
        connector("toFinance", "Level", "FinanceReview", "Else"),
        connector("managerApprove", "ManagerReview", "VendorCheck", "FlowAction", pyFlowActionName="Approve"),
        connector("managerReject", "ManagerReview", "Rejected", "FlowAction", pyFlowActionName="Reject"),
        connector("needsCheck", "VendorCheck", "FinanceReview", "When", pyWhenName="NeedsVendorCheck"),
        connector("noCheck", "VendorCheck", "Approved", "Else"),
        connector("financeApprove", "FinanceReview", "Approved", "FlowAction", pyFlowActionName="Approve"),
        connector("financeReject", "FinanceReview", "Rejected", "FlowAction", pyFlowActionName="Reject"),
    ])
    e["flows/ApproveRequest.xml"] = page("Rule-Obj-Flow", WORK, "ApproveRequest", "Purchasing", "01-01-05",
        stamp="20261001T110500.000", body={"pyShapes": approve_shapes, "pyConnectors": approve_connectors})
    e["flows/ApproveRequest-01-01-01.xml"] = page("Rule-Obj-Flow", WORK, "ApproveRequest", "Purchasing", "01-01-01",
        stamp="20261001T110100.000", body={
            "pyShapes": PageList([{"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
                                  {"pyShapeID": "Done", "pyShapeType": "End", "pyShapeName": "Done"}]),
            "pyConnectors": PageList([connector("c1", "Start1", "Done", "Always")])})
    e["flows/ApproveRequest-sandbox.xml"] = page("Rule-Obj-Flow", REQUEST, "ApproveRequest", "PurchasingSandbox", "01-01-01",
        stamp="20261001T110000.000", body={
            "pyShapes": PageList([{"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
                                  {"pyShapeID": "Done", "pyShapeType": "End", "pyShapeName": "Done"}]),
            "pyConnectors": PageList([connector("c1", "Start1", "Done", "Always")])})
    e["flows/Fulfil.xml"] = page("Rule-Obj-Flow", WORK, "Fulfil", "Purchasing", "01-01-05", body={
        "pyShapes": PageList([{"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
                              assignment("ConfirmOrder", "Confirm order", "Procurement", "OrderSLA"),
                              {"pyShapeID": "Ordered", "pyShapeType": "End", "pyShapeName": "Ordered"},
                              {"pyShapeID": "Cancelled", "pyShapeType": "End", "pyShapeName": "Order cancelled",
                               "pyStatusWork": "Resolved-Rejected"}]),
        "pyConnectors": PageList([connector("f1", "Start1", "ConfirmOrder", "Always"),
                                  connector("confirm", "ConfirmOrder", "Ordered", "FlowAction", pyFlowActionName="Approve"),
                                  connector("cancel", "ConfirmOrder", "Cancelled", "FlowAction", pyFlowActionName="Reject")])})
    e["flowactions/Approve.xml"] = flow_action("Approve", "ReviewDecision")
    e["flowactions/Reject.xml"] = flow_action("Reject", "ReviewDecision")
    # Service levels: deadlines become approval due times and the stage limit.
    e["sla/ManagerReviewSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "ManagerReviewSLA", "Purchasing", "01-01-01", "Final",
        body={"pyGoalHours": "4", "pyDeadlineDays": "1", "pyUseBusinessDays": "false"})
    e["sla/OrderSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "OrderSLA", "Purchasing", "01-01-01",
        body={"pyDeadlineHours": "2", "pyDeadlineMinutes": "30", "pyUseBusinessDays": "false"})
    e["sla/ReviewStageSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "ReviewStageSLA", "Purchasing", "01-01-01",
        body={"pyDeadlineDays": "3", "pyUseBusinessDays": "false"})
    # Sections and harness. RequestDetails 01-01-04 in the request class is withdrawn and masks 01-01-02, so the
    # work-class version is used.
    request_cells = PageList([
        {"pyProperty": ".Description", "pyControl": "pxTextArea", "pyLabel": "What do you need?", "pyRequired": "true"},
        {"pyProperty": ".Amount", "pyControl": "pxCurrency", "pyLabel": "Amount", "pyRequired": "true"},
        {"pyProperty": ".Category", "pyControl": "pxDropdown", "pyLabel": "Category", "pyRequired": "true"},
        {"pyProperty": ".NeededBy", "pyControl": "pxDateTime", "pyLabel": "Needed by", "pyRequired": "false"},
    ])
    e["sections/RequestDetails-Work.xml"] = page("Rule-HTML-Section", WORK, "RequestDetails", "Purchasing", "01-01-03",
        body={"pyTitle": "Request", "pyColumns": "2", "pyCells": request_cells})
    for version, available in (("01-01-02", "Yes"), ("01-01-04", "Withdrawn")):
        e[f"sections/RequestDetails-Request-{version}.xml"] = page(
            "Rule-HTML-Section", REQUEST, "RequestDetails", "Purchasing", version, available,
            stamp=f"20261001T12{version[-2:]}00.000",
            body={"pyTitle": "Old request", "pyColumns": "1", "pyCells": PageList([
                {"pyProperty": ".Description", "pyControl": "pxTextInput", "pyLabel": "Description", "pyRequired": "true"}])})
    e["sections/VendorDetails.xml"] = page("Rule-HTML-Section", WORK, "VendorDetails", "Purchasing", "01-01-03",
        body={"pyTitle": "Vendor", "pyColumns": "1", "pyCells": PageList([
            {"pyProperty": ".Vendor.Name", "pyControl": "pxTextInput", "pyLabel": "Vendor name", "pyRequired": "true"},
            {"pyProperty": ".Vendor.Preferred", "pyControl": "pxCheckbox", "pyLabel": "Preferred vendor", "pyRequired": "false"}])})
    e["harness/New.xml"] = page("Rule-HTML-Harness", WORK, "New", "Purchasing", "01-01-03",
                                body={"pySections": PropertyList(["RequestDetails", "VendorDetails"])})
    # pyDefault sets a literal: reported for review because request forms have no default values.
    e["transforms/pyDefault.xml"] = page("Rule-Obj-Model", WORK, "pyDefault", "Purchasing", "01-01-01",
        body={"pySteps": PageList([{"pyAction": "Set", "pyTarget": ".Category", "pySource": '"Hardware"'}])})
    e["casetypes/PurchaseRequest.xml"] = page("Rule-Obj-CaseType", REQUEST, "PurchaseRequest", "Purchasing", "01-01-05",
        body={"pyNewHarness": "New", "pyStages": PageList([
            {"pyStageID": "review", "pyStageName": "Review", "pyServiceLevelName": "ReviewStageSLA",
             "pyProcesses": PageList([{"pyFlowName": "ApproveRequest"}])},
            {"pyStageID": "fulfilment", "pyStageName": "Fulfilment",
             "pyProcesses": PageList([{"pyFlowName": "Fulfil"}])}])})
    # Not referenced by the case type: inventoried and reported, never executed.
    e["activities/SendNotice.xml"] = page("Rule-Obj-Activity", WORK, "SendNotice", "Purchasing", "01-01-01",
                                          body={"pySteps": PageList([{"pyMethod": "Java"}])})
    e["data/Managers.xml"] = data_page("Data-Admin-WorkBasket", "Managers", {"pyWorkBasket": "Managers",
                                                                              "pyDescription": "Managers queue"})
    return e


def refusals() -> dict[str, bytes]:
    """Rule-level refusals: each referenced construct must become a source-linked blocker."""
    base = purchase_request()
    e = {k: v for k, v in base.items() if not k.startswith(("flows/", "casetypes/"))}
    e["flows/Escalate.xml"] = page("Rule-Obj-Flow", WORK, "Escalate", "Purchasing", "01-01-05", body={
        "pyIsStartingFlow": "true",
        "pyShapes": PageList([
            {"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
            {"pyShapeID": "Notify", "pyShapeType": "Utility", "pyShapeName": "Send notice", "pyActivityName": "SendNotice"},
            {"pyShapeID": "Lookup", "pyShapeType": "Integrator", "pyShapeName": "Credit check", "pyConnectorName": "CreditCheck"},
            assignment("Review", "Review", "Managers", "BusinessDaySLA"),
            {"pyShapeID": "Route", "pyShapeType": "Decision", "pyShapeName": "Tier", "pyDecisionType": "DecisionTable",
             "pyDecisionName": "Tier"},
            {"pyShapeID": "Done", "pyShapeType": "End", "pyShapeName": "Done"},
            {"pyShapeID": "Stop", "pyShapeType": "End", "pyShapeName": "Stopped", "pyStatusWork": "Resolved-Rejected"},
        ]),
        "pyConnectors": PageList([
            connector("e1", "Start1", "Notify", "Always"),
            connector("e2", "Notify", "Lookup", "Always"),
            connector("e3", "Lookup", "Review", "Always"),
            connector("e4", "Review", "Route", "FlowAction", pyFlowActionName="Approve"),
            connector("e5", "Review", "Stop", "FlowAction", pyFlowActionName="Escalate"),
            connector("e6", "Route", "Done", "Result", pyResultValue="Gold"),
            connector("e7", "Route", "Stop", "Else"),
        ])})
    e["connectors/CreditCheck.xml"] = page("Rule-Connect-REST", WORK, "CreditCheck", "Purchasing", "01-01-01",
                                           body={"pyEndpointURL": "https://example.invalid/credit"})
    e["sla/BusinessDaySLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "BusinessDaySLA", "Purchasing", "01-01-01",
        body={"pyDeadlineDays": "2", "pyUseBusinessDays": "true"})
    # Tier is blocked in the higher ruleset: resolution stops and no rule is found.
    e["decisions/Tier-org.xml"] = page("Rule-Declare-DecisionTable", WORK, "Tier", "PurchasingOrg", "01-02-01", "Blocked",
        stamp="20261003T090000.000", body={"pyEvaluateAllRows": "false", "pyColumns": PageList([{"pyProperty": ".Amount", "pyColumnDataType": "Decimal"}]),
              "pyRows": PageList([{"pyCondition": "<100", "pyResult": "Gold"}]), "pyOtherwise": "Silver"})
    e["decisions/Tier.xml"] = page("Rule-Declare-DecisionTable", WORK, "Tier", "Purchasing", "01-01-01",
        body={"pyEvaluateAllRows": "false", "pyColumns": PageList([{"pyProperty": ".Amount", "pyColumnDataType": "Decimal"}]),
              "pyRows": PageList([{"pyCondition": "<100", "pyResult": "Gold"}]), "pyOtherwise": "Silver"})
    # A circumstanced Approve flow action and an implicit declarative rule on the work class.
    e["flowactions/Approve-circumstanced.xml"] = page("Rule-Obj-FlowAction", WORK, "Approve", "Purchasing", "01-01-01",
        stamp="20261002T090000.000", circumstance=(".Category", "Services"), body={"pySection": "ReviewDecision"})
    e["declare/TotalExpression.xml"] = page("Rule-Declare-Expressions", WORK, "Total", "Purchasing", "01-01-01",
                                            body={"pyExpression": ".Amount * 1.2"})
    e["binary/legacy.bin"] = b"\x00\x01SYNTHETIC binary entry that the decoder cannot read\x00"
    return e


def table(name: str, version: str, threshold: str, stamp: str, available: str = "Yes", **qualifiers) -> bytes:
    return page("Rule-Declare-DecisionTable", WORK, name, "Purchasing", version, available, stamp=stamp, extra=qualifiers.get("extra"),
                circumstance=qualifiers.get("circumstance"), body={
                    "pyEvaluateAllRows": "false", "pyColumns": PageList([{"pyProperty": ".Amount", "pyColumnDataType": "Decimal"}]),
                    "pyRows": PageList([{"pyCondition": f"<{threshold}", "pyResult": "Standard"}]), "pyOtherwise": "Finance"})


def child_flow(name: str) -> bytes:
    return page("Rule-Obj-Flow", WORK, name, "Purchasing", "01-01-01", body={
        "pyIsStartingFlow": "true",
        "pyShapes": PageList([{"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
                              {"pyShapeID": "Done", "pyShapeType": "End", "pyShapeName": "Done", "pyStatusWork": "Resolved-Completed"}]),
        "pyConnectors": PageList([connector("c1", "Start1", "Done", "Always")])})


def purchase_advanced() -> dict[str, bytes]:
    """S115: circumstances, a date range, delegation, subflows, a split-join, a business calendar with escalation,
    a single-stage case with a case-level service level and an explicit currency."""
    base = purchase_request()
    keep = ("META-INF/", "application/", "classes/", "properties/", "flowactions/", "harness/", "transforms/", "data/")
    e = {k: v for k, v in base.items() if k.startswith(keep) and k != "properties/InternalNote.xml"}
    e["sections/RequestDetails.xml"] = page("Rule-HTML-Section", WORK, "RequestDetails", "Purchasing", "01-01-03",
        body={"pyTitle": "Request", "pyColumns": "2", "pyCells": PageList([
            {"pyProperty": ".Description", "pyControl": "pxTextArea", "pyLabel": "What do you need?", "pyRequired": "true"},
            {"pyProperty": ".Amount", "pyControl": "pxCurrency", "pyLabel": "Amount", "pyRequired": "true", "pyCurrency": "USD"},
            {"pyProperty": ".Category", "pyControl": "pxDropdown", "pyLabel": "Category", "pyRequired": "true"}])})
    e["sections/VendorDetails.xml"] = base["sections/VendorDetails.xml"]
    # ApprovalLevel: a base rule and a property-circumstanced variant for Services (lower threshold).
    e["decisions/ApprovalLevel.xml"] = table("ApprovalLevel", "01-01-01", "5000", "20261001T100100.000")
    e["decisions/ApprovalLevel-Services.xml"] = table("ApprovalLevel", "01-01-01", "2000", "20261001T100200.000",
                                                      circumstance=(".Category", "Services"))
    # NeedsVendorCheck: a base when rule and a Software variant.
    e["when/NeedsVendorCheck.xml"] = page("Rule-Obj-When", REQUEST, "NeedsVendorCheck", "Purchasing", "01-01-03", body={
        "pyConditions": PageList([{"pyLabel": "A", "pyLeft": ".Vendor.Preferred", "pyOperator": "=", "pyRight": "false"},
                                  {"pyLabel": "B", "pyLeft": ".Amount", "pyOperator": ">=", "pyRight": "1000"}]),
        "pyLogicString": "A AND B"})
    e["when/NeedsVendorCheck-Software.xml"] = page("Rule-Obj-When", REQUEST, "NeedsVendorCheck", "Purchasing", "01-01-03",
        stamp="20261001T090500.000", circumstance=(".Category", "Software"), body={
            "pyConditions": PageList([{"pyLabel": "A", "pyLeft": ".Amount", "pyOperator": ">=", "pyRight": "500"}]),
            "pyLogicString": "A"})
    # OrderTier: a decision tree and a variant time-qualified to 2020 (expired today).
    tree = lambda limit: {"pyBranches": PageList([{"pyProperty": ".Amount", "pyOperator": "<", "pyValue": limit, "pyResult": "Express"}]),
                          "pyOtherwise": "Standard"}
    e["decisions/OrderTier.xml"] = page("Rule-Declare-DecisionTree", WORK, "OrderTier", "Purchasing", "01-01-01", body=tree("100"))
    e["decisions/OrderTier-2020.xml"] = page("Rule-Declare-DecisionTree", WORK, "OrderTier", "Purchasing", "01-01-01",
        stamp="20261001T090600.000", extra={"pyStartDateTime": "20200101T000000.000 GMT", "pyEndDateTime": "20201231T000000.000 GMT"},
        body=tree("500"))
    e["sla/BusinessSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "BusinessSLA", "Purchasing", "01-01-01", body={
        "pyGoalHours": "4", "pyDeadlineDays": "2", "pyUseBusinessDays": "true", "pyBusinessCalendar": "ACMEBusiness",
        "pyDeadlineEscalation": "NotifyAssignee", "pyPassedDeadlineHours": "4", "pyPassedDeadlineLimit": "3"})
    e["sla/OrderSLA.xml"] = base["sla/OrderSLA.xml"]
    e["sla/CaseSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "CaseSLA", "Purchasing", "01-01-01",
                                body={"pyDeadlineDays": "5", "pyUseBusinessDays": "false"})
    e["data/ACMEBusiness.xml"] = data_page("Data-Admin-Calendar", "ACMEBusiness", {
        "pyCalendarName": "ACMEBusiness", "pyTimeZone": "America/New_York",
        "pyWorkDays": PropertyList(["Mon", "Tue", "Wed", "Thu", "Fri"]), "pyStartTime": "09:00", "pyEndTime": "17:00",
        "pyHolidays": PropertyList(["2026-12-25", "2027-01-01"])})
    e["delegation/ApprovalLevel.xml"] = data_page("System-User-MyRules", "Purchasing:Managers ApprovalLevel", {
        "pyRuleInsKey": "RULE-DECLARE-DECISIONTABLE ACME-PURCHASING-WORK APPROVALLEVEL #20261001T100100.000 GMT",
        "pyAccessGroup": "Purchasing:Managers", "pyLabel": "Approval thresholds"})
    review_shapes = PageList([
        {"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
        {"pyShapeID": "Level", "pyShapeType": "Decision", "pyShapeName": "Approval level", "pyDecisionType": "DecisionTable",
         "pyDecisionName": "ApprovalLevel"},
        assignment("ManagerReview", "Manager review", "Managers", "BusinessSLA"),
        {"pyShapeID": "VendorCheck", "pyShapeType": "Decision", "pyShapeName": "Vendor check needed?", "pyDecisionType": "Fork"},
        {"pyShapeID": "Checks", "pyShapeType": "Split-Join", "pyShapeName": "Budget and vendor checks",
         "pySplitFlows": PropertyList(["BudgetCheck", "VendorScreen"]), "pyJoinCondition": "All"},
        assignment("FinanceReview", "Finance review", "Finance"),
        {"pyShapeID": "Approved", "pyShapeType": "End", "pyShapeName": "Approved"},
        {"pyShapeID": "Rejected", "pyShapeType": "End", "pyShapeName": "Rejected", "pyStatusWork": "Resolved-Rejected"},
    ])
    review_connectors = PageList([
        connector("r1", "Start1", "Level", "Always"),
        connector("toManager", "Level", "ManagerReview", "Result", pyResultValue="Standard"),
        connector("toChecks", "Level", "Checks", "Else"),
        connector("managerApprove", "ManagerReview", "VendorCheck", "FlowAction", pyFlowActionName="Approve"),
        connector("managerReject", "ManagerReview", "Rejected", "FlowAction", pyFlowActionName="Reject"),
        connector("needsCheck", "VendorCheck", "Checks", "When", pyWhenName="NeedsVendorCheck"),
        connector("noCheck", "VendorCheck", "Approved", "Else"),
        connector("checksDone", "Checks", "FinanceReview", "Always"),
        connector("financeApprove", "FinanceReview", "Approved", "FlowAction", pyFlowActionName="Approve"),
        connector("financeReject", "FinanceReview", "Rejected", "FlowAction", pyFlowActionName="Reject"),
    ])
    e["flows/Review.xml"] = page("Rule-Obj-Flow", WORK, "Review", "Purchasing", "01-01-05",
                                 body={"pyShapes": review_shapes, "pyConnectors": review_connectors})
    e["flows/Fulfil.xml"] = page("Rule-Obj-Flow", WORK, "Fulfil", "Purchasing", "01-01-05", body={
        "pyShapes": PageList([{"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"},
                              {"pyShapeID": "Tier", "pyShapeType": "Decision", "pyShapeName": "Order tier",
                               "pyDecisionType": "DecisionTree", "pyDecisionName": "OrderTier"},
                              assignment("ConfirmOrder", "Confirm order", "Procurement", "OrderSLA"),
                              {"pyShapeID": "Record", "pyShapeType": "Subprocess", "pyShapeName": "Record the order",
                               "pyFlowName": "RecordOrder"},
                              {"pyShapeID": "Ordered", "pyShapeType": "End", "pyShapeName": "Ordered"},
                              {"pyShapeID": "Cancelled", "pyShapeType": "End", "pyShapeName": "Order cancelled",
                               "pyStatusWork": "Resolved-Rejected"}]),
        "pyConnectors": PageList([connector("f1", "Start1", "Tier", "Always"),
                                  connector("express", "Tier", "Record", "Result", pyResultValue="Express"),
                                  connector("standard", "Tier", "ConfirmOrder", "Else"),
                                  connector("confirm", "ConfirmOrder", "Record", "FlowAction", pyFlowActionName="Approve"),
                                  connector("cancel", "ConfirmOrder", "Cancelled", "FlowAction", pyFlowActionName="Reject"),
                                  connector("recorded", "Record", "Ordered", "Always")])})
    for name in ("BudgetCheck", "VendorScreen", "RecordOrder"):
        e[f"flows/{name}.xml"] = child_flow(name)
    e["casetypes/PurchaseOrder.xml"] = page("Rule-Obj-CaseType", REQUEST, "PurchaseOrder", "Purchasing", "01-01-05",
        body={"pyNewHarness": "New", "pyStages": PageList([
            {"pyStageID": "review", "pyStageName": "Review", "pyProcesses": PageList([{"pyFlowName": "Review"}])},
            {"pyStageID": "fulfilment", "pyStageName": "Fulfilment", "pyProcesses": PageList([{"pyFlowName": "Fulfil"}])}])})
    e["casetypes/QuickOrder.xml"] = page("Rule-Obj-CaseType", REQUEST, "QuickOrder", "Purchasing", "01-01-05",
        body={"pyNewHarness": "New", "pyServiceLevelName": "CaseSLA", "pyStages": PageList([
            {"pyStageID": "fulfilment", "pyStageName": "Fulfilment", "pyProcesses": PageList([{"pyFlowName": "Fulfil"}])}])})
    return e


def simple_flow(name: str, shapes: list, connectors: list, starting: bool = False) -> bytes:
    body = {"pyShapes": PageList(shapes), "pyConnectors": PageList(connectors)}
    if starting:
        body = {"pyIsStartingFlow": "true", **body}
    return page("Rule-Obj-Flow", WORK, name, "Purchasing", "01-01-01", body=body, stamp="20261001T130000.000")


def purchase_connected() -> dict[str, bytes]:
    """S117: a REST connector with a request data transform and response mappings, a validate rule, a read-only data
    page, and a case type with an optional process, an alternate stage, a stage service level and a case service level,
    which converts to a CMMN case definition whose workflow tasks pin the converted process flows."""
    base = purchase_request()
    keep = ("META-INF/", "application/", "classes/", "flowactions/", "data/")
    e = {k: v for k, v in base.items() if k.startswith(keep)}
    e["properties/Amount.xml"] = prop(WORK, "Amount", "Decimal", pyLabel="Amount (USD)")
    e["properties/Title.xml"] = prop(WORK, "Title", "Text", pyMaxLength="120", pyLabel="What are you buying?")
    e["properties/CostCenter.xml"] = prop(WORK, "CostCenter", "Text", pyTableType="Local List",
                                          pyLocalList=PropertyList(["OPS", "IT", "HR"]), pyLabel="Cost center")
    e["properties/OrderId.xml"] = prop(WORK, "OrderId", "Text", pyMaxLength="64")
    e["validate/RequestChecks.xml"] = page("Rule-Obj-Validate", WORK, "RequestChecks", "Purchasing", "01-01-01", body={
        "pyConditions": PageList([
            {"pyProperty": ".Title", "pyCheck": "Required"},
            {"pyProperty": ".Amount", "pyCheck": "Required"},
            {"pyProperty": ".Amount", "pyCheck": "Minimum", "pyValue": "1"},
            {"pyProperty": ".Amount", "pyCheck": "Maximum", "pyValue": "1000000"},
            {"pyProperty": ".Title", "pyCheck": "MaxLength", "pyValue": "120"}])})
    e["connectors/CreateOrder.xml"] = page("Rule-Connect-REST", WORK, "CreateOrder", "Purchasing", "01-01-01", body={
        "pyMethod": "POST", "pyEndpointURL": "https://procurement.example.invalid/orders", "pyTimeoutSeconds": "10",
        "pyRequestDataTransform": "OrderRequest",
        "pyResponseMappings": PageList([{"pyTarget": ".OrderId", "pyJsonPointer": "/id"}])})
    e["transforms/OrderRequest.xml"] = page("Rule-Obj-Model", WORK, "OrderRequest", "Purchasing", "01-01-01", body={
        "pySteps": PageList([{"pyAction": "Set", "pyTarget": ".request.title", "pySource": ".Title"},
                             {"pyAction": "Set", "pyTarget": ".request.amount", "pySource": ".Amount"},
                             {"pyAction": "Set", "pyTarget": ".request.costCenter", "pySource": ".CostCenter"}])})
    e["datapages/D_OrderPlacement.xml"] = page("Rule-Declare-Pages", WORK, "D_OrderPlacement", "Purchasing", "01-01-01", body={
        "pyDataPageScope": "Thread", "pyIsReadOnly": "true", "pySourceType": "Connector", "pySourceName": "CreateOrder"})
    start = {"pyShapeID": "Start1", "pyShapeType": "Start", "pyShapeName": "Start"}
    e["flows/Order.xml"] = simple_flow("Order", [
        start, assignment("Review", "Review the order", "Managers"),
        {"pyShapeID": "PlaceOrder", "pyShapeType": "Integrator", "pyShapeName": "Place the order", "pyConnectorName": "CreateOrder"},
        {"pyShapeID": "Placed", "pyShapeType": "End", "pyShapeName": "Order placed"},
        {"pyShapeID": "Declined", "pyShapeType": "End", "pyShapeName": "Declined", "pyStatusWork": "Resolved-Rejected"}], [
        connector("o1", "Start1", "Review", "Always"),
        connector("orderApprove", "Review", "PlaceOrder", "FlowAction", pyFlowActionName="Approve"),
        connector("orderReject", "Review", "Declined", "FlowAction", pyFlowActionName="Reject"),
        connector("o2", "PlaceOrder", "Placed", "Always")])
    e["flows/Escalate.xml"] = simple_flow("Escalate", [
        start, assignment("FinanceCheck", "Finance check", "Finance"),
        {"pyShapeID": "Checked", "pyShapeType": "End", "pyShapeName": "Checked"},
        {"pyShapeID": "Refused", "pyShapeType": "End", "pyShapeName": "Refused", "pyStatusWork": "Resolved-Rejected"}], [
        connector("x1", "Start1", "FinanceCheck", "Always"),
        connector("financeOk", "FinanceCheck", "Checked", "FlowAction", pyFlowActionName="Approve"),
        connector("financeNo", "FinanceCheck", "Refused", "FlowAction", pyFlowActionName="Reject")])
    e["flows/Close.xml"] = simple_flow("Close", [
        start, assignment("WrapUp", "Wrap up", "Managers"),
        {"pyShapeID": "Closed", "pyShapeType": "End", "pyShapeName": "Closed"},
        {"pyShapeID": "Reopen", "pyShapeType": "End", "pyShapeName": "Reopened", "pyStatusWork": "Resolved-Rejected"}], [
        connector("w1", "Start1", "WrapUp", "Always"),
        connector("wrapOk", "WrapUp", "Closed", "FlowAction", pyFlowActionName="Approve"),
        connector("wrapNo", "WrapUp", "Reopen", "FlowAction", pyFlowActionName="Reject")])
    e["flows/Withdraw.xml"] = simple_flow("Withdraw", [
        start, {"pyShapeID": "Withdrawn", "pyShapeType": "End", "pyShapeName": "Withdrawn", "pyStatusWork": "Resolved-Rejected"}], [
        connector("d1", "Start1", "Withdrawn", "Always")])
    e["sla/CaseSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "CaseSLA", "Purchasing", "01-01-01",
                                body={"pyDeadlineDays": "5", "pyUseBusinessDays": "false"})
    e["sla/IntakeSLA.xml"] = page("Rule-Obj-ServiceLevel", WORK, "IntakeSLA", "Purchasing", "01-01-01",
                                  body={"pyGoalHours": "8", "pyDeadlineDays": "2", "pyUseBusinessDays": "false"})
    e["sections/Request.xml"] = page("Rule-HTML-Section", WORK, "Request", "Purchasing", "01-01-01", body={
        "pyTitle": "Order", "pyColumns": "1", "pyCells": PageList([
            {"pyProperty": ".Title", "pyControl": "pxTextInput", "pyLabel": "What are you buying?", "pyRequired": "true"},
            {"pyProperty": ".Amount", "pyControl": "pxCurrency", "pyLabel": "Amount", "pyRequired": "true", "pyCurrency": "USD"},
            {"pyProperty": ".CostCenter", "pyControl": "pxDropdown", "pyLabel": "Cost center", "pyRequired": "true"}])})
    e["harness/New.xml"] = page("Rule-HTML-Harness", WORK, "New", "Purchasing", "01-01-01", body={"pySections": PropertyList(["Request"])})
    e["casetypes/ConnectedPurchase.xml"] = page("Rule-Obj-CaseType", REQUEST, "ConnectedPurchase", "Purchasing", "01-01-05", body={
        "pyLabel": "Connected purchase", "pyNewHarness": "New", "pyCreateValidate": "RequestChecks", "pyServiceLevelName": "CaseSLA",
        "pyStages": PageList([
            {"pyStageID": "intake", "pyStageName": "Intake", "pyServiceLevelName": "IntakeSLA", "pyProcesses": PageList([
                {"pyFlowName": "Order"}, {"pyFlowName": "Escalate", "pyOptional": "true"}])},
            {"pyStageID": "close", "pyStageName": "Close", "pyProcesses": PageList([{"pyFlowName": "Close"}])}]),
        "pyAlternateStages": PageList([
            {"pyStageID": "withdrawn", "pyStageName": "Withdrawn", "pyProcesses": PageList([{"pyFlowName": "Withdraw"}])}])})
    return e


FIXTURES = {"purchase-request": purchase_request, "refusals": refusals, "purchase-advanced": purchase_advanced,
            "purchase-connected": purchase_connected}


def generated() -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for name, build in FIXTURES.items():
        data = archive(build())
        out[f"{name}.zip"] = data
        analysis = pega_native.analyze(data)
        out[f"expected/{name}.json"] = (json.dumps(pega_native.expectation(analysis), indent=2, sort_keys=True) + "\n").encode("utf-8")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail when committed files differ from generated ones")
    args = parser.parse_args()
    outputs = generated()
    if args.check:
        stale = [name for name, data in outputs.items()
                 if not (ROOT / name).exists() or (ROOT / name).read_bytes() != data]
        if stale:
            print("Stale or missing fixtures: " + ", ".join(stale), file=sys.stderr)
            return 1
        print(f"{len(outputs)} fixture files match.")
        return 0
    for name, data in outputs.items():
        (ROOT / name).parent.mkdir(parents=True, exist_ok=True)
        (ROOT / name).write_bytes(data)
    print(f"Wrote {len(outputs)} fixture files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
