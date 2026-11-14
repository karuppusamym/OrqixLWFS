using System;

namespace Orqix.Client;

/// <summary>
/// The server refused a call. <see cref="Code"/> is the problem code of the RFC 9457 answer, for example
/// <c>JOB_LEASE_LOST</c>; <c>HTTP_ERROR</c> when the answer had none, <c>TRANSPORT_ERROR</c> when no answer arrived
/// (status 0).
/// </summary>
public class OrqixApiException : Exception
{
    /// <summary>Creates the exception.</summary>
    public OrqixApiException(int status, string code, string? detail, string? correlationId, long? retryAfterSeconds,
        Exception? inner = null)
        : base($"{status} {code}" + (string.IsNullOrEmpty(detail) ? "" : ": " + detail), inner)
    {
        Status = status;
        Code = code;
        Detail = detail;
        CorrelationId = correlationId;
        RetryAfterSeconds = retryAfterSeconds;
    }

    /// <summary>HTTP status; 0 when no answer arrived.</summary>
    public int Status { get; }

    /// <summary>The problem code.</summary>
    public string Code { get; }

    /// <summary>The problem detail, when sent.</summary>
    public string? Detail { get; }

    /// <summary>Quote this to an Orqix operator: it finds the server log entries of the call.</summary>
    public string? CorrelationId { get; }

    /// <summary>The server's <c>Retry-After</c> in seconds, for 429 and 503 answers.</summary>
    public long? RetryAfterSeconds { get; }

    /// <summary>Worth repeating with the same idempotency key: no answer, 429, 502, 503 or 504.</summary>
    public bool IsTransient => Status is 0 or 429 or 502 or 503 or 504;

    /// <summary>The server no longer accepts this caller: 401 or 403.</summary>
    public bool IsAuthenticationFailure => Status is 401 or 403;
}

/// <summary>
/// The server answered a call to a deprecated operation (docs/api-compatibility.md): the raw <c>Deprecation</c>
/// (RFC 9745), <c>Sunset</c> (RFC 8594) and <c>Link</c> headers.
/// </summary>
public sealed record DeprecationNotice(OrqixOperation Operation, string Deprecation, string? Sunset, string? Link);
