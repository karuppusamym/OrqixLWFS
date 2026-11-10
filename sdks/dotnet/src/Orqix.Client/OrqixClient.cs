using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Orqix.Client.Models;

namespace Orqix.Client;

/// <summary>Settings of an <see cref="OrqixClient"/>.</summary>
public sealed class OrqixClientOptions
{
    /// <summary>Absolute URL of the API, ending in <c>/api/v1</c>.</summary>
    public string BaseUrl { get; set; } = "";

    /// <summary>The workspace UUID.</summary>
    public string WorkspaceId { get; set; } = "";

    /// <summary>Returns the current access token of the machine identity; called for every request.</summary>
    public Func<CancellationToken, ValueTask<string>>? Token { get; set; }

    /// <summary>A configured HttpClient (proxy, TLS). The default never follows redirects.</summary>
    public HttpClient? HttpClient { get; set; }

    /// <summary>Per-request timeout; default 30 seconds.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);

    /// <summary>Called once per operation when the server marks it deprecated. Default: a line on standard error.</summary>
    public Action<DeprecationNotice>? OnDeprecation { get; set; }
}

/// <summary>
/// Typed client for the Orqix machine API (<c>/api/v1</c>): start and read requests, read their events, send messages,
/// the external-worker protocol and callback tokens. Method names are the contract's operationIds. Thread-safe; the
/// token function is called for every request and the token is never stored or logged.
/// </summary>
public sealed class OrqixClient
{
    private readonly Transport _transport;
    private readonly Func<CancellationToken, ValueTask<string>> _token;
    private readonly OrqixClientOptions _options;
    private readonly HttpClient _http;

    /// <summary>Creates the client.</summary>
    public OrqixClient(OrqixClientOptions options)
    {
        _options = options ?? throw new ArgumentNullException(nameof(options));
        _token = options.Token ?? throw new ArgumentException("Token is required", nameof(options));
        _http = options.HttpClient ?? Transport.DefaultHttpClient();
        _transport = new Transport(options.BaseUrl, options.WorkspaceId, _http, options.Timeout, Deprecations(options));
    }

    /// <summary>The workspace this client works in.</summary>
    public string WorkspaceId => _transport.WorkspaceId;

    private static Action<DeprecationNotice> Deprecations(OrqixClientOptions options) =>
        options.OnDeprecation ?? (notice => Console.Error.WriteLine(
            $"Orqix operation {notice.Operation.OperationId} is deprecated (Deprecation: {notice.Deprecation}, "
            + $"Sunset: {notice.Sunset ?? "not announced"}); see docs/api-compatibility.md"));

    /// <summary>Starts a request of the workflow's active release. Repeating the key with the same body replays the answer.</summary>
    public Task<Run> StartRunAsync(string idempotencyKey, StartRunRequest request, CancellationToken cancellationToken = default) =>
        Bearer<Run>(OrqixOperations.StartRun, Array.Empty<string>(), null, idempotencyKey, request, cancellationToken);

    /// <summary>Reads a request with the sections the caller may see.</summary>
    public Task<RunDetail> GetRunAsync(string runId, CancellationToken cancellationToken = default) =>
        Bearer<RunDetail>(OrqixOperations.GetRun, new[] { runId }, null, null, null, cancellationToken);

    /// <summary>Events after sequence number <paramref name="after"/>; pass <c>NextAfter</c> to poll on.</summary>
    public Task<EventPage> ListRunEventsAsync(string runId, long after = 0, int? limit = null,
        CancellationToken cancellationToken = default)
    {
        var query = new List<KeyValuePair<string, string?>>
        {
            new("after", after.ToString(CultureInfo.InvariantCulture)),
            new("limit", limit?.ToString(CultureInfo.InvariantCulture)),
        };
        return Bearer<EventPage>(OrqixOperations.ListRunEvents, new[] { runId }, query, null, null, cancellationToken);
    }

    /// <summary>Tells waiting requests that something happened (a "Wait for a message" step with this name and key).</summary>
    public Task<MessageAccepted> SendMessageAsync(string idempotencyKey, SendMessageRequest request,
        CancellationToken cancellationToken = default) =>
        Bearer<MessageAccepted>(OrqixOperations.SendMessage, Array.Empty<string>(), null, idempotencyKey, request, cancellationToken);

    /// <summary>Message helper: a name such as <c>payment-received</c>, the correlation key and a flat payload of scalars.</summary>
    public Task<MessageAccepted> SendMessageAsync(string idempotencyKey, string name, string correlationKey,
        IDictionary<string, object?>? payload = null, CancellationToken cancellationToken = default) =>
        SendMessageAsync(idempotencyKey, new SendMessageRequest
        {
            Name = name,
            CorrelationKey = correlationKey,
            Payload = payload is null ? null : new Dictionary<string, object?>(payload),
        }, cancellationToken);

