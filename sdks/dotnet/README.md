# Orqix SDK for .NET

`Orqix.Client` 0.1.0 for .NET 8: typed client for the machine API, the external-worker loop and the callback-token
helper. No package dependencies (`HttpClient`, `System.Text.Json`). Not yet published to a feed; reference the project
or `dotnet pack` it.

## Client

```csharp
var orqix = new OrqixClient(new OrqixClientOptions
{
    BaseUrl = "https://orqix.example/api/v1",
    WorkspaceId = workspaceId,
    Token = ct => tokens.GetAccessTokenAsync(ct),   // called for every request
});

var run = await orqix.StartRunAsync($"order-{orderId}", new StartRunRequest
{
    WorkflowId = workflowId, Input = new() { ["amount"] = 1200 },
});
var detail = await orqix.GetRunAsync(run.Id);
await orqix.SendMessageAsync($"payment-{orderId}", "payment-received", orderId, new Dictionary<string, object?> { ["paid"] = true });
```

Errors are `OrqixApiException` with `Status`, `Code`, `CorrelationId`, `RetryAfterSeconds`, `IsTransient` and
`IsAuthenticationFailure`. Models in `Orqix.Client.Models` are generated from the contract; enum fields are strings with
constants (`Lifecycle.Waiting`), and unknown fields are ignored. Deprecated operations are reported once through
`OnDeprecation`.

## Worker

```csharp
var worker = new OrqixWorker(orqix, new OrqixWorkerOptions { WorkerId = "address-checker-1" });
await worker.RunAsync(new Dictionary<string, JobHandler>
{
    ["address-check"] = async (job, ct) =>
    {
        if (!await directory.KnowsAsync(job.GetInput<string>("postcode"), ct))
            throw new BusinessErrorException("ADDRESS_UNKNOWN", "No such postcode");
        return new Dictionary<string, object?> { ["valid"] = true, ["score"] = 0.9 };
    },
}, stoppingToken);
```

`RunAsync` ends when `stoppingToken` is cancelled or `Stop()` is called, after the job in progress. In a generic host,
call it from a `BackgroundService.ExecuteAsync`. Throw `JobFailedException(message, retryable, retryAfterSeconds)` for a
technical failure. For asynchronous gateways use `FetchAndLockAsync`, `AwaitCallbackAsync`, `CompleteCallbackAsync` and
`FailCallbackAsync` and persist the `CallbackTicket`.

## Callback tokens

```csharp
var issued = await orqix.IssueCallbackTokenAsync(waitId);           // run.operate; shown once
var wait = CallbackTokenClient.Create(baseUrl, workspaceId, issued.Token);
var poll = await wait.PollAsync();                                   // pass poll.ETag next time
await wait.ResumeAsync($"shipment-{shipmentId}", new Dictionary<string, object?> { ["trackingNumber"] = "1Z999" });
```

## Tests

```bash
dotnet test Orqix.sln
```

xUnit (Apache-2.0) against a scripted Kestrel server on the loopback address; no Orqix stack is needed.
