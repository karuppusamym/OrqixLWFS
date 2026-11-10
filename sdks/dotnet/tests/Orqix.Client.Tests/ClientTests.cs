using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Orqix.Client.Models;
using Xunit;

namespace Orqix.Client.Tests;

public sealed class ClientTests : IAsyncLifetime
{
    internal const string Ws = "00000000-0000-7000-8000-000000000201";
    internal const string RunId = "0193a0c4-1111-7000-8000-000000000001";
    internal const string RunJson = """
        {"id":"0193a0c4-1111-7000-8000-000000000001","reference":"ORQ-1043","workflow":{"id":"0193a0c4-2222-7000-8000-000000000002",
         "key":"purchase-approval","name":"Purchase approval"},"release":{"id":"0193a0c4-3333-7000-8000-000000000003","releaseNumber":4},
         "businessKey":"PO-7","requestTitle":null,"initiator":{"id":"0193a0c4-4444-7000-8000-000000000004","displayName":"Order bot"},
         "lifecycle":"waiting","outcome":null,"outcomeKind":null,"currentSteps":[{"nodeId":"approve","label":"Approve","kind":"approval",
         "since":"2026-11-09T10:15:30+05:30"}],"attention":null,"createdAt":"2026-11-09T04:45:30Z","updatedAt":"2026-11-09T04:45:30Z",
         "endedAt":null,"isTest":false,"version":2,"addedByALaterRelease":{"nested":true}}
        """;
    private const string Token = "ocb_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private ScriptedServer _server = null!;
    private readonly List<DeprecationNotice> _notices = new();
    private OrqixClient _client = null!;

    public async Task InitializeAsync()
    {
        _server = await ScriptedServer.StartAsync();
        _client = new OrqixClient(new OrqixClientOptions
        {
            BaseUrl = _server.BaseUrl, WorkspaceId = Ws, Token = _ => ValueTask.FromResult("machine-token"),
            OnDeprecation = _notices.Add,
        });
    }

    public async Task DisposeAsync() => await _server.DisposeAsync();

    [Fact]
    public async Task StartRunSendsTheKeyAndReadsATypedRunIgnoringUnknownFields()
    {
        _server.Once("POST", $"/workspaces/{Ws}/runs", 201, RunJson);

        var run = await _client.StartRunAsync("start-PO-7-0001", new StartRunRequest
        {
            WorkflowId = "0193a0c4-2222-7000-8000-000000000002", Input = new Dictionary<string, object?> { ["amount"] = 1200 },
        });

        Assert.Equal("ORQ-1043", run.Reference);
        Assert.Equal(Lifecycle.Waiting, run.Lifecycle);
        Assert.Equal(4, run.Release.ReleaseNumber);
        // The answer's offset is kept as an instant; the machine time zone plays no part.
        Assert.Equal(DateTimeOffset.Parse("2026-11-09T04:45:30Z").UtcTicks, run.CurrentSteps[0].Since.UtcTicks);
        var request = _server.All[0];
        Assert.Equal($"/api/v1/workspaces/{Ws}/runs", request.Path);
        Assert.Equal("Bearer machine-token", request.Header("Authorization"));
        Assert.Equal("start-PO-7-0001", request.Header("Idempotency-Key"));
        Assert.Equal(1200, request.Json().GetProperty("input").GetProperty("amount").GetInt32());
        Assert.False(request.Json().TryGetProperty("businessKey", out _), "null fields are not sent");
    }

    [Fact]
    public async Task AMalformedIdempotencyKeyIsRefusedBeforeAnyCall()
    {
        await Assert.ThrowsAsync<ArgumentException>(() =>
            _client.StartRunAsync("short", new StartRunRequest { WorkflowId = "w", Input = new() }));
        Assert.Empty(_server.All);
    }

