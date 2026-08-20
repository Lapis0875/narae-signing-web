package com.naraesigning.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.Part;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemHeaders;
import org.apache.commons.fileupload2.core.FileUploadException;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletDiskFileUpload;

final class BufferedMultipartRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    private Collection<Part> parts;

    BufferedMultipartRequest(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ByteArrayServletInputStream(body);
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (parts == null) {
            try {
                var upload = new JakartaServletDiskFileUpload(DiskFileItemFactory.builder().get());
                var parsed = upload.parseRequest(this);
                var result = new ArrayList<Part>(parsed.size());
                parsed.forEach(item -> result.add(new FileItemPart(item)));
                parts = Collections.unmodifiableList(result);
            } catch (FileUploadException exception) {
                throw new ServletException("Invalid multipart request", exception);
            }
        }
        return parts;
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        return getParts().stream().filter(part -> part.getName().equals(name)).findFirst().orElse(null);
    }

    void cleanup() throws IOException {
        if (parts != null) {
            for (var part : parts) part.delete();
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(byte[] body) { delegate = new ByteArrayInputStream(body); }
        @Override public int read() { return delegate.read(); }
        @Override public int read(byte[] bytes, int offset, int length) { return delegate.read(bytes, offset, length); }
        @Override public boolean isFinished() { return delegate.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) {
            try {
                listener.onDataAvailable();
                if (isFinished()) listener.onAllDataRead();
            } catch (IOException exception) {
                listener.onError(exception);
            }
        }
    }

    private record FileItemPart(DiskFileItem item) implements Part {
        @Override public InputStream getInputStream() throws IOException { return item.getInputStream(); }
        @Override public String getContentType() { return item.getContentType(); }
        @Override public String getName() { return item.getFieldName(); }
        @Override public String getSubmittedFileName() { return item.getName(); }
        @Override public long getSize() { return item.getSize(); }
        @Override public void write(String fileName) throws IOException { item.write(Path.of(fileName)); }
        @Override public void delete() throws IOException { item.delete(); }
        @Override public String getHeader(String name) { return headers().getHeader(name); }
        @Override public Collection<String> getHeaders(String name) { return list(headers().getHeaders(name)); }
        @Override public Collection<String> getHeaderNames() { return list(headers().getHeaderNames()); }

        private FileItemHeaders headers() { return item.getHeaders(); }
        private static Collection<String> list(java.util.Iterator<String> values) {
            var result = new ArrayList<String>();
            values.forEachRemaining(result::add);
            return Collections.unmodifiableList(result);
        }
    }
}
