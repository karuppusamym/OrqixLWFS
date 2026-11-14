using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Orqix.Client.Models;

namespace Orqix.Client.Worker;

/// <summary>A job leased to this worker. <see cref="Fence"/> numbers the lease; every later call presents it.</summary>
public sealed class Job
{
    private long _leaseExpiresAtTicks;

    /// <summary>Wraps a job as the server sent it.</summary>
    public Job(ExternalJob raw)
    {
        Raw = raw ?? throw new ArgumentNullException(nameof(raw));
        Input = (raw.Input ?? new Dictionary<string, object?>()).ToDictionary(kv => kv.Key, kv => kv.Value);
        _leaseExpiresAtTicks = raw.LeaseExpiresAt.UtcTicks;
    }

    /// <summary>The job as the server sent it.</summary>
    public ExternalJob Raw { get; }

    /// <summary>The job ID.</summary>
    public string Id => Raw.Id;

    /// <summary>The topic.</summary>
    public string Topic => Raw.Topic;

    /// <summary>The values the step maps, by name (values are <see cref="JsonElement"/>; see <see cref="GetInput{T}"/>).</summary>
    public IReadOnlyDictionary<string, object?> Input { get; }

    /// <summary>The lease number.</summary>
    public long Fence => Raw.Fence;

    /// <summary>Attempt number, from 1.</summary>
    public long Attempt => Raw.Attempt;

    /// <summary>The workflow's attempt limit.</summary>
    public long MaxAttempts => Raw.MaxAttempts;

    /// <summary>The human request reference, for example ORQ-1043; use it in logs instead of the job ID.</summary>
    public string RunReference => Raw.RunReference;

    /// <summary>The step.</summary>
    public string NodeId => Raw.NodeId;

    /// <summary>The end of the current lease (UTC).</summary>
    public DateTimeOffset LeaseExpiresAt
    {
        get => new(Interlocked.Read(ref _leaseExpiresAtTicks), TimeSpan.Zero);
        internal set => Interlocked.Exchange(ref _leaseExpiresAtTicks, value.UtcTicks);
    }

    /// <summary>An input value converted to <typeparamref name="T"/>; default when the step did not map it.</summary>
    public T? GetInput<T>(string name) => Input.TryGetValue(name, out var value) && value is JsonElement element
        ? element.Deserialize<T>(Transport.Json)
        : default;

    /// <inheritdoc/>
    public override string ToString() => $"Job[{Topic} {RunReference}/{NodeId} attempt {Attempt}]";
}

/// <summary>
/// Does the work of one job and returns the declared result fields (scalars). Throw <see cref="BusinessErrorException"/>
/// for a business "no", <see cref="JobFailedException"/> for a technical failure; anything else is reported as a
/// retryable failure naming only its type.
/// </summary>
public delegate Task<IDictionary<string, object?>?> JobHandler(Job job, CancellationToken cancellationToken);

/// <summary>Persist these fields in a gateway outbox. The callback ID is a correlation identifier, not a credential.</summary>
public sealed record CallbackTicket(string JobId, string CallbackId, DateTimeOffset Deadline);

/// <summary>A technical failure; retryable by default. <c>retryable: false</c> fails the job for good.</summary>
public class JobFailedException : Exception
{
    /// <summary>Creates the failure.</summary>
    public JobFailedException(string message, bool retryable = true, int? retryAfterSeconds = null) : base(message)
    {
        Retryable = retryable;
        RetryAfterSeconds = retryAfterSeconds;
    }

    /// <summary>Whether the job returns to the queue.</summary>
    public bool Retryable { get; }

    /// <summary>Delay before the next attempt, when given.</summary>
    public int? RetryAfterSeconds { get; }
}

/// <summary>
/// A business error (docs/build/S132): the work was done and the answer is "no", for example
/// <c>new BusinessErrorException("ADDRESS_UNKNOWN", "No such street")</c>. Final: never retried.
/// </summary>
public class BusinessErrorException : Exception
{
    private static readonly Regex CodePattern = new("^[A-Z][A-Z0-9_]{1,39}$", RegexOptions.CultureInvariant);

    /// <summary>Creates the business error.</summary>
    public BusinessErrorException(string code, string message) : base($"{CheckCode(code)}: {message}")
    {
        Code = code;
        BusinessMessage = message;
    }

    /// <summary>The code, such as ADDRESS_UNKNOWN.</summary>
    public string Code { get; }

    /// <summary>The message for the request's timeline.</summary>
    public string BusinessMessage { get; }

    /// <summary>Validates a code: <c>^[A-Z][A-Z0-9_]{1,39}$</c>, not starting with the reserved ORQIX_.</summary>
    public static string CheckCode(string code)
    {
        if (code is null || !CodePattern.IsMatch(code) || code.StartsWith("ORQIX_", StringComparison.Ordinal))
        {
            throw new ArgumentException("A business error code looks like ADDRESS_UNKNOWN (A-Z, 0-9, _; 2-40 characters; "
                + "not starting with ORQIX_).", nameof(code));
        }
        return code;
    }
}
