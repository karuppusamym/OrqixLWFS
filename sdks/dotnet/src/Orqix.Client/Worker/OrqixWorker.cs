using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Orqix.Client.Models;

namespace Orqix.Client.Worker;

/// <summary>Settings of an <see cref="OrqixWorker"/>.</summary>
public sealed class OrqixWorkerOptions
{
    /// <summary>Names this worker process in the server's records (1-100 characters).</summary>
    public string WorkerId { get; set; } = "";

    /// <summary>Longest a job is held without extending, 30-3600 seconds; default 120.</summary>
    public int LeaseSeconds { get; set; } = 120;

    /// <summary>Jobs taken per fetch, 1-10; default 5. They are handled one after another.</summary>
    public int MaxJobs { get; set; } = 5;

    /// <summary>Wait after a fetch that returned nothing; default 2 seconds.</summary>
    public TimeSpan PollInterval { get; set; } = TimeSpan.FromSeconds(2);

    /// <summary>How often a running job's lease is extended; default half the lease.</summary>
    public TimeSpan? ExtendEvery { get; set; }

    /// <summary>Attempts of complete/fail on transient failures, with the same idempotency key; default 3.</summary>
    public int FinalCallAttempts { get; set; } = 3;

    /// <summary>First delay between those attempts, doubled each time; default 1 second.</summary>
    public TimeSpan FinalCallBackoff { get; set; } = TimeSpan.FromSeconds(1);

    /// <summary>Receives the loop's warnings; default standard error.</summary>
    public Action<string>? Log { get; set; }
}

/// <summary>
/// External-worker loop (docs/build/S64). Same feature names as the Python SDK: FetchAndLock, ExtendLock, Complete,
/// Fail, FailWithError, AwaitCallback, CompleteCallback, FailCallback, Handle, Run, Stop.
/// <code>
/// var worker = new OrqixWorker(client, new OrqixWorkerOptions { WorkerId = "address-checker-1" });
/// await worker.RunAsync(new Dictionary&lt;string, JobHandler&gt; { ["address-check"] = Check }, stoppingToken);
/// </code>
/// While a handler runs, the lease is extended at half its length. Final reports use the idempotency key
/// <c>&lt;job&gt;:&lt;fence&gt;:complete|fail</c> and are repeated on transient failures. A job whose lease was lost is
/// dropped; 401 and 403 stop the loop.
/// </summary>
public sealed class OrqixWorker
{
    private static readonly Regex Topic = new("^[a-z][a-z0-9-]{0,62}[a-z0-9]$", RegexOptions.CultureInvariant);

    private readonly OrqixClient _client;
    private readonly OrqixWorkerOptions _options;
    private readonly TimeSpan _extendEvery;
    private readonly Action<string> _log;
    private CancellationTokenSource _stop = new();

    /// <summary>Creates the worker.</summary>
    public OrqixWorker(OrqixClient client, OrqixWorkerOptions options)
    {
        _client = client ?? throw new ArgumentNullException(nameof(client));
        _options = options ?? throw new ArgumentNullException(nameof(options));
        if (string.IsNullOrWhiteSpace(options.WorkerId) || options.WorkerId.Length > 100)
            throw new ArgumentException("WorkerId is 1-100 characters", nameof(options));
        if (options.LeaseSeconds is < 30 or > 3600)
            throw new ArgumentOutOfRangeException(nameof(options), "LeaseSeconds is 30-3600");
        if (options.MaxJobs is < 1 or > 10)
            throw new ArgumentOutOfRangeException(nameof(options), "MaxJobs is 1-10");
        _extendEvery = options.ExtendEvery ?? TimeSpan.FromMilliseconds(Math.Max(1000, options.LeaseSeconds * 500));
        _log = options.Log ?? (line => Console.Error.WriteLine(line));
    }

    /// <summary>The worker ID.</summary>
    public string WorkerId => _options.WorkerId;

    // ------------------------------------------------------------------ protocol calls

    /// <summary>Leases available jobs of the topics.</summary>
    public async Task<IReadOnlyList<Job>> FetchAndLockAsync(IEnumerable<string> topics, CancellationToken cancellationToken = default)
    {
        var batch = await _client.FetchExternalJobsAsync(new ExternalJobFetchRequest
        {
            WorkerId = WorkerId, Topics = topics.ToList(), MaxJobs = _options.MaxJobs, LeaseSeconds = _options.LeaseSeconds,
        }, cancellationToken).ConfigureAwait(false);
        return (batch.Items ?? new List<ExternalJob>()).Select(item => new Job(item)).ToList();
    }

    /// <summary>Pushes the lease out; returns its new end.</summary>
    public async Task<DateTimeOffset> ExtendLockAsync(Job job, int? seconds = null, CancellationToken cancellationToken = default)
    {
        var result = await _client.ExtendExternalJobLockAsync(job.Id, new ExternalJobExtendRequest
        {
            WorkerId = WorkerId, Fence = job.Fence, LeaseSeconds = seconds ?? _options.LeaseSeconds,
        }, cancellationToken).ConfigureAwait(false);
        job.LeaseExpiresAt = result.LeaseExpiresAt;
        return result.LeaseExpiresAt;
    }

