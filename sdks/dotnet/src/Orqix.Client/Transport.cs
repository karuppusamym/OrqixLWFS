using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Orqix.Client;

/// <summary>The HTTP exchange shared by <see cref="OrqixClient"/> and <see cref="CallbackTokenClient"/>.</summary>
internal sealed class Transport
{
    internal const string Version = "0.1.0";
    private static readonly Regex IdempotencyKey = new("^[A-Za-z0-9._:-]{8,128}$", RegexOptions.CultureInvariant);

    /// <summary>Unknown response fields are ignored and nulls are not sent: additive server changes never break the SDK.</summary>
    internal static readonly JsonSerializerOptions Json = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    internal readonly record struct Answer<T>(int Status, T? Body, HttpResponseHeaders Headers);

    private readonly string _baseUrl;
    private readonly HttpClient _http;
    private readonly TimeSpan _timeout;
    private readonly Action<DeprecationNotice> _deprecations;
    private readonly ConcurrentDictionary<string, bool> _reported = new();

    internal Transport(string baseUrl, string workspaceId, HttpClient http, TimeSpan timeout,
        Action<DeprecationNotice> deprecations)
    {
        if (string.IsNullOrEmpty(baseUrl) ||
            !(baseUrl.StartsWith("https://", StringComparison.Ordinal) || baseUrl.StartsWith("http://", StringComparison.Ordinal)))
        {
            throw new ArgumentException("BaseUrl must be the absolute URL of /api/v1, for example https://orqix.example/api/v1");
        }
        if (string.IsNullOrWhiteSpace(workspaceId))
        {
            throw new ArgumentException("WorkspaceId is required");
        }
        _baseUrl = baseUrl.TrimEnd('/');
        WorkspaceId = workspaceId;
        _http = http;
        _timeout = timeout;
        _deprecations = deprecations;
    }

    internal string WorkspaceId { get; }

    internal static HttpClient DefaultHttpClient() =>
        new(new SocketsHttpHandler { AllowAutoRedirect = false, ConnectTimeout = TimeSpan.FromSeconds(10) })
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };

    internal static string CheckIdempotencyKey(string? key)
    {
        if (key is null || !IdempotencyKey.IsMatch(key))
        {
            throw new ArgumentException("An idempotency key is 8-128 characters of A-Z, a-z, 0-9, '.', '_', ':' or '-'; "
                + "use one key per logical command (a GUID is fine).");
        }
        return key;
    }

    /// <summary>
    /// Sends one call. <paramref name="pathValues"/> are the path parameters after workspaceId; headers with a null
    /// value are skipped; <paramref name="allowNotModified"/> accepts 304 with a null body.
    /// </summary>
    internal async Task<Answer<T>> SendAsync<T>(OrqixOperation operation, string[] pathValues,
        IEnumerable<KeyValuePair<string, string?>> query, IEnumerable<KeyValuePair<string, string?>> headers,
        object? body, bool allowNotModified, CancellationToken cancellationToken)
    {
        var url = new StringBuilder(_baseUrl).Append(operation.Path(new[] { WorkspaceId }.Concat(pathValues).ToArray()));
        var separator = '?';
        foreach (var (name, value) in query)
        {
            if (value is null) continue;
            url.Append(separator).Append(Uri.EscapeDataString(name)).Append('=').Append(Uri.EscapeDataString(value));
            separator = '&';
        }
        using var request = new HttpRequestMessage(new HttpMethod(operation.Method), url.ToString());
        request.Headers.Accept.ParseAdd("application/json");
        request.Headers.Accept.ParseAdd("application/problem+json");
        request.Headers.UserAgent.ParseAdd("orqix-sdk-dotnet/" + Version);
        foreach (var (name, value) in headers)
        {
            if (value is not null) request.Headers.TryAddWithoutValidation(name, value);
        }
        if (body is not null)
        {
            request.Content = new ByteArrayContent(JsonSerializer.SerializeToUtf8Bytes(body, body.GetType(), Json));
            request.Content.Headers.ContentType = new MediaTypeHeaderValue("application/json");
        }

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(_timeout);
        HttpResponseMessage response;
        byte[] payload;
        try
        {
            response = await _http.SendAsync(request, timeout.Token).ConfigureAwait(false);
            payload = await response.Content.ReadAsByteArrayAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception e) when (e is HttpRequestException or OperationCanceledException)
        {
            throw new OrqixApiException(0, "TRANSPORT_ERROR", e.GetType().Name, null, null, e);
        }
        using (response)
        {
            ReportDeprecation(operation, response);
            var status = (int)response.StatusCode;
            if (status == (int)HttpStatusCode.NotModified && allowNotModified)
            {
                return new Answer<T>(status, default, response.Headers);
            }
            if (status is < 200 or > 299)
            {
                throw Problem(status, response, payload);
            }
            if (typeof(T) == typeof(object) || payload.Length == 0)
            {
                return new Answer<T>(status, default, response.Headers);
            }
            try
            {
                return new Answer<T>(status, JsonSerializer.Deserialize<T>(payload, Json), response.Headers);
            }
            catch (JsonException e)
            {
                throw new OrqixApiException(status, "RESPONSE_UNREADABLE", e.Message, null, null, e);
            }
        }
    }

    private void ReportDeprecation(OrqixOperation operation, HttpResponseMessage response)
    {
        if (!response.Headers.TryGetValues("Deprecation", out var values)) return;
        if (!_reported.TryAdd(operation.OperationId, true)) return;
        string? First(string name) => response.Headers.TryGetValues(name, out var v) ? v.FirstOrDefault() : null;
        _deprecations(new DeprecationNotice(operation, values.First(), First("Sunset"), First("Link")));
    }

    private static OrqixApiException Problem(int status, HttpResponseMessage response, byte[] payload)
    {
        string code = "HTTP_ERROR";
        string? detail = null, correlationId = null;
        try
        {
            using var document = JsonDocument.Parse(payload);
            if (document.RootElement.ValueKind == JsonValueKind.Object)
            {
                string? Text(string name) => document.RootElement.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String
                    ? p.GetString() : null;
                code = Text("code") ?? code;
                detail = Text("detail");
                correlationId = Text("correlationId");
            }
        }
        catch (JsonException)
        {
            // Not a problem document (a proxy page, for example): keep HTTP_ERROR.
        }
        long? retryAfter = response.Headers.RetryAfter?.Delta is { } delta ? (long)delta.TotalSeconds : null;
        return new OrqixApiException(status, code, detail, correlationId, retryAfter);
    }
}