    [Fact]
    public async Task GetRunAndEventsUseTheirPathsAndQuery()
    {
        var trimmed = RunJson.Trim();
        var detail = trimmed[..^1] + ",\"tasks\":[],\"allowedActions\":{\"cancel\":{\"allowed\":true},"
            + "\"retry\":{\"allowed\":false,\"reasonCode\":\"RUN_NOT_RECOVERABLE\"},\"reconcile\":{\"allowed\":false},"
            + "\"suspend\":{\"allowed\":true},\"resume\":{\"allowed\":false}}}";
        _server.Once("GET", $"/runs/{RunId}", 200, detail);
        _server.Once("GET", $"/runs/{RunId}/events", 200,
            "{\"items\":[{\"seq\":5,\"type\":\"RUN_STARTED\",\"occurredAt\":\"2026-11-09T04:45:30Z\",\"data\":{}}],\"nextAfter\":5,\"hasMore\":false,\"lifecycle\":\"waiting\"}");

        var run = await _client.GetRunAsync(RunId);
        var events = await _client.ListRunEventsAsync(RunId, 4, 50);

        Assert.True(run.AllowedActions.Cancel.Allowed);
        Assert.Equal("RUN_NOT_RECOVERABLE", run.AllowedActions.Retry.ReasonCode);
        Assert.Equal(5, events.NextAfter);
        Assert.Equal("after=4&limit=50", _server.All[1].Query);
        Assert.Null(_server.All[0].Header("Idempotency-Key"));
    }

    [Fact]
    public async Task SendMessageHelperBuildsTheRequest()
    {
        _server.Once("POST", $"/workspaces/{Ws}/messages", 202,
            "{\"id\":\"0193a0c4-5555-7000-8000-000000000005\",\"status\":\"buffered\",\"matched\":0,\"delivered\":[],\"bufferedUntil\":\"2026-11-10T04:45:30Z\"}");

        var accepted = await _client.SendMessageAsync("payment-PO-7", "payment-received", "PO-7",
            new Dictionary<string, object?> { ["paid"] = true });

        Assert.Equal("buffered", accepted.Status);
        var body = _server.All[0].Json();
        Assert.Equal("payment-received", body.GetProperty("name").GetString());
        Assert.True(body.GetProperty("payload").GetProperty("paid").GetBoolean());
    }

    [Fact]
    public async Task ProblemAnswersBecomeApiExceptions()
    {
        _server.Once("POST", "/runs", 429, "{\"status\":429,\"code\":\"RATE_LIMITED\",\"detail\":\"Slow down\",\"correlationId\":\"c-42\"}",
            new Dictionary<string, string> { ["Retry-After"] = "7" });

        var e = await Assert.ThrowsAsync<OrqixApiException>(() =>
            _client.StartRunAsync("start-PO-7-0002", new StartRunRequest { WorkflowId = "w", Input = new() }));

        Assert.Equal(429, e.Status);
        Assert.Equal("RATE_LIMITED", e.Code);
        Assert.Equal("c-42", e.CorrelationId);
        Assert.Equal(7, e.RetryAfterSeconds);
        Assert.True(e.IsTransient);
    }

    [Fact]
    public async Task AnAnswerWithoutProblemDocumentKeepsItsStatus()
    {
        _server.Once("GET", $"/runs/{RunId}", 502, "<html>bad gateway</html>");
        var e = await Assert.ThrowsAsync<OrqixApiException>(() => _client.GetRunAsync(RunId));
        Assert.Equal("HTTP_ERROR", e.Code);
        Assert.Equal(502, e.Status);
    }

    [Fact]
    public async Task AnUnreachableServerIsATransientTransportError()
    {
        var client = new OrqixClient(new OrqixClientOptions
        {
            BaseUrl = "http://127.0.0.1:9/api/v1", WorkspaceId = Ws, Token = _ => ValueTask.FromResult("t"),
            Timeout = TimeSpan.FromSeconds(5),
        });
        var e = await Assert.ThrowsAsync<OrqixApiException>(() => client.GetRunAsync(RunId));
        Assert.Equal("TRANSPORT_ERROR", e.Code);
        Assert.True(e.IsTransient);
    }

    [Fact]
    public async Task ADeprecatedOperationIsReportedOnce()
    {
        var headers = new Dictionary<string, string> { ["Deprecation"] = "@1767225600", ["Sunset"] = "Wed, 30 Jun 2027 00:00:00 GMT" };
        const string page = "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}";
        _server.Once("GET", "/events", 200, page, headers).Once("GET", "/events", 200, page, headers);

        await _client.ListRunEventsAsync(RunId);
        await _client.ListRunEventsAsync(RunId);

        var notice = Assert.Single(_notices);
        Assert.Equal(OrqixOperations.ListRunEvents, notice.Operation);
        Assert.Equal("Wed, 30 Jun 2027 00:00:00 GMT", notice.Sunset);
    }