    /// <summary>
    /// Hands the job off before submitting remote work (asynchronous gateways). Use an outbox and a stable provider
    /// dispatch key; neither a callback timeout nor a failure resubmits remote work automatically.
    /// </summary>
    public async Task<CallbackTicket> AwaitCallbackAsync(Job job, int timeoutSeconds = 3600, CancellationToken cancellationToken = default)
    {
        var registration = await _client.AwaitExternalJobCallbackAsync(job.Id, $"{job.Id}:{job.Fence}:await-callback",
            new ExternalJobAwaitCallbackRequest { WorkerId = WorkerId, Fence = job.Fence, TimeoutSeconds = timeoutSeconds },
            cancellationToken).ConfigureAwait(false);
        return new CallbackTicket(job.Id, registration.CallbackId, registration.Deadline);
    }

    /// <summary>Delivers the result as the subject that registered the callback.</summary>
    public Task CompleteCallbackAsync(CallbackTicket ticket, IDictionary<string, object?> result, CancellationToken cancellationToken = default) =>
        _client.CompleteExternalJobCallbackAsync(ticket.JobId, ticket.CallbackId, $"{ticket.CallbackId}:complete",
            new ExternalJobCallbackResult { Result = new Dictionary<string, object?>(result) }, cancellationToken);

    /// <summary>Reports a final remote failure. An operator inspects the remote outcome before authorizing a retry.</summary>
    public Task FailCallbackAsync(CallbackTicket ticket, string message, CancellationToken cancellationToken = default) =>
        _client.FailExternalJobCallbackAsync(ticket.JobId, ticket.CallbackId, $"{ticket.CallbackId}:fail",
            new ExternalJobCallbackFailure { Message = Message(message) }, cancellationToken);

    /// <summary>Completes the job with the step's declared result fields.</summary>
    public Task CompleteAsync(Job job, IDictionary<string, object?>? result, CancellationToken cancellationToken = default) =>
        FinalCallAsync(() => _client.CompleteExternalJobAsync(job.Id, $"{job.Id}:{job.Fence}:complete",
            new ExternalJobCompleteRequest
            {
                WorkerId = WorkerId, Fence = job.Fence,
                Result = result is null ? new Dictionary<string, object?>() : new Dictionary<string, object?>(result),
            }, cancellationToken), cancellationToken);

    /// <summary>Reports a technical failure.</summary>
    public Task FailAsync(Job job, string message, bool retryable = true, int? retryAfterSeconds = null,
        CancellationToken cancellationToken = default) =>
        FinalCallAsync(() => _client.FailExternalJobAsync(job.Id, $"{job.Id}:{job.Fence}:fail", new ExternalJobFailRequest
        {
            WorkerId = WorkerId, Fence = job.Fence, Message = Message(message), Retryable = retryable,
            RetryAfterSeconds = retryAfterSeconds,
        }, cancellationToken), cancellationToken);

    /// <summary>Reports a business error (S132). Final; same idempotency key as Fail: a lease ends with one final report.</summary>
    public Task FailWithErrorAsync(Job job, string errorCode, string message, CancellationToken cancellationToken = default)
    {
        BusinessErrorException.CheckCode(errorCode);
        return FinalCallAsync(() => _client.FailExternalJobAsync(job.Id, $"{job.Id}:{job.Fence}:fail", new ExternalJobFailRequest
        {
            WorkerId = WorkerId, Fence = job.Fence, Message = Message(message), Retryable = false, ErrorCode = errorCode,
        }, cancellationToken), cancellationToken);
    }

    // ------------------------------------------------------------------ running handlers

    /// <summary>Runs one job with automatic lease extension; true when the job was completed.</summary>
    public async Task<bool> HandleAsync(Job job, JobHandler handler, CancellationToken cancellationToken = default)
    {
        IDictionary<string, object?>? result;
        using (var beat = new CancellationTokenSource())
        {
            var keepAlive = KeepAliveAsync(job, beat.Token);
            try
            {
                result = await handler(job, cancellationToken).ConfigureAwait(false);
            }
            catch (BusinessErrorException failure)
            {
                await StopBeat(beat, keepAlive).ConfigureAwait(false);
                await FailWithErrorAsync(job, failure.Code, failure.BusinessMessage, CancellationToken.None).ConfigureAwait(false);
                return false;
            }
            catch (JobFailedException failure)
            {
                await StopBeat(beat, keepAlive).ConfigureAwait(false);
                await FailAsync(job, failure.Message, failure.Retryable, failure.RetryAfterSeconds, CancellationToken.None).ConfigureAwait(false);
                return false;
            }
            catch (Exception unexpected)
            {
                await StopBeat(beat, keepAlive).ConfigureAwait(false);
                // The type name is all the workflow's timeline gets to see.
                await FailAsync(job, $"The worker raised {unexpected.GetType().Name}", true, null, CancellationToken.None).ConfigureAwait(false);
                return false;
            }
            await StopBeat(beat, keepAlive).ConfigureAwait(false);
        }
        try
        {
            await CompleteAsync(job, result, CancellationToken.None).ConfigureAwait(false);
            return true;
        }
        catch (OrqixApiException refused) when (refused.Status == 422)
        {
            await FailAsync(job, $"The worker's result was refused ({refused.Code})", true, null, CancellationToken.None).ConfigureAwait(false);
            return false;
        }
    }

