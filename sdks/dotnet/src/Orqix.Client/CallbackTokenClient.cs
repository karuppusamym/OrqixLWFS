using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Orqix.Client.Models;

namespace Orqix.Client;

/// <summary>The answer of a poll: <c>NotModified</c> after a 304 for the ETag passed, otherwise the current state.</summary>
public sealed record CallbackPoll(bool NotModified, CallbackWaitState? State, string? ETag);

/// <summary>
/// Callback-token helper (docs/build/S98): an outside system with the one-time token of a wait polls and resumes it.
/// The token travels in the <c>X-Orqix-Callback-Token</c> header, never in a URL; no bearer token is sent.
/// </summary>
public sealed class CallbackTokenClient
{
    private const string Header = "X-Orqix-Callback-Token";
    private readonly Transport _transport;
    private readonly string _token;

    internal CallbackTokenClient(Transport transport, string token)
    {
        if (string.IsNullOrEmpty(token) || token.Any(c => c < 0x21 || c > 0x7e))
        {
            throw new ArgumentException("A callback token is required (printable ASCII, as issued)", nameof(token));
        }
        _transport = transport;
        _token = token;
    }

    /// <summary>A callback-token client with its own HttpClient.</summary>
    public static CallbackTokenClient Create(string baseUrl, string workspaceId, string token, HttpClientOptions? options = null) =>
        new(new Transport(baseUrl, workspaceId, options?.HttpClient ?? Transport.DefaultHttpClient(),
            options?.Timeout ?? TimeSpan.FromSeconds(30), _ => { }), token);

    /// <summary>Polls the wait. Pass the previous ETag to get <c>NotModified</c> while nothing changed.</summary>
    public async Task<CallbackPoll> PollAsync(string? etag = null, CancellationToken cancellationToken = default)
    {
        var answer = await _transport.SendAsync<CallbackWaitState>(OrqixOperations.PollCallbackWait, Array.Empty<string>(),
            Array.Empty<KeyValuePair<string, string?>>(),
            new KeyValuePair<string, string?>[] { new(Header, _token), new("If-None-Match", etag) },
            null, true, cancellationToken).ConfigureAwait(false);
        var newEtag = answer.Headers.ETag?.ToString() ?? etag;
        return new CallbackPoll(answer.Status == 304, answer.Body, newEtag);
    }

    /// <summary>Resumes the wait once; a repeat with the same key and result returns the original answer.</summary>
    public async Task<CallbackResumed> ResumeAsync(string idempotencyKey, IDictionary<string, object?> result,
        CancellationToken cancellationToken = default)
    {
        Transport.CheckIdempotencyKey(idempotencyKey);
        ArgumentNullException.ThrowIfNull(result);
        var answer = await _transport.SendAsync<CallbackResumed>(OrqixOperations.ResumeCallbackWait, Array.Empty<string>(),
            Array.Empty<KeyValuePair<string, string?>>(),
            new KeyValuePair<string, string?>[] { new(Header, _token), new("Idempotency-Key", idempotencyKey) },
            new CallbackResumeRequest { Result = new Dictionary<string, object?>(result) }, false, cancellationToken)
            .ConfigureAwait(false);
        return answer.Body!;
    }
}

/// <summary>HTTP settings of a standalone <see cref="CallbackTokenClient"/>.</summary>
public sealed class HttpClientOptions
{
    /// <summary>A configured HttpClient; default one that never follows redirects.</summary>
    public System.Net.Http.HttpClient? HttpClient { get; set; }

    /// <summary>Per-request timeout; default 30 seconds.</summary>
    public TimeSpan? Timeout { get; set; }
}