    [Fact]
    public async Task TheTokenFunctionIsCalledForEveryRequest()
    {
        var calls = 0;
        var rotating = new OrqixClient(new OrqixClientOptions
        {
            BaseUrl = _server.BaseUrl, WorkspaceId = Ws, Token = _ => ValueTask.FromResult($"token-{++calls}"),
        });
        _server.Always("GET", "/events", 200, "{\"items\":[],\"nextAfter\":0,\"hasMore\":false,\"lifecycle\":\"running\"}");

        await rotating.ListRunEventsAsync(RunId);
        await rotating.ListRunEventsAsync(RunId);

        Assert.Equal("Bearer token-1", _server.All[0].Header("Authorization"));
        Assert.Equal("Bearer token-2", _server.All[1].Header("Authorization"));
    }

    [Fact]
    public void OperationPathsEncodeTheirParameters()
    {
        Assert.Equal("/workspaces/a%20b/runs/x%2Fy", OrqixOperations.GetRun.Path("a b", "x/y"));
        Assert.Throws<ArgumentException>(() => OrqixOperations.GetRun.Path("only-one"));
        Assert.Equal(OrqixCredential.CallbackToken, OrqixOperations.ResumeCallbackWait.Credential);
        Assert.Equal(14, OrqixOperations.All.Count);
    }

    [Fact]
    public async Task CallbackTokenPollSendsOnlyTheTokenAndHonoursTheEtag()
    {
        const string state = "{\"waitId\":\"0193a0c4-6666-7000-8000-000000000006\",\"runId\":\"" + RunId + "\",\"runReference\":\"ORQ-1043\","
            + "\"nodeId\":\"ship\",\"state\":\"waiting\",\"suspended\":false,\"fields\":[{\"name\":\"trackingNumber\",\"type\":\"text\",\"required\":true}]}";
        _server.Once("GET", $"/workspaces/{Ws}/callbacks/wait", 200, state, new Dictionary<string, string> { ["ETag"] = "\"v1\"" });
        _server.Once("GET", $"/workspaces/{Ws}/callbacks/wait", 304, null, new Dictionary<string, string> { ["ETag"] = "\"v1\"" });
        var wait = CallbackTokenClient.Create(_server.BaseUrl, Ws, Token);

        var first = await wait.PollAsync();
        var second = await wait.PollAsync(first.ETag);

        Assert.Equal("waiting", first.State!.State);
        Assert.Equal("trackingNumber", first.State.Fields[0].Name);
        Assert.True(second.NotModified);
        var request = _server.All[1];
        Assert.Equal(Token, request.Header("X-Orqix-Callback-Token"));
        Assert.Equal("\"v1\"", request.Header("If-None-Match"));
        Assert.Null(request.Header("Authorization"));
        Assert.Equal("", request.Query);
    }

    [Fact]
    public async Task CallbackTokenResumeAndIssue()
    {
        _server.Once("POST", "/callback-waits/0193a0c4-6666-7000-8000-000000000006/token", 201,
            "{\"waitId\":\"0193a0c4-6666-7000-8000-000000000006\",\"token\":\"" + Token + "\",\"expiresAt\":\"2026-11-10T04:45:30Z\","
            + "\"pollPath\":\"p\",\"resumePath\":\"r\",\"header\":\"X-Orqix-Callback-Token\"}");
        _server.Once("POST", "/callbacks/wait/resume", 200,
            "{\"waitId\":\"0193a0c4-6666-7000-8000-000000000006\",\"runId\":\"" + RunId + "\",\"runReference\":\"ORQ-1043\",\"nodeId\":\"ship\","
            + "\"state\":\"resumed\",\"resumedAt\":\"2026-11-09T05:00:00Z\",\"acceptedFields\":[\"trackingNumber\"]}");

        var issued = await _client.IssueCallbackTokenAsync("0193a0c4-6666-7000-8000-000000000006");
        var resumed = await _client.CallbackTokenClient(issued.Token)
            .ResumeAsync("shipment-0001", new Dictionary<string, object?> { ["trackingNumber"] = "1Z999" });

        Assert.Equal("resumed", resumed.State);
        Assert.Equal("Bearer machine-token", _server.All[0].Header("Authorization"));
        Assert.Null(_server.All[1].Header("Authorization"));
        Assert.Equal("shipment-0001", _server.All[1].Header("Idempotency-Key"));
        Assert.Equal("{\"result\":{\"trackingNumber\":\"1Z999\"}}", _server.All[1].Body);
    }

    [Fact]
    public void ATokenWithControlCharactersIsRefused()
    {
        Assert.Throws<ArgumentException>(() => CallbackTokenClient.Create(_server.BaseUrl, Ws, "ocb_x\r\nX-Evil: 1"));
    }
}
