# ServiceNow ITSM connector package

Package `com.orqix.servicenow-itsm` 1.0.0 (example publisher `orqix-example-2026`). It lets a workflow raise incidents
and catalog requests in ServiceNow and wait for their outcome. ServiceNow facts here follow the default data model and
are marked as such in Orqix's plan (doc 29 section 6.14): check field names, states and grant types on your release
before production use. Nothing in this package contains a base URL, a credential or code.

## What it contains

| Part | Content |
|---|---|
| `servicenow-itsm` connection | `createIncident` (idempotent by lookup on `correlation_id`), `getIncident`, `findIncidentByCorrelation`, `updateIncidentFields` (repeat-safe field values), `addWorkNote` (appends, so never repeated automatically), `attachFile` (a file of the request; appends, so never repeated automatically), `lookupUser`, `listChangedIncidents` |
| `servicenow-catalog` connection | `orderCatalogItem` (idempotent by lookup on the request's `correlation_id`), `findRequestByReference`, `getRequestItem` |
| Triggers | `incident-updated` (webhook from a business rule, HMAC-signed) and `incident-changes` (polling, for instances where the rule cannot be installed) |
| Step templates | Raise an incident; Wait until the incident is resolved (check until done) |
| Starter workflow | IT access request |
| Fixtures | Recorded answers replayed on import and usable as test mocks |

Default incident states: 1 New, 2 In Progress, 3 On Hold, 6 Resolved, 7 Closed, 8 Canceled.

## Orqix side

1. **Import and approve.** An integration administrator (`connector.manage`) imports `servicenow-itsm-1.0.0.orqpkg`
   and reviews hosts, operations by effect class, data leaving Orqix and triggers. A different administrator holding
   `connector.approve` approves the exact hash. Then install it in the workspace.
2. **Bind.** Bind `servicenow-itsm` (and `servicenow-catalog` if you order catalog items) to
   `https://<instance>.service-now.com` with an OAuth client registered in ServiceNow (client credentials where your
   instance enables that grant, or authorization code for delegated per-user actions) or a bearer token. Add
   `<instance>.service-now.com:443` to the egress allow-list. Use **Test connection** on `getIncident` or `lookupUser`.
3. **Webhook trigger.** Set up `incident-updated` with a mounted secret (for example `servicenow-incident-hook`), a
   long random value. Orqix shows the trigger's address; the business rule below posts to it.
4. **Polling trigger (alternative).** Enable `incident-changes` instead when you cannot install a business rule. It
   reads every 2 minutes with a one-minute overlap and drops repeats by `sys_id` and `sys_mod_count`.

## ServiceNow side: business rule

Create a business rule on **Incident** [Knowledge, default ServiceNow scripting API]:

- When: **after**, **update**; condition: *State changes* and *State is one of Resolved, Closed, Canceled*, and
  *Correlation ID starts with* `ORQ-`.
- Store the Orqix trigger secret in a system property (for example `x_orqix.incident_hook_secret`, type password2) and
  the trigger address in another (`x_orqix.incident_hook_url`).

```javascript
(function executeRule(current, previous) {
    var secret = gs.getProperty('x_orqix.incident_hook_secret');
    var url = gs.getProperty('x_orqix.incident_hook_url');
    var body = JSON.stringify({
        correlation_id: current.getValue('correlation_id'),
        sys_id: current.getUniqueValue(),
        number: current.getValue('number'),
        state: current.getValue('state'),
        close_code: current.getValue('close_code') || '',
        sys_mod_count: current.getValue('sys_mod_count')
    });
    var timestamp = '' + Math.floor(new GlideDateTime().getNumericValue() / 1000);
    // HMAC-SHA256 over "<timestamp>.<body>", base64; GlideCertificateEncryption takes the key in base64.
    var mac = new GlideCertificateEncryption().generateMac(GlideStringUtil.base64Encode(secret), 'HmacSHA256',
        timestamp + '.' + body);
    var request = new sn_ws.RESTMessageV2();
    request.setEndpoint(url);
    request.setHttpMethod('post');
    request.setRequestHeader('Content-Type', 'application/json');
    request.setRequestHeader('X-ServiceNow-Timestamp', timestamp);
    request.setRequestHeader('X-ServiceNow-Signature', mac);
    request.setRequestBody(body);
    request.executeAsync();
})(current, previous);
```

Orqix verifies the signature and the timestamp (5 minutes), takes the outcome fields only, and resumes the one request
whose wait carries that `correlation_id`. A repeated post is acknowledged as a duplicate. Where your organization
licenses IntegrationHub, a Flow Designer flow with the same payload and headers works too.

If the post is lost, the starter workflow's safety-net check reads the incident every 2 hours, so the request still
continues; normal delay is seconds.

## ServiceNow side: catalog orders

`orderCatalogItem` sends Orqix's reference in the catalog variable `orqix_reference`. Add a single-line text variable
with that name to the items you order from Orqix, and a business rule on **Request [sc_request]**, *before insert*:

```javascript
(function executeRule(current, previous) {
    var item = new GlideRecord('sc_req_item');
    item.addQuery('request', current.getUniqueValue());
    item.setLimit(1);
    item.query();
    if (item.next() && item.variables.orqix_reference) {
        current.correlation_id = item.variables.orqix_reference;
    }
})(current, previous);
```

(Depending on your release the requested items are created after the request; then run the same copy *after insert*
on `sc_req_item`, updating the parent request.) Without it, an order whose answer is lost needs an operator to
reconcile it.

## How failures are handled

| Failure | What happens |
|---|---|
| `createIncident` times out after sending | The lookup by `correlation_id` finds the incident: recorded as success, nothing is sent again |
| Token expired (401) | Refresh and retry once |
| 429 | The next attempt waits for `Retry-After`; repeated failures open the binding's circuit |
| Incident Canceled (8) | The confirm step takes its failed path: "IT cancelled your request" |
| Webhook lost | The safety-net check finds the outcome within 2 hours |
| No answer in 5 days | The timed-out path tells the requester; the service desk lead follows up |
| Instance down | Checks fail and are retried; after 5 in a row the request needs attention with a retry action |