    /// <summary>Leases available jobs of the topics to the worker.</summary>
    public Task<ExternalJobBatch> FetchExternalJobsAsync(ExternalJobFetchRequest request, CancellationToken cancellationToken = default) =>
        Bearer<ExternalJobBatch>(OrqixOperations.FetchExternalJobs, Array.Empty<string>(), null, null, request, cancellationToken);

    /// <summary>Pushes the lease of a job out.</summary>
    public Task<ExternalJobExtendResult> ExtendExternalJobLockAsync(string jobId, ExternalJobExtendRequest request,
        CancellationToken cancellationToken = default) =>
        Bearer<ExternalJobExtendResult>(OrqixOperations.ExtendExternalJobLock, new[] { jobId }, null, null, request, cancellationToken);

    /// <summary>Completes a job with the step's declared result fields.</summary>
    public Task CompleteExternalJobAsync(string jobId, string idempotencyKey, ExternalJobCompleteRequest request,
        CancellationToken cancellationToken = default) =>
        Bearer<object>(OrqixOperations.CompleteExternalJob, new[] { jobId }, null, idempotencyKey, request, cancellationToken);

    /// <summary>Fails a job (technical failure or business error).</summary>
    public Task FailExternalJobAsync(string jobId, string idempotencyKey, ExternalJobFailRequest request,
        CancellationToken cancellationToken = default) =>
        Bearer<object>(OrqixOperations.FailExternalJob, new[] { jobId }, null, idempotencyKey, request, cancellationToken);

    /// <summary>Hands a job off to an asynchronous gateway; the answer is the callback registration.</summary>
    public Task<ExternalJobCallbackRegistration> AwaitExternalJobCallbackAsync(string jobId, string idempotencyKey,
        ExternalJobAwaitCallbackRequest request, CancellationToken cancellationToken = default) =>
        Bearer<ExternalJobCallbackRegistration>(OrqixOperations.AwaitExternalJobCallback, new[] { jobId }, null,
            idempotencyKey, request, cancellationToken);

    /// <summary>Delivers the result of a handed-off job.</summary>
    public Task CompleteExternalJobCallbackAsync(string jobId, string callbackId, string idempotencyKey,
        ExternalJobCallbackResult request, CancellationToken cancellationToken = default) =>
        Bearer<object>(OrqixOperations.CompleteExternalJobCallback, new[] { jobId, callbackId }, null, idempotencyKey,
            request, cancellationToken);

    /// <summary>Reports the final remote failure of a handed-off job.</summary>
    public Task FailExternalJobCallbackAsync(string jobId, string callbackId, string idempotencyKey,
        ExternalJobCallbackFailure request, CancellationToken cancellationToken = default) =>
        Bearer<object>(OrqixOperations.FailExternalJobCallback, new[] { jobId, callbackId }, null, idempotencyKey,
            request, cancellationToken);

    /// <summary>Issues the one-time callback token of an open wait (needs run.operate). Store it like a password.</summary>
    public Task<CallbackTokenIssued> IssueCallbackTokenAsync(string waitId, CancellationToken cancellationToken = default) =>
        Bearer<CallbackTokenIssued>(OrqixOperations.IssueCallbackToken, new[] { waitId }, null, null, null, cancellationToken);

    /// <summary>A client that polls and resumes the wait of <paramref name="callbackToken"/>; it sends no bearer token.</summary>
    public CallbackTokenClient CallbackTokenClient(string callbackToken) =>
        new(new Transport(_options.BaseUrl, WorkspaceId, _http, _options.Timeout, Deprecations(_options)), callbackToken);

    private async Task<T> Bearer<T>(OrqixOperation operation, string[] path, IEnumerable<KeyValuePair<string, string?>>? query,
        string? idempotencyKey, object? body, CancellationToken cancellationToken)
    {
        if (operation.IdempotencyKeyRequired) Transport.CheckIdempotencyKey(idempotencyKey);
        var token = await _token(cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(token)) throw new InvalidOperationException("The token function returned no access token");
        var headers = new List<KeyValuePair<string, string?>>
        {
            new("Authorization", "Bearer " + token),
            new("Idempotency-Key", operation.IdempotencyKeyRequired ? idempotencyKey : null),
        };
        var answer = await _transport.SendAsync<T>(operation, path, query ?? Array.Empty<KeyValuePair<string, string?>>(),
            headers, body, false, cancellationToken).ConfigureAwait(false);
        return answer.Body!;
    }
}