    private static async Task StopBeat(CancellationTokenSource beat, Task keepAlive)
    {
        beat.Cancel();
        await keepAlive.ConfigureAwait(false);
    }

    private async Task KeepAliveAsync(Job job, CancellationToken cancellationToken)
    {
        using var timer = new PeriodicTimer(_extendEvery);
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken).ConfigureAwait(false))
            {
                try
                {
                    await ExtendLockAsync(job, null, cancellationToken).ConfigureAwait(false);
                }
                catch (OrqixApiException)
                {
                    // The lease is gone or the server is unreachable; the final call reports it.
                }
            }
        }
        catch (OperationCanceledException)
        {
            // The handler finished.
        }
    }

    /// <summary>Polls for the topics of <paramref name="handlers"/> until stopped; returns the number of jobs completed.</summary>
    public Task<int> RunAsync(IReadOnlyDictionary<string, JobHandler> handlers, CancellationToken stoppingToken = default) =>
        LoopAsync(handlers, false, stoppingToken);

    /// <summary>One fetch and the jobs it returned.</summary>
    public Task<int> RunOnceAsync(IReadOnlyDictionary<string, JobHandler> handlers, CancellationToken cancellationToken = default) =>
        LoopAsync(handlers, true, cancellationToken);

    /// <summary>Ends <see cref="RunAsync"/> after the current job.</summary>
    public void Stop() => _stop.Cancel();

    private async Task<int> LoopAsync(IReadOnlyDictionary<string, JobHandler> handlers, bool once, CancellationToken stoppingToken)
    {
        var topics = handlers.Keys.OrderBy(t => t, StringComparer.Ordinal).ToList();
        if (topics.Count is 0 or > 10) throw new ArgumentException("A worker serves 1-10 topics", nameof(handlers));
        foreach (var topic in topics)
        {
            if (!Topic.IsMatch(topic)) throw new ArgumentException($"A topic is lowercase words joined with dashes: {topic}", nameof(handlers));
        }
        if (_stop.IsCancellationRequested) _stop = new CancellationTokenSource();
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken, _stop.Token);
        var stop = linked.Token;
        var completed = 0;
        while (!stop.IsCancellationRequested)
        {
            IReadOnlyList<Job> jobs = Array.Empty<Job>();
            var wait = _options.PollInterval;
            try
            {
                jobs = await FetchAndLockAsync(topics, stop).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested)
            {
                break;
            }
            catch (OrqixApiException e) when (!e.IsAuthenticationFailure)
            {
                _log($"Fetching jobs failed: {e.Message}");
                if (e.RetryAfterSeconds is { } seconds) wait = TimeSpan.FromSeconds(Math.Min(300, seconds));
            }
            foreach (var job in jobs)
            {
                if (!handlers.TryGetValue(job.Topic, out var handler)) continue;
                try
                {
                    if (await HandleAsync(job, handler, stop).ConfigureAwait(false)) completed++;
                }
                catch (OrqixApiException e) when (!e.IsAuthenticationFailure)
                {
                    _log(e.Code is "JOB_LEASE_LOST" or "JOB_NOT_OPEN"
                        ? $"{job} dropped: {e.Code}"
                        : $"Reporting {job} failed: {e.Message}");
                }
            }
            if (once) break;
            if (jobs.Count == 0)
            {
                try
                {
                    await Task.Delay(wait, stop).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
        return completed;
    }

    private async Task FinalCallAsync(Func<Task> call, CancellationToken cancellationToken)
    {
        var backoff = _options.FinalCallBackoff;
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                await call().ConfigureAwait(false);
                return;
            }
            catch (OrqixApiException e) when (e.IsTransient && attempt < Math.Max(1, _options.FinalCallAttempts))
            {
                var delay = e.RetryAfterSeconds is { } s ? TimeSpan.FromSeconds(Math.Min(60, s)) : backoff;
                await Task.Delay(delay, cancellationToken).ConfigureAwait(false);
                backoff *= 2;
            }
        }
    }

    private static string Message(string? message)
    {
        var text = string.IsNullOrWhiteSpace(message) ? "The worker reported a failure" : message;
        return text.Length > 500 ? text[..500] : text;
    }
}
