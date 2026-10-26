package com.orqix.shared.web;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.orqix.shared.config.AttachmentSettings;
import com.orqix.shared.config.OrqixProperties;
import com.orqix.shared.problem.ProblemCode;
import com.orqix.shared.problem.ProblemWriter;

/**
 * Request body limits (S3 section 8): drafts 256 KiB, BPMN source 2 MiB (S60), every other body 64 KiB, enforced on Content-Length and on the
 * actual stream (chunked bodies). Exceeding returns {@code 413 PAYLOAD_TOO_LARGE}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLimitsFilter extends OncePerRequestFilter {

    private static final Pattern DRAFT_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/workflows/[^/]+/draft$");
    /** BPMN source of a draft (docs/build/S60): up to 1 MiB of XML, JSON-escaped inside the request body. */
    private static final Pattern DRAFT_BPMN_PATH = Pattern
            .compile("^/api/v1/workspaces/[^/]+/workflows/[^/]+/draft/bpmn(/check)?$");
    private static final Pattern OPENAPI_REVIEW_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/connections/import-openapi$");
    /** Multipart file uploads (docs/build/S17): the form-request upload and the upload to a request. */
    private static final Pattern ATTACHMENT_UPLOAD_PATH = Pattern
            .compile("^/api/v1/workspaces/[^/]+/(attachment-uploads|runs/[^/]+/attachments)$");
    /** Room for the multipart envelope (boundaries, part headers, the description) around a maximal file. */
    private static final int MULTIPART_ENVELOPE = 64 * 1024;
    private static final Pattern MIGRATION_UPLOAD_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/migrations$");
    /** Pega Blueprint PDFs (docs/build/S91): up to 25 MiB, multipart; the edited transcription up to 1 MiB of JSON. */
    private static final Pattern BLUEPRINT_UPLOAD_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/migrations/blueprints$");
    private static final Pattern BLUEPRINT_TRANSCRIPTION_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/migrations/[^/]+/blueprint$");
    static final long BLUEPRINT_MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final Pattern MIGRATION_CONVERT_PATH = Pattern.compile("^/api/v1/workspaces/[^/]+/migrations/[^/]+/convert$");
    /** Room for the {"definition": ...} wrapper around a maximal draft. */
    private static final int DRAFT_ENVELOPE = 1024;

    private final OrqixProperties properties;
    private final ProblemWriter problems;
    private final AttachmentSettings attachments;

    public RequestLimitsFilter(OrqixProperties properties, ProblemWriter problems,
            AttachmentSettings attachments) {
        this.attachments = attachments;
        this.properties = properties;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean upload = "POST".equals(request.getMethod()) && ATTACHMENT_UPLOAD_PATH.matcher(request.getRequestURI()).matches();
        long limit = upload ? attachments.maxFileBytes() + MULTIPART_ENVELOPE
                : BLUEPRINT_UPLOAD_PATH.matcher(request.getRequestURI()).matches() ? BLUEPRINT_MAX_FILE_BYTES + MULTIPART_ENVELOPE
                : BLUEPRINT_TRANSCRIPTION_PATH.matcher(request.getRequestURI()).matches() ? 1_048_576
                : OPENAPI_REVIEW_PATH.matcher(request.getRequestURI()).matches() ? 4_194_304
                : MIGRATION_UPLOAD_PATH.matcher(request.getRequestURI()).matches() ? 2_097_152
                : DRAFT_BPMN_PATH.matcher(request.getRequestURI()).matches() ? 2_097_152
                : DRAFT_PATH.matcher(request.getRequestURI()).matches() || MIGRATION_CONVERT_PATH.matcher(request.getRequestURI()).matches()
                ? properties.limits().draftMaxBytes() + DRAFT_ENVELOPE
                : properties.limits().requestMaxBytes();
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            problems.write(response, ProblemCode.PAYLOAD_TOO_LARGE, "The request body exceeds " + limit + " bytes.");
            return;
        }
        chain.doFilter(new LimitedRequest(request, limit), response);
    }

    /** Thrown while reading a body that exceeds the limit; mapped to 413 by the exception handler. */
    public static class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("Request body exceeds the limit");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), limit);
            }
            return stream;
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long limit;
        private long count;

        LimitedStream(ServletInputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            count += n;
            if (count > limit) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
