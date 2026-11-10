using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Orqix.Client.Tests;

/// <summary>A loopback HTTP server (Kestrel) that answers from a script and records every request.</summary>
public sealed class ScriptedServer : IAsyncDisposable
{
    public sealed record Received(string Method, string Path, string Query, IReadOnlyDictionary<string, string> Headers, string Body)
    {
        public string? Header(string name) => Headers.TryGetValue(name.ToLowerInvariant(), out var v) ? v : null;
        public JsonElement Json() => JsonDocument.Parse(Body).RootElement;
    }

    private sealed record Rule(string Method, string Suffix, int Status, string? Body, IReadOnlyDictionary<string, string> Headers, bool Always);

    private readonly WebApplication _app;
    private readonly List<Rule> _rules = new();
    private readonly ConcurrentQueue<Received> _received = new();

    private ScriptedServer(WebApplication app) => _app = app;

    public static async Task<ScriptedServer> StartAsync()
    {
        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseKestrel(o => o.Listen(IPAddress.Loopback, 0));
        var app = builder.Build();
        var server = new ScriptedServer(app);
        app.Run(server.AnswerAsync);
        await app.StartAsync();
        return server;
    }

    public string BaseUrl
    {
        get
        {
            var address = _app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.First();
            return address.TrimEnd('/') + "/api/v1";
        }
    }

    public IReadOnlyList<Received> All => _received.ToList();

    public IReadOnlyList<Received> Requests(string method, string suffix) =>
        _received.Where(r => r.Method == method && r.Path.EndsWith(suffix, StringComparison.Ordinal)).ToList();

    public ScriptedServer Once(string method, string suffix, int status, object? body = null, IReadOnlyDictionary<string, string>? headers = null)
    {
        lock (_rules) _rules.Add(new Rule(method, suffix, status, Serialize(body), headers ?? new Dictionary<string, string>(), false));
        return this;
    }

    public ScriptedServer Always(string method, string suffix, int status, object? body = null)
    {
        lock (_rules) _rules.Add(new Rule(method, suffix, status, Serialize(body), new Dictionary<string, string>(), true));
        return this;
    }

    public async Task<Received> WaitForAsync(Func<Received, bool> test, int seconds = 10)
    {
        var deadline = DateTime.UtcNow.AddSeconds(seconds);
        while (DateTime.UtcNow < deadline)
        {
            var found = _received.FirstOrDefault(test);
            if (found is not null) return found;
            await Task.Delay(10);
        }
        throw new TimeoutException($"No matching request within {seconds} s");
    }

    private static string? Serialize(object? body) => body switch
    {
        null => null,
        string text => text,
        _ => JsonSerializer.Serialize(body),
    };

    private async Task AnswerAsync(HttpContext context)
    {
        using var reader = new StreamReader(context.Request.Body, Encoding.UTF8);
        var body = await reader.ReadToEndAsync();
        var path = context.Request.Path.Value ?? "";
        var headers = context.Request.Headers.ToDictionary(h => h.Key.ToLowerInvariant(), h => h.Value.ToString());
        _received.Enqueue(new Received(context.Request.Method, path, context.Request.QueryString.Value?.TrimStart('?') ?? "", headers, body));
        Rule? rule;
        lock (_rules)
        {
            rule = _rules.FirstOrDefault(r => r.Method == context.Request.Method && path.EndsWith(r.Suffix, StringComparison.Ordinal));
            if (rule is not null && !rule.Always) _rules.Remove(rule);
        }
        if (rule is null)
        {
            context.Response.StatusCode = 500;
            await context.Response.WriteAsync("{\"code\":\"INTERNAL_ERROR\",\"detail\":\"no scripted answer\"}");
            return;
        }
        context.Response.StatusCode = rule.Status;
        foreach (var (name, value) in rule.Headers) context.Response.Headers[name] = value;
        if (rule.Body is not null)
        {
            context.Response.ContentType = rule.Status >= 400 ? "application/problem+json" : "application/json";
            await context.Response.WriteAsync(rule.Body);
        }
    }

    public async ValueTask DisposeAsync()
    {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        await _app.StopAsync(timeout.Token);
        await _app.DisposeAsync();
    }
}
