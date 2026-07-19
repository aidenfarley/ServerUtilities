package serverutils.lib.io;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import com.google.gson.JsonElement;

import serverutils.lib.util.JsonUtils;
import serverutils.lib.util.StringJoiner;

public class HttpDataReader extends DataReader {

    private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    public interface HttpDataOutput {

        void writeData(OutputStream output) throws Exception;

        class StringOutput implements HttpDataOutput {

            private final String string;

            public StringOutput(String text) {
                string = text;
            }

            public StringOutput(Iterable<String> text) {
                this(StringJoiner.with('\n').join(text));
            }

            @Override
            public void writeData(OutputStream output) throws Exception {
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                writer.write(string);
                writer.flush();
            }
        }
    }

    public static class ConnectionNotOKException extends IllegalStateException {

        private final int responseCode;

        public ConnectionNotOKException(int code) {
            super("Connection not OK! Response code: " + code);
            responseCode = code;
        }

        public int getResponseCode() {
            return responseCode;
        }
    }

    public final URL url;
    public final RequestMethod requestMethod;
    public final String contentType;
    public final HttpDataOutput data;
    public final Proxy proxy;

    HttpDataReader(URL u, RequestMethod r, String c, @Nullable HttpDataOutput d, Proxy p) {
        url = u;
        requestMethod = r;
        contentType = c;
        data = d;
        proxy = p;
    }

    public String toString() {
        return url.toString();
    }

    private HttpURLConnection getConnection() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection(proxy);
        boolean ready = false;

        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setRequestMethod(requestMethod.name());
            connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows; U; Windows NT 6.0; en-GB; rv:1.9.0.3) Gecko/2008092417 Firefox/3.0.3");

            if (!contentType.isEmpty()) {
                connection.setRequestProperty("Content-Type", contentType);
            }

            connection.setDoInput(true);

            if (data != null) {
                connection.setDoOutput(true);
                try (OutputStream output = connection.getOutputStream()) {
                    data.writeData(output);
                    output.flush();
                }
            }

            int responseCode = connection.getResponseCode();

            if (responseCode / 100 != 2) {
                throw new ConnectionNotOKException(responseCode);
            }

            ready = true;
            return connection;
        } finally {
            if (!ready) {
                connection.disconnect();
            }
        }
    }

    @Override
    public String string(int bufferSize) throws Exception {
        HttpURLConnection connection = getConnection();

        try (InputStream stream = connection.getInputStream()) {
            return readStringFromStream(stream, bufferSize);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public List<String> stringList() throws Exception {
        HttpURLConnection connection = getConnection();

        try (InputStream stream = connection.getInputStream()) {
            return readStringListFromStream(stream);
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public JsonElement json() throws Exception {
        HttpURLConnection connection = getConnection();

        try (InputStream stream = connection.getInputStream()) {
            return JsonUtils.parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public BufferedImage image() throws Exception {
        HttpURLConnection connection = getConnection();

        try (InputStream stream = connection.getInputStream()) {
            return ImageIO.read(stream);
        } finally {
            connection.disconnect();
        }
    }
}
