package com.orqix.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;

/**
 * An asynchronous report API under {@code /reports} (CG-W02, docs/build/S133): {@code POST /reports/api/jobs} answers
 * 202 with a {@code Location} of the job, {@code GET /reports/api/jobs/{jobId}} answers its state, done after
 * {@link #checksUntilDone} reads. The location the next POST returns can be replaced, to point outside the binding.
 */
public final class ReportJobStub {

    private static final Map<String, Integer> READS = new LinkedHashMap<>();
    private static int sequence;
    private static int checksUntilDone = 2;
    private static String nextLocation;

    private ReportJobStub() {
    }

    public static synchronized void reset() {
        READS.clear();
        sequence = 0;
        checksUntilDone = 2;
        nextLocation = null;
    }

    public static synchronized void checksUntilDone(int checks) {
        checksUntilDone = checks;
    }

    /** The Location header of the next accepted job (null: the job's own path). */
    public static synchronized void nextLocation(String location) {
        nextLocation = location;
    }

    public static synchronized int reads(String jobId) {
        return READS.getOrDefault(jobId, 0);
    }

    static synchronized ResponseDefinition handle(Request request) {
        String path = request.getUrl().replaceFirst("\\?.*$", "");
        String method = request.getMethod().getName();
        if ("POST".equals(method) && path.equals("/reports/api/jobs")) {
            String jobId = "job" + (++sequence) + "x" + Long.toHexString(System.nanoTime() & 0xffffff);
            READS.put(jobId, 0);
            String location = nextLocation != null ? nextLocation : "/reports/api/jobs/" + jobId;
            nextLocation = null;
            return new ResponseDefinitionBuilder().withStatus(202).withHeader("Location", location)
                    .withHeader("Content-Type", "application/json").withBody("{\"accepted\":true}").build();
        }
        if ("GET".equals(method) && path.startsWith("/reports/api/jobs/")) {
            String jobId = path.substring("/reports/api/jobs/".length());
            Integer reads = READS.get(jobId);
            if (reads == null) {
                return new ResponseDefinitionBuilder().withStatus(404).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"unknown job\"}").build();
            }
            READS.put(jobId, reads + 1);
            boolean done = reads + 1 >= checksUntilDone;
            return new ResponseDefinitionBuilder().withStatus(200).withHeader("Content-Type", "application/json")
                    .withBody("{\"state\":\"" + (done ? "done" : "running") + "\",\"documentId\":\""
                            + (done ? "DOC-" + jobId : "") + "\"}").build();
        }
        return new ResponseDefinitionBuilder().withStatus(404).withBody("{}").build();
    }
}
