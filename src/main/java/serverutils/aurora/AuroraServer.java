package serverutils.aurora;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import serverutils.ServerUtilities;
import serverutils.aurora.page.HomePage;
import serverutils.aurora.page.WebPage;
import serverutils.aurora.page.WebPageNotFound;
import serverutils.aurora.page.WebPageUnauthorized;

public class AuroraServer {

    private final MinecraftServer server;
    private final int port;
    private ChannelFuture channel;
    private final EventLoopGroup masterGroup;
    private final EventLoopGroup slaveGroup;
    private Thread shutdownHook;
    private boolean stopped;

    private byte[] iconBytes = null;

    public AuroraServer(MinecraftServer s, int p) {
        server = s;
        port = p;
        masterGroup = new NioEventLoopGroup();
        slaveGroup = new NioEventLoopGroup();
    }

    public MinecraftServer getServer() {
        return server;
    }

    boolean start() {
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(masterGroup, slaveGroup);
            bootstrap.channel(NioServerSocketChannel.class);
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {

                @Override
                public void initChannel(final SocketChannel ch) {
                    ch.pipeline().addLast("codec", new HttpServerCodec());
                    ch.pipeline().addLast("aggregator", new HttpObjectAggregator(512 * 1024));
                    ch.pipeline().addLast("request", new SimpleChannelInboundHandler<FullHttpRequest>() {

                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                            handleRequest(ctx, request);
                        }

                        @Override
                        public void channelReadComplete(ChannelHandlerContext ctx) {
                            ctx.flush();
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            String message = cause.getMessage();
                            if (message == null || message.isEmpty()) {
                                message = cause.getClass().getName();
                            }

                            byte[] content = message.getBytes(StandardCharsets.UTF_8);
                            FullHttpResponse response = new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                                    Unpooled.wrappedBuffer(content));
                            response.headers().set(HttpHeaders.Names.CONTENT_TYPE, "text/plain; charset=UTF-8");
                            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, content.length);
                            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
                            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                        }
                    });
                }
            });

            bootstrap.option(ChannelOption.SO_BACKLOG, 128);
            bootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
            channel = bootstrap.bind(port).sync();
            shutdownHook = new Thread(this::shutdown, "ServerUtilities-Aurora-Shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            return true;
        } catch (InterruptedException ex) {
            ServerUtilities.LOGGER.warn("Interrupted while starting Aurora", ex);
            stopAfterFailedStart();
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ex) {
            ServerUtilities.LOGGER.error("Failed to start Aurora on port " + port, ex);
            stopAfterFailedStart();
            return false;
        }
    }

    synchronized void shutdown() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            if (channel != null) {
                channel.channel().close().sync();
            }
        } catch (InterruptedException ex) {
            ServerUtilities.LOGGER.warn("Interrupted while stopping Aurora", ex);
            Thread.currentThread().interrupt();
        } finally {
            slaveGroup.shutdownGracefully();
            masterGroup.shutdownGracefully();
            removeShutdownHook();
        }
    }

    private synchronized void stopAfterFailedStart() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (channel != null && channel.channel() != null) {
            channel.channel().close();
        }
        slaveGroup.shutdownGracefully();
        masterGroup.shutdownGracefully();
        removeShutdownHook();
    }

    private void removeShutdownHook() {
        if (shutdownHook == null || Thread.currentThread() == shutdownHook) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ex) {
            // The JVM is already shutting down, so the hook no longer needs removal.
        } finally {
            shutdownHook = null;
        }
    }

    boolean eventLoopsAreShuttingDown() {
        return masterGroup.isShuttingDown() && slaveGroup.isShuttingDown();
    }

    private void handleRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.getUri();
        WebPage page;

        while (uri.startsWith("/")) {
            uri = uri.substring(1);
        }

        while (uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }

        if (uri.isEmpty()) {
            page = new HomePage(this);
        } else {
            try {
                AuroraPageEvent event = new AuroraPageEvent(this, request, uri);
                MinecraftForge.EVENT_BUS.post(event);
                page = event.getPage();

                if (page != null && page.getPageType() != PageType.ENABLED
                        && !System.getProperty("AuroraIgnoreAuth", "0").equals("1")) {
                    page = new WebPageUnauthorized(event.getUri());
                }

                if (page == null) {
                    page = new WebPageNotFound(event.getUri());
                }
            } catch (Exception ex) {
                page = new WebPageNotFound("errored");
                ServerUtilities.LOGGER.error("Failed to resolve Aurora page " + uri, ex);
            }
        }

        String content;
        String contentType;

        try {
            content = page.getContent();
            contentType = page.getContentType();
        } catch (Exception ex) {
            ServerUtilities.LOGGER.error("Failed to render Aurora page " + uri, ex);
            StringWriter writer = new StringWriter();
            PrintWriter printWriter = new PrintWriter(writer);
            printWriter.println("Error!");
            printWriter.println();
            ex.printStackTrace(printWriter);
            content = writer.toString();
            contentType = "text/plain";
        }

        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                page.getStatus(),
                Unpooled.wrappedBuffer(contentBytes));

        boolean keepAlive = HttpHeaders.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
        } else {
            response.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        }

        response.headers().set(HttpHeaders.Names.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, contentBytes.length);
        response.headers().set(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ChannelFuture responseFuture = ctx.writeAndFlush(response);
        if (!keepAlive) {
            responseFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    public boolean allow(String uri) {
        return true;
    }
}
