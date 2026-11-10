using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Orqix.Client.Worker;
using Xunit;

namespace Orqix.Client.Tests;

public sealed class WorkerTests : IAsyncLifetime
{
    private const string Ws = ClientTests.Ws;
    private const string JobId = "0193a0c4-7777-7000-8000-000000000007";
    private const string Jobs = "{\"items\":[{\"id\":\"" + JobId + "\",\"topic\":\"address-check\",\"input\":{\"postcode\":\"W1A 1AA\",\"amount\":12.5},"
        + "\"fence\":3,\"leaseExpiresAt\":\"2026-11-09T10:00:00Z\",\"attempt\":1,\"maxAttempts\":3,\"runReference\":\"ORQ-1043\","
        + "\"nodeId\":\"check\",\"notYetKnown\":\"ignored\"}]}";

    private ScriptedServer _server = null!;
    private OrqixClient _client = null!;

    public async Task InitializeAsync()
    {
        _server = await ScriptedServer.StartAsync();
        _client = new OrqixClient(new OrqixClientOptions
        {
            BaseUrl = _server.BaseUrl, WorkspaceId = Ws, Token = _ => ValueTask.FromResult("worker-token"),
        });
    }

    public async Task DisposeAsync() => await _server.DisposeAsync();

    private OrqixWorker Worker(TimeSpan? extendEvery = null) => new(_client, new OrqixWorkerOptions
    {
        WorkerId = "address-checker-1", PollInterval = TimeSpan.FromMilliseconds(10),
        FinalCallBackoff = TimeSpan.FromMilliseconds(5), ExtendEvery = extendEvery, Log = _ => { },
    });

    private static Dictionary<string, JobHandler> Handle(JobHandler handler) => new() { ["address-check"] = handler };

    private static Task<IDictionary<string, object?>?> Result(params (string, object?)[] values)
    {
        IDictionary<string, object?> map = new Dictionary<string, object?>();
        foreach (var (k, v) in values) map[k] = v;
        return Task.FromResult<IDictionary<string, object?>?>(map);
    }

    [Fact]
    public async Task CompletesAJobWithTheFenceAndAJobFenceKey()
    {
        _server.Once("POST", "/external-jobs/fetch-and-lock", 200, Jobs);
        _server.Once("POST", $"/external-jobs/{JobId}/complete", 204);
        Job? seen = null;

        var completed = await Worker().RunOnceAsync(Handle((job, _) => { seen = job; return Result(("valid", true), ("score", 0.9)); }));

        Assert.Equal(1, completed);
        Assert.Equal("W1A 1AA", seen!.GetInput<string>("postcode"));
        Assert.Equal(12.5m, seen.GetInput<decimal>("amount"));
        Assert.Equal(DateTimeOffset.Parse("2026-11-09T10:00:00Z").UtcTicks, seen.LeaseExpiresAt.UtcTicks);
        var fetch = _server.Requests("POST", "/fetch-and-lock")[0].Json();
        Assert.Equal("address-checker-1", fetch.GetProperty("workerId").GetString());
        Assert.Equal(5, fetch.GetProperty("maxJobs").GetInt32());
        Assert.Equal(120, fetch.GetProperty("leaseSeconds").GetInt32());
        var complete = _server.Requests("POST", "/complete")[0];
        Assert.Equal($"{JobId}:3:complete", complete.Header("Idempotency-Key"));
        Assert.Equal(3, complete.Json().GetProperty("fence").GetInt32());
        Assert.True(complete.Json().GetProperty("result").GetProperty("valid").GetBoolean());
    }

    [Fact]
    public async Task ABusinessErrorIsAFinalFailWithItsCode()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs).Once("POST", $"/{JobId}/fail", 204);
        await Worker().RunOnceAsync(Handle((_, _) => throw new BusinessErrorException("ADDRESS_UNKNOWN", "No such street")));
        var fail = _server.Requests("POST", "/fail")[0];
        Assert.Equal($"{JobId}:3:fail", fail.Header("Idempotency-Key"));
        Assert.Equal("ADDRESS_UNKNOWN", fail.Json().GetProperty("errorCode").GetString());
        Assert.False(fail.Json().GetProperty("retryable").GetBoolean());
        Assert.Equal("No such street", fail.Json().GetProperty("message").GetString());
    }

    [Fact]
    public async Task AJobFailureKeepsItsRetryChoiceAndDelay()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs).Once("POST", $"/{JobId}/fail", 204);
        await Worker().RunOnceAsync(Handle((_, _) => throw new JobFailedException("Directory offline", true, 60)));
        var fail = _server.Requests("POST", "/fail")[0].Json();
        Assert.True(fail.GetProperty("retryable").GetBoolean());
        Assert.Equal(60, fail.GetProperty("retryAfterSeconds").GetInt32());
        Assert.False(fail.TryGetProperty("errorCode", out _));
    }

    [Fact]
    public async Task AnUnexpectedExceptionReportsOnlyItsTypeName()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs).Once("POST", $"/{JobId}/fail", 204);
        await Worker().RunOnceAsync(Handle((_, _) => throw new InvalidOperationException("secret connection string")));
        Assert.Equal("The worker raised InvalidOperationException",
            _server.Requests("POST", "/fail")[0].Json().GetProperty("message").GetString());
    }

    [Fact]
    public async Task ALostLeaseDropsTheJob()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs).Once("POST", $"/{JobId}/complete", 409, "{\"code\":\"JOB_LEASE_LOST\"}");
        Assert.Equal(0, await Worker().RunOnceAsync(Handle((_, _) => Result())));
        Assert.Single(_server.Requests("POST", "/complete"));
    }

    [Fact]
    public async Task TransientFailuresOfTheFinalCallRepeatWithTheSameKey()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs)
            .Once("POST", $"/{JobId}/complete", 503, "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}")
            .Once("POST", $"/{JobId}/complete", 204);
        Assert.Equal(1, await Worker().RunOnceAsync(Handle((_, _) => Result(("valid", true)))));
        var completes = _server.Requests("POST", "/complete");
        Assert.Equal(2, completes.Count);
        Assert.Equal(completes[0].Header("Idempotency-Key"), completes[1].Header("Idempotency-Key"));
        Assert.Equal(completes[0].Body, completes[1].Body);
    }

    [Fact]
    public async Task ARefusedResultIsReportedAsAFailure()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs)
            .Once("POST", $"/{JobId}/complete", 422, "{\"code\":\"VALIDATION_FAILED\"}")
            .Once("POST", $"/{JobId}/fail", 204);
        Assert.Equal(0, await Worker().RunOnceAsync(Handle((_, _) => Result(("unexpected", 1)))));
        Assert.Equal("The worker's result was refused (VALIDATION_FAILED)",
            _server.Requests("POST", "/fail")[0].Json().GetProperty("message").GetString());
    }

    [Fact]
    public async Task TheLeaseIsExtendedWhileTheHandlerRuns()
    {
        _server.Once("POST", "/fetch-and-lock", 200, Jobs)
            .Always("POST", $"/{JobId}/extend-lock", 200, "{\"leaseExpiresAt\":\"2026-11-09T10:02:00Z\"}")
            .Once("POST", $"/{JobId}/complete", 204);
        DateTimeOffset lease = default;
        await Worker(TimeSpan.FromMilliseconds(20)).RunOnceAsync(Handle(async (job, _) =>
        {
            await _server.WaitForAsync(r => r.Path.EndsWith("/extend-lock", StringComparison.Ordinal));
            await Task.Delay(50);
            lease = job.LeaseExpiresAt;
            return new Dictionary<string, object?> { ["valid"] = true };
        }));
        var extend = _server.Requests("POST", "/extend-lock")[0].Json();
        Assert.Equal(3, extend.GetProperty("fence").GetInt32());
        Assert.Equal(DateTimeOffset.Parse("2026-11-09T10:02:00Z").UtcTicks, lease.UtcTicks);
    }

    [Fact]
    public async Task AuthenticationFailureStopsTheLoop()
    {
        _server.Once("POST", "/fetch-and-lock", 401, "{\"code\":\"UNAUTHENTICATED\"}");
        var e = await Assert.ThrowsAsync<OrqixApiException>(() => Worker().RunAsync(Handle((_, _) => Result())));
        Assert.Equal("UNAUTHENTICATED", e.Code);
    }

    [Fact]
    public async Task RunPollsUntilCancelledOrStopped()
    {
        _server.Always("POST", "/fetch-and-lock", 200, "{\"items\":[]}");
        using var stopping = new CancellationTokenSource();
        var worker = Worker();
        var running = worker.RunAsync(Handle((_, _) => Result()), stopping.Token);
        await _server.WaitForAsync(_ => _server.Requests("POST", "/fetch-and-lock").Count >= 2);
        stopping.Cancel();
        Assert.Equal(0, await running.WaitAsync(TimeSpan.FromSeconds(10)));

        var again = worker.RunAsync(Handle((_, _) => Result()));
        await _server.WaitForAsync(_ => _server.Requests("POST", "/fetch-and-lock").Count >= 4);
        worker.Stop();
        Assert.Equal(0, await again.WaitAsync(TimeSpan.FromSeconds(10)));
    }

    [Fact]
    public async Task CallbackHandOffUsesItsOwnKeys()
    {
        const string callbackId = "0193a0c4-8888-7000-8000-000000000008";
        _server.Once("POST", "/fetch-and-lock", 200, Jobs)
            .Once("POST", $"/{JobId}/await-callback", 200, "{\"callbackId\":\"" + callbackId + "\",\"deadline\":\"2026-11-09T11:00:00Z\"}")
            .Once("POST", $"/callbacks/{callbackId}/complete", 204)
            .Once("POST", $"/callbacks/{callbackId}/fail", 204);
        var worker = Worker();
        var job = (await worker.FetchAndLockAsync(new[] { "address-check" }))[0];
        var ticket = await worker.AwaitCallbackAsync(job, 3600);
        var restored = ticket with { }; // a gateway outbox round trip
        await worker.CompleteCallbackAsync(restored, new Dictionary<string, object?> { ["score"] = 0.7 });
        await worker.FailCallbackAsync(restored, new string('x', 600));
        Assert.Equal($"{JobId}:3:await-callback", _server.Requests("POST", "/await-callback")[0].Header("Idempotency-Key"));
        Assert.Equal($"{callbackId}:complete", _server.Requests("POST", $"/callbacks/{callbackId}/complete")[0].Header("Idempotency-Key"));
        Assert.Equal(500, _server.Requests("POST", $"/callbacks/{callbackId}/fail")[0].Json().GetProperty("message").GetString()!.Length);
    }

    [Fact]
    public async Task CodesTopicsAndLimitsAreChecked()
    {
        Assert.Throws<ArgumentException>(() => new BusinessErrorException("ORQIX_INTERNAL", "x"));
        Assert.Throws<ArgumentException>(() => new BusinessErrorException("lower", "x"));
        Assert.Throws<ArgumentOutOfRangeException>(() => new OrqixWorker(_client, new OrqixWorkerOptions { WorkerId = "w", LeaseSeconds = 10 }));
        Assert.Throws<ArgumentOutOfRangeException>(() => new OrqixWorker(_client, new OrqixWorkerOptions { WorkerId = "w", MaxJobs = 11 }));
        await Assert.ThrowsAsync<ArgumentException>(() =>
            Worker().RunOnceAsync(new Dictionary<string, JobHandler> { ["Bad Topic"] = (_, _) => Result() }));
        await Assert.ThrowsAsync<ArgumentException>(() => Worker().RunOnceAsync(new Dictionary<string, JobHandler>()));
    }
}
